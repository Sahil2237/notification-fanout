# Event Notification Fanout

Java 25 / Dropwizard 5 service deployed on three DigitalOcean Ubuntu 24.04 Droplets in Bangalore. It accepts durable structured events, matches subscriber rules after Kafka consumption, delivers signed HTTPS webhooks, retries transient failures, and exposes delivery audit.

**Live API:** https://174.138.121.225 · [Liveness](https://174.138.121.225/health/live) · [Readiness](https://174.138.121.225/health/ready)

## Read only these documents

| Document | Purpose |
| --- | --- |
| [System design](docs/system-design.md) | Architecture diagrams, component designs, database, guarantees, capacity and trade-offs |
| [API contract](docs/api.md) | Every endpoint, authentication, filters, event/subscription idempotency and errors |
| [Interviewer demo](docs/interviewer-demo.md) | Fresh inboxes, two subscriptions, one event, verified captures and speaking notes |
| [Operations](docs/operations.md) | Host inventory, ports/firewalls, metrics, deployment, backups and recovery |
| [Validation and evidence](docs/validation.md) | Requirements coverage, exact deployed revision, executed tests and remaining gaps |

These six Markdown files, including this README, replace the overlapping HLD/LLD, onboarding, status and historical narrative pages. Component low-level designs are sections of system-design.md. Historical machine-readable records remain under [docs/evidence](docs/evidence); Git history preserves removed prose. Start the demo from preparation rather than reusing an expired inbox or an old run's IDs.

## Current release and evidence boundary

Application deployment bundle **d1612d8** is running on all three nodes; its application JAR source is **fa1a0b1**. The later bundle corrects cross-version Docker metadata verification. Migration 011 adds durable subscription creation keys/hashes. A fresh audit on 27 November 2026 confirms all three apps ready and undrained, with eleven migrations applied.

Local verification passed **98 Java tests and 63 Python tests**. The same test/configuration steps passed in [CI 45943599738](https://github.com/Sahil2237/notification-fanout/actions/runs/45943599738), but artifact uploads failed on GitHub Actions storage quota. Downstream hosted release/HA jobs were skipped. A locally verified/scanned AMD64 image was signed and promoted sequentially through the existing gateway. Do not describe that workflow as green or that deployment as automatic. [Release proof](docs/evidence/subscription-idempotency-cloud-2026-11-27.json).

The cloud demonstration verified two keyed subscription replays, two 409 conflicts, one accepted event, its duplicate replay and two externally captured/HMAC-verified deliveries returning 204. A smoke test does not establish sustained 5,000 events/second, exactly-once webhook effects or a new disaster-recovery drill.

## Development and local setup

All code, tests and assets belong under **/workspaces/notification-fanout inside the Ubuntu 24.04 development container**. Use Rancher Desktop and an IntelliJ terminal attached to that container, as requested. The assessment's VS Code/Cursor alternative is checked into [.devcontainer](.devcontainer/devcontainer.json); that editor alternative was not the verified session. Do not use host Desktop/Downloads.

For the existing named container, enter it from a terminal whose Docker client is connected to Rancher:

```bash
docker exec -it -w /workspaces/notification-fanout notification-dev bash
```

First verify the terminal:
```bash
uname -s
cd /workspaces/notification-fanout && pwd
```

Expected: Linux and /workspaces/notification-fanout. A Mac shell under /Users is outside the development container.

For a fresh setup on a Docker-capable workstation with the repository workspace available:
```bash
docker compose --profile dev up --build -d db redis kafka kafka-init development
```

Open a shell in that development container. Java 25, Maven and the test services must be available. Tests migrate/truncate their dedicated test database: **never point TEST_DB_URL at production**.

```bash
cd /workspaces/notification-fanout
mvn -B -ntp verify
python3 -m unittest discover -s deploy/droplet -p 'test_*.py'
python3 -m unittest discover -s deploy/ha -p 'test_*.py'
java -jar target/notification-fanout.jar db migrate config/local.yml
./scripts/start-local.sh
```

mvn test runs units; mvn verify includes real PostgreSQL, Kafka, Redis and HTTP integration scenarios. Local API/admin ports are 8080/8081; the optional local receiver is [scripts/receiver.py](scripts/receiver.py) on 9090. Local credentials/HTTP destinations are development-only. Do not expose them publicly or start two applications on the same ports.

For a packaged local application:
```bash
docker compose up -d db redis kafka kafka-init
docker compose run --build --rm migrate
docker compose up --build -d app
```

## Repository layout

| Path | Responsibility |
| --- | --- |
| src/main/java/com/example/notifications | REST/security, Store transactions, Redis clients, Kafka loops, delivery workers and metrics |
| src/main/resources/migrations.xml and migrations/ | Immutable database change history, currently 001–011 |
| src/test/java/com/example/notifications | Unit and real-dependency integration tests |
| config/ | Local, test and HA runtime configuration |
| deploy/ha/ | Three-node rendering, TLS/Redis provisioning, network policy, backup and HA validation tools |
| deploy/droplet/ | Signed promotion gateway and release helpers; legacy single-host tooling is not the active deployment |
| .github/workflows/ | Test, image/security/HA verification and signed deployment workflows |
| docs/evidence/ | Sanitized dated observations; not secrets or production dumps |

## Simplicity, limits and handoff

One modular application runs API, publishers, a consumer and delivery workers in one JVM per host. PostgreSQL is the authoritative acceptance/deduplication store; Kafka does not shield that first write. Eight containers share each small host. This reduces infrastructure count but creates resource contention and same-region failure exposure.

Next hardening work: measured burst/fanout capacity and SLOs, stronger global admission, improved publisher/consumer backoff, latest-schema Spaces restoration, durable off-cluster secret escrow, certificate renewal automation and a working artifact-publication path. Managed Kafka, per-source delivery ordering, replay API, autoscaling, per-user quotas, OIDC/WAF and general response caching are not deployed. Alarms/tickets were explicitly excluded. Custom Metrics Explorer visibility remains unverified.

Before assessment handoff: grant the interviewer access to the private repository, retain recovery material securely outside both cluster and bucket, and sign out of personal GitHub/IDE/browser sessions. This documentation refresh does not sign you out or modify production.
