# Interview walkthrough

Explain one event end to end: validate/authenticate -> transaction -> matching subscription snapshot -> commit event/jobs -> 202 -> worker lease/attempt commit -> HTTP -> atomic result/audit -> retry or terminal state.

Questions to rehearse:
1. Why one database rather than Kafka? Atomic acceptance and fewer dependencies; move with an outbox when measured workload warrants it.
2. Why match synchronously? No versioned subscription snapshot/fanout cursor needed; latency and fanout size are the cost.
3. Where can duplicates happen? Receiver accepts but response/result is lost; stable delivery ID and receiver deduplication.
4. What does at-least-once mean with five attempts? Recoverable processing, possible duplicates, explicit terminal failure; no unconditional eventual receipt.
5. Why leases and tokens? Short transactions and crash recovery; old worker cannot overwrite newer owner. Fencing does not undo external HTTP side effects.
6. What if DB is down? No new successful acceptance; persisted queue resumes later. Liveness stays up, readiness fails.
7. What if subscriber deletes during ingestion? Matching SELECT visibility defines membership; queued deliveries use immutable destination snapshot.
8. What prevents cross-tenant access? Credential-derived identity, tenant predicates and composite foreign keys. RLS is future defense in depth.
9. Is the whole system production-ready? Distinguish implemented behavior/test evidence from absent production infrastructure, restore drills and hostname/fairness limitations.
10. How do you introduce a broker? Transactional outbox, idempotent relay and consumer; never independent save/publish.
11. Ordering? Serialize by tenant/subscription/source and decide whether a failed head blocks later work.
12. Replay? Authorized bounded range, retained events, new delivery generation and explicit filter/duplicate semantics.

Demo: start app/receiver, run demo.sh, show 500/500/204 history, demonstrate idempotency, delete subscription, restart with queued work, show tests and diagram. Explain actual measurements with machine/workload context. Be able to read every changed Java class and justify each SQL constraint.
