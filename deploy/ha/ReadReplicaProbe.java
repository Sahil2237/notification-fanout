import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** Executed only against disposable HA fixture addresses, using the application JDBC driver. */
public class ReadReplicaProbe {
    public static void main(String[] args) throws Exception {
        var hosts = System.getenv("PGPROBE_HOSTS");
        if (hosts == null || !hosts.matches("172\\.29\\.0\\.(11|12|13)(,172\\.29\\.0\\.(11|12|13)){2}"))
            throw new IllegalArgumentException("Disposable HA fixture only");
        var properties = new Properties();
        properties.setProperty("user", "notification_owner");
        properties.setProperty("password", System.getenv("PGPROBE_PASSWORD"));
        properties.setProperty("sslmode", "verify-full");
        properties.setProperty("sslrootcert", "/tls/postgres-ca.crt");
        properties.setProperty("targetServerType", "secondary");
        properties.setProperty("loadBalanceHosts", "true");
        properties.setProperty("connectTimeout", "2");
        properties.setProperty("socketTimeout", "5");
        properties.setProperty("readOnly", "true");
        Class.forName("org.postgresql.Driver");
        var unavailable = Boolean.parseBoolean(System.getenv("PGPROBE_EXPECT_UNAVAILABLE"));
        java.sql.Connection connection;
        try {
            connection = DriverManager.getConnection("jdbc:postgresql://" + hosts + "/postgres", properties);
        } catch (SQLException e) {
            if (!unavailable || !e.getSQLState().startsWith("08")) throw e;
            System.out.println("{\"primary_fallback_refused\":true}");
            return;
        }
        try (connection) {
            if (unavailable) throw new IllegalStateException("Read connection fell back to a primary");
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT pg_is_in_recovery(),inet_server_addr()::text,(SELECT count(*) FROM ha_acknowledged_events)")) {
                result.next();
                if (!result.getBoolean(1) || result.getInt(3) < 100)
                    throw new IllegalStateException("Read did not reach a caught-up standby");
                System.out.println("{\"read_on_replica\":true,\"server\":\"" + result.getString(2) + "\"}");
            }
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE forbidden_replica_write(id integer)");
                throw new IllegalStateException("Replica unexpectedly accepted DDL");
            } catch (SQLException e) {
                if (!"25006".equals(e.getSQLState())) throw e;
            }
            connection.rollback();
        }
    }
}
