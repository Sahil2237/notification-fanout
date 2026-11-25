# Assessment requirements and acceptance evidence

This is the requirement-to-implementation map for the deployed assessment system, not a promise that every production failure has been tested. Runtime evidence is pinned to `6474ad3` on 24 November 2026; documentation-only commits can advance Git HEAD separately. [Current deployment](submission-status.md), [architecture](architecture.md), [design](design.md), [tests](validation.md).

## Mandatory functional scope

| ID | Requirement and acceptance condition | Actual implementation | Verification and limits |
| --- | --- | --- | --- |
| FR1 | Accept type, source and arbitrary JSON; never return202 before durable acceptance | `POST /v1/events`; transaction commits event, matching jobs and Kafka outbox; synchronous PostgreSQL commit | REST/transaction rollback, storage-failure and Kafka-outage integrations; latest public cloud202 and replay check. Loss of every DB copy plus usable backups is outside zero-loss scope |
| FR2 | Create/list/delete webhook subscriptions and type/source/payload filters without restart | `/v1/subscriptions`; JSON Pointer equality conditions combined with AND; tenant-scoped storage | Lifecycle, filter, ownership and deletion-boundary tests. Operator approves public HTTPS origin before subscriber registration; no subscription update endpoint requested |
| FR3 | Deliver to every matching subscription, retry failures with backoff, expose pending/delivered/failed | Matching snapshots jobs in ingestion; Kafka activates jobs; bounded workers persist attempts and jittered retry schedules; transient failures allow six total attempts then DLQ | Real HTTP retry/timeout/exhaustion tests and real Kafka journey; latest cloud duplicate submissions yielded one delivered job/one capture. Permanent failures stop earlier; uncertain receipt can cause repeat HTTP requests |
| FR4 | Query event/subscription history with attempt timestamps, response codes and final status | Event, subscription and delivery history APIs; tenant-scoped DLQ history | Integration tests plus latest cloud audit/receiver comparison. HTTP code can be null after no response or uncertain crash outcome; reads can briefly lag on replicas |

Accepted events with no matching subscriptions are still stored and dispatched through Kafka. Deleting a subscription stops future matches; it does not cancel jobs already snapshotted. New subscriptions do not receive historical events automatically.

## Mandatory engineering scope

| ID | Requirement | Status and evidence |
| --- | --- | --- |
| EN1 | Full architecture flow diagram | Implemented in [Mermaid diagrams](architecture.md): ingress, matching, durable transaction, outbox/Kafka, retry, DLQ and audit |
| EN2 | Explain delivery guarantees and failures | [README](../README.md#delivery-contract-and-failure-conditions): duplicate-tolerant processing with finite HTTP attempts; exactly-once external effects require receiver cooperation |
| EN3 | Unit and integration coverage | 72 Java tests and 41 Python tests passed for the runtime release, plus real-service CI drills. [Coverage matrix](validation.md) states named scenarios and untested boundaries; no exhaustive-coverage claim |
| EN4 | GitHub Actions tests on push | [CI workflow](../.github/workflows/ci.yml) and successful runtime [run 45447905686](https://github.com/Sahil2237/notification-fanout/actions/runs/45447905686). Image publication and signature-verifying deployment workflows are separate |
| EN5 | README setup, filters and simplicity/hardening trade-offs | Implemented in [README](../README.md), with detailed design, API and operations links |

## Nonfunctional acceptance and actual bounds

These are implemented controls or executed checks. They are not an agreed availability/latency SLA.

| Concern | Measurable implemented condition | Evidence or outstanding boundary |
| --- | --- | --- |
| Durability | Acknowledgement follows event/jobs/outbox commit with primary plus synchronous standby apply | Earlier whole-host failover preserved acknowledged probe IDs; latest migration preserved pre-rollout data. Regional loss has daily-backup exposure |
| Availability | Three application/data hosts; health-based load balancing; quorum replication | Latest three application/proxy readiness responses200 and Redis failover/rejoin; earlier PostgreSQL host-offline test. Temporary503/timeouts are permitted; no measured full-disaster RTO |
| Shared admission | 6,000 authenticated tenant requests per fixed minute across instances; exhausted429, unavailable Redis503 | Latest cloud fixture with two remaining slots produced200,200,429,429,429 across nodes; synthetic counter removed. No independent per-user quota or strict zero-overshoot failover accounting |
| Bounded resources | Four HTTP delivery workers/instance; subscription concurrency1 globally; writer pool 8/read pool 4 per app; PG max_connections100/server | Config/code and cloud pool setting checks. Jetty retains its internal I/O queue while application request admission is bounded; no claim that every library-internal queue is finite |
| Recovery and retries | Five-second HTTP deadline, 30-second claim lease, at most six transient attempts; budget-deferred work does not spend an HTTP attempt | Unit/integration timeout, fencing, concurrent reservation and exhaustion/DLQ rollback tests. Receiver deduplication remains necessary |
| Read consistency | Tenant history/list reads must use a standby, with no primary fallback | JDBC HA fixture checks role changes/unavailability; cloud headers report replica/eventual. Immediate post-write reads may lag |
| Security | Credential-derived tenant; separate admin/runtime/migration roles; HTTPS, private TLS cluster ports, public-address destination validation, HMAC webhooks | Authorization/SSRF/signature/security-gateway tests, public HTTPS/capture checks; root password locked and SSH password login disabled. Static credentials are not a full identity platform |
| Backup/recovery | One successful UTC-day snapshot; 24-hour backup retention plus timer delay; encrypted/authenticated Spaces objects | Earlier actual Spaces restore checked nine historical snapshot tables. Latest tools verify ten-table snapshots and backup access to delivery DLQ; a new ten-table Spaces restore was not executed for this rollout |
| Observability | Common event IDs, attempt audit, local metrics and health, bounded aggregate export | Local metrics/bridge deployed. Last observed provider 404 blocks Metrics Explorer confirmation. No alarms/tickets by explicit request |
| Performance | Admission and concurrency are bounded; report test workload rather than invent an SLA | Historical10,000-event test was no-match50 events/s; separate Kafka distribution test is not an end-to-end fanout benchmark. Representative slow-receiver/fanout soak and p95/p99 targets remain open |
| Retention/privacy | No silent business-history deletion | Event/audit retention and idempotency expiry are not specified or automated. Backup expiry does not delete event history; no compliance certification |

## Extensions and deliberate exclusions

| Extension | Actual status |
| --- | --- |
| DigitalOcean deployment | Complete on three existing BLR1 Droplets with a managed regional load balancer and private Spaces bucket |
| Real managed message queue | Not deployed: the user deferred managed Kafka. The backbone is real, self-managed three-broker Kafka; this is a deviation from the managed-queue extension |
| Per-source ordering to a subscriber | Not implemented. UUID partition keys favor distribution; ordering also needs a delivery scheduler policy for failed/retrying predecessors |
| Subscriber time-range replay | Not implemented. Requires authorization, retained range limits, new delivery generations and explicit filter/deduplication semantics |
| Horizontal autoscaling | Not configured; manual scaling/rebalance only |
| General event/subscription cache | Not implemented; Redis is a counter/budget store |
| Alarms and ticket integration | Explicitly excluded; do not treat this as an accidentally missing mandatory feature |

## Environment and handoff obligations

All project development belongs under `/workspaces/notification-fanout` in the Ubuntu 24.04 container; no project code/assets belong on host Desktop/Downloads. Java 25/Dropwizard 5 are used. The existing session used Rancher with IntelliJ at the user's request. The checked-in [Dev Container configuration](../.devcontainer/devcontainer.json) supports the brief's VS Code/Cursor route, but that editor route was not the verified IDE session; obtain assessor acceptance of IntelliJ if the named-editor rule is strict.

Code, diagrams and workflows are pushed to the personal private [GitHub repository](https://github.com/Sahil2237/notification-fanout). Grant interviewer access separately. The user relaxed the original three-hour limit; no claim is made that the historical work finished within three hours. Personal-account sign-out is a final workstation handoff obligation, not an action already completed during active development. Do not commit tokens, private keys, DSNs, backup contents or protected `.deploy` files as submission evidence.
