# Requirements-to-verification matrix

**27 November network update:** [Provider LB-only backend ingress and restricted egress are deployed; SSH sources remain unchanged by user decision](network-hardening.md). Historical observations below remain version-pinned.

**November 26 redesign:** [Current asynchronous matching, 5-minute event identity cache, subscription consistency and delivery history contract](async-cache-history.md). Older revision-specific test/cloud evidence below remains historical until new release validation is recorded.

Runtime release `6474ad3` passed [CI45447905686](https://github.com/Sahil2237/notification-fanout/actions/runs/45447905686) and [image publication45450924076](https://github.com/Sahil2237/notification-fanout/actions/runs/45450924076). Counts below were checked against that run's JUnit XML, not stale documentation. Documentation-only changes use diagram/link/contract checks rather than claiming a new application test/deployment run.

## Executed release checks

| Suite | Tests | Outcome |
| --- | --- | --- |
| CoreTest |21|Passed |
| MetricsExporterTest |4|Passed |
| MetricsBridgeTest |2|Passed |
| ServiceIT: real PostgreSQL/HTTP |29|Passed |
| KafkaIT: real Kafka/PostgreSQL/HTTP |12|Passed |
| RedisIT: real Redis integration |4|Passed |
| Java total |72:27 unit+45 integration|Zero failures/errors/skips in CI reports |
| Python deployment/security |12|Passed |
| Python HA/configuration/backup |29|Passed |
| Python total |41|Passed |

CI additionally exercised real runtime startup/DB permissions, deliberate failed-release rollback and restore, real three-member synchronous PostgreSQL failure with JDBC standby reads, real TLS Redis/Sentinel promotion with existing Jedis clients, proxy routes/draining, signed packaging and fixable HIGH/CRITICAL image scans. Those drills are not extra Java/Python test-case counts. Scans passed for the app, database/backup tools, proxy, Kafka, etcd and Redis images at build time; they do not prove absence of unfixed, unknown or future vulnerabilities, or that the Rancher host engine was upgraded.

## Material-risk coverage

| Requirement or failure risk | Automated verification | Cloud evidence boundary |
| --- | --- | --- |
| Structured/filterable durable acceptance | Filter/null/missing/array/JSON Pointer cases; all JSON kinds; transaction rollback/storage-error responses; atomic outbox rollback | Latest public202/replay and pre-migration preservation of 10,136 events/five subscriptions |
| Dynamic subscriptions and authorization | REST lifecycle, tenant ownership, deletion boundary, invalid filters and origin approval/revocation | Historical public HTTPS registration; latest normal list/history reads |
| Destination abuse | Public HTTPS policy, mixed/private DNS answers and connection-time rebinding cases; administrator isolation | Public administrator404 and signed synthetic receiver capture; no exhaustive penetration-test claim |
| Duplicate event requests | Concurrent explicit/generated canonical identities; producer-ID conflict and identity scoping | Same missing-key body through three apps returned original event; explicit replay/conflict 409 |
| Kafka duplicate/recovery boundary | Outage/restart, duplicate envelopes, quarantine, partition-specific failure, offset-after-DB-commit and outbox lease recovery | Earlier whole-host broker loss retained ISR2 and delivered a webhook; latest normal pipeline/capture |
| Single event-type distribution |10,000UUID-keyed records through real six-partition Kafka | Not a10,000 events/s or complete fanout benchmark |
| Retry/timeout/audit | Real500/500/204, permanent errors, timeout cancellation, backoff, null status and six-total-attempt exhaustion | Latest live event delivered204 in one attempt; no new live six-failure exhaustion probe |
| Fencing and partial failures | Concurrent claims, lost HTTP-success result, expired/stale worker/DLQ tokens and rollback if terminal DLQ insert fails | Redis failure did not cause a second observed successful-event capture; no universal exactly-once claim |
| Shared rate limit and retry budgets | Real Redis atomic windows, independent clients, reset/identity behavior and bounded resource configuration | Synthetic two-slot quota produced200,200,429,429,429 across apps; counter removed before reopening ingress |
| Redis safety/HA | TLS hostname rejection, application ACL denial, existing client follows promotion, no-replica failure closes admission, rejoin | Latest cloud Redis/Sentinel primary-pair stop yielded503,503,200; all app clients recovered; replicas rejoined |
| Subscriber isolation/fairness | Cross-instance reservation caps and least-recently-claimed scheduling | No representative long-running heterogeneous-receiver fairness/soak claim |
| Replica-only reads | Reject primary as read pool; actual JDBC standby selection before/after promotion; fail when no standby remains | Latest history/list responses identify replica/eventual |
| Delivery DLQ | Atomic terminal record, tenant history, real Kafka DLQ publication, publisher ownership and event correlation | Topic/ACL provisioned RF3/min ISR2; backup role can read new DLQ table |
| Release authenticity and rollback | Bad signature/path/symlink/manifest/digest/config/layer rejection; real failed-startup rollback/restore | Latest app images verified by portable identity and promoted on all hosts; no infrastructure restart during application rollout |
| PostgreSQL HA/data | Real TLS etcd/PostgreSQL cluster preserves100 acknowledged rows after primary loss and accepts another write | Earlier actual Droplet power-off promoted a different primary; latest data-preservation comparison is separate |
| Backup integrity/retention | Manifest/checksum/authentication/expiry-prefix tests and isolated restore drills; historical metadata compatibility | Earlier actual Spaces archive restored all nine snapshot counts. New ten-table actual Spaces restore not executed for latest rollout |
| Metrics/export | Six Java exporter/bridge tests: actual bounded local OTLP HTTP transport, deltas/labels, endpoint rejection, failure/no retry, scrape/restart behavior | Bridges running; last provider 404 means Explorer visibility remains unverified |

Historical [local HA evidence](evidence/local-ha-507f989.json), [whole-Droplet/Spaces restore evidence](evidence/cloud-ha-bd082b6.json) and [latest controls evidence](evidence/cloud-redis-6474ad3.json) are deliberately separate. The full historical Rancher HA drill preserved 106 acknowledged events and four HTTP 204 deliveries; it was not rerun in full for the Redis rollout. The new dedicated Redis drill was run locally and in CI.

## Reproduce safely

Inside the Ubuntu container under `/workspaces/notification-fanout`:

```sh
mvn -B -ntp verify
python3 -m unittest discover -s deploy/droplet -p 'test_*.py' -v
python3 -m unittest discover -s deploy/ha -p 'test_*.py' -v
```

Use dedicated disposable test services/database only. Tests migrate/truncate test data; never set TEST_DB_URL or test Kafka/Redis endpoints to shared production resources. The CI workflow documents actual ephemeral-service/image dependencies and guards for destructive local HA fixtures. See README and deploy/ha/redis.md for local setup rather than copying cloud credentials.

For cloud release acceptance, verify signed revision/image identity, all three app/proxy readiness states, expected replication/quorum, authenticated public HTTPS, synthetic matching/nonmatching behavior as appropriate, history, receiver capture/HMAC and original-event idempotency. Record which failure drills were actually repeated. AsyncCacheHistoryIT adds cache expiry/failure/LRU, consumer-time membership, concurrent replay and terminal-history/deduplication checks. Do not manipulate a live request quota; the latest fixture ran only while every backend was drained and removed its synthetic state before opening ingress.

## Remaining validation gaps

No suite covers every possible failure. Full-region recovery, full-service disaster RTO, elapsed certificate-renewal/backup-expiry cycles, sustained representative fanout/slow-receiver saturation, endpoint-wide fairness and a negotiated production availability/latency SLO remain open. No separate per-user quota, autoscaler, ETag update, managed queue, ordering or replay tests can pass as implemented features because those capabilities do not exist. Alarms/tickets were excluded. A subsequent read-only control-panel inspection verified live health/affinity values; [evidence](evidence/load-balancer-2026-11-25.json) distinguishes those fields from the provider-documented managed distribution algorithm. No equal-distribution load benchmark or precise failover RTO was run.

## November 26 redesign validation

Local validation runs in the existing Rancher Ubuntu container under /workspaces/notification-fanout. The new AsyncCacheHistoryIT exercises database-backed concurrent duplicate handling, consumer replay and matching rollback, processing-time subscription membership, Redis expiry/failure/LRU limits, bounded publication claims, terminal archive/status, attempt/DLQ foreign keys and permanent delivery-pair identity. Existing KafkaIT polling now tolerates the legitimate pre-matching empty delivery list. Backup policy tests include the new durable tables and cache ACL namespace restrictions.

The 10,000/s k6 scenario is a reproducible capacity gate, not an executed or passing production benchmark. Existing tenant quota and worker bounds are not automatically raised. No claim of exhaustive testing or new whole-host chaos evidence follows from this release. Final executed results and cloud revision belong in submission-status.md.

Executed release result: 27 unit + 59 integration Java tests and 42 Python tests passed locally, and the complete hosted verification/tested-release/HA-runtime pipeline passed for f0c4d33. Cloud duplicate/cache/subscription/history/public-HTTPS/HMAC results and their exact limits are recorded in [current evidence](evidence/cloud-async-cache-history-f0c4d33.json).

## Paced 1,000 events/minute public test

[Saved evidence](evidence/load-1000-per-minute-2026-11-26.json): f0c4d33 accepted all 1,000 unique requests in 59.993 seconds with 202, zero request retries/errors/generator drops, 1,000 persisted events/outbox rows and completed Kafka matches. No subscriptions matched and no notifications were sent. p95 acceptance was 77.63 ms and p99 121.16 ms. This is a one-minute, no-fanout observation, not a sustained or 10,000/s capacity certification.

Retention follow-up local result: 27 unit plus 62 integration tests passed (89 Java tests). Three new cases protect identity TTL under subscription/cache pressure, verify non-refreshing expiry and protect legacy LRU identities during rolling upgrades. Hosted CI/deployment evidence is recorded after that revision completes.
