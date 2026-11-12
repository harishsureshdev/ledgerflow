# LedgerFlow

A payments platform that never double-charges or loses money, even when everything around it fails.
Double-entry ledger, idempotent payment APIs, transactional outbox to Kafka, a deliberately unreliable
fake card processor, nightly reconciliation, and a read-only AI investigator for reconciliation mismatches.

Portfolio project for a **Java / Backend SDE** resume (fintech domain). The point is to *prove*
correctness under failure with tests and a clickable demo, not to build a real bank.

## Core design principles (non-negotiable)

1. **Money is never created or destroyed.** Every movement is a journal transaction of balanced entries
   (sum of amounts per transaction = 0). The journal is **append-only**: no UPDATE or DELETE on entries.
   Mistakes are fixed with **reversal entries**.
2. **PostgreSQL is the single source of truth** for idempotency, payment state and balances.
   Redis is only for rate limiting and non-critical caching. Never make a money decision from Redis.
3. **Exactly-once *effect*, not exactly-once delivery.** Kafka is at-least-once. Correctness comes from
   idempotency keys + transactional outbox + idempotent consumers (dedupe by event ID).
4. **AI never moves money.** The reconciliation investigator has **read-only** tools. Any adjustment it
   proposes is executed only after a user with `ROLE_FINANCE_ADMIN` approves, enforced in the
   **service layer**, never in a prompt.
5. **PCI-aware.** Never accept, store or log card numbers (PAN) or CVV. Only tokens (`tok_...`).
   Log scrubbing is tested.

## Stack

- **Java 21+**, **Spring Boot 3.4.x** (with Spring Framework 6.2+)
- PostgreSQL + **Flyway** migrations, Spring Data JPA / JDBC
- **Kafka** (local via Docker; mention AWS MSK mapping in README)
- Redis (rate limiting via Bucket4j or similar)
- Spring Security: OAuth2 resource server (JWT) + RBAC roles: `CUSTOMER`, `OPS_AGENT`, `FINANCE_ADMIN`
- **Resilience4j** (timeouts, retries with capped backoff, circuit breaker) for processor calls
- **Spring Batch** for the reconciliation job
- **Spring AI** for the investigator; model provider configurable (Amazon Bedrock in prod, Ollama locally)
- Micrometer + **OpenTelemetry** tracing; Grafana dashboard
- Tests: **JUnit 5, Testcontainers** (Postgres, Kafka, Redis), **Toxiproxy** for network faults, k6 or Gatling for load
- Frontend: **React + TypeScript** demo dashboard
- Docker Compose locally

## Architecture

Modular monolith:
```
ledgerflow-app/
  payments/     API, idempotency, state machine, outbox writer
  ledger/       accounts, journal transactions + entries, balance checks
  processor/    client for sim-processor (Resilience4j), webhook receiver (HMAC verify, dedupe)
  recon/        Spring Batch job: settlement file vs ledger → recon items
  investigator/ Spring AI read-only tools + proposal records; approval endpoint (FINANCE_ADMIN)
  audit/        append-only audit log
  security/     OAuth2 resource server, RBAC
sim-processor/  fake card network: configurable latency, failure rate, DUPLICATE webhooks
web/            React/TS dashboard
```

## Domain rules

- **Money:** `long` amount in **minor units** (cents) + ISO-4217 currency code. Never `double`/`float`.
- **Time:** `Instant`, stored in UTC.
- **Payment state machine** (reject anything else at the domain layer):
  `INITIATED → AUTHORIZED → CAPTURED → SETTLED`; `INITIATED|AUTHORIZED → FAILED`;
  `CAPTURED|SETTLED → REVERSED`. Transitions live in one place, with a unit test per allowed/illegal pair.
- **Idempotency** (`Idempotency-Key` header, scoped per merchant), backed by a Postgres unique constraint:
  - same key + same request hash → return the **stored** status code and body
  - same key + different body → `422`
  - same key while the first request is still in flight → `409`
- **Balance checks:** lock the account row (`SELECT ... FOR UPDATE`) in the **same transaction** that
  inserts journal entries. No balance decisions from caches.
- **Outbox:** state change + outbox row in one DB transaction; a relay publishes to Kafka; consumers dedupe by event ID.
- **Webhooks:** verify HMAC signature, dedupe by processor event ID, return 2xx for duplicates.
- **Reconciliation mismatch types:** `MISSING_IN_BANK`, `MISSING_IN_LEDGER`, `AMOUNT_MISMATCH`, `DUPLICATE_SETTLEMENT`.
- **Errors:** RFC 7807 `application/problem+json`.

## Testing rules

- Every bug becomes a test first.
- Integration tests use Testcontainers, not mocks, for Postgres/Kafka/Redis.
- After every test that touches money: assert **journal sums to zero** and **no account below allowed minimum**.
- Concurrency tests use `CountDownLatch` / executors with many threads.
- A test verifies no PAN-like strings ever appear in logs.

## Code conventions

- Package by feature, not by layer.
- Records for DTOs, events and value objects (`Money`, `PaymentId`).
- Constructor injection only.
- Transactions at the service layer; keep them short; never call the processor inside a DB transaction.
- Every new endpoint declares its required role explicitly; no default-allow.
