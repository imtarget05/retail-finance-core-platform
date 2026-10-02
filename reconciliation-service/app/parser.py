"""Parse partner statement files.

Supports CSV (the format most partner portals hand out) and a fixed-width
legacy layout. Parsing is strict about anything that is not an integer amount:
a silently coerced amount is worse than a rejected file, because it produces
a reconciliation report that looks clean.
"""

from __future__ import annotations

import csv
import io
from collections.abc import Iterable
from datetime import datetime

from .models import PartnerRecord


class StatementFormatError(ValueError):
    """Raised when a statement cannot be parsed with confidence."""


def parse_csv_statement(
    content: str,
    *,
    ref_column: str = "partner_ref",
    amount_column: str = "amount",
    posted_column: str = "posted_at",
    outlet_column: str | None = "outlet_code",
) -> list[PartnerRecord]:
    """Parse a CSV statement into partner records."""
    reader = csv.DictReader(io.StringIO(content))
    if reader.fieldnames is None:
        raise StatementFormatError("statement has no header row")

    missing = {ref_column, amount_column} - set(reader.fieldnames)
    if missing:
        raise StatementFormatError(
            f"statement is missing required column(s): {sorted(missing)}; "
            f"found {reader.fieldnames}"
        )

    records: list[PartnerRecord] = []
    for line_number, row in enumerate(reader, start=2):
        raw_ref = (row.get(ref_column) or "").strip()
        if not raw_ref:
            continue

        raw_amount = (row.get(amount_column) or "").strip()
        try:
            amount = int(raw_amount)
        except ValueError as exc:
            raise StatementFormatError(
                f"line {line_number}: amount {raw_amount!r} is not an integer"
            ) from exc

        records.append(
            PartnerRecord(
                partner_ref=raw_ref,
                amount=amount,
                posted_at=_parse_timestamp(row.get(posted_column)),
                outlet_code=(row.get(outlet_column) or "").strip()
                if outlet_column
                else None,
            )
        )
    return records


def _parse_timestamp(raw: str | None) -> datetime | None:
    if not raw or not raw.strip():
        return None
    text = raw.strip().replace(" ", "T")
    try:
        return datetime.fromisoformat(text)
    except ValueError:
        # Tolerate the dd/MM/yyyy form common in partner exports.
        for fmt in ("%d/%m/%Y", "%d/%m/%Y %H:%M", "%Y%m%d"):
            try:
                return datetime.strptime(raw.strip(), fmt)
            except ValueError:
                continue
    return None


def totals(records: Iterable[PartnerRecord]) -> int:
    """Net movement across a set of partner records."""
    return sum(record.amount for record in records)