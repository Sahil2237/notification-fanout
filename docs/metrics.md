# Metrics emission

This feature emits aggregate metrics only. It creates no alerts, alarms, incident tickets, dashboards, log exports, or traces.

## Current state

The metrics-only bridge is **running on all three existing Droplets**, using the metrics JAR from verified release `cf92a03729053857d35756a61762798785294c34`. The complete verification and exact-image publication passed:

- https://github.com/Sahil2237/notification-fanout/actions/runs/45370272386
- https://github.com/Sahil2237/notification-fanout/actions/runs/45372414195

**Metrics Explorer visibility is blocked by the provider endpoint.** Actual Java OpenTelemetry SDK exports from every Droplet receive HTTP404 with a DigitalOcean maintenance HTML page at the documented Bangalore OTLP URL. All three resource metrics signals are already enabled, each bridge remains running with zero restarts, and each local application readiness and metrics source endpoint returns200. The Metrics Explorer search for `notification.` in BLR1 has no results. See [current cloud runtime evidence](evidence/metrics-cloud-cf92a03.json). The earlier [manual preflight](evidence/metrics-preflight-64d0d92.json) is retained as historical evidence before activation, not as the current activation state.

A DigitalOcean administrator/provider must enable the team's custom telemetry preview or confirm a functioning supported BLR1 ingestion endpoint. Do not infer preview entitlement from token scope availability. The active bridges continue bounded one-minute export attempts and can recover once the endpoint accepts telemetry. No alerts, tickets or extra paid resources were created.

After explicit authorization, a 90-day metrics token was created; DigitalOcean automatically includes `insights:read` with `insights:custom_ingest`. Protected root-owned copies reside at `/opt/notification-fanout/ha/metrics/metrics.env` on the three approved hosts. The temporary workstation/container token staging files were removed. The token expires on22February2027 and needs operator rotation. Its value is never committed or printed.

The event application itself remains the preceding `bd082b6` release. Redis and the new application controls require their own coordinated rollout; the independent metrics process does not activate them. Automatic application deployment is held with `HA_DEPLOY_READY=false` until that prerequisite is ready.

## What is emitted

The existing loopback-only Dropwizard admin `/metrics` endpoint remains available. An optional OpenTelemetry exporter bridges a fixed allowlist into DigitalOcean Insights over HTTPS, once every 60 seconds by default.

| Metric | Meaning |
| --- | --- |
| `notification.http.success` / `notification.http.client.errors` / `notification.http.server.errors` | API response status classes, excluding health checks |
| `notification.http.rate.limited` | API responses rejected with HTTP 429 |
| `notification.events.accepted` | Newly accepted durable events |
| `notification.events.duplicates` | Requests returning an existing event |
| `notification.events.acceptance.latency.p95` | Rolling local acceptance latency estimate, seconds |
| `notification.delivery.attempts` | Completed webhook attempt outcomes recorded by workers |
| `notification.delivery.succeeded` | Successful delivery outcomes |
| `notification.delivery.unsuccessful.attempts` | Unsuccessful HTTP attempts |
| `notification.delivery.retry.budget.deferred` | Retry reservations deferred due to the shared retry budget |
| `notification.delivery.stale.results` | Late outcomes rejected by the database fencing check |
| `notification.delivery.pending` | Current pending jobs |
| `notification.delivery.oldest.pending.seconds` | Age of the oldest pending job |
| `notification.kafka.outbox.pending` | Events awaiting Kafka dispatch |
| `notification.kafka.oldest.undispatched.seconds` | Age of the oldest undispatched event |
| `notification.kafka.published` / `notification.kafka.dispatched` | Kafka publishing and matching progress |
| `notification.kafka.publish.errors` / `notification.kafka.consumer.errors` | Kafka failures |
| `notification.kafka.quarantined` | Invalid Kafka records durably quarantined |
| `notification.kafka.dead.letters.published` | Dead-letter outbox records published to Kafka |
| `notification.kafka.dead.letter.publish.errors` | Dead-letter publishing failures |
| `notification.delivery.recovered` / `notification.delivery.worker.errors` | Worker recovery and processing failures |
| `notification.redis.replicas` | Replica count observed on the Redis primary |
| `notification.http.admission.waiting` | Requests waiting for bounded HTTP admission |
| `notification.http.admission.rejected` | Cumulative local rejected admission count (gauge) |

Counters use **delta** temporality. Queue depths, ages, local percentile estimates and replica counts are gauges. Do not sum the p95 gauge across hosts or treat it as a cluster percentile. Queue gauges read the same shared database from each host: use `max`, not `sum`, across replicas to avoid triple-counting backlog.

Each metric has only the `service.name=notification-fanout` data-point label. Required resource attributes identify the DigitalOcean team, Droplet and region. No business tenant, user, event ID, webhook URL, signing secret, request body, payload, or API credential is included. Event IDs remain in application logs and propagated request headers, not metric labels. SDK internal instruments are suppressed so names containing underscores cannot cause provider rejection.

## Configuration and safe enablement

The `telemetry` block in `config/ha.yml` is disabled by default. The HA renderer uses `inventory.telemetry.enabled` to opt in, `inventory.telemetry.team_id` for the actual DigitalOcean team UUID, and each inventory node's `droplet_id` for attribution. A root-owned `metrics.env` file supplies only `DO_METRICS_INGEST_TOKEN`; its mode must be `0600`. Keep it outside Git and the signed public artifact. The application gets the token from its environment, never a command-line argument.

1. Confirm the team has DigitalOcean custom telemetry preview access.
2. Create a dedicated token with `insights:custom_ingest`; the current console automatically adds its required `insights:read` dependency. Do not grant resource management or broad account permissions. Plan rotation before expiration.
3. Enable the metrics signal on the three existing Droplet resource URNs in Insights. Logs and traces need not be enabled.
4. Set `METRICS_ENABLED=true`, `DO_DROPLET_ID`, `DO_TEAM_ID`, and the protected token environment variable for each application. Use the same team UUID, each host's own Droplet ID, and `blr1` for all three.
5. Deploy a verified release, generate a synthetic event, wait for at least two export intervals, then open **Insights > Metrics > Bangalore / BLR1**, search `notification.events.accepted`, and filter `service.name=notification-fanout`.
6. Verify the series has recent samples and correct host attribution. Do not mark cloud validation complete based only on an HTTP 200; check the Metrics Explorer result.

Endpoint: `https://otlp-insights-blr1.digitalocean.com/v1/metrics`. Configuration rejects another host, non-HTTPS transport, mismatched region, missing IDs, missing token, and header injection. Normal certificate and hostname verification remain enabled. No public application metrics endpoint or new inbound firewall port is required.

## Failure and resource bounds

Export uses one periodic collection task and a dedicated HTTP executor with one worker and one queued task. Export bodies are limited to 64 KiB, connection timeout is two seconds, request timeout is three seconds, and there is one HTTP attempt per collection. Shutdown is bounded. There is no per-event export task, growing metrics queue, or disk spool. Metrics are best-effort: outages and process failures can lose samples. Event acceptance and delivery correctness do not depend on the external metrics service. An explicitly enabled but invalid configuration fails startup rather than silently pretending export is active.

Gauge collection omits a failed dependency sample while retaining other metrics. Metrics queries can use the existing bounded database pools, once per interval. Export does not consume application event or delivery retry budgets.

## Validation

Local full verification: **71 Java tests** (26 unit, 45 integration), **34 Python tests** (12 deployment, 22 HA/backup/configuration), all passing. The metrics-specific six tests include the actual bounded production HTTP transport, one-attempt failure behavior, counter baselining and source restart handling.

`MetricsExporterTest` verifies delta values across consecutive collections, required resource attribution, one bounded service label, exclusion of unapproved instruments, omission of a failed gauge, a real authenticated OTLP/HTTP protobuf request to a local collector, export failure without transport retries, and rejection of untrusted or region-mismatched endpoints. Cloud visibility is a separate release acceptance check.

Official provider contract: https://docs.digitalocean.com/products/insights/how-to/send-custom-telemetry/

DigitalOcean bills custom telemetry against the team's shared Insights ingestion allowance. Check current billing and other workloads before enabling; low-cardinality aggregate metrics at one-minute intervals keep volume small. No extra compute resource is needed for this exporter.

## Independent bridge for an existing release

`com.example.notifications.MetricsBridge` reads only `http://127.0.0.1:8081/metrics`, with redirects disabled, two/three-second connect/read timeouts and a 1MiB response limit. It has no application bearer token, database credential, Kafka identity or Redis credential. It exports the same fixed instrument allowlist. The first scrape baselines pre-existing counters so installing it does not report historical traffic as a new burst; subsequent scrapes count increases, including after a source process restart. A source outage emits `notification.metrics.scrape.success=0` and suppresses stale gauges. A bridge restart can lose counter increments around the restart, consistent with best-effort telemetry.

`deploy/ha/metrics-compose.yaml` defines the canonical full-image bridge deployment with a separate Java entry point. It has a read-only filesystem, non-root UID, no Linux capabilities, no new privileges, a 256MiB memory cap, a 0.25 CPU cap and a 64-process limit. No service port is exposed. This allows metrics to be deployed independently of the Redis/application controls cutover. **Use one exporter per node: stop the independent bridge before enabling in-process `telemetry.enabled`, to avoid duplicate series.**

### Current bandwidth-conscious cloud installation

The signed CI application bundle was downloaded and its Ed25519 signature and every SHA256 entry verified inside the Rancher workspace. Only the 57MiB application JAR was extracted from that exact image and transferred over pinned-host-key SSH, then checked against SHA256 `5a46357593c8106b85658c5ca0292c47b10c4aa85006be91a3cec7ec2908882c` on each host. The Java25 runtime definition is identical between the running application and verified metrics release. Each metrics container therefore reuses the existing verified runtime image, with the new JAR mounted read-only at `/opt/metrics/runtime.jar`. This avoids retransferring the larger runtime layers. It does not claim that the event application was upgraded.

Effective configuration is under `/opt/notification-fanout/ha/metrics/`: `compose.yaml`, `image.env`, protected `metrics.env`, and `runtime.jar`. The bridge has a 256MiB memory cap,0.25CPU cap,64-process cap, read-only root filesystem, non-root UID10001, all Linux capabilities dropped, and no public listener. The in-process exporter remains disabled to prevent duplicate export.

To inspect or stop **only** the metrics bridge, use the following on a Droplet as an authorized operator:

```sh
docker compose --env-file /opt/notification-fanout/ha/metrics/image.env -f /opt/notification-fanout/ha/metrics/compose.yaml ps
docker compose --env-file /opt/notification-fanout/ha/metrics/image.env -f /opt/notification-fanout/ha/metrics/compose.yaml stop exporter
```

For future JAR updates, stage to a new filename, verify the signed source artifact and JAR digest, atomically select the verified file, then recreate only the metrics container. Do not overwrite a JAR currently mounted by a running process. The full-image deployment template is preferable when transfer bandwidth permits.

The SDK can log a long provider error page; checking only a short log tail can omit the line containing the HTTP status. Runtime verification searched the complete bounded recent log window, confirmed the actual SDK404 response on every node, and separately checked the UI. Absence of a warning in a short tail is not proof of successful ingestion.

When bridging the older application release, counters for newer controls can remain zero and unsupported gauges are omitted. Those values do not establish that Redis limits, replica-only API reads or the delivery DLQ have been activated. Refer to the deployed application revision and the separate controls rollout status.
