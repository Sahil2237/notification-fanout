# Visual design guide

[Hands-on test: one event → two subscribers → webhook verification](manual-two-subscriber-test.md) — copy-and-paste REST requests, health checks, delivery audit and private metrics access.

**Revised target:** [five tenants at 1,000 events/second each; five-minute cache sizing](capacity-1000-rps.md); higher-rate validation is recorded separately from the earlier 1,000/minute test.

**November 26 redesign:** [Current asynchronous matching, 5-minute event identity cache, subscription consistency and delivery history contract](async-cache-history.md). Older revision-specific test/cloud evidence below remains historical until new release validation is recorded.

Read this in order for an interview explanation. Diagrams describe the implemented 6474 ad 3 runtime and verified 25 November LB settings, not hypothetical microservices. [Current evidence](submission-status.md) separates current checks from historical failure drills. Markdown/Mermaid diagrams render directly in GitHub.

```mermaid
flowchart LR
    A[1. Requirements] --> B[2. Visual HLD]
    B --> C[3. Component LLDs]
    C --> D[4. Concepts and metrics]
    D --> E[5. Evidence and limits]
```

| Document | Use it to explain |
| --- | --- |
| [Current redesign](async-cache-history.md) | Asynchronous matching, cache correctness, archival and 10,000/s validation boundaries |
| [Requirements and scope](requirements.md) | Mandatory journeys, acceptance checks, exclusions and handoff |
| [Visual high-level design](hld.md) | System boundary, end-to-end flow, topology, failure domains and capacity |
| [Architecture detail](architecture.md) | Precise transaction/sequence/state/deployment diagrams |
| [Design decisions](design.md) | Recommendation, reason, trade-off and reconsideration trigger |
| [Concepts and metrics catalogue](concepts-and-metrics.md) | Every implemented design control and application metric, with export/verification gaps |
| [API contract](api.md) | Actual public/private routes, errors, idempotency and unsupported updates |
| [Network/security](network-security.md) | Public/private traffic, auth, effective LB settings and firewall gaps |
| [Validation](validation.md) / [submission status](submission-status.md) | Named tests, exact release evidence and what is not proven |
| [Operations](operations.md) / [interview walkthrough](interview.md) | Safe diagnosis, recovery, release and talking points |

## Separate low-level design documents

| Component | LLD | Boundary |
| --- | --- | --- |
| Notification business application | [Notification application](lld/notification-app.md) | REST use cases, identity, fanout jobs, delivery/audit and retries |
| Dropwizard runtime | [Dropwizard](lld/dropwizard.md) | Same Java process as the app: HTTP, validation, lifecycle, admission, configuration and metrics |
| PostgreSQL and Patroni | [PostgreSQL](lld/postgresql.md) | Durable records/transactions, standby reads, pools, replication and backup |
| Kafka | [Kafka](lld/kafka.md) | Outbox publication, dispatch consumption, partitions, offsets and DLQ |
| Redis and Sentinel | [Redis/Sentinel](lld/redis-sentinel.md) | Shared windows/retry budgets, bounded clients, discovery and failover |
| etcd | [etcd](lld/etcd.md) | PostgreSQL leader coordination, quorum and watchdog relationship |
| Caddy | [Caddy](lld/caddy.md) | TLS termination/reverse proxy, shared certificate storage and drain route |
| DigitalOcean load balancer | [Load balancer](lld/load-balancer.md) | Provider-managed routing, health thresholds, affinity and native telemetry |

Each LLD has component-specific low-level requirements, diagrams, configuration, state/failure behavior, security, metrics, tests and limits. Source/configuration links let a reviewer trace diagrams back to runnable code. The redesign reuses existing services and nodes; its throughput target still requires capacity validation.
