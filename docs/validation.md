# Validation, release evidence and open work

This is the single requirements/status/evidence document. Historical JSON records are retained unchanged; they do not establish that every old scenario was rerun on the latest application. [Design](system-design.md), [API](api.md), [runbook](operations.md), [demo](interviewer-demo.md).

## Current deployed state

Application deployment bundle **d1612d82b64a6a66c2d1ae2ff3cd843525b90cb9** runs on all three nodes. Application JAR source is fa1a0b1ea664ce6136d8e7a1fc6c9c18ed21b917; the later bundle adds the Docker metadata-verification compatibility correction and docs. Java 25/Dropwizard 5 application code is unchanged by subsequent documentation-only commits.

The consolidation audit on 27 November 2026 rechecked all nodes: readiness 200, undrained, eleven migrations, same deployed SHA. Public tables were checked: fourteen physical tables, comprising twelve application and two Liquibase tables. Backup metadata verifies thirteen tables, excluding databasechangeloglock. [Dated audit inventory](evidence/documentation-audit-2026-11-27.json).

The latest release used a locally verified JAR, an AMD64 runtime built from the pinned Java base with package upgrades, current Trivy 0.76.2 vulnerability/Java databases, existing release signing and sequential gateway promotion. Signed archive config digest: sha256:13a380d76957e951853a50c208ce386168dbb2d059378a2f351f56f3734dff5e. The JAR hash/portable identity/fleet checks are in [the release record](evidence/subscription-idempotency-cloud-2026-11-27.json). Legacy Config.Image build metadata alone is normalized; filesystem and execution-setting tampering tests remain enforced.

## Executed release checks

| Suite | Tests | Result |
| --- | --- | --- |
| CoreTest | 22 | Passed |
| MetricsBridgeTest | 2 | Passed |
| MetricsExporterTest | 4 | Passed |
| AsyncCacheHistoryIT | 17 | Passed |
| KafkaIT | 12 | Passed |
| RedisIT | 5 | Passed |
| ServiceIT | 36 | Passed |
| Java total | **98** | Zero failures/errors/skips in local reports |
| Deployment Python | 14 | Passed |
| HA/config/backup Python | 49 | Passed |
| Python total | **63** | Passed |
| Local runtime image | Configuration and tested-JAR equality | Passed |
| Local image vulnerability gate | Fixable HIGH/CRITICAL, Ubuntu and Java | Zero matching findings |
| Sequential signed cloud promotion | Three nodes and migration 011 | Passed |
| Latest synthetic cloud flow | Two subscriptions, one event, two signed external captures | Passed |

Source suites: [Java tests](../src/test/java/com/example/notifications), [deployment tests](../deploy/droplet), [HA tests](../deploy/ha). Current feature tests cover concurrent keyed registration, original 201/Location replay, tenant/operation isolation, changed URL/filter409, key validation/authentication, canonical order, cap handling, no reactivation after delete, rollback and one intended recipient after registration retries.

[CI 45943599738](https://github.com/Sahil2237/notification-fanout/actions/runs/45943599738) passed Java, both Python suites and HA configuration validation. **Overall CI failed on Actions artifact storage quota.** Hosted release/security/HA-runtime jobs were skipped. Local signed deployment is not equivalent to a passing latest full CI pipeline; do not silently mark the skipped hosted HA drills as executed. The previous [70f0df1 verification](https://github.com/Sahil2237/notification-fanout/actions/runs/45927971103) and [deployment](https://github.com/Sahil2237/notification-fanout/actions/runs/45929375771) succeeded for their own revision.

Fresh cloud event be35433b-ebc0-4732-88b9-cca8fdba577a returned 202/duplicate=false; event replay returned the original ID. Both subscription creation replays returned original 201 responses, conflicting filters409. Two distinct Delivery IDs reached delivered with one 204 attempt each, externally captured event/subscription/delivery IDs, exact event body and valid tenant-derived HMAC. The same registration replay passed directly against each app. All nodes returned metrics/readiness 200 and retained default-deny egress; twenty sampled LB readiness requests returned 200 during part of rollout. This is sampled availability, not a continuous zero-interruption proof. Temporary demo subscriptions remained active for the user; receiver expiry was requested as24 hours and those URLs are not permanent test dependencies.

## Requirements coverage

| Requirement | Verification and scope |
| --- | --- |
| Type/source/arbitrary JSON ingestion | REST validation, transaction and replay integrations; accepted event/cloud GET/callback proof |
| Accepted events durable | Event/outbox atomicity, publisher recovery/reconciliation, uniqueness and earlier HA acknowledged-row checks |
| Dynamic subscription management | Create/list/delete/filter/ownership tests; actual two keyed cloud registrations, conflicts and replica-list access |
| Consumer-time matching | AsyncCacheHistoryIT membership/create/delete/cache-generation and repeated-dispatch scenarios |
| Retry/backoff/status | RetryPolicy, timeout, reservation/fencing, exhaustion/DLQ and rollback tests; latest cloud success path, not a new six-failure live receiver test |
| Audit per event/subscription | HTTP integration and transparent active/history reads; real attempt 204 and capture agree |
| Delivery guarantee | At-least-once HTTP attempts, duplicate-job protection and receiver deduplication responsibility documented |
| Architecture diagram | End-to-end and component/data/state Mermaid diagrams in system-design.md |
| Unit/integration testing |98 Java and 63 Python; not a claim of exhaustive scenario coverage |
| CI on code push | Workflow configured and test steps passed; artifact publication/storage is an unresolved operational blocker |
| README setup/filters/trade-offs | README, API and design are the only canonical sources for these concerns |
| DigitalOcean deployment | Three real Droplets, regional LB, private cluster network and Spaces backup |
| Managed queue extension | Deferred by user; actual Kafka is self-managed |
| Per-source ordered delivery | Not implemented |
| Subscriber time-range replay | Not implemented |

## Backup and recovery evidence

- An actual Spaces snapshot created 24 November 2026 at 06:51:26 UTC was fetched, authenticated/decrypted, restored into disposable network-isolated PostgreSQL 17 and checked against all nine historical snapshot counts. [Historical HA/restore record](evidence/cloud-ha-bd082b6.json).
- Backup tools were updated to support the current thirteen snapshot tables; both restore-helper paths were synchronized. Migration 011 adds subscription key/hash columns inside an existing backed-up table.
- A fresh actual thirteen-table Spaces restore and end-to-end service recovery have **not** been verified. Local/CI restore tests and an active timer are separate evidence.
- Read-only 27 November job inspection found timers active on all three nodes; node 2 recorded backed_up at 02:00:19 UTC/07:30:19 IST and later checks not_due. [Audit inventory](evidence/documentation-audit-2026-11-27.json).
- Whole-Droplet automated backups, continuous WAL/PITR, automatic disaster restoration and measured full-disaster RTO are not configured/proven. One-day backup retention can leave no valid snapshot if replacement fails after expiry.

## Capacity and HA boundaries

The target is five tenants at 1,000 requests/second each, not an achieved 5,000-event/second capacity rating. Quota also counts non-ingestion requests. Cache admission is 32 MiB/50,000 entries, not a full 1.5 million-event five-minute working set. No infrastructure resize was performed for that target.

The [1,000-event/minute test](evidence/load-1000-per-minute-2026-11-26.json) recorded acceptance at roughly 16.7/second; it is not 1,000/second or representative fanout throughput. [Earlier10,000-event evidence](evidence/release-57b3fbf.json) was a 50/second no-match test. A single Kafka distribution test does not certify end-to-end throughput. Representative bursts, slow/failing receivers, realistic fanout, disk growth, one-host-degraded throughput and agreed p95/p99/SLOs remain open.

[Earlier whole-Droplet failover evidence](evidence/cloud-ha-bd082b6.json), [Redis Sentinel failover](evidence/cloud-redis-6474ad3.json) and [asynchronous redesign checks](evidence/cloud-async-cache-history-f0c4d33.json) are revision-pinned. The latest subscription change did not repeat whole-host failure. Configured restart policies were inspected on 27 November, not retested by reboot during the audit. One-host/quorum survival is different from a region-wide zero-loss guarantee.

## Network, security and telemetry evidence

| Claim | Evidence and boundary |
| --- | --- |
| Provider LB settings | [25 November settings](evidence/load-balancer-2026-11-25.json): health timing/affinity; provider-managed distribution, no exact internal scheduler name |
| LB-only ingress and restricted egress | [27 November provider/host record](evidence/provider-network-hardening-2026-11-27.json) |
| Direct backend isolation | [Protocol checks](evidence/network-hardening-final-2026-11-27.json); corporate gateway limitation recorded; TCP connect alone is insufficient |
| Signed external webhook | [Latest two-receiver proof](evidence/subscription-idempotency-cloud-2026-11-27.json); earlier [single callback](evidence/network-webhook-2026-11-27.json) |
| Local metrics | All three loopback endpoints checked; no public metrics port |
| DigitalOcean custom metrics | [Last recorded 404/visibility gap](evidence/metrics-cloud-cf92a03.json); no claim of successful Explorer display |
| SSH | Public source scope retained at user's explicit decision, key-only login and restricted CI identity; not private-only host administration |
| Secrets/privacy | No tokens, signing keys, production dumps or full environment/ACL data belong in committed evidence |
| Encryption | HTTPS/private TLS and age-encrypted backup implemented; full active-disk encryption/compliance not established |

## Remaining work and release acceptance

Implemented controls should not be presented as a fully hardened production platform. Required follow-up depends on the interview/production scope:

1. Restore a latest-schema actual Spaces backup and exercise recovered application/idempotency/audit; escrow recovery keys/configuration outside both cluster and bucket.
2. Resolve Actions artifact quota/publication without discarding required recovery bundles; rerun full hosted image/HA gates before calling CI healthy.
3. Measure sustained/burst ingestion, matching and delivery capacity, including fanout/slow receivers/degraded nodes; set global/backlog/disk thresholds and agreed SLOs from results.
4. Improve outbox/consumer/DLQ retry backoff, partial acknowledgement tracking and webhook recovery window/Retry-After handling. Those recommendations are not deployed.
5. Verify custom metrics in Explorer and establish reliable pool/lag/disk/backup/certificate visibility. Alarms/tickets remain explicitly excluded unless requirements change.
6. Plan certificate/credential renewal, durable secret escrow, retention/partitioning and dedicated/managed service capacity as actual growth justifies.

A release acceptance check must cover tests/security, migration compatibility, exact signed image identity, all-node readiness/no drains, public authenticated smoke tests, unchanged idempotency/recipient semantics and independently captured webhooks. Infrastructure/data upgrades require their own safe quorum/restore verification. No failure injection, reboot, resize or new paid resource is part of this documentation consolidation.

## Documentation audit and consolidation

The previous 37 Markdown pages mixed historical and current narratives. The canonical set is now six: root README, system-design, api, operations, validation and interviewer-demo. Removed pages remain recoverable in Git history; historical JSON evidence stays intact. Component LLDs moved into system-design.md; onboarding/manual test content is split between README, API, interviewer-demo and operations. Network/firewall/storage/backup/metrics are owned by operations; status/test/capacity evidence by this document.

Corrections include Kafka-consumer-time matching instead of ingestion-time recipient snapshots; targeted Redis caches instead of counters-only claims; current subscription idempotency; current pools/memory/retry caveats; fourteen physical versus thirteen snapshot tables; resolved restore-helper drift; current application/backup-tool revisions; and artifact-blocked latest CI instead of an unqualified green pipeline.

This refresh changes documentation only. It does not clear Actions usage, change service configuration, deploy another image, generate a new event, renew credentials or perform a new failover/restore test. Assessor repository access and personal-account sign-out remain explicit handoff obligations; the original editor requirement and the user-requested IntelliJ alternative are distinguished in README.

Documentation-only verification on 27 November 2026 passed: six canonical Markdown files, eight Mermaid diagrams parsed, 77 relative links/anchors resolved, 27 shell blocks passed bash syntax checks and five JSON examples parsed. Demo fixtures passed three generated requests, two filter examples, thirteen positive/negative predicates and local transport/status/retry checks. No live events/subscriptions were created, and no application redeployment was needed. These documentation checks do not represent a fresh run of the Java/Python release suites above.

Fresh-demo refresh: two independent local preparation runs produced distinct folders, run IDs, types, sources, producer IDs and all three idempotency keys. Order IDs now derive from the run ID. All thirteen Bash blocks passed syntax checks; three request-generation examples, two matching filters, thirteen predicates and local transport/status/retry checks passed again. No cloud events, subscriptions or receiver inboxes were created by this documentation refresh.
