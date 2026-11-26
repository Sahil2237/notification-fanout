# Visual high-level design

**November 26 redesign:** [Current asynchronous matching, 5-minute event identity cache, subscription consistency and delivery history contract](async-cache-history.md). Older revision-specific test/cloud evidence below remains historical until new release validation is recorded.

Start with [requirements](requirements.md). Explain diagrams 1 through 7 in order, then open the relevant [component LLD](README.md#separate-low-level-design-documents). Values describe the f0c4d33 runtime and 25 November 2026 read-only LB inspection. The functional redesign was deployed as f0c4d33; later documentation-only changes do not require another runtime deployment.

## 1. System boundary: who uses it?

```mermaid
flowchart LR
    P[Producer: structured event] -->|Public HTTPS and tenant bearer token| SYS[Notification fanout service]
    S[Subscriber: URL and filter registration] -->|Same tenant API| SYS
    O[Operator] -->|SSH tunnel and separate admin credential| SYS
    SYS -->|Signed HTTPS event with stable Delivery ID| W[Approved receiver]
    SYS -->|Status and attempt history| S
    SYS -->|Encrypted daily snapshot| SP[Private DigitalOcean Spaces]
    SYS -->|Local logs and metrics plus export attempts| M[Observability]
```

Contract: accept type/source/arbitrary JSON durably, match subscriptions, deliver asynchronously with bounded retries, and expose audit. Operators approve public receiver origins before registration. No replay/update/ordering API is hidden in this boundary. Exactly-once receiver business effects need receiver-side transactional deduplication.

## 2. One deployable app with separate responsibilities

```mermaid
flowchart TB
    I[Internet client] --> LB[Managed regional load balancer]
    LB --> C[Caddy TLS proxy]
    subgraph JVM[One Java container: Notification app running on Dropwizard]
        API[Authentication, validation and REST]
        O[Outbox publisher]
        K[Kafka dispatch consumer]
        W[Delivery workers and lease recovery]
        DL[Delivery DLQ publisher]
    end
    C -->|Loopback HTTP8080| API
    API -->|Atomic limit before event transaction| R[Shared Redis with Sentinel]
    API -->|Write and consistency-sensitive decisions| PG[(PostgreSQL primary)]
    API -->|History and lists only| RO[(PostgreSQL standbys)]
    PG --> O
    O --> B[Kafka event topic]
    B --> K
    K -->|Match subscriptions and create jobs| PG
    PG --> W
    W -->|Retry budget only for retries| R
    W --> H[HTTPS webhook receiver]
    W -->|Fenced audit and final state| PG
    PG --> DL
    DL --> KD[Kafka delivery DLQ]
```

**Dropwizard is the runtime inside the Notification app, not an additional application replica.** Ingestion commits event and outbox; Kafka consumers match subscriptions and create jobs in a second atomic transaction. Completed matching is not repeated on replay. Network delivery holds no database connection/transaction. One codebase keeps assessment complexity low; role separation into services is future work if independent scaling/ownership requires it.

## 3. Follow one event and its identifiers

```mermaid
sequenceDiagram
    participant P as Producer
    participant A as API
    participant R as Redis
    participant D as PostgreSQL
    participant O as Outbox publisher
    participant K as Kafka
    participant C as Dispatch consumer
    participant W as Worker
    participant H as Receiver
    P->>A: Event with optional producer ID and idempotency key
    A->>R: Tenant window reservation and replica acknowledgement
    A->>D: Deduplicate and commit event plus outbox
    D-->>A: Synchronous commit
    A-->>P: 202 with canonical Event ID
    O->>D: Claim outbox work
    O->>K: Publish keyed by Event ID
    K->>C: Deliver event envelope
    C->>D: Match subscriptions and create jobs and commit
    C->>K: Commit offset after DB commit
    W->>D: Reserve subscription and lease Delivery ID
    W->>H: Signed POST with Event ID and Delivery ID
    H-->>W: Response or timeout
    W->>D: Atomically record attempt and outcome
    Note over A,H: Canonical Event ID correlates logs, Kafka, audit and HTTP
```

The API does not synchronously publish Kafka in its request thread; the separate outbox role performs that work. A repeated producer request returns the original event or 409 for conflict. A repeated Kafka record does not create another event/subscription job. A lost successful HTTP response can still lead to repeated webhook effects.

## 4. Delivery attempts are durable, not an in-memory retry queue

```mermaid
flowchart LR
    J[Pending due job in PostgreSQL] --> L[Lease and subscription slot]
    L --> B{Retry attempt?}
    B -->|No| A[Persist attempt start]
    B -->|Yes| Q{Shared retry budgets available?}
    Q -->|No or Redis unavailable| DEFER[Defer without spending HTTP attempt]
    DEFER --> J
    Q -->|Yes| A
    A --> HTTP[HTTPS deadline5seconds]
    HTTP --> RESULT{Result with valid lease token?}
    RESULT -->|Stale| IGNORE[Reject stale DB completion]
    RESULT -->|2xx| OK[Delivered and attempt audit]
    RESULT -->|Transient and attempts remain| NEXT[Persist jittered backoff]
    NEXT --> J
    RESULT -->|Permanent or exhausted| FAIL[Failed plus delivery DLQ row atomically]
    FAIL --> PUB[Retryable Kafka DLQ publisher]
```

Defaults: six total attempts means initial plus five retries, 30 second lease, 1 second retry base/60 second cap, four delivery workers/app, one active delivery/subscription across all instances. Shared retry budgets are 300/tenant/minute and 30/subscription/minute. Expired/uncertain started attempts count toward exhaustion. There is no unconditional eventual-receipt promise.

## 5. Three machines, several independent HA mechanisms

```mermaid
flowchart TB
    U[Public client] --> LB[DigitalOcean LB: two managed units]
    subgraph VPC[BLR1 VPC: exactly three compute Droplets]
        subgraph N1[Droplet1]
            A1[App plus Dropwizard]
            D1[PostgreSQL plus Patroni]
            K1[Kafka broker and controller]
            R1[Redis plus Sentinel]
            E1[etcd]
            C1[Caddy and metrics bridge]
        end
        subgraph N2[Droplet2]
            A2[App plus Dropwizard]
            D2[PostgreSQL plus Patroni]
            K2[Kafka broker and controller]
            R2[Redis plus Sentinel]
            E2[etcd]
            C2[Caddy and metrics bridge]
        end
        subgraph N3[Droplet3]
            A3[App plus Dropwizard]
            D3[PostgreSQL plus Patroni]
            K3[Kafka broker and controller]
            R3[Redis plus Sentinel]
            E3[etcd]
            C3[Caddy and metrics bridge]
        end
        D1 <-->|Synchronous and streaming roles can move| D2
        D2 <--> D3
        K1 <-->|Kafka controller quorum and replicated partitions| K2
        K2 <--> K3
        R1 <-->|Redis replication and separate Sentinel election| R2
        R2 <--> R3
        E1 <-->|etcd quorum for PostgreSQL leadership| E2
        E2 <--> E3
    end
    LB --> C1
    LB --> C2
    LB --> C3
    D1 --> J[Daily job coordinated on existing hosts]
    D2 --> J
    D3 --> J
    J --> SP[age-encrypted dump to private SGP1 Spaces]
```

The backup job runs on whichever existing host acquires the database advisory lock, not a fixed database leader or fourth machine. PostgreSQL, Redis and Kafka do not share one election protocol. etcd is used by Patroni, not Kafka or Redis. Each host's roles share CPU/disk/memory and a region. This design targets one-host loss with a safe surviving quorum, not independent-zone or regional disaster tolerance.

## 6. Understand the two failure cases

```mermaid
flowchart LR
    F{What failed?} -->|Two Java app containers only| APPS[Databases, brokers and coordination still on all hosts]
    APPS --> ONE[Remaining ready app can serve with reduced capacity]
    APPS --> RESTART[Docker can restart exited containers on surviving hosts]
    F -->|Two whole Droplets| HOSTS[Only one member of each data cluster remains]
    HOSTS --> QUORUM[No safe quorum or required replication]
    QUORUM --> STOP[Normal durable admission unavailable and readiness fails]
    F -->|One whole Droplet| TWO[Remaining two hosts can fail over and rebalance]
    TWO --> RECOVER[Temporary errors then readiness recovers if safe]
    TWO --> MANUAL[Operator replaces or recovers lost Droplet]
```

Docker restart is not a Droplet replacement controller. There is no horizontal autoscaler. A healthy but hung process may be excluded by LB health without automatically being restarted. LB checks HTTP 9080/health/ready every 5 seconds, timeout 3 seconds, thresholds 2 failed/2 successful, sticky sessions off. Distribution is provider-managed rather than a user-configured round-robin/least-connections selector. These settings do not guarantee exactly-ten-second recovery.

## 7. Security and observability boundaries

```mermaid
flowchart LR
    INTERNET[Public network] -->|HTTPS443 and bearer authentication| EDGE[LB and Caddy]
    EDGE -->|HTTP loopback only| APP[Java8080 and private admin8081]
    APP -->|TLS and database credentials| DATA[PostgreSQL]
    APP -->|TLS and limiter credentials| REDIS[Redis]
    APP -->|Mutual TLS and topic ACLs| K[Kafka]
    CTRL[Patroni and etcd] -->|Mutual TLS coordination and private PG role control| DATA
    DATA -->|age encryption before HTTPS upload| B[Private Spaces backups]
    APP --> LOCAL[Local structured logs, audit and metrics]
    LOCAL --> BRIDGE[Bounded aggregate metrics bridge]
    BRIDGE --> DO[Insights export attempts: last observed provider404]
```

The control-plane arrow represents Patroni coordination and role management, not etcd replicating PostgreSQL data. Kafka and Redis use their own independent mechanisms. Public tenant endpoints require a token, but they are not private-only endpoints. Root SSH is key-only; cloud/UFW still allow public 22/80/443 and broad egress. No dedicated API gateway/WAF/OIDC is deployed. Webhook allowlisting and connection-time DNS checks mitigate SSRF in application code, not a restrictive network egress policy. Active-data disk/TDE/field encryption has not been established; do not claim complete encryption at rest. Backups are encrypted, while local proxy-to-Java traffic is loopback HTTP.

Local/custom application instrumentation and native LB metrics are different paths. Metrics Explorer visibility for notification.* remains unverified after provider 404; LB native health signals are provider-supported but current samples were not verified. No alarms/tickets are configured by request. See [the complete concepts/metrics catalogue](concepts-and-metrics.md).

## Explain the concrete limits

| Control | Implemented value | What it does not prove |
| --- | --- | --- |
| Tenant window |6,000 authenticated requests/minute shared across apps | No separate user quota or strict no-overshoot accounting through Redis failover |
| HTTP admission/app |32 active, 128 waiting, 1 second wait; Jetty 8-64 threads | Not a full bound on every library-internal queue |
| JDBC/app |8 write plus 4 standby-read maximum;3 second pool wait | Not 36 connections per DB: app totals are 24 write plus 12 read across cluster; other clients need headroom |
| PostgreSQL |100 connections/server, remote_apply and required sync standby | No regional RPO 0 or infinite connection capacity |
| Kafka |6 partitions, 3 consumers, RF 3/min ISR 2, event UUID key | No per-source ordering, managed queue or automatic scaling |
| Delivery |12 global workers and 1 active attempt/subscription | Not a guarantee of high fanout throughput or endpoint-wide isolation |
| Daily backup |One successful UTC-day snapshot and 24-hour retention | No continuous WAL/PITR or guaranteed usable backup if expiry/new-backup failure overlap |

Illustrative sizing, not a benchmark:100 events/s with three matches needs 300 delivery attempts/s. At 200 ms/receiver, 12 workers have an idealized 60 attempts/s ceiling, leaving 240 jobs/s backlog growth before retries and other overhead. The historical 10,000-event no-match probe and Kafka distribution test do not prove that fanout load. [Capacity assumptions and decisions](design.md#i-capacity-cost-and-evolution) explain the arithmetic and remaining measurements.

## What is complete and what is next?

Mandatory ingestion, subscriptions/filtering, fanout/retries/status and audit are implemented and deployed. Unit/integration/CI and named cloud checks are recorded in [validation](validation.md) and [submission status](submission-status.md). Per-user quotas, general cache, managed queue, source ordering, replay, resource updates/ETags, autoscaling, full tracing, complete at-rest encryption and regional failover are not claimed. Use [the LLD directory](README.md#separate-low-level-design-documents) to explain each component without inventing additional services.
