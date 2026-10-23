# ADR 0001 — Messaging and cache boundaries

**Status:** accepted architecture; broker integrations below are proposed
increments until their gates pass on CI.

## Context

MySQL is the authoritative ledger. A successful HTTP response must reflect a
committed account and transaction update. A broker outage must not create an
ambiguous debit. The current application has no Kafka producer, RabbitMQ
consumer, or Redis-backed business operation, despite their Compose entries.

## Decision

| Technology | Retail Finance responsibility | Explicit boundary |
| --- | --- | --- |
| Kafka | Publish immutable `TransactionSettled.v1` facts for downstream analytics after commit, through a transactional outbox and a replayable relay. | Kafka does not decide whether a debit succeeds. Consumers deduplicate by event ID. No claim of end-to-end exactly-once across MySQL and Kafka. |
| RabbitMQ | Dispatch reconciliation jobs with bounded retry and dead-letter handling once run IDs, result persistence, and idempotent workers exist. | No money movement on a queue; a duplicate delivery cannot create a second run effect. |
| Redis | Cache non-authoritative, read-heavy summaries with TTL and invalidation only after measured DB pressure. | Never store balance or idempotency truth in Redis. Redis failure falls back to MySQL. |

The first two increments require workload evidence. Kafka needs a second
consumer that actually benefits from replay; RabbitMQ needs asynchronous
statement volume beyond the current HTTP request path; Redis needs a measured
read bottleneck. Until then, Compose services are development examples, not
proof of integration. They must not be mandatory dependencies for core startup.

## Cross-project fit

| Project | Primary use |
| --- | --- |
| Retail Finance | Kafka for ledger facts after outbox gate; RabbitMQ for reconciliation commands; Redis only for measured, disposable read cache. |
| OpsBridge / FlashSale | Existing order event streaming and short-lived inventory reservation/cache must be evaluated against its own outbox and load-test evidence. Avoid a second broker for the same order transition. |
| QuanLyKho | PostgreSQL transaction and outbox own stock truth; use a broker only after worker/retry throughput is measured. Never use Redis as stock truth. |
| AKS-SRE-Platform | Own deployment, security policy, metrics, backup and cost of any approved broker; it has no business events of its own. |

## Delivery and failure gates

1. Kafka: migration inserts an outbox row in the same DB transaction as
   settlement; relay survives restart and broker outage; replay emits one
   logical event ID; consumer contract and schema evolution tests pass.
2. RabbitMQ: durable run record precedes enqueue; worker acks only after
   persisted result; bounded retry, poison message and dead-letter drill pass.
3. Redis: benchmark shows value; TTL and write invalidation tests pass; Redis
   unavailable test proves reads still work from MySQL.

See [Kafka's processing semantics](https://kafka.apache.org/43/design/design/),
[RabbitMQ quorum queues](https://www.rabbitmq.com/docs/quorum-queues), and
[Redis cache-aside](https://redis.io/docs/latest/develop/use-cases/cache-aside/)
for the vendor guarantees behind these gates.
