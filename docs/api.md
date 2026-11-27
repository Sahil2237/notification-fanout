# REST API contract

**Base URL:** https://174.138.121.225. Public health links work in a browser; tenant routes require an Authorization header, so use curl/an API client. [Fresh live demo](interviewer-demo.md), [design](system-design.md), [operations](operations.md).

## Authentication, common headers and reads

Tenant /v1 calls require Authorization: Bearer followed by a configured tenant token. The credential selects tenant and configured user/compartment; request fields/headers cannot impersonate them. A separate administrator credential is valid only for administrator routes. Static service credentials are not OAuth/OIDC or per-user login; no token-issuance endpoint exists.

Responses include X-Request-ID and Cache-Control:no-store. Optional X-Request-ID accepts1–64 letters/digits/period/underscore/hyphen; otherwise the service generates one. Accepted event responses also carry canonical X-Event-ID. Tenant API admission is 1,000 authenticated requests/second per tenant shared across apps, counting reads/writes/replays; X-RateLimit-Limit and X-RateLimit-Window-Seconds describe it.429 includes Retry-After:1. Health paths are exempt.

HA tenant GETs use standby-only read-only transactions, advertised by X-Read-Source:replica and X-Read-Consistency:eventual. A fresh record may briefly return404/empty data; retry the GET. An unavailable/promoted read connection returns503 with no primary fallback. A successful write response already proves its primary commit.

## Public routes

| Method/path | Input | Success |
| --- | --- | --- |
| GET /health/live | None; anonymous | 200 alive |
| GET /health/ready | None; anonymous | 200 ready or 503 unavailable |
| POST /v1/events | Event input; optional Idempotency-Key | 202 accepted response and Location |
| GET /v1/events/{id} | Canonical internal Event UUID | 200 event |
| POST /v1/subscriptions | Subscription input; optional Idempotency-Key | 201 subscription and Location |
| GET /v1/subscriptions | limit/offset | 200 active subscription page |
| DELETE /v1/subscriptions/{id} | Subscription UUID | 204; repeated owned delete stays204 |
| GET /v1/deliveries/{id} | Delivery UUID | 200 delivery and attempts |
| GET /v1/events/{id}/deliveries | limit/offset | 200 delivery page |
| GET /v1/subscriptions/{id}/deliveries | limit/offset | 200 audit page, including deleted-subscription history |
| GET /v1/dead-letters | limit/offset | 200 tenant terminal-delivery DLQ page |

There is no GET /v1/subscriptions/{id}; inspect the saved creation response or active list. No PUT/PATCH, ETag/If-Match, replay, manual-DLQ-redrive or public /metrics endpoint exists. Location identifies the created subscription even though a standalone GET is not implemented.

Page response:
```json
{"items":[],"limit":20,"offset":0,"hasMore":false}
```

limit is 1–100 and offset0–100000. Ordering uses creation time/ID. Offset pages across concurrent mutations are not a frozen snapshot. UUID path parameters and JSON Content-Type are required where applicable.

## Event input and response

```json
{"eventId":"c5359287-ddf1-424c-ab87-75726b11fb7f","type":"order.created","source":"checkout","payload":{"orderId":"demo-001","customer":{"tier":"gold"},"region":"blr1"}}
```

Type/source are nonblank strings of at most 128 characters. Payload must be supplied and can be an object, array, scalar or JSON null. Body size is at most 256 KiB, nesting at most 32, numeric token length at most 256. Authentication/quota precede body parsing. Unknown or malformed inputs are rejected according to Jackson/validation.

```json
{"event":{"id":"be35433b-ebc0-4732-88b9-cca8fdba577a","type":"order.created","source":"checkout","payload":{},"acceptedAt":"2026-11-27T05:00:00Z"},"duplicate":false}
```

This response is a shape example; actual payload/timestamp/IDs reflect the request.202 follows the atomic event/outbox commit. Delivery lists may initially be empty while matching runs. Location points to /v1/events/{internal-id}. Use event.id in GET paths, logs and webhook comparisons; producer eventId is a separate client occurrence identity.

An explicit Idempotency-Key is tenant-scoped; keys must be nonblank, at most 200 characters, without control characters. Tenant/producer UUID uniqueness also protects replay. Same identity/content returns the original 202 event with duplicate=true; changed content or conflicting configured identity returns409.

Without an explicit key, a versioned SHA-256 key includes authenticated tenant/user/compartment and canonical content including a supplied producer UUID. Object field order is normalized; array order, value types and number representation remain significant. Without both key and producer ID, identical content/identity deliberately represents the same retained occurrence. Separate legitimate identical occurrences need distinct producer IDs or keys.

Only committed identities populate Redis. Their non-sliding300-second cache lifetime does not expire PostgreSQL keys. Capacity/malformed/missing/unavailable optional cache falls back to PostgreSQL; shared quota Redis failure still returns503. Reusing an event does not republish/rematch completed recipients or reset delivery attempts.

## Subscription input, filters and idempotency

```json
{"webhookUrl":"https://receiver.example/events","filter":{"type":"order.created","source":"checkout","payloadConditions":[{"path":"/customer/tier","operator":"eq","value":"gold"}]}}
```

The example hostname is a placeholder, not an approved deployed receiver. Use an approved public HTTPS origin such as the demo's Webhook.site origin. URL length is at most 2048; credentials/fragments/private or unsafe resolved destinations are rejected by policy. Origin policy is rechecked at connection time; redirects are refused. Custom destination ports additionally require reviewed network egress.

Omitted/null filter defaults to unrestricted rules. Omitted type/source/conditions impose no restriction at that level; all supplied conditions combine with AND. Only eq is supported, with at most 20 conditions and 256-character JSON Pointer paths. /items/0/name addresses an array element; ~1 escapes slash, ~0 escapes tilde; empty path selects the whole payload. Missing differs from null, strings are case-sensitive, scalar numbers 1/1.0 compare equal, booleans differ from numbers, and objects/arrays use structural equality. These matching rules do not imply all semantically equivalent requests have identical idempotency hashes.

Subscription response fields: id, webhookUrl, filter, createdAt, deletedAt. Timestamps use UTC ISO-8601. At most1,000 active registrations/tenant are allowed.

| Subscription POST case | Behavior |
| --- | --- |
| New explicit key | Resource/key/hash commit together;201 and Location |
| Same tenant/key and normalized request | Original201 creation representation, ID, createdAt and Location |
| Same key, different valid content | 409; no rule mutation |
| Same key in another tenant or event operation | Independent scope |
| Missing key or a different key | A distinct registration may be created |
| Replay after soft deletion | Original historical creation response; does not reactivate the rule |
| Fresh registration after deletion | Use a fresh key |

The key/hash live on the subscriptions row with a tenant/key unique index, independent of Redis TTL. A tenant registration lock serializes retries and cap checks across instances. Replay happens before the cap and does not advance rule generation. Normalized defaults/object order are stable; array order and URL text remain significant. Authentication, quota, input validation and current destination approval still apply on replay. No duplicate flag is added to subscription responses; compare the original ID/body.

Subscriber membership is selected at the successful Kafka matching transaction. Create before matching can include a new rule; delete before matching excludes it. Existing jobs retain their destination after deletion. Delete/create replaces rules; it is not an atomic update API.

## Webhook and audit contracts

```json
{"schemaVersion":1,"deliveryId":"stable UUID","subscriptionId":"UUID","event":{"id":"internal Event UUID","type":"order.created","source":"checkout","payload":{},"acceptedAt":"UTC ISO-8601"}}
```

Headers: Content-Type, X-Event-ID, X-Delivery-ID, X-Webhook-Timestamp, X-Webhook-Signature. Timestamp is Unix seconds. Signature is v1= followed by hex HMAC-SHA256 over timestamp + period + the exact raw body; its key is HMAC-SHA256(master, "tenant:" + tenant). Provision the tenant-derived receiver key out of band; never distribute the master. Validate signatures in constant time with an agreed timestamp tolerance and atomically deduplicate business effects by deliveryId. The API does not automatically provision receiver signing keys.

Two subscriptions have different Delivery IDs even for the same event/URL. Retries retain the Delivery ID. Receivers must tolerate additive envelope fields. At-least-once attempts and ambiguous HTTP outcomes prevent an exactly-once remote-effect guarantee.

Delivery fields: id, eventId, subscriptionId, status(pending/delivered/failed), attemptCount, nextAttemptAt, deliveredAt, attempts. Attempt fields: number, startedAt, finishedAt, nullable httpStatus, outcome(started/retry_scheduled/delivered/failed/unknown) and nullable bounded error category. A terminal nextAttemptAt is not a scheduling promise.

Six total attempts are permitted for transient failure; permanent failures can terminate earlier. Terminal status/attempt/DLQ are transactional. /v1/dead-letters queries failures, not replay. Existing audit endpoints include terminal history after 24-hour archival through all_deliveries; clients never select active versus historical tables.

## Private administrator routes

These require the separate admin token and an entitled shell on a Droplet using http://127.0.0.1:8080. Public Caddy returns404 for /v1/admin/*; tenant tokens receive403 at private admin routes, and the admin token cannot ingest tenant events.

| Method/path | Request | Success |
| --- | --- | --- |
| GET /v1/admin/webhook-origins?limit=100&offset=0 | None | 200 array of normalized approved origins |
| POST /v1/admin/webhook-origins | origin JSON string field | 200 normalized origin and approved=true |
| DELETE /v1/admin/webhook-origins?origin=URL_ENCODED_ORIGIN | None | 204 |

Origin input must be public HTTPS without path/query/credentials. Policy changes persist/audit in PostgreSQL; revoked configured seeds are not silently reactivated. Do not approve a new non-443 port before host/cloud egress review.

## Errors and compatibility

| Status | Meaning |
| --- | --- |
| 400 | Malformed JSON, business/key/filter/destination validation |
| 401 | Missing/invalid credential; WWW-Authenticate:Bearer |
| 403 | Tenant credential used for private administrator action |
| 404 | Missing/foreign resource, or a briefly lagging replica |
| 409 | Conflicting event/subscription identity or active-subscription cap |
| 413 | Request over256 KiB |
| 415 | Unsupported content type |
| 422 | Bean-validation error |
| 429 | Shared tenant quota exhausted; Retry-After:1 |
| 503 | Required quota/database/read-replica/admission dependency unavailable |
| 500 | Unexpected internal failure |

Application errors have code/message/requestId; built-in Dropwizard parser/validation errors can use its standard envelope. Internal secrets are not returned. /v1 and webhook schemaVersion=1 are the explicit versions. Changing existing identity/filter/delivery semantics requires a compatibility/migration plan, not a silent reinterpretation of prior keys.
