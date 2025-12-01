package io.ekbatan.core.persistence;

import static io.ekbatan.core.persistence.connection.ConnectionProvider.hikariConnectionProvider;
import static java.util.Optional.empty;

import io.ekbatan.core.config.DataSourceConfig;

public class Main {

    static void main(String[] args) {
        var primaryConfig = new DataSourceConfig(
                "jdbc:postgresql://localhost:5432/primarydb",
                "dbuser",
                "dbpassword",
                empty(), // driverClassName, let Hikari auto-detect
                10, // maximumPoolSize
                empty(), // minimumIdle 2
                empty(), // idleTimeout 60000L
                empty() // leakDetectionThreshold 3000L
                );

        var replicaConfig = new DataSourceConfig(
                "jdbc:postgresql://localhost:5432/replicadb",
                "dbuser",
                "dbpassword",
                empty(),
                10,
                empty(),
                empty(),
                empty());

        final var primaryConnectionProvider = hikariConnectionProvider(primaryConfig, true);
        final var secondaryConnectionProvider = hikariConnectionProvider(replicaConfig, false);

        final var txManager = new TransactionManager(primaryConnectionProvider, secondaryConnectionProvider);

        final var result = txManager.inTransaction(
                dslContext -> { // This now unambiguously calls the method with a standard Function
                    return 1;
                });

        System.out.println("Query result: " + result);
    }
}
