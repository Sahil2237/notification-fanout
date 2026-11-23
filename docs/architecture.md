# Current Kafka-backed architecture

```mermaid
flowchart LR
 P[Producer] --> A[Authenticated Dropwizard REST API]
 S[Manage subscription rules] --> A
 A -->|Match active subscriptions; atomically save event, jobs and outbox| D[(PostgreSQL)]
 A -->|202 only after commit| P
 D --> O[Outbox publisher with expiring lease]
 O -->|Event ID key, acks all| K[Kafka topic, six partitions]
 K --> C[Dispatch consumer]
 C -->|Activate persisted jobs idempotently| D
 C -->|Commit offset after DB commit| K
 D --> W[Delivery workers claim ready jobs]
 W -->|Signed HTTPS request| H[Approved subscriber webhook]
 H -->|Success, failure or timeout| W
 W -->|Attempt history and final state| D
 W -->|Persist retry schedule| D
 Q[Event or subscription audit query] --> A
 A -->|Tenant-scoped history| D
```

An unavailable Kafka broker delays dispatch but accepted events remain in the database outbox. Workers cannot claim newly accepted Kafka-gated deliveries before dispatch. A publisher crash can repeat Kafka publication; duplicate consumption does not create extra delivery jobs. Kafka offsets commit after the database dispatch transaction. Events still undispatched after the reconciliation interval can be republished. Webhook delivery remains at-least-once-style with bounded attempts and possible duplicates, requiring receiver deduplication by delivery ID.

Matching remains in the ingestion transaction, preserving the original active-subscription snapshot. PostgreSQL schedules delayed retries because Kafka does not provide arbitrary per-message scheduled delivery. Event IDs distribute a single event type across partitions; per-source ordering is not currently implemented.

For the selected low-cost DigitalOcean deployment, use one Bangalore Ubuntu 24.04 Droplet running Docker Compose: the API and delivery workers share one JVM, with a Kafka container and PostgreSQL container on the same private bridge. GitHub Actions deploys the tested revision over pinned SSH. PostgreSQL and Kafka persist in named volumes and have no published host ports. The API binds to host loopback; optional Caddy provides public HTTPS once a domain is configured. This is a single-server assessment deployment, with no host or broker failover. See [the deployment guide](../deploy/droplet/README.md) for setup and operational limits. The Bangalore Droplet is running the service, Kafka and PostgreSQL. GitHub CI/CD, live ingestion/dispatch, reboot persistence and a same-server backup restore have passed. Public HTTPS and a real receiver test await the domain and approved receiver origin. See submission-status.md for evidence and remaining limits.

```mermaid
flowchart LR
  G[Private GitHub repository] --> T[Unit, integration and Compose smoke tests]
  T --> B[Build image from tested commit]
  B -->|Pinned SSH transfer| D
  subgraph D[Bangalore Ubuntu 24.04 Droplet]
    C[Caddy optional HTTPS] --> A[Java API and delivery workers]
    A <--> P[(PostgreSQL volume)]
    A <--> K[(Kafka volume)]
  end
  U[Producer] -->|HTTPS or SSH tunnel| C
  A -->|Signed HTTPS| H[Approved webhook]
```

The earlier App Platform/managed-cluster scaffolding is an unused alternative, not the active target. No managed Kafka will be provisioned for the selected deployment.
