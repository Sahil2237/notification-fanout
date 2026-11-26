# Ingress, authentication, networking and failure behavior

**November 26 redesign:** [Current asynchronous matching, 5-minute event identity cache, subscription consistency and delivery history contract](async-cache-history.md). Older revision-specific test/cloud evidence below remains historical until new release validation is recorded.

Checked against application `6474ad3`, effective node configuration and the DigitalOcean load-balancer overview during the 25 November 2026 IST documentation review. This is a description of actual deployment, not a proposed tighter firewall. No infrastructure rules were changed by this documentation task.

## Load balancing and gateway boundary

The public REST base is `https://174.138.121.225`. A managed regional external load balancer in BLR1 forwards to the three Caddy/application backends; the overview showed all three Active and the balancer Healthy. Readiness probes use private port 9080, path `/health/ready`; a backend drain file returns503 on that route. Health-based removal/rejoin is implemented. The existing architecture uses HTTPS passthrough to Caddy, which terminates public TLS and forwards to Java on loopback 8080.

**Verified follow-up, 25 November 2026:** live Settings values are recorded below and in [sanitized inspection evidence](evidence/load-balancer-2026-11-25.json). The earlier statement that the health/affinity settings were unconfirmed is superseded. Both edit forms were cancelled without saving; no settings changed.

Traffic distribution is **DigitalOcean-managed**, rather than a user-selected round-robin/least-connections setting. The provider describes distribution intended to balance new connections/requests approximately evenly, and its API marks the algorithm selector deprecated. The Settings page exposes no algorithm selector. This confirms the provider contract, not a disclosed internal implementation or a measured equal request split. Sources: [current features](https://docs.digitalocean.com/products/networking/load-balancers/details/features/) and [API deprecation](https://docs.digitalocean.com/reference/api/reference/load-balancers/).

| Effective live setting | Verified value |
| --- | --- |
| Health protocol, port and path | HTTP, 9080,`/health/ready` |
| Probe interval | 5 seconds |
| Response timeout | 3 seconds |
| Unhealthy threshold | 2 failed checks |
| Healthy/rejoin threshold | 2 successful checks |
| Sticky sessions / session affinity |Off; no sticky-session cookie affinity is enabled |
| Backend keepalive |Enabled |
| HTTP idle timeout | 60 seconds |
| PROXY protocol |Disabled |
| Managed LB nodes / active backends | 2 / 3 |

Two probes at a five-second interval do not establish an exactly-ten-second outage/recovery SLA: probe phase/timeouts, in-flight traffic and dependency recovery matter. Sticky sessions off does not mean every consecutive request reaches a different Droplet; connection reuse and backend health still affect observed distribution. The load balancer's own HTTP-to-HTTPS redirect is off; Caddy owns its HTTPS/redirect handling. Do not equate the LB setting with a public cleartext-ingestion guarantee.

There is no separately deployed API gateway product, WAF, service mesh or API-management portal. Caddy provides reverse proxy/TLS/body limits/private-admin rejection. The application itself authenticates, authorizes, validates, rate-limits and handles API errors. `/v1` provides contract versioning; it does not imply a gateway.

## Public versus private exposure

| Surface | Actual exposure |
| --- | --- |
| Tenant REST APIs and health | Public HTTPS through the load balancer; tenant APIs require a valid bearer credential, health does not |
| Droplet Caddy80/443 | Host/cloud rules permit public sources; backends are not restricted to traffic originating only from the load balancer |
| Java application 8080 | Binds127.0.0.1, reached by local Caddy/operator tunnel, not directly published on public/private interfaces |
| Dropwizard admin/metrics 8081 | Loopback only; no public metrics listener |
| `/v1/admin/*` | Public Caddy rejects with 404; use an authorized SSH tunnel and independent admin bearer credential |
| PostgreSQL, Kafka, etcd, Patroni, Redis/Sentinel | Private peer traffic with TLS and service-specific authentication/ACLs |

Network reachability is not user authentication: anyone can establish a public HTTPS connection, but missing/invalid tenant credentials receive401 before protected operations. A valid tenant token cannot authorize another tenant's data or administer origins.

## Authentication and authorization

Authentication uses static service-to-service `Authorization: Bearer <token>` credentials from protected operator configuration. Comparisons use constant-time equality. Each configured credential maps to a tenant; user/compartment identity is also operator-configured. There is no OAuth2/OIDC, JWT claim verification, interactive login, MFA, end-user account directory or token-issuing endpoint in this application.

Authorization uses tenant predicates/ownership checks in Store and a separate administrator credential/role for origin management. Tenant credentials cannot approve/revoke destinations; the admin credential cannot ingest tenant events. Foreign or missing tenant resources appear as404. There is no fine-grained per-user read/write permission model or PostgreSQL RLS policy. Tenant authentication is shared across app replicas through common protected configuration, not sticky sessions.

The shared Redis request allowance is 6,000 per tenant per fixed minute, including reads and writes. It is not a separate per-user quota. Administrators have a separate internal rate-limit identity. Invalid credentials and health probes do not spend tenant quota. Redis outage/insufficient replication acknowledgement returns503 rather than bypassing the limit.

Root passwords are locked; SSH password and keyboard-interactive authentication are disabled on all three nodes. Authorized public keys are enabled, including privileged operator root-key access. CI uses a restricted deployment account and signed promotion gateway. Database/Redis passwords and Kafka/control certificate identities are distinct from Droplet login credentials. None belong in Git, screenshots or examples.

## Effective network rules and gaps

The deployment uses a DigitalOcean VPC and Cloud Firewall plus Ubuntu UFW on each host. It does not use OCI NSGs or Kubernetes NetworkPolicy. The shared Cloud Firewall is attached to all three Droplets. The effective host policy inspected is deny incoming, allow outgoing and deny routed traffic, with these explicit inbound allowances:

| TCP port | Allowed source | Purpose |
| --- | --- | --- |
|22 | Any IPv4/IPv6 | SSH, protected by keys; source CIDRs are not narrowed |
|80,443 | Any IPv4/IPv6 | Caddy HTTP/HTTPS; not load-balancer-only ingress |
|2379-2380 | Three private peer IPs | etcd client/peer quorum with mutual TLS |
|5432 | Three private peer IPs | PostgreSQL TLS connections/replication |
|8008 | Three private peer IPs | Patroni control with mutual TLS |
|9092-9093 | Three private peer IPs | Kafka broker/controller TLS traffic |
|6379,26379 | Three private peer IPs | Redis data and Sentinel TLS |
|9080 | Private VPC10.122.0.0/20 | Readiness probes; not internet-open |

Approved private peers are 10.122.0.2,10.122.0.6 and10.122.0.5. No public inbound5432/Kafka/Redis/admin ports are allowed. Service bind addresses and TLS/credentials provide additional boundaries behind the firewall.

Outbound traffic is broadly allowed; the Cloud Firewall allows outbound TCP/UDP/ICMP and UFW defaults to outgoing allow. There is no destination-restricted egress gateway/firewall. Webhook code separately requires approved public HTTPS origins, rejects private/loopback/link-local/metadata DNS answers, validates addresses again at connection time, verifies TLS hostnames and disables redirects. This is application enforcement, not network-level egress isolation.

Outstanding hardening includes SSH operator/VPN source restrictions, load-balancer-only backend ingress, controlled outbound dependencies and a WAF/gateway only if justified. A network allowlist alone cannot determine which bearer-token holder is legitimate. Broader IAM, rotation/expiry automation and granular permissions also remain evolution work.

## REST surfaces and conditional updates

See [complete API table](api.md) for methods, response codes and pagination. Public authenticated routes cover event POST/GET, subscription POST/list/DELETE, delivery lookup and event/subscription delivery history, and delivery dead-letter listing. Public liveness/readiness are unauthenticated. Origin-policy GET/POST/DELETE are private administrator routes. There is no PUT/PATCH subscription update, replay API, ETag or If-Match support. A future mutable resource needs a database version plus conditional-update semantics to reject lost updates; HMAC webhook signatures serve a different purpose.

Only `POST /v1/events` has the explicit/generated event-idempotency contract. `POST /v1/subscriptions` creates a new registration on every successful call, so a lost response/retry can create duplicate subscriptions for the same URL. Unique event/subscription jobs do not deduplicate two distinct subscription IDs. Administrative origin approval stores one origin state using an upsert but has no general Idempotency-Key response-replay contract. Subscription deletion is repeatable with 204.

## Who restarts or replaces failures?

| Failure | Actual behavior |
| --- | --- |
| Application container exits on a healthy Droplet | Docker `restart: unless-stopped` can restart it; readiness keeps an unhealthy backend out of normal routing |
| Application is alive but unhealthy/hung | Load balancer excludes it after health detection; no separate autoheal controller is deployed to guarantee replacement |
| One whole Droplet is lost | Remaining safe PostgreSQL/etcd/Kafka/Redis members fail over/rebalance, with temporary errors possible; the remaining two application instances serve after readiness recovers |
| Replacement Droplet needed | No Droplet autoscaler/replacement controller is configured. An operator must recover/provision and safely join a replacement, respecting quorum, certificates and image identity |
| Two application containers fail, infrastructure healthy | The last healthy application can serve with reduced capacity; there is no blanket two-app-failures traffic-stop switch |
| Two entire Droplets fail | Safety/quorum requirements are lost: normal durable admission cannot continue, readiness fails and clients receive unavailable/errors. Liveness/diagnostic endpoints may still be reachable. Do not force unsafe database/Kafka promotion |

Failover is not autoscaling, and retrying a failed HTTP request must preserve its original event identity. Dependency recovery does not create exactly-once receiver effects. See [delivery controls](reliability-controls.md) and [operations](operations.md).

## Metrics visibility

Application `/metrics` is loopback-only. Independent bridges export an aggregate allowlist every 60 seconds: API success/client/server errors/rate rejections; accepted/duplicate events and local acceptance p95; delivery success/failure/attempts/pending age/retry-budget deferral/stale results/recovery; Kafka publication/dispatch/errors/quarantine/DLQ/backlog age; Redis replica count; admission waiting/rejections; bridge scrape success. The full names and aggregation rules are in [metrics.md](metrics.md).

No event IDs, payloads, user IDs, webhook URLs or credentials are exported as metric labels. Shared backlog gauges should be aggregated with max, not summed across the three apps; per-host p95 gauges are not a cluster percentile. These aggregate instruments are not a complete Kafka/Redis/PostgreSQL host-monitoring or tracing suite. Last observed DigitalOcean OTLP exports returned 404 and Metrics Explorer had no custom series, so successful cloud metric visibility remains unverified. Alarms and tickets are excluded by request.
