# Verified HA deployment

**Controls update in progress:** shared fixed-window limits, generated identity keys, subscriber bulkheads, a delivery DLQ and replica-only API reads are implemented in the new source. Cloud production remains bd082b6 until a verified coordinated cutover. See [the new contract](reliability-controls.md).

Verified on **24 November 2026 IST** after explicit authorization to replicate existing service credentials/data and activate HA. The earlier instruction to retain the single-host service was superseded by that authorization. No additional paid resources were created during activation.

- Live REST base: **https://174.138.121.225**; [readiness](https://174.138.121.225/health/ready). Normal public certificate validation passed.
- Private repository: [Sahil2237/notification-fanout](https://github.com/Sahil2237/notification-fanout), default branch codex/notification-fanout.
- Deployed application: **bd082b64bc51664595264901d6f79137248bad7b** on all three nodes. [Verification](https://github.com/Sahil2237/notification-fanout/actions/runs/45341972631), [exact-image publication](https://github.com/Sahil2237/notification-fanout/actions/runs/45343398780), and [signed rolling deployment](https://github.com/Sahil2237/notification-fanout/actions/runs/45343513088) all passed.
- Infrastructure remains the separately verified/signed **507f9896e914d8da00353626da2f8aa503bd31b1** runtime. [Its verification](https://github.com/Sahil2237/notification-fanout/actions/runs/45335125767) and [publication](https://github.com/Sahil2237/notification-fanout/actions/runs/45336595057) passed. Application promotion does not silently replace infrastructure.
- [Sanitized cloud evidence](evidence/cloud-ha-bd082b6.json), [published image identities](evidence/published-images-bd082b6.json), [architecture diagrams](architecture.md), [operations](operations.md), [API](api.md), [requirements-to-tests](validation.md).

## Functional expectations

| Requirement | Implementation and verification |
| --- | --- |
| Durable structured events | REST accepts type, source and arbitrary JSON. A PostgreSQL transaction saves event, matching jobs and Kafka outbox before 202; synchronous remote_apply commits require a standby. All 10,011 original events remained byte-structure equivalent by content fingerprint after the cloud failure/reboot tests. All 124 acknowledged synthetic events remained queryable. |
| Dynamic subscriptions | Create/list/delete without restart; event type, source and JSON Pointer equality conditions. Tenant isolation, lifecycle, invalid filters and origin policy have tests. Cloud validation registered a matching subscription through public HTTPS. |
| Kafka fanout and delivery | Kafka UUID keys distribute single-type workloads across six partitions. RF3/min ISR 2, idempotent acks=all producers, no unclean election. Workers persist retry/backoff state and lease fencing. A webhook delivered while one entire Droplet was powered off and all Kafka partitions had only two in-sync replicas. |
| Delivery audit | Event/subscription/delivery endpoints expose pending/delivered/failed status and attempt timestamps, HTTP codes and outcomes. Four new cloud events delivered with HTTP 204; receiver bodies and HMAC signatures were verified. Existing successful and terminal-failed audit history was imported with matching fingerprints. |

Finite retries do not promise eventual receipt. Processing and HTTP effects can repeat after uncertain outcomes; subscribers must deduplicate stable deliveryId. A producer with an uncertain response must reuse its idempotency key. Exactly-once subscriber side effects are not claimed.

## Engineering verification

- **48 Java tests passed**: 17 unit and 31 real PostgreSQL/Kafka/HTTP integration tests. **28 Python tests passed**: 12 deployment-security and 16 HA/backup tests.
- CI passed real service startup, runtime database permissions, deliberate failed-startup rollback and database restore, a real three-node synchronous PostgreSQL failover, proxy/HTTPS/drain behavior, signed packaging and image identity checks.
- App, PostgreSQL/backup tools, proxy, Kafka and etcd scans passed with **zero fixable HIGH/CRITICAL findings**. The gate excludes unfixed findings; this does not establish freedom from unknown vulnerabilities.
- Local Rancher HA drill passed eight checks, preserved 106 acknowledged events, measured 28.82-second simulated-node recovery, refused unsafe writes without quorum, restored all nine snapshot tables and delivered four synthetic webhooks. [Local evidence](evidence/local-ha-507f989.json) is separate from independent cloud testing.
- GitHub Actions tests on push; a successful trusted default-branch verification supplies signed artifacts to the restricted rolling-deployment gateway. The successful APP_IMAGE is persisted for later Compose operations and host reboots.
- README contains setup, REST/filter syntax, guarantees and simplicity-versus-next-hardening trade-offs. Mermaid architecture/request flows, capacity assumptions, security, API/model and operations are versioned in the repository. Documentation/evidence follow-up commits do not rebuild the already verified running image.

## Cloud migration and failure tests

The legacy API was stopped for a controlled import. The custom dump remained on the original Droplet, and the destination was required to be empty. All nine imported tables matched the source by count and sorted row-content fingerprints: events 10,011, outbox 10,011, subscriptions 4, deliveries 9, attempts 13, schema history 4, origin approvals 2, origin audit 3, dead letters 0. Existing tenant/admin/signing identities were preserved only within the three explicitly authorized Droplets over verified encrypted connections.

Pausing the active Patroni/PostgreSQL container triggered the required watchdog and a real host reboot. The same node regained leadership; this probe proves fencing/restart recovery, not promotion to another node. The first later successful request began about 53.56 seconds after the pause, with completed response occurring afterward; the probe is not a precise response-time SLO. Some requests timed out or received 503 during recovery, rather than unsafe durability being promised.

A separate DigitalOcean control-panel power-off held the primary Droplet offline. PostgreSQL automatically promoted node1 from the surviving pair, with a synchronous standby on node3. All Kafka partitions had ISR 2, and a new event was accepted and delivered with 204 while the old primary stayed offline. All acknowledged probe IDs were retrieved afterward. Powering the node back on restored streaming replication and ISR 3. A planned safe database switchover preceded maintenance of the original host.

Final state: all three load-balancer backends Active/Healthy; all application replicas ready with the exact signed image; PostgreSQL node3 primary, node2 synchronous standby, node1 replica, both reported lag 0; six Kafka partitions all ISR 3. The database contains **10,135 events**, including all original 10,011 and 124 synthetic events. All hosts run Ubuntu kernel **6.8.0-151-generic** and Docker **29.10.0**, have no pending apt upgrades or reboot requirement, and passed required-watchdog boot checks. The older Rancher development engine was not upgraded.

Old single-host app/broker/proxy/database containers are stopped, with volumes retained. Their database is stale and is not a rollback authority after HA accepts writes. The original direct-IP service URL is superseded by the load-balancer API address above.

## Actual Spaces backup and restore

All three backup timers are active. The first job uploaded an age-encrypted archive and authenticated completion manifest to private bucket **notification-fanout-backups-sahil2237**, Singapore/sgp1. Backup timestamp: **2026-11-24T06:51:26.609559Z**. Singapore was used because new Bangalore Spaces creation was unavailable. CDN/public listing/versioning are off; the key is scoped to this bucket.

The restore downloaded those actual Spaces objects, verified their authentication and checksums, decrypted them, restored a fresh network-isolated PostgreSQL 17 database, and compared all nine table counts, including 10,011 events. The disposable restore/plaintext files were removed. No production dump was downloaded to the development workstation. A second same-day job returned not_due, confirming it did not create another scheduled snapshot.

Retention is **24 hours**, enforced every five minutes plus jitter while the cluster is healthy. Automated tests cover expiry boundaries and safe prefix filtering; a full elapsed cloud expiry cycle has not yet been observed. The scoped key cannot configure native bucket lifecycle, so no provider-native expiry is claimed. A complete cluster outage delays pruning; expiring an old backup before a failed next backup can leave no backup. Recovery keys and deployment secrets need durable operator escrow outside cluster and bucket; the development workspace is not a long-term secret manager.

## Cost and remaining limits

This deployment uses exactly three 4-GB/2-vCPU Bangalore Droplets ($72/month), two managed regional load-balancer nodes ($24/month) and Standard Spaces ($5/month): approximately **$101/month base**, excluding taxes/overages and unrelated team resources. No managed Kafka/database, paid registry or fourth compute node was added. This is the cost-conscious selected three-node design; it is not a claim that every alternative was exhaustively priced.

The tested zero-loss scope is acknowledged event commits through a single-node failure with a surviving safe quorum and intact synchronous replication. This is same-region HA; independent availability-zone/physical-host placement is not guaranteed. Total cluster/region destruction can lose up to roughly24 hours with daily dumps; continuous WAL/PITR and regional failover are not implemented. There is no measured full-disaster RTO.

External paging/dashboards, automatic internal-certificate renewal before 90 days, durable recovery-secret escrow, shared tenant rate limits and business-data retention remain operator/future work. Public certificate auto-renewal is configured, but an elapsed renewal cycle has not been observed. Representative slow-receiver/fanout soak tests and a production SLO are still required before a real workload commitment. Per-source ordering and replay remain optional extensions.

The earlier single-host 10,000-event test measured 50 events/s for small no-match payloads, not 10,000 events/s or fanout capacity. Its historical evidence is [release 57b3fbf](evidence/release-57b3fbf.json). Do not transfer those latency numbers directly to synchronous three-node HA.
