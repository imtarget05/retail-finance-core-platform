# Five-minute Backend Java walkthrough (Fresher/Junior)

Evidence revision: [`11620ed67c5c6e208a3af1bd72e7cf0a96b67271`](https://github.com/imtarget05/retail-finance-core-platform/tree/11620ed67c5c6e208a3af1bd72e7cf0a96b67271). [Backend CI run 43835337581](https://github.com/imtarget05/retail-finance-core-platform/actions/runs/43835337581) passed the MySQL 8 Testcontainers integration job and reconciliation tests on that exact SHA. This document is a repeatable test walkthrough, not a claim of a live banking deployment.

## Reproduce

Prerequisites: JDK 17, Maven, Docker daemon, Python 3.11+ and the dependencies in `reconciliation-service/requirements.txt`. Run from the repository root:

```bash
cd backend
mvn -B verify
cd ../reconciliation-service
python -m pytest tests/ -q
```

`mvn verify` runs unit tests and MySQL 8 integration tests through Testcontainers. CI's `Integration tests (real MySQL 8)` job is the recorded database-backed run. The Python suite ran locally on 2026-11-17: **17 passed**. These commands are the full gate; using `mvn test` alone omits the MySQL integration tests. An interactive Compose run is described in the root README, but the current evidence is from automated tests.

For a disposable full API walkthrough, the `Disposable Compose transaction and reconciliation demo` CI job builds MySQL, the Java core and the Python reconciliation service. It runs `python3 scripts/backend_application_demo.py` and uploads `backend-application.json` with the commit SHA and observed statuses. To reproduce locally, start MySQL and core with `docker compose up -d --build mysql core`, log in as the seeded `ops` user, start reconciliation with `CORE_SERVICE_TOKEN` set to that OPS JWT, then run the script. The workflow contains the exact startup commands. Use only the seeded local Compose stack; the script posts synthetic transactions and assumes its own fresh time window.

## Walkthrough and direct assertions

1. **Repeated request:** `TransactionServiceIT.idempotentReplayMovesMoneyOnce` posts the same key twice, asserts one transaction reference and one balance movement. `idempotencyKeyCannotBeReusedForDifferentAmount` rejects a changed amount and leaves one ledger row.
2. **Concurrent request:** `AccountDebitConcurrencyIT.concurrentRetriesSettleOnce` starts two requests at one latch with the same key and asserts one settled row. `concurrentDebitsCompetingForLastUnit_exactlyOneSucceeds` checks that eight competing debits cannot spend the same final 100,000 VND twice.
3. **Failure between writes:** `TransactionServiceIT.auditFailureRollsBackMoneyMovement` injects an audit persistence failure and asserts unchanged balance, ledger row count and audit row count. `overdraftIsRefusedAndLeavesNoTrace` checks a refused debit leaves no partial state.
4. **Reconciliation and audit:** `settledWindowFeedIncludesTheTransaction` checks the settled feed; `settlementIsAudited` finds a `TXN_SETTLED` audit entry. The Python `test_reconciler.py` cases distinguish balanced, missing, mismatched and duplicate references. The reconciliation service reads all core feed pages and never edits money movements.

## Design answers to defend

- **Transaction boundary:** The Java core owns money movement. Balance, ledger and audit are checked together against MySQL. The Python reconciliation service is read-only; discrepancies go to human review.
- **Idempotency:** A client-generated key identifies a retry. The database unique index is the last line of defense under concurrent inserts; a same-key changed payload is a conflict, not a replay.
- **Consistency:** Optimistic locking (`@Version`) prevents a stale account write from silently overwriting another debit. A conflict can require a client retry; this does not imply every concurrent request succeeds.
- **Limits:** This is a test system, not a production payment guarantee. No Kafka outbox, RabbitMQ reconciliation worker or scheduler is implemented. The demo proves the tested invariants on MySQL 8, not external settlement, operational recovery or a service-level throughput target.
