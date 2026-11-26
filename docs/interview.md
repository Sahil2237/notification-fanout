# Interview walkthrough for the deployed implementation

**November 26 redesign:** [Current asynchronous matching, 5-minute event identity cache, subscription consistency and delivery history contract](async-cache-history.md). Older revision-specific test/cloud evidence below remains historical until new release validation is recorded.

Use [requirements](requirements.md), [architecture diagrams](architecture.md), [design trade-offs](design.md), [network/authentication](network-security.md) and [version-pinned evidence](submission-status.md). Explain what code and tests demonstrate, not what an ideal platform might do.

## One event end to end

Public HTTPS -> load balancer -> Caddy -> authenticate bearer tenant -> shared Redis fixed-minute admission -> validate/identity-check -> PostgreSQL transaction stores event/outbox -> synchronous commit ->202. Outbox publishes UUID-keyed Kafka envelope -> consumer matches the current subscription generation and atomically creates jobs once and commits offset after DB commit -> worker fairly reserves subscription/lease -> retry budget if needed -> persist attempt start -> signed HTTPS without holding DB transaction -> fenced result/audit -> delivered, scheduled retry or failed plus durable DLQ -> separate Kafka DLQ publisher.

## Questions to rehearse

1. **Why PostgreSQL and Kafka?** PostgreSQL makes acceptance/fanout state atomic and queryable; the outbox crosses the DB/broker boundary safely. Kafka already exists as the durable dispatch backbone, not a future addition.
2. **Why match after Kafka?** Remove candidate lookup and job creation from request latency. The consumer locks a persistent subscription generation and commits jobs with matching completion; replay does not rematch. Large unbounded fanout still needs durable progress before removing the subscription cap.
3. **Where can duplicates occur?** Uncertain publish/offset/HTTP outcomes. DB uniqueness prevents duplicate jobs; receiver transactional Delivery ID deduplication is still needed for exactly-once effects.
4. **What do five retries mean?** Six total transient attempts, not five total attempts or unconditional eventual delivery. Permanent failures stop early; exhausted work enters queryable DLQ.
5. **What does fencing protect?** Database claim ownership/results using unique tokens and expiry, not cancellation of already-issued receiver effects or a universal monotonically increasing fencing protocol.
6. **How are tenant limits shared?** Redis Lua/TIME plus same-connection replica acknowledgement;6,000 requests/tenant/minute across apps. No independent per-user limit; asynchronous Redis replication can lose counters.
7. **Why not send all reads to replicas?** User history/list reads can be eventual. Idempotency, matching, origin policy and job coordination need primary consistency. Standby-only read APIs fail rather than silently use primary.
8. **How are receivers isolated?** Four workers/app, globally one claim per subscription, fair subscription ordering, shared tenant/subscription retry budgets and bounded deadlines. Different subscriptions for the same URL are not grouped into an endpoint-wide bulkhead.
9. **What happens on node loss?** Three co-located application/data hosts with quorum replication; safe one-host failover tested. Container restart is Docker's job; no controller creates a replacement Droplet. Two whole-host losses remove safety quorum; two app-only failures are different.
10. **What is public and who authenticates?** Public HTTPS, Caddy, application static bearer authentication/tenant predicates, private administrator API. No separate API gateway/OIDC, no ETag/update route. Public SSH/backend ingress and broad egress are documented gaps; LB distribution is provider-managed with its legacy selector deprecated; live health checks use 5 s interval/3 s timeout and 2 failed/2 successful probes, with sticky sessions off. Do not claim a named provider-internal algorithm or exact-ten-second failover.
11. **What if the subscriber deletes during ingest?** Successful consumer matching defines membership. Changes before that point can affect recipients; existing queued jobs retain their destination snapshot. Duplicate POST subscription creates can produce separate registrations.
12. **Why six partitions and which key?** Internal event UUID avoids a single-type hotspot; currently three consumers/two partitions each. Use lag and measured service rate for scaling; consumers and HTTP workers solve different bottlenecks. No horizontal autoscaling or source ordering is configured.
13. **What would ordering require?** A source/subscriber ordering identity plus serial dispatch policy; decide whether a failed predecessor blocks later work. A partition key alone is not complete HTTP ordering.
14. **What would replay require?** Authorized bounded retained ranges, a delivery-generation identity, filter snapshot policy, limits and receiver deduplication semantics. No replay endpoint exists now.
15. **What can the backup recover?** Daily encrypted/authenticated Spaces data snapshot with 24-hour retention, not continuous WAL/PITR. Actual historical nine-table restore passed; new ten-table cloud restore is not claimed. Full service also needs roles, TLS, keys, broker rebuild and validation.
16. **Is it production-ready?** Mandatory behavior and named CI/cloud checks are implemented. Be explicit about same-region failure scope, unmeasured fanout/SLOs, static IAM, certificate/secret operations, metrics-provider failure and unsupported extensions. Alarms/tickets were intentionally excluded.

## Demo and evidence

Use synthetic data only. Show subscription filters, one matching event, the original event returned by retry, one delivery/audit and receiver HMAC/IDs. Show conflict 409, six-attempt behavior using local controlled receivers, DLQ history and replica headers. Do not claim a public demo sink implements receiver deduplication. Review the 72 Java/41 Python runtime test evidence and10,000-record partition-distribution test with their workload limits. A short no-match probe is not a fanout throughput result.

Do not disrupt the live cluster for an interview demonstration without a specific failure-test plan. Local disposable fixtures and recorded cloud evidence are available. Explain every changed Java class, SQL constraint and failure boundary. Arrange private-repository access and sign out of personal accounts only at final workstation handoff.
