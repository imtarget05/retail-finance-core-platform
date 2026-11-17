"""Exercise the disposable Compose stack and write an auditable demo report.

This uses seeded local users and synthetic money only. Start core and
reconciliation as described in docs/apply-2027-backend-demo.md first.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

CORE = "http://127.0.0.1:8080"
RECONCILIATION = "http://127.0.0.1:8000"
OUTLET = "OUTLET-HCM-01"


def call(method: str, url: str, *, token: str = "", body: dict | None = None) -> tuple[int, object]:
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"} if data is not None else {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = Request(url, data=data, headers=headers, method=method)
    try:
        with urlopen(request, timeout=30) as response:
            return response.status, json.load(response)
    except HTTPError as exc:
        try:
            return exc.code, json.loads(exc.read())
        except ValueError:
            return exc.code, {"error": "non-JSON HTTP error"}


def require(status: int, expected: int, label: str) -> None:
    if status != expected:
        raise AssertionError(f"{label}: expected HTTP {expected}, got {status}")


def login(username: str, password: str) -> str:
    status, body = call("POST", f"{CORE}/api/v1/auth/login",
                        body={"username": username, "password": password})
    require(status, 200, f"login {username}")
    token = body.get("accessToken") if isinstance(body, dict) else None
    if not token:
        raise AssertionError(f"login {username}: missing access token")
    return token


def wait_for(url: str, timeout: int = 120) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            status, _ = call("GET", url)
            if status == 200:
                return
        except (URLError, TimeoutError):
            pass
        time.sleep(2)
    raise TimeoutError(f"service did not become healthy: {url}")


def post(token: str, account: str, key: str, amount: int,
         txn_type: str = "CASH_IN") -> tuple[int, object]:
    return call("POST", f"{CORE}/api/v1/transactions", token=token, body={
        "type": txn_type, "accountNo": account, "outletCode": OUTLET,
        "amount": amount, "fee": 0, "idempotencyKey": key,
    })


def balance(token: str, account: str) -> int:
    status, body = call("GET", f"{CORE}/api/v1/accounts/{account}", token=token)
    require(status, 200, "account balance")
    return int(body["balance"])


def run() -> dict:
    elapsed_start = time.monotonic()
    wait_for(f"{CORE}/actuator/health")
    wait_for(f"{RECONCILIATION}/health")
    password = os.getenv("DEMO_SEED_PASSWORD", "ChangeMe123!")
    pos_token = login("pos01", password)
    ops_token = login("ops", password)
    start = datetime.now(timezone.utc) - timedelta(seconds=5)
    run_id = uuid.uuid4().hex[:12]
    account = f"APPLY-{run_id}"
    national_id = str(uuid.uuid4().int % 10**12).zfill(12)
    status, _ = call("POST", f"{CORE}/api/v1/accounts", token=pos_token, body={
        "accountNo": account, "customerName": "Synthetic Demo Customer",
        "idNumber": national_id, "openingBalance": 0,
    })
    require(status, 201, "create owned demo account")
    initial_balance = balance(pos_token, account)

    first_key = f"apply-{run_id}-first"
    status, first = post(pos_token, account, first_key, 100_000)
    require(status, 200, "first cash-in")
    status, replay = post(pos_token, account, first_key, 100_000)
    require(status, 200, "identical replay")
    if replay["txnRef"] != first["txnRef"] or balance(pos_token, account) != initial_balance + 100_000:
        raise AssertionError("replay moved money twice or changed reference")
    status, _ = post(pos_token, account, first_key, 101_000)
    require(status, 409, "changed-payload replay")

    concurrent_key = f"apply-{run_id}-race"
    with ThreadPoolExecutor(max_workers=2) as pool:
        pending = [pool.submit(post, pos_token, account, concurrent_key, 20_000) for _ in range(2)]
        raced = [future.result() for future in pending]
    if [status for status, _ in raced] != [200, 200]:
        raise AssertionError(f"concurrent retry statuses: {[status for status, _ in raced]}")
    if raced[0][1]["txnRef"] != raced[1][1]["txnRef"]:
        raise AssertionError("concurrent retry created two references")
    expected_balance = initial_balance + 120_000
    if balance(pos_token, account) != expected_balance:
        raise AssertionError("concurrent retry moved money more than once")

    status, _ = post(pos_token, account, f"apply-{run_id}-overdraft", expected_balance + 1, "CASH_OUT")
    require(status, 422, "refused debit")
    if balance(pos_token, account) != expected_balance:
        raise AssertionError("refused debit changed balance")

    refs = (first["txnRef"], raced[0][1]["txnRef"])
    audits = []
    for ref in refs:
        status, rows = call("GET", f"{CORE}/api/v1/audit/target/Transaction/{ref}", token=ops_token)
        require(status, 200, "audit lookup")
        if not any(row.get("action") == "TXN_SETTLED" for row in rows):
            raise AssertionError(f"missing TXN_SETTLED audit for {ref}")
        audits.append(len(rows))

    end = datetime.now(timezone.utc) + timedelta(seconds=5)
    window = {"window_start": start.isoformat(), "window_end": end.isoformat()}
    statement = "partner_ref,amount,posted_at\n" + "".join(
        f"{ref},{amount},{end.isoformat()}\n" for ref, amount in zip(refs, (100_000, 20_000))
    )
    recon_token = os.getenv("RECONCILIATION_API_TOKEN", "local-dev-reconciliation-token")
    status, balanced = call("POST", f"{RECONCILIATION}/api/v1/reconcile", token=recon_token,
                            body={**window, "statement_csv": statement})
    require(status, 200, "balanced reconciliation")
    if not balanced.get("is_balanced") or balanced.get("matched_count") != 2:
        raise AssertionError(f"expected two matched references: {balanced.get('by_status')}")
    mismatched_statement = statement.replace(f"{refs[0]},100000,", f"{refs[0]},100001,")
    status, discrepant = call("POST", f"{RECONCILIATION}/api/v1/reconcile", token=recon_token,
                              body={**window, "statement_csv": mismatched_statement})
    require(status, 200, "mismatched reconciliation")
    if discrepant.get("by_status", {}).get("AMOUNT_MISMATCH") != 1:
        raise AssertionError("partner amount mismatch was not detected")

    return {
        "scope": "disposable_local_compose_synthetic",
        "git_sha": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "run_id": run_id, "account_no": account,
        "first_ref": refs[0], "concurrent_ref": refs[1],
        "initial_balance": initial_balance, "final_balance": expected_balance,
        "replay_same_reference": True, "changed_payload_http_status": 409,
        "concurrent_statuses": [status for status, _ in raced],
        "refused_debit_http_status": 422, "audit_row_counts": audits,
        "balanced_reconciliation": balanced["by_status"],
        "mismatch_reconciliation": discrepant["by_status"],
        "elapsed_seconds": round(time.monotonic() - elapsed_start, 3),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("demo-results/backend-application.json"))
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        report = {"status": "passed", **run()}
    except Exception as exc:
        report = {"status": "failed", "error": str(exc),
                  "timestamp_utc": datetime.now(timezone.utc).isoformat()}
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
