# Retail Finance agent workflow

This repository handles money movement. Agents may inspect, test, propose, and
prepare pull requests. Only reviewed application code can write the ledger.

## Three engineering roles

1. **Correctness reviewer:** trace transaction and reconciliation invariants;
   require a failing regression test for a claimed defect. Check idempotency,
   concurrency, overflow, duplicate references, and complete feed reads.
2. **Platform reviewer:** inspect CI on the exact commit, migrations, container
   security, dependency health, secrets, observability, recovery, and cost.
   Report missing evidence instead of inferring production readiness.
3. **Architecture reviewer:** verify service ownership and the ADRs. Challenge
   broker additions that duplicate another mechanism or put the ledger behind
   an eventually consistent cache.

The roles may analyze in parallel. One integrator owns changes to a file and
combines findings into a single review. Each finding needs a path, test or run
URL, impact, and proposed verification. A green badge on a different SHA is
not proof for the current change.

## Release gate

Run `mvn -B verify` in `backend/` with Docker for MySQL Testcontainers, and
`python -m pytest tests/ -q` in `reconciliation-service/`. Never skip a failed
integration test to make a gate green. Record the exact SHA and CI run. Any
schema change needs fresh-database and upgrade-path tests plus recovery notes.

The read-only reconciliation agents in `app/triage.py` provide advisory output.
They cannot approve a discrepancy, edit a statement, or post a transaction.
