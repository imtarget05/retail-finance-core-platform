# Senior readiness backlog — Retail Finance

**Current evidence:** Java 17/Spring Boot ledger, MySQL/Flyway, Python
reconciliation, CI on a previous fix branch. This repo is a Java/Spring Boot
flagship candidate, not a production system or a claim of senior experience.

## Gate 0 — trusted baseline (this PR)

- Bring the Maven workflow fix from the green branch into main through review.
- Fail reconciliation on duplicate references; read every settled feed page.
- Reject balance arithmetic overflow; keep read-only triage advisory.
- CI must pass Java unit, MySQL integration, and Python tests on the same SHA.
- Record unresolved risks: payload mismatch on idempotency replay, concurrent
  duplicate transaction handling, and security of the reconciliation endpoint.

## Gate 1 — senior software and architecture

- Add integration tests for same key/different payload, concurrent retries,
  overflow, auth boundaries, and stable pagination under realistic load.
- Fix replay semantics so conflicting payload returns a conflict, with a
  durable request fingerprint. Use an isolated transaction boundary for a
  unique-key race; do not query inside a rollback-only transaction.
- Version the reconciliation report, persist run metadata, and require an
  authenticated operator to review/close discrepancies. Keep agent advice
  separate from approval.
- Add C4 context/container diagrams, API contracts, migration upgrade and
  forward-fix drill, capacity assumptions, threat model, and reviewable ADRs.

## Gate 2 — platform and delivery

- Harden non-root images, SBOM/dependency scan, immutable image digest,
  branch protection, promotion and rollback evidence.
- Instrument posting latency/error rate, reconciliation completeness,
  outbox lag (if introduced), queue age/DLQ (if introduced), and alerts with
  runbooks. Prove an incident and restore drill with measured RTO/RPO.
- Add broker increments only after ADR 0001's gate. Include cost ceilings,
  backup ownership, secrets rotation, and failure drills.

## Multi-agent automation, staged

1. **Business agents now:** evidence, risk, and action agents return structured,
   read-only advice for each discrepancy. They are deterministic rules, not
   LLM inference; every suggested action requires human review.
2. **Engineering automation:** scheduled reviewer checks exact-SHA CI,
   correctness, platform, and architecture findings. It reports only changes
   that need action; code edits still go through PR review.
3. **Optional AI:** after persisted runs and access control, an LLM may draft
   an evidence-linked explanation. Evaluate it against synthetic cases,
   redact sensitive fields, and keep final disposition human-owned.

No gate may be called complete from a local unit-test result alone.
