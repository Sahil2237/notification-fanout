import com.example.notifications.RedisRateLimiter;
import com.example.notifications.RedisSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.ServiceUnavailableException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Exercises the real application Redis client against a disposable TLS/Sentinel cluster. */
public class RedisHaProbe {
    public static void main(String[] args) throws Exception {
        var config = new ObjectMapper().readValue(Path.of(args[0]).toFile(), RedisSettings.class);
        var state = Path.of(args[1]);
        var mode = args[2];
        if (mode.equals("hostname")) {
            config.sentinels = java.util.List.of(); config.host = "wrong-redis-host";
            try (var limiter = new RedisRateLimiter(config)) {
                if (limiter.healthy()) throw new AssertionError("TLS hostname validation bypassed");
            }
            System.out.println("PROBE_RESULT {\"invalid_hostname_rejected\":true}"); return;
        }
        config.validate(true, false);
        try (var one = new RedisRateLimiter(config); var two = new RedisRateLimiter(config); var three = new RedisRateLimiter(config)) {
            if (mode.equals("unavailable")) {
                try { one.allow("unavailable-" + UUID.randomUUID(), 100); throw new AssertionError("Missing replica accepted a request"); }
                catch (ServiceUnavailableException expected) { System.out.println("PROBE_RESULT {\"missing_replication_fails_closed\":true}"); return; }
            }
            one.start(); two.start(); three.start();
            if (mode.equals("shared")) {
                var tenant = "ha-shared-" + UUID.randomUUID(); var clients = java.util.List.of(one,two,three); var accepted = 0;
                for (var i=0;i<36;i++) if (clients.get(i%3).allow(tenant,12)) accepted++;
                if (accepted != 12) throw new AssertionError("Shared limit diverged: " + accepted);
                System.out.println("PROBE_RESULT {\"three_clients_share_limit\":true,\"accepted\":12}"); return;
            }
            var tenant = "follow-" + UUID.randomUUID();
            if (!one.allow(tenant,1000)) throw new AssertionError("Initial primary rejected request");
            Files.writeString(state.resolve("ready"), "ready");
            var deadline = System.nanoTime()+java.time.Duration.ofSeconds(90).toNanos();
            while (!Files.exists(state.resolve("stopped"))) {
                if (System.nanoTime()>deadline) throw new AssertionError("Failure trigger missing"); Thread.sleep(100);
            }
            while (System.nanoTime()<deadline) {
                try {
                    if (one.allow(tenant,1000) && one.healthy()) {
                        Files.writeString(state.resolve("followed"), "followed");
                        System.out.println("PROBE_RESULT {\"existing_client_followed_automatic_failover\":true}"); return;
                    }
                } catch (ServiceUnavailableException retryDuringElection) { }
                Thread.sleep(500);
            }
            throw new AssertionError("Existing client did not follow elected Redis primary");
        }
    }
}
