# Retail Finance Core Platform

A transaction-processing platform for retail financial outlets, built as two
services sharing one domain: a **Java/Spring Boot core** that owns the ledger,
and a **Python reconciliation service** that compares the ledger with partner
statements and reports differences for human review.

```
  POS terminal / mobile app
          |
          |  REST + JWT
          v
  +------------------------+        pulls settled movements
  |  backend/              |----------------------------+
  |  Spring Boot 3 · Java 17|                            |
  |  MySQL 8 · Flyway      |                            v
  |  Prometheus · OpenAPI  |              +-----------------------------+
  |                        |              | reconciliation-service/    |
  +------------------------+              | Python · FastAPI           |
                                         | CSV parse + match           |
                                         +-----------------------------+
```

---

## Why it is split this way

Reconciliation is a **batch, I/O-heavy, read-only** job: pull a window of
settled movements, parse a partner statement, line them up, report the gaps.
It shares no tables with the core and never writes to it. That boundary keeps
the Python service simple and lets the core stay strict.

Money is `int` in Python and `BIGINT` in MySQL on both sides, so a value
crosses the HTTP boundary without any rounding decision.

---

## The two guarantees this system is built around

### 1. A retried submission moves money exactly once

A POS terminal on a flaky link re-sends requests. The client generates an
`idempotencyKey` and reuses it on retry:

```
POST /api/v1/transactions  { ..., "idempotencyKey": "POS01-20261002-0001" }
  -> 200, TXN-20261002-8294172

POST /api/v1/transactions  { ..., "idempotencyKey": "POS01-20261002-0001" }   # retry
  -> 200, TXN-20261002-8294172      same reference, no second debit
```

The database has a **unique index** (`uk_txn_idempotency`) so two rows cannot
persist with the same key. The complete retry contract under concurrency is
still a Gate 1 item.

Same-key/different-payload requests now return HTTP 409, and a losing
concurrent insert reads the winning row after its own transaction rolls back.
These cases have MySQL integration tests. This is still not a production
payment guarantee: outlet authorization, account-opening atomicity, recovery,
and operational evidence remain open.

### 2. A refused debit leaves no partial state

The balance check and the balance write happen in one transaction at
`READ_COMMITTED`, with the account row guarded by **optimistic locking**
(`@Version`). Two concurrent debits cannot both read the same balance and
overwrite each other. If a debit is refused, its ledger row rolls back with it
— `TransactionServiceIT.overdraftIsRefusedAndLeavesNoTrace` asserts both the
unchanged balance *and* the unchanged row count.

---

## Design decisions worth knowing

| Decision | Why |
|---|---|
| `BIGINT`, never `FLOAT`/`DOUBLE` | VND has no minor unit. Whole dong as integers keeps arithmetic exact; `AccountTest.movementsRoundTripExactly` pins this. |
| `CHECK (balance >= 0)` in MySQL | The database refuses a negative balance even if application code is bypassed. |
| Flyway owns the schema; `ddl-auto: validate` | Hibernate never alters the schema. Migrations get reviewed like code. |
| `@Enumerated(STRING)` + `columnDefinition = "VARCHAR(n)"` | Avoids a MySQL native `ENUM`, so adding a role is a migration rather than a schema rebuild. |
| National ID stored as SHA-256 | The raw identity number is never persisted. |
| Audit written in `REQUIRES_NEW` | Audit writes use independent transactions; the operational consequences and failure handling still need review. |
| Reconciliation never auto-corrects | Anything not an exact match becomes a discrepancy for a human. A job that "fixes" mismatches hides the loss it exists to surface. |
| Parser rejects non-integer amounts | A silently coerced amount yields a report that looks clean. Rejecting the file is safer. |
| Reconciliation reads all feed pages and flags duplicate references | A truncated or collapsed feed could otherwise produce a false `BALANCED` verdict. |

---

## Running it

```bash
docker compose up --build

# Optional infrastructure playground; no application feature depends on it yet.
docker compose --profile messaging up --build

# Core API        http://localhost:8080
# Swagger UI      http://localhost:8080/swagger-ui.html
# Reconciliation  http://localhost:8000/docs
```

The reconciliation endpoint requires `Authorization: Bearer` with
`RECONCILIATION_API_TOKEN`. Compose supplies a local-only demo token; replace
it outside local development. Its call to the Java settled feed requires an
OPS JWT in `CORE_SERVICE_TOKEN`; without one the request fails closed. Do not
put either token in a committed file. The `/health` endpoint reports only
process health, not core connectivity.
---

## Tests

```bash
./scripts/verify.sh        # unit tests only — no Docker needed
./scripts/verify.sh --all  # unit + integration (needs Docker, for MySQL)
cd reconciliation-service && python -m pytest tests/ -v
```

Run CI on the exact review SHA for the authoritative test counts and result.
Local unit tests alone do not establish that MySQL integration tests pass.

The integration tests run against a **real MySQL 8** via Testcontainers, not
H2. That is deliberate: unique-index idempotency, `CHECK` constraints,
optimistic locking and transactional rollback are all enforced by MySQL itself.
An in-memory database would let these tests pass while production breaks.

---

## Project layout

```
backend/                              Spring Boot 3 · Java 17 · MySQL 8
  src/main/java/.../domain/           Account, TxnTransaction, Outlet, AppUser, AuditLog
  src/main/java/.../service/          TransactionService (idempotency + locking)
  src/main/java/.../security/         JWT provider, filter, 401/403 handlers
  src/main/resources/db/migration/    Flyway (schema + constraints)
  src/test/.../domain/                Fast, context-free domain tests
  src/test/.../integration/           MySQL-backed behaviour tests

reconciliation-service/               Python 3.11 · FastAPI
  app/models.py                       Domain types
  app/parser.py                       CSV statement parsing (strict)
  app/reconciler.py                   Matching rules
  app/main.py                         HTTP surface
  tests/                              Matching contract tests

docs/                                 Architecture notes and decisions
```

---

## Current scope, stated plainly

Implemented: outlets, accounts, transaction posting with optimistic locking,
RBAC + JWT, audit trail, and reconciliation matching. The exact integration
test result for this revision is determined by CI.

Not yet built: Kafka outbox/relay, RabbitMQ reconciliation jobs, Redis read
cache, the Angular admin console, and a reconciliation scheduler. Redis and
RabbitMQ are optional Compose examples, not application integrations. See the
[messaging ADR](docs/adr/0001-messaging-boundaries.md) and
[senior readiness gates](docs/senior-readiness.md). Reconciliation currently
has three deterministic, read-only triage agents; no agent can correct a
money movement.

With `SEED_ENABLED=true` the stack creates `admin`, `ops` and `pos01`
(password: `ChangeMe123!`).

```bash
TOKEN=$(curl -s localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"pos01","password":"ChangeMe123!"}' | jq -r .accessToken)

curl -s localhost:8080/api/v1/transactions \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"CASH_IN","accountNo":"ACC000001","outletCode":"OUTLET-HCM-01",
       "amount":250000,"fee":0,"idempotencyKey":"POS01-0001"}' | jq
```
