package com.example.notifications;

import static com.example.notifications.Models.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

public final class Store {
    private final Jdbi db;
    private final Jdbi reads;
    private final int subscriberConcurrency;
    private final ObjectMapper json;
    private final boolean kafkaRequired;
    private final int maxQueuedEvents;

    public Store(Jdbi db, ObjectMapper json) {
        this(db, json, false);
    }

    public Store(Jdbi db, ObjectMapper json, boolean kafkaRequired) {
        this(db,json,kafkaRequired,100000);
    }
    public Store(Jdbi db,ObjectMapper json,boolean kafkaRequired,int maxQueuedEvents) {
        this(db, db, json, kafkaRequired, maxQueuedEvents, 1);
    }
    public Store(Jdbi db, Jdbi reads, ObjectMapper json, boolean kafkaRequired, int maxQueuedEvents, int subscriberConcurrency) {
        this.db = db;
        this.reads = reads;
        this.subscriberConcurrency = subscriberConcurrency;
        this.json = json;
        this.kafkaRequired = kafkaRequired;
        this.maxQueuedEvents=maxQueuedEvents;
    }

    public void initializeWebhookOrigins(List<String> origins) {
        db.useTransaction(h -> {
            for (var value : origins) {
                var origin=WebhookPolicy.origin(java.net.URI.create(value));
                var created=h.createUpdate("INSERT INTO approved_webhook_origins(origin) VALUES(:origin) ON CONFLICT DO NOTHING")
                        .bind("origin",origin).execute();
                if(created==1) auditOrigin(h,origin,"approved","configuration");
            }
        });
    }

    public boolean isWebhookOriginApproved(String origin) {
        return db.withHandle(h -> h.createQuery("SELECT active FROM approved_webhook_origins WHERE origin=:origin")
                .bind("origin",origin).mapTo(Boolean.class).findOne().orElse(false));
    }

    public List<String> webhookOrigins(int limit,int offset) {
        return db.withHandle(h -> h.createQuery("SELECT origin FROM approved_webhook_origins WHERE active ORDER BY origin LIMIT :limit OFFSET :offset")
                .bind("limit",limit).bind("offset",offset).mapTo(String.class).list());
    }

    public void setWebhookOrigin(String origin,boolean active) {
        db.useTransaction(h -> {
            h.createUpdate("INSERT INTO approved_webhook_origins(origin,active) VALUES(:origin,:active) "
                    +"ON CONFLICT(origin) DO UPDATE SET active=EXCLUDED.active,updated_at=clock_timestamp()")
                    .bind("origin",origin).bind("active",active).execute();
            auditOrigin(h,origin,active?"approved":"revoked","administrator");
        });
    }

    private void auditOrigin(Handle h,String origin,String action,String actor) {
        h.createUpdate("INSERT INTO webhook_origin_audit(id,origin,action,actor) VALUES(:id,:origin,:action,:actor)")
                .bind("id",UUID.randomUUID()).bind("origin",origin).bind("action",action).bind("actor",actor).execute();
    }

    public Subscription createSubscription(String tenant, SubscriptionInput input) {
        return db.inTransaction(
                h -> {
                    h.createQuery("SELECT pg_advisory_xact_lock(hashtextextended(:tenant, 0))")
                            .bind("tenant", tenant)
                            .map((r, c) -> 0)
                            .list();
                    var count =
                            h.createQuery(
                                            "SELECT count(*) FROM subscriptions WHERE"
                                                + " tenant_id=:tenant AND deleted_at IS NULL")
                                    .bind("tenant", tenant)
                                    .mapTo(Integer.class)
                                    .one();
                    if (count >= 1000)
                        throw new WebApplicationException("Active subscription limit reached", 409);
                    var id = UUID.randomUUID();
                    h.createUpdate(
                                    "INSERT INTO subscriptions(id,tenant_id,webhook_url,filter)"
                                        + " VALUES(:id,:tenant,:url,CAST(:filter AS jsonb))")
                            .bind("id", id)
                            .bind("tenant", tenant)
                            .bind("url", input.webhookUrl())
                            .bind("filter", encode(input.filter()))
                            .execute();
                    return subscription(h, tenant, id);
                });
    }

    public List<Subscription> subscriptions(String tenant, int limit, int offset) {
        return read(
                h ->
                        h.createQuery(
                                        "SELECT * FROM subscriptions WHERE tenant_id=:tenant AND"
                                            + " deleted_at IS NULL ORDER BY created_at,id LIMIT"
                                            + " :limit OFFSET :offset")
                                .bind("tenant", tenant)
                                .bind("limit", limit)
                                .bind("offset", offset)
                                .map((r, c) -> mapSubscription(r))
                                .list());
    }

    public Subscription subscription(String tenant, UUID id) {
        return read(h -> subscription(h, tenant, id));
    }

    private Subscription subscription(Handle h, String tenant, UUID id) {
        return h.createQuery("SELECT * FROM subscriptions WHERE tenant_id=:tenant AND id=:id")
                .bind("tenant", tenant)
                .bind("id", id)
                .map((r, c) -> mapSubscription(r))
                .findOne()
                .orElseThrow(NotFoundException::new);
    }

    public void deleteSubscription(String tenant, UUID id) {
        db.useHandle(
                h -> {
                    var count =
                            h.createUpdate(
                                            "UPDATE subscriptions SET"
                                                + " deleted_at=COALESCE(deleted_at,clock_timestamp())"
                                                + " WHERE tenant_id=:tenant AND id=:id")
                                    .bind("tenant", tenant)
                                    .bind("id", id)
                                    .execute();
                    if (count == 0) throw new NotFoundException();
                });
    }

    public Accepted ingest(String tenant, EventInput input, String key) {
        return ingest(new Identity(tenant, tenant, "default"), input, key);
    }
    public Accepted ingest(Identity identity, EventInput input, String suppliedKey) {
        var tenant = identity.tenant();
        var hash = hash(input);
        var key = suppliedKey == null ? generatedKey(identity, input) : suppliedKey;
        return db.inTransaction(
                h -> {
                    var id = UUID.randomUUID();
                    var inserted =
                            h.createUpdate(
                                            """
                                            INSERT INTO events(id,tenant_id,type,source,payload,idempotency_key,content_hash,producer_event_id,user_id,compartment_id)
                                            VALUES(:id,:tenant,:type,:source,CAST(:payload AS jsonb),:key,:hash,CAST(:producer AS uuid),:user,:compartment)
                                            ON CONFLICT DO NOTHING
                                            """)
                                    .bind("id", id)
                                    .bind("tenant", tenant)
                                    .bind("type", input.type())
                                    .bind("source", input.source())
                                    .bind("payload", encode(input.payload()))
                                    .bind("key", key)
                                    .bind("producer", input.eventId())
                                    .bind("user", identity.userId())
                                    .bind("compartment", identity.compartmentId())
                                    .bind("hash", hash)
                                    .execute();
                    if (inserted == 0) {
                        var existing = h.createQuery("""
                                SELECT * FROM events WHERE tenant_id=:tenant AND
                                (idempotency_key=:key OR (producer_event_id=CAST(:producer AS uuid) AND user_id=:user AND compartment_id=:compartment))
                                ORDER BY (idempotency_key=:key) DESC LIMIT 1
                                """)
                                .bind("tenant",tenant).bind("key",key).bind("producer",input.eventId())
                                .bind("user",identity.userId()).bind("compartment",identity.compartmentId()).mapToMap().one();
                        if (!hash.equals(existing.get("content_hash"))
                                || existing.get("user_id") != null && (!identity.userId().equals(existing.get("user_id"))
                                || !identity.compartmentId().equals(existing.get("compartment_id"))))
                            throw new WebApplicationException("Event identity or idempotency key conflicts with original content", 409);
                        return new Accepted(event(h, tenant, (UUID) existing.get("id")), true);
                    }
                    if(kafkaRequired && h.createQuery("SELECT count(*) FROM event_outbox WHERE dispatched_at IS NULL")
                            .mapTo(Long.class).one()>=maxQueuedEvents) {
                        throw new WebApplicationException("Event backlog capacity reached; retry later",503);
                    }
                    var subscriptions =
                            h.createQuery(
                                            "SELECT * FROM subscriptions WHERE tenant_id=:tenant"
                                                + " AND deleted_at IS NULL")
                                    .bind("tenant", tenant)
                                    .map((r, c) -> mapSubscription(r))
                                    .list();
                    for (var subscription : subscriptions) {
                        if (!Filters.matches(subscription.filter(), input)) continue;
                        h.createUpdate(
                                        """
                                        INSERT INTO deliveries(id,tenant_id,event_id,subscription_id,webhook_url,dispatch_ready)
                                        VALUES(:id,:tenant,:event,:subscription,:url,:ready)
                                        """)
                                .bind("id", UUID.randomUUID())
                                .bind("tenant", tenant)
                                .bind("event", id)
                                .bind("subscription", subscription.id())
                                .bind("ready", !kafkaRequired)
                                .bind("url", subscription.webhookUrl())
                                .execute();
                    }
                    if (kafkaRequired) {
                        h.createUpdate("INSERT INTO event_outbox(event_id,tenant_id) VALUES(:id,:tenant)")
                            .bind("id",id).bind("tenant",tenant).execute();
                    }
                    return new Accepted(event(h, tenant, id), false);
                });
    }

    public Event event(String tenant, UUID id) {
        return read(h -> event(h, tenant, id));
    }

    private Event event(Handle h, String tenant, UUID id) {
        return h.createQuery("SELECT * FROM events WHERE tenant_id=:tenant AND id=:id")
                .bind("tenant", tenant)
                .bind("id", id)
                .map((r, c) -> mapEvent(r))
                .findOne()
                .orElseThrow(NotFoundException::new);
    }

    public Delivery delivery(String tenant, UUID id) {
        return read(h -> delivery(h, tenant, id));
    }

    private Delivery delivery(Handle h, String tenant, UUID id) {
        var row =
                h.createQuery("SELECT * FROM deliveries WHERE tenant_id=:tenant AND id=:id")
                        .bind("tenant", tenant)
                        .bind("id", id)
                        .mapToMap()
                        .findOne()
                        .orElseThrow(NotFoundException::new);
        var attempts =
                h.createQuery(
                                "SELECT * FROM delivery_attempts WHERE delivery_id=:id ORDER BY"
                                    + " attempt_number")
                        .bind("id", id)
                        .map(
                                (r, c) ->
                                        new Attempt(
                                                r.getInt("attempt_number"),
                                                time(r, "started_at"),
                                                time(r, "finished_at"),
                                                (Integer) r.getObject("http_status"),
                                                r.getString("outcome"),
                                                r.getString("error")))
                        .list();
        return new Delivery(
                id,
                (UUID) row.get("event_id"),
                (UUID) row.get("subscription_id"),
                (String) row.get("status"),
                (Integer) row.get("attempts"),
                instant(row.get("next_attempt_at")),
                instant(row.get("delivered_at")),
                attempts);
    }

    public List<Delivery> deliveries(
            String tenant, UUID owner, boolean byEvent, int limit, int offset) {
        return read(
                h -> {
                    if (byEvent) event(h, tenant, owner);
                    else subscription(h, tenant, owner);
                    var column = byEvent ? "event_id" : "subscription_id";
                    var ids =
                            h.createQuery(
                                            "SELECT id FROM deliveries WHERE tenant_id=:tenant AND "
                                                    + column
                                                    + "=:owner ORDER BY created_at,id LIMIT :limit"
                                                    + " OFFSET :offset")
                                    .bind("tenant", tenant)
                                    .bind("owner", owner)
                                    .bind("limit", limit)
                                    .bind("offset", offset)
                                    .mapTo(UUID.class)
                                    .list();
                    return ids.stream().map(id -> delivery(h, tenant, id)).toList();
                });
    }

    public Claim claim(int leaseSeconds, int maxAttempts) {
        var reservation=reserve(leaseSeconds,maxAttempts);
        return reservation != null && startAttempt(reservation) ? reservation : null;
    }

    public Claim reserve(int leaseSeconds, int maxAttempts) {
        return db.inTransaction(
                h -> {
                    // Serialize reservations per subscriber across all replicas, then recheck with a fresh statement.
                    var subscriber = h.createQuery("""
                            SELECT s.id FROM subscriptions s WHERE EXISTS (
                              SELECT 1 FROM deliveries d WHERE d.subscription_id=s.id AND d.status='pending'
                              AND d.dispatch_ready AND d.lease_token IS NULL AND d.attempts<:max
                              AND d.next_attempt_at<=clock_timestamp())
                            AND (SELECT count(*) FROM deliveries active WHERE active.subscription_id=s.id
                              AND active.status='pending' AND active.lease_expires_at>clock_timestamp()) < :cap
                            ORDER BY s.last_claimed_at NULLS FIRST,s.id LIMIT 1 FOR UPDATE OF s SKIP LOCKED
                            """).bind("max",maxAttempts).bind("cap",subscriberConcurrency).mapTo(UUID.class).findOne();
                    if (subscriber.isEmpty()) return null;
                    var active = h.createQuery("SELECT count(*) FROM deliveries WHERE subscription_id=:sub AND status='pending' AND lease_expires_at>clock_timestamp()")
                            .bind("sub",subscriber.get()).mapTo(Integer.class).one();
                    if (active >= subscriberConcurrency) return null;
                    var id = h.createQuery("""
                            SELECT id FROM deliveries WHERE subscription_id=:sub AND status='pending' AND dispatch_ready
                            AND lease_token IS NULL AND next_attempt_at<=clock_timestamp() AND attempts<:max
                            ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                            """).bind("sub",subscriber.get()).bind("max",maxAttempts).mapTo(UUID.class).findOne();
                    if (id.isEmpty()) return null;
                    h.createUpdate("UPDATE subscriptions SET last_claimed_at=clock_timestamp() WHERE id=:id")
                            .bind("id",subscriber.get()).execute();
                    var token = UUID.randomUUID();
                    var row =
                            h.createQuery(
                                            """
                                            UPDATE deliveries SET lease_token=:token, lease_expires_at=clock_timestamp()+:seconds * interval '1 second',
                                            attempts=attempts WHERE id=:id RETURNING *
                                            """)
                                    .bind("token", token)
                                    .bind("seconds", leaseSeconds)
                                    .bind("id", id.get())
                                    .mapToMap()
                                    .one();
                    var number = (Integer) row.get("attempts")+1;
                    var tenant = (String) row.get("tenant_id");
                    return new Claim(
                            id.get(),
                            (UUID) row.get("subscription_id"),
                            tenant,
                            (String) row.get("webhook_url"),
                            token,
                            number,
                            event(h, tenant, (UUID) row.get("event_id")));
                });
    }

    public boolean startAttempt(Claim claim) {
        return db.inTransaction(h->{
            var changed=h.createUpdate("UPDATE deliveries SET attempts=attempts+1 WHERE id=:id AND lease_token=:token AND lease_expires_at>clock_timestamp() AND attempts=:previous")
                    .bind("id",claim.id()).bind("token",claim.token()).bind("previous",claim.number()-1).execute();
            if(changed==0)return false;
            h.createUpdate("INSERT INTO delivery_attempts(id,delivery_id,attempt_number) VALUES(:id,:delivery,:number)")
                    .bind("id",UUID.randomUUID()).bind("delivery",claim.id()).bind("number",claim.number()).execute();
            return true;
        });
    }

    public boolean deferForRetryBudget(Claim claim) {
        return db.withHandle(h->h.createUpdate("""
                UPDATE deliveries SET lease_token=NULL,lease_expires_at=NULL,
                next_attempt_at=date_trunc('minute',clock_timestamp())+interval '1 minute'+random()*interval '1 second'
                WHERE id=:id AND lease_token=:token AND lease_expires_at>clock_timestamp() AND attempts=:previous
                """).bind("id",claim.id()).bind("token",claim.token()).bind("previous",claim.number()-1).execute()==1);
    }

    public boolean finish(Claim claim, Result result, RetryPolicy policy) {
        var success =
                result.httpStatus() != null
                        && result.httpStatus() >= 200
                        && result.httpStatus() < 300;
        var retry =
                !success
                        && !"destination_policy".equals(result.error())
                        && policy.retryable(result.httpStatus())
                        && claim.number() < policy.maxAttempts();
        var status = success ? "delivered" : retry ? "pending" : "failed";
        var delay = retry ? policy.delayMillis(claim.number()) : 0;
        return db.inTransaction(
                h -> {
                    var changed =
                            h.createUpdate(
                                            """
                                            UPDATE deliveries SET status=:status, lease_token=NULL, lease_expires_at=NULL,
                                            next_attempt_at=clock_timestamp()+:delay * interval '1 millisecond',
                                            delivered_at=CASE WHEN :success THEN clock_timestamp() ELSE NULL END
                                            WHERE id=:id AND lease_token=:token AND lease_expires_at>clock_timestamp()
                                            """)
                                    .bind("status", status)
                                    .bind("delay", delay)
                                    .bind("success", success)
                                    .bind("id", claim.id())
                                    .bind("token", claim.token())
                                    .execute();
                    if (changed == 0) return false;
                    h.createUpdate(
                                    """
                                    UPDATE delivery_attempts SET finished_at=clock_timestamp(),http_status=:code,outcome=:outcome,error=:error
                                    WHERE delivery_id=:id AND attempt_number=:number AND finished_at IS NULL
                                    """)
                            .bind("code", result.httpStatus())
                            .bind(
                                    "outcome",
                                    success ? "delivered" : retry ? "retry_scheduled" : "failed")
                            .bind("error", result.error())
                            .bind("id", claim.id())
                            .bind("number", claim.number())
                            .execute();
                    if ("failed".equals(status)) deadLetter(h, claim.id(),
                            claim.number() >= policy.maxAttempts() ? "retries_exhausted" : "permanent_failure");
                    return true;
                });
    }

    public int recover(int maxAttempts) {
        return db.inTransaction(
                h -> {
                    var rows =
                            h.createQuery(
                                            """
                                            SELECT id,attempts FROM deliveries WHERE status='pending' AND lease_expires_at<=clock_timestamp()
                                            LIMIT 100 FOR UPDATE SKIP LOCKED
                                            """)
                                    .mapToMap()
                                    .list();
                    for (var row : rows) {
                        h.createUpdate(
                                        """
                                        UPDATE delivery_attempts SET finished_at=clock_timestamp(),outcome='unknown',error='lease_expired'
                                        WHERE delivery_id=:id AND finished_at IS NULL
                                        """)
                                .bind("id", row.get("id"))
                                .execute();
                        h.createUpdate(
                                        """
                                        UPDATE deliveries SET lease_token=NULL,lease_expires_at=NULL,next_attempt_at=clock_timestamp(),
                                        status=CASE WHEN attempts>=:max THEN 'failed' ELSE 'pending' END WHERE id=:id
                                        """)
                                .bind("max", maxAttempts)
                                .bind("id", row.get("id"))
                                .execute();
                    }
                    // Handles an operator reducing the retry limit between deployments.
                    h.createUpdate(
                                    "UPDATE deliveries SET status='failed' WHERE status='pending'"
                                        + " AND lease_token IS NULL AND attempts>=:max")
                            .bind("max", maxAttempts)
                            .execute();
                    h.createUpdate("""
                            INSERT INTO delivery_dead_letters(delivery_id,tenant_id,event_id,subscription_id,attempt_count,reason)
                            SELECT id,tenant_id,event_id,subscription_id,attempts,'lease_expired_or_retry_limit'
                            FROM deliveries WHERE status='failed' ON CONFLICT DO NOTHING
                            """).execute();
                    return rows.size();
                });
    }

    public record Outbox(UUID eventId, String tenant, UUID token, Event event) {}

    public Outbox claimOutbox() {
        return db.inTransaction(h -> {
            var row = h.createQuery("""
                SELECT event_id,tenant_id FROM event_outbox WHERE dispatched_at IS NULL
                AND (published_at IS NULL OR published_at<clock_timestamp()-interval '60 seconds')
                AND available_at<=clock_timestamp()
                AND (lease_expires_at IS NULL OR lease_expires_at<=clock_timestamp())
                ORDER BY available_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """).mapToMap().findOne();
            if(row.isEmpty()) return null;
            var id=(UUID)row.get().get("event_id");
            var tenant=(String)row.get().get("tenant_id");
            var token=UUID.randomUUID();
            h.createUpdate("""
                UPDATE event_outbox SET lease_token=:token,lease_expires_at=clock_timestamp()+interval '30 seconds',
                publish_attempts=publish_attempts+1 WHERE event_id=:id
                """).bind("token",token).bind("id",id).execute();
            return new Outbox(id,tenant,token,event(h,tenant,id));
        });
    }

    public void finishPublish(Outbox outbox, boolean success) {
        db.useHandle(h -> h.createUpdate("""
            UPDATE event_outbox SET published_at=CASE WHEN :success THEN clock_timestamp() ELSE published_at END,
            lease_token=NULL,lease_expires_at=NULL,available_at=clock_timestamp()+interval '2 seconds'
            WHERE event_id=:id AND lease_token=:token
            """).bind("success",success).bind("id",outbox.eventId()).bind("token",outbox.token()).execute());
    }

    public void activateDispatch(String tenant, UUID eventId) {
        db.useTransaction(h -> {
            var exists=h.createQuery("SELECT event_id FROM event_outbox WHERE event_id=:id AND tenant_id=:tenant FOR UPDATE")
                .bind("id",eventId).bind("tenant",tenant).mapTo(UUID.class).findOne();
            if(exists.isEmpty()) throw new IllegalStateException("Accepted event not available; retry without committing Kafka offset");
            h.createUpdate("UPDATE deliveries SET dispatch_ready=TRUE WHERE event_id=:id AND tenant_id=:tenant")
                .bind("id",eventId).bind("tenant",tenant).execute();
            h.createUpdate("UPDATE event_outbox SET dispatched_at=COALESCE(dispatched_at,clock_timestamp()) WHERE event_id=:id")
                .bind("id",eventId).execute();
        });
    }

    public void quarantine(String topic, int partition, long offset, String payload) {
        db.useHandle(h -> h.createUpdate("""
            INSERT INTO kafka_dead_letters(topic,partition_id,record_offset,payload,error)
            VALUES(:topic,:partition,:offset,:payload,'invalid_envelope') ON CONFLICT DO NOTHING
            """).bind("topic",topic).bind("partition",partition).bind("offset",offset).bind("payload",payload).execute());
    }

    public long outboxPending() {
        return db.withHandle(h -> h.createQuery("SELECT count(*) FROM event_outbox WHERE published_at IS NULL AND dispatched_at IS NULL").mapTo(Long.class).one());
    }

    public double outboxOldestSeconds() {
        return db.withHandle(h -> h.createQuery("SELECT COALESCE(EXTRACT(EPOCH FROM clock_timestamp()-min(created_at)),0) FROM event_outbox WHERE dispatched_at IS NULL")
            .mapTo(Double.class).one());
    }

    public long pendingCount() {
        return db.withHandle(
                h ->
                        h.createQuery("SELECT count(*) FROM deliveries WHERE status='pending'")
                                .mapTo(Long.class)
                                .one());
    }

    public double oldestPendingSeconds() {
        return db.withHandle(
                h ->
                        h.createQuery(
                                        "SELECT COALESCE(EXTRACT(EPOCH FROM"
                                            + " clock_timestamp()-min(created_at)),0) FROM"
                                            + " deliveries WHERE status='pending'")
                                .mapTo(Double.class)
                                .one());
    }

    public boolean healthy() {
        try {
            if (db.withHandle(h -> h.createQuery("SELECT 1").mapTo(Integer.class).one()) != 1) return false;
            return reads == db || read(h -> h.createQuery("SELECT 1").mapTo(Integer.class).one()) == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Subscription mapSubscription(ResultSet r) throws SQLException {
        return new Subscription(
                r.getObject("id", UUID.class),
                r.getString("webhook_url"),
                decode(r.getString("filter"), Rules.class),
                time(r, "created_at"),
                time(r, "deleted_at"));
    }

    private Event mapEvent(ResultSet r) throws SQLException {
        return new Event(
                r.getObject("id", UUID.class),
                r.getString("type"),
                r.getString("source"),
                decode(r.getString("payload"), JsonNode.class),
                time(r, "accepted_at"));
    }

    private static Instant time(ResultSet r, String column) throws SQLException {
        var value = r.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.OffsetDateTime date) return date.toInstant();
        throw new IllegalStateException("Unsupported database timestamp");
    }

    public String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to encode JSON", e);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid persisted JSON", e);
        }
    }

    private <T> T read(java.util.function.Function<Handle,T> query) {
        return reads.inTransaction(org.jdbi.v3.core.transaction.TransactionIsolationLevel.REPEATABLE_READ, h -> {
            h.execute("SET TRANSACTION READ ONLY");
            if (reads != db && !h.createQuery("SELECT pg_is_in_recovery()").mapTo(Boolean.class).one())
                throw new WebApplicationException("Read replica temporarily unavailable",503);
            var result = query.apply(h);
            if (reads != db && !h.createQuery("SELECT pg_is_in_recovery()").mapTo(Boolean.class).one())
                throw new WebApplicationException("Read replica changed role during query",503);
            return result;
        });
    }

    public String generatedKey(Identity identity, EventInput input) {
        try {
            // No random server-generated ID enters this hash: retries must produce the same key.
            return "auto-v1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    encode(List.of(identity.tenant(),identity.userId(),identity.compartmentId(),hash(input))).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private void deadLetter(Handle h, UUID delivery, String reason) {
        h.createUpdate("""
                INSERT INTO delivery_dead_letters(delivery_id,tenant_id,event_id,subscription_id,attempt_count,reason)
                SELECT id,tenant_id,event_id,subscription_id,attempts,:reason FROM deliveries WHERE id=:id
                ON CONFLICT DO NOTHING
                """).bind("id",delivery).bind("reason",reason).execute();
    }

    public List<DeadLetter> deadLetters(String tenant,int limit,int offset) {
        return read(h -> h.createQuery("SELECT * FROM delivery_dead_letters WHERE tenant_id=:tenant ORDER BY failed_at,delivery_id LIMIT :limit OFFSET :offset")
                .bind("tenant",tenant).bind("limit",limit).bind("offset",offset)
                .map((r,c) -> new DeadLetter(r.getObject("delivery_id",UUID.class),r.getObject("event_id",UUID.class),
                        r.getObject("subscription_id",UUID.class),r.getInt("attempt_count"),r.getString("reason"),time(r,"failed_at"),time(r,"published_at"))).list());
    }

    public record DeadLetterPublish(UUID deliveryId, UUID token, String tenant, Event event, int attempts, String reason) {}

    public DeadLetterPublish claimDeadLetter() {
        return db.inTransaction(h -> {
            var row=h.createQuery("SELECT * FROM delivery_dead_letters WHERE published_at IS NULL AND (lease_expires_at IS NULL OR lease_expires_at<=clock_timestamp()) ORDER BY failed_at,delivery_id LIMIT 1 FOR UPDATE SKIP LOCKED")
                    .mapToMap().findOne();
            if(row.isEmpty()) return null;
            var value=row.get();var token=UUID.randomUUID();var id=(UUID)value.get("delivery_id");var tenant=(String)value.get("tenant_id");
            h.createUpdate("UPDATE delivery_dead_letters SET lease_token=:token,lease_expires_at=clock_timestamp()+interval '30 seconds',publish_attempts=publish_attempts+1 WHERE delivery_id=:id")
                    .bind("token",token).bind("id",id).execute();
            return new DeadLetterPublish(id,token,tenant,event(h,tenant,(UUID)value.get("event_id")),(Integer)value.get("attempt_count"),(String)value.get("reason"));
        });
    }

    public void finishDeadLetter(DeadLetterPublish claim) {
        db.useHandle(h -> h.createUpdate("UPDATE delivery_dead_letters SET published_at=clock_timestamp(),lease_token=NULL,lease_expires_at=NULL WHERE delivery_id=:id AND lease_token=:token AND lease_expires_at>clock_timestamp()")
                .bind("id",claim.deliveryId()).bind("token",claim.token()).execute());
    }

    private String hash(EventInput input) {
        try {
            var content = json.createObjectNode().put("type",input.type()).put("source",input.source());
            content.set("payload",input.payload());
            if (input.eventId() != null) content.put("eventId",input.eventId().toString());
            var canonical = canonical(content);
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(encode(canonical).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Object canonical(JsonNode node) {
        if (node.isObject()) {
            var sorted = new TreeMap<String, Object>();
            node.fields()
                    .forEachRemaining(
                            entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            var values = new ArrayList<Object>();
            node.forEach(value -> values.add(canonical(value)));
            return values;
        }
        return node;
    }
}
