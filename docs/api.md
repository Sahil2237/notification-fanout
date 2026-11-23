# REST API contract

All /v1 requests require `Authorization: Bearer <token>`. Tenant comes from that token, never a supplied tenant ID. Responses include X-Request-ID and Cache-Control:no-store. Optional X-Request-ID accepts 1..64 letters/digits/period/underscore/hyphen. UUID path parameters; JSON Content-Type for bodies. No version negotiation: /v1 and webhook schemaVersion=1 are explicit.

|Method/path|Request|Success|
|---|---|---|
|POST /v1/events|Event below; optional Idempotency-Key|202 Accepted object, Location header|
|GET /v1/events/{id}|—|200 Event|
|POST /v1/subscriptions|Subscription below|201 Subscription, Location header|
|GET /v1/subscriptions?limit=20&offset=0|—|200 Page of active subscriptions|
|DELETE /v1/subscriptions/{id}|—|204; repeat delete remains 204|
|GET /v1/deliveries/{id}|—|200 Delivery including attempts|
|GET /v1/events/{id}/deliveries|limit/offset|200 Page of Delivery|
|GET /v1/subscriptions/{id}/deliveries|limit/offset|200 Page of Delivery, including deleted subscription history|
|GET /health/live|No authentication|200 process alive|
|GET /health/ready|No authentication|200 ready / 503 DB or worker unavailable|

Event input:
```json
{"type":"order.created","source":"checkout","payload":{"order_id":"o-123","customer":{"tier":"gold"}}}
```
Type/source nonblank, <=128 characters. Payload is required but can be null, scalar, array or object. Body <=256KiB, JSON nesting <=32, numeric token length <=256. Request authentication happens before body parsing. Filters have <=20 conditions; paths <=256; URLs <=2048. Unknown/unexpected input is rejected by Jackson/validation where applicable.

Accepted response:
```json
{"event":{"id":"UUID","type":"order.created","source":"checkout","payload":{},"acceptedAt":"ISO-8601"},"duplicate":false}
```
Timestamps are UTC ISO-8601 strings. Subscription shape: id, webhookUrl, filter, createdAt, deletedAt. See README for full filter input.

Delivery: id, eventId, subscriptionId, status (pending/delivered/failed), attemptCount, nextAttemptAt, deliveredAt, attempts. Each attempt: number, startedAt, finishedAt, httpStatus (nullable), outcome (started/retry_scheduled/delivered/failed/unknown), error (nullable bounded category). nextAttemptAt only schedules pending work; it has no scheduling meaning on a terminal delivery.

Page: `{ "items": [], "limit":20, "offset":0, "hasMore":false }`. limit 1..100, offset 0..100000. Stable ordering by creation time/ID; concurrent inserts/deletes mean offset pages are not a snapshot.

Errors: malformed JSON/business validation 400; bean validation 422; unsupported media type 415; authentication 401 (WWW-Authenticate:Bearer); foreign/missing resource 404; conflicting idempotency/subscription cap 409; oversized 413; process-local tenant limit 429 (Retry-After:60); persistence unavailable 503; unexpected server failure 500. Application errors have code/message/requestId; Dropwizard's built-in parsing/bean-validation errors can use its standard error envelope. Every matched response still receives the correlation header. Sensitive internal exceptions are not returned.

Webhook POST body:
```json
{"schemaVersion":1,"deliveryId":"stable UUID","subscriptionId":"UUID","event":{"id":"UUID","type":"order.created","source":"checkout","payload":{},"acceptedAt":"ISO-8601"}}
```
Headers: Content-Type, X-Delivery-ID, X-Event-ID, X-Webhook-Timestamp, X-Webhook-Signature. The delivery ID remains the same across retries; event ID is not a unique delivery ID when multiple subscriptions match. The receiver must tolerate additive envelope fields and deduplicate delivery side effects. Subscriptions are immutable in v1; delete/create to replace them.


## Runtime administrator destination policy

An independent `ADMIN_TOKEN` authorizes these private endpoints. Tenant tokens receive 403; the administrator token is not accepted as a tenant ingestion token. Public Caddy ingress returns 404 for `/v1/admin/*`; use SSH for operator requests.

|Method/path|Request|Success|
|---|---|---|
|GET /v1/admin/webhook-origins?limit=100&offset=0|None|200 JSON array of approved origin strings; limit 1..100|
|POST /v1/admin/webhook-origins|`{"origin":"https://receiver.example"}`|200 normalized origin and approved=true|
|DELETE /v1/admin/webhook-origins?origin=URL_ENCODED_ORIGIN|None|204; unknown origins get a durable revoked row|

Approvals require an origin without path/query/credentials and public HTTPS DNS answers. Connection-time DNS validation still applies to every webhook. Changes persist immediately in PostgreSQL and are audited. Configured seed origins are not reactivated after revocation. Subscription creation and deletion remain ordinary tenant APIs and require no restart.
