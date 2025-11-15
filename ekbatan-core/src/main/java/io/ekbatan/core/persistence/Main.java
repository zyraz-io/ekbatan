package io.ekbatan.core.persistence;

import static io.ekbatan.core.persistence.connection.ConnectionProvider.hikariConnectionProvider;

import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.persistence.connection.ConnectionMode;
import java.sql.SQLException;

public class Main {

    static void main(String[] args) {
        var primaryConfig = new DataSourceConfig(
                "jdbc:postgresql://localhost:5432/primarydb",
                "dbuser",
                "dbpassword",
                null, // driverClassName, let Hikari auto-detect
                10, // maximumPoolSize
                2, // minimumIdle
                60000L, // idleTimeout
                3000L // leakDetectionThreshold
                );

        var replicaConfig = new DataSourceConfig(
                "jdbc:postgresql://localhost:5432/replicadb", "dbuser", "dbpassword", null, 10, 2, 60000L, 3000L);

        final var primaryConnectionProvider = hikariConnectionProvider(primaryConfig, true);
        final var replicaConnectionProvider = hikariConnectionProvider(replicaConfig, false);

        final var txManager = new TransactionManager(primaryConnectionProvider, replicaConnectionProvider);

        final var result = txManager.inTransaction(ConnectionMode.REQUIRE_NEW, conn -> {
            try (var stmt = conn.createStatement();
                    var rs = stmt.executeQuery("SELECT NOW()")) {

                if (rs.next()) {
                    return rs.getString(1);
                } else {
                    return "No result";
                }

            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });

        System.out.println("Query result: " + result);
    }
}
