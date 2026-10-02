# Retail Finance Core Platform

A transaction-processing platform for retail financial outlets, built as two
services sharing one domain: a **Java/Spring Boot core** that owns the ledger,
and a **Python reconciliation service** that proves the ledger agrees with what
the partner bank reports.

```
  POS terminal / mobile app
          |
          |  REST + JWT
          v
  +------------------------+        pulls settled movements
  |  backend/              |----------------------------+
  |  Spring Boot 3 · Java 17|                            |
  |  MySQL 8 · Flyway      |                            v
  |  Redis · RabbitMQ      |              +-----------------------------+
  |  Prometheus · OpenAPI  |              | reconciliation-service/    |
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
  -> 201, TXN-20261002-8294172

POST /api/v1/transactions  { ..., "idempotencyKey": "POS01-20261002-0001" }   # retry
  -> 200, TXN-20261002-8294172      same reference, no second debit
```

This is enforced by a **unique index** (`uk_txn_idempotency`), not an
application-level check — so it holds under concurrent requests too. A race
between two in-flight retries is caught and resolved by returning the winning
row.

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
| Audit written in `REQUIRES_NEW` | An audit row survives a rollback — a failed debit is exactly the event an investigator needs. |
| Reconciliation never auto-corrects | Anything not an exact match becomes a discrepancy for a human. A job that "fixes" mismatches hides the loss it exists to surface. |
| Parser rejects non-integer amounts | A silently coerced amount yields a report that looks clean. Rejecting the file is safer. |

---

## Running it

```bash
docker compose up --build

# Core API        http://localhost:8080
# Swagger UI      http://localhost:8080/swagger-ui.html
# Reconciliation  http://localhost:8000/docs
---

## Tests

```bash
./scripts/verify.sh        # unit tests only — no Docker needed
./scripts/verify.sh --all  # unit + integration (needs Docker, for MySQL)
cd reconciliation-service && python -m pytest tests/ -v
```

Current state: **36 Java tests** (26 unit + 10 integration) and **11 Python
tests**, all green.

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

Implemented and tested: outlets, accounts, idempotent transaction posting with
optimistic locking, RBAC + JWT, append-only audit trail, and reconciliation
matching.

Not yet built: RabbitMQ async settlement, Redis caching, the Angular admin
console, and a reconciliation scheduler. They are wired into the compose file
but are the next increment, not current behaviour.
```

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