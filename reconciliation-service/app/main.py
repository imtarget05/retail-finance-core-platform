"""FastAPI surface for the reconciliation service.

The service pulls settled transactions from the Java core, accepts a partner
statement, and returns a report of every difference. It never mutates the core:
discrepancies are for a human to action.
"""

from __future__ import annotations

import os
from datetime import datetime, timezone

import httpx
from fastapi import Depends, FastAPI, HTTPException, Query
from pydantic import BaseModel, Field

from .models import CoreTransaction, ReconciliationReport
from .parser import StatementFormatError, parse_csv_statement
from .reconciler import reconcile, summary_line

CORE_BASE_URL = os.getenv("CORE_BASE_URL", "http://localhost:8080")
CORE_TOKEN = os.getenv("CORE_SERVICE_TOKEN", "")
HTTP_TIMEOUT = float(os.getenv("CORE_HTTP_TIMEOUT", "10"))

app = FastAPI(
    title="Retail Reconciliation Service",
    version="0.1.0",
    description="Matches partner statements against the retail finance core ledger.",
)


class ReconcileRequest(BaseModel):
    """A statement to check, plus the window it covers."""

    statement_csv: str = Field(..., description="Raw CSV statement from the partner")
    window_start: datetime = Field(..., description="Inclusive lower bound (ISO-8601)")
    window_end: datetime = Field(..., description="Exclusive upper bound (ISO-8601)")

    model_config = {
        "json_schema_extra": {
            "example": {
                "statement_csv": "partner_ref,amount,posted_at\n"
                                 "TXN-20261002-1111111,50000,2026-10-02T09:00:00\n",
                "window_start": "2026-10-02T00:00:00Z",
                "window_end": "2026-10-03T00:00:00Z",
            }
        }
    }


def _to_core_transaction(payload: dict) -> CoreTransaction:
    settled = payload.get("settledAt")
    return CoreTransaction(
        txn_ref=payload["txnRef"],
        account_no=payload.get("accountNo", ""),
        outlet_code=payload.get("outletCode", ""),
        type=payload["type"],
        amount=int(payload["amount"]),
        fee=int(payload.get("fee", 0)),
        signed_amount=int(payload.get("signedAmount", payload["amount"])),
        settled_at=datetime.fromisoformat(settled) if settled else None,
    )


async def fetch_settled(
    client: httpx.AsyncClient,
    window_start: datetime,
    window_end: datetime,
    limit: int = 1000,
) -> list[CoreTransaction]:
    """Pull settled transactions from the core service for a window."""
    params = {
        "from": _as_instant(window_start),
        "to": _as_instant(window_end),
        "limit": limit,
    }
    headers = {"Authorization": f"Bearer {CORE_TOKEN}"} if CORE_TOKEN else {}

    transactions: list[CoreTransaction] = []
    try:
        for page in range(1001):
            params["page"] = page
            response = await client.get(
                f"{CORE_BASE_URL}/api/v1/transactions/settled",
                params=params,
                headers=headers,
                timeout=HTTP_TIMEOUT,
            )
            response.raise_for_status()
            payload = response.json()
            if not isinstance(payload, list) or len(payload) > limit:
                raise HTTPException(status_code=502, detail="invalid core feed page")
            transactions.extend(_to_core_transaction(item) for item in payload)
            if len(payload) < limit:
                return transactions
    except httpx.HTTPStatusError as exc:
        raise HTTPException(
            status_code=502,
            detail=f"core service rejected the request: {exc.response.status_code}",
        ) from exc
    except httpx.HTTPError as exc:
        raise HTTPException(
            status_code=503, detail=f"core service unreachable: {exc}"
        ) from exc

    raise HTTPException(status_code=503, detail="core feed exceeded 1000 pages; narrow the window")


def _as_instant(value: datetime) -> str:
    """Render a datetime the way Spring's Instant binder expects it."""
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


@app.get("/health")
async def health() -> dict:
    return {"status": "ok", "core_base_url": CORE_BASE_URL}


@app.post("/api/v1/reconcile", response_model=dict)
async def run_reconciliation(
    request: ReconcileRequest,
    limit: int = Query(default=1000, ge=1, le=1000),
) -> dict:
    """Compare a partner statement against the core ledger for a window."""
    if request.window_start >= request.window_end:
        raise HTTPException(
            status_code=422, detail="window_start must be before window_end"
        )

    try:
        partner_records = parse_csv_statement(request.statement_csv)
    except StatementFormatError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc

    async with httpx.AsyncClient() as client:
        core_transactions = await fetch_settled(
            client, request.window_start, request.window_end, limit
        )

    report: ReconciliationReport = reconcile(core_transactions, partner_records)
    body = report.to_dict()
    body["summary"] = summary_line(report)
    return body
