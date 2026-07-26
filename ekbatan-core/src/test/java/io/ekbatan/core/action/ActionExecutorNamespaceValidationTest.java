package io.ekbatan.core.action;

import static io.ekbatan.core.action.ActionExecutor.Builder.actionExecutor;
import static io.ekbatan.core.action.ActionRegistry.Builder.actionRegistry;
import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.persistence.ConnectionProvider.hikariConnectionProvider;
import static io.ekbatan.core.repository.RepositoryRegistry.Builder.repositoryRegistry;
import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/**
 * The namespace has to be spellable as a schema package, not just non-blank.
 *
 * <p>It is a naming root: it becomes part of the Kafka topic name, and for binary streaming it is
 * the package a payload's {@code .proto} or {@code .avsc} declares. Protobuf's grammar is
 * {@code ident = letter { letter | decimalDigit | "_" }}, so {@code package my-service;} is a
 * syntax error - protoc reports {@code Expected ";"}. Accepting a hyphen here only defers that
 * failure to whoever later writes a schema for these events, by which point the namespace is
 * already on every outbox row and in every topic name.
 *
 * <p>This was permissive until now, and the repository's own values proved the point: fourteen of
 * nineteen namespaces in use were hyphenated, including {@code test.local-event-handler}, because
 * a namespace names a service and service names are conventionally hyphenated.
 */
class ActionExecutorNamespaceValidationTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "wallet",
                "example.wallet",
                "example.wallet.worker",
                "test.local_event_handler",
                "_leading.underscore",
                "with9digits.after1letter",
            })
    void a_namespace_shaped_like_a_java_package_is_accepted(String namespace) {
        assertThatCode(() -> build(namespace)).doesNotThrowAnyException();
    }

    /** The case the whole rule exists for: protoc rejects a hyphen outright. */
    @ParameterizedTest
    @ValueSource(strings = {"my-service", "example.wallet.quarkus-wallet-rest-gradle-pg", "test.local-event-handler"})
    void a_hyphenated_namespace_is_rejected(String namespace) {
        assertThatThrownBy(() -> build(namespace))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(namespace)
                .hasMessageContaining("my_service");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "9leading.digit",
                "trailing.",
                ".leading",
                "double..dot",
                "has space",
                "has/slash",
                "has:colon",
            })
    void anything_else_that_is_not_an_identifier_is_rejected(String namespace) {
        assertThatThrownBy(() -> build(namespace)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Blank stays an {@code IllegalArgumentException} too - the message just says something different. */
    @Test
    void a_blank_namespace_still_says_it_is_required() {
        assertThatThrownBy(() -> build("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace is required");
    }

    /**
     * The pattern is anchored. Without that, {@code matcher.find()} semantics would accept
     * {@code my-service} because the legal {@code my} prefix matches on its own.
     */
    @Test
    void a_valid_prefix_does_not_smuggle_in_an_invalid_remainder() {
        assertThatThrownBy(() -> build("valid.prefix-then-rubbish")).isInstanceOf(IllegalArgumentException.class);
    }

    private static ActionExecutor build(String namespace) {
        return actionExecutor()
                .namespace(namespace)
                .databaseRegistry(registry())
                .objectMapper(new ObjectMapper())
                .repositoryRegistry(repositoryRegistry().build())
                .actionRegistry(actionRegistry().build())
                .build();
    }

    /**
     * Points at a host that does not resolve; {@code initializationFailTimeout = -1} means the pool
     * is constructed without contacting it, so this stays a unit test.
     */
    private static DatabaseRegistry registry() {
        var provider = hikariConnectionProvider(dataSourceConfig()
                .jdbcUrl("jdbc:postgresql://nowhere-9e3f:5432/db")
                .username("u")
                .password("p")
                .maximumPoolSize(1)
                .build());
        return databaseRegistry()
                .withDefaultDatabase(
                        new TransactionManager(provider, provider, SQLDialect.POSTGRES, ShardIdentifier.of(0, 0)))
                .build();
    }
}
