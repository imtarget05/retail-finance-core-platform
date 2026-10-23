# Senior readiness backlog — Retail Finance

**Current evidence:** Java 17/Spring Boot ledger, MySQL/Flyway, Python
reconciliation, CI on a previous fix branch. This repo is a Java/Spring Boot
flagship candidate, not a production system or a claim of senior experience.

## Gate 0 — trusted baseline (this PR)

- Bring the Maven workflow fix from the green branch into main through review.
- Fail reconciliation on duplicate references; read every settled feed page.
- Reject balance arithmetic overflow; keep read-only triage advisory.
- CI must pass Java unit, MySQL integration, and Python tests on the same SHA.
- Verify same-key/different-payload conflict, concurrent replay, and operator
  auth on MySQL-backed CI. Recheck outlet-scoped authorization separately.

## Gate 1 — senior software and architecture

- Make account creation and its opening ledger movement atomic. Inject a
  failure between the two writes and prove no orphan account remains.
- Enforce outlet/account/transaction object-level authorization. A POS token
  from outlet A must not read or post for outlet B; add negative HTTP tests.
- Keep the demo JWT secret and seed password unavailable in non-local
  environments; replace plain SHA-256 national-ID hashing with a reviewed PII
  protection design before real customer data is used.
- Expand tests for cross-outlet access, rare reference collisions, overflow at
  the API boundary, and stable pagination while settlement is concurrent.
- Evaluate a durable request fingerprint for future request schema changes;
  current replay comparison uses the persisted transaction fields. Verify the
  unique-key race under repeated load, not just one two-thread test.
- Version the reconciliation report, persist run metadata, and require an
  individually identified operator to review/close discrepancies. The current
  shared API token protects intake but does not identify a human approver.
  Keep agent advice separate from approval.
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
