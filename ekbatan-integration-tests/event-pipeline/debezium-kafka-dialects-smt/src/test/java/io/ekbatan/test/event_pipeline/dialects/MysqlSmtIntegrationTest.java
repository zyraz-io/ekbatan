package io.ekbatan.test.event_pipeline.dialects;

import static io.ekbatan.test.event_pipeline.dialects.DialectPipeline.CONTAINER_DESCRIPTORS_DIR;
import static io.ekbatan.test.event_pipeline.dialects.DialectPipeline.CONTAINER_PLUGIN_DIR;
import static io.ekbatan.test.event_pipeline.dialects.DialectPipeline.EVENT_TYPE;
import static io.ekbatan.test.event_pipeline.dialects.DialectPipeline.INIT_SQL;
import static io.ekbatan.test.event_pipeline.dialects.DialectPipeline.NAMESPACE;
import static io.ekbatan.test.event_pipeline.dialects.DialectPipeline.TOPIC_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;

import io.debezium.testing.testcontainers.ConnectorConfiguration;
import io.debezium.testing.testcontainers.DebeziumContainer;
import io.ekbatan.events.streaming.actionevent.protobuf.ActionEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The MySQL half of the encoding pipeline, end to end against real Debezium.
 *
 * <p>This is the case that used to kill the connector task on its first record. MySQL has no
 * boolean type - {@code BOOLEAN} is an alias for {@code TINYINT(1)} - so Debezium sends
 * {@code delivered} as an INT16, and the SMT handed that straight to protobuf's
 * {@code setField}, which throws for a {@code bool} field.
 *
 * <p>The unit tests assert the same thing against hand-built Connect records, but those encode
 * one particular model of what Debezium emits. This test is what holds that model accountable to
 * what the real connector actually produces.
 */
class MysqlSmtIntegrationTest {

    private static final Path SMT_JAR = Path.of(System.getProperty("smt.plugin.jar"));
    private static final Path ACTION_EVENT_DESCRIPTOR = Path.of(System.getProperty("smt.action.event.descriptor"));

    private static final Network NETWORK = Network.newNetwork();

    private static final MySQLContainer DB = new MySQLContainer("mysql:9.4.0")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withCopyToContainer(Transferable.of(INIT_SQL), "/docker-entrypoint-initdb.d/init.sql")
            .withEnv("TZ", "UTC")
            .withNetwork(NETWORK)
            .withNetworkAliases("mysql");

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.0")
            .withNetwork(NETWORK)
            .withNetworkAliases("kafka")
            .withListener("kafka:19092");

    private static DebeziumContainer debezium;
    private static ActionEvent event;

    @BeforeAll
    static void setUp() throws Exception {
        var descriptors = Files.createTempDirectory("ekbatan-mysql-smt");
        var payloadDescriptor = DialectPipeline.writePayloadDescriptor(descriptors);

        debezium = new DebeziumContainer("quay.io/debezium/connect:3.5.0.Final")
                .withNetwork(NETWORK)
                .withKafka(NETWORK, "kafka:19092")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(SMT_JAR), CONTAINER_PLUGIN_DIR + "/" + SMT_JAR.getFileName())
                .withCopyFileToContainer(
                        MountableFile.forHostPath(ACTION_EVENT_DESCRIPTOR),
                        CONTAINER_DESCRIPTORS_DIR + "/ActionEvent.desc")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(payloadDescriptor), CONTAINER_DESCRIPTORS_DIR + "/payload.desc")
                .dependsOn(KAFKA);

        Startables.deepStart(DB, KAFKA, debezium).join();

        DialectPipeline.createOutboxTable(
                DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), DialectPipeline.MYSQL_DDL);

        debezium.registerConnector("mysql-outbox", connectorConfig());

        var registry = DialectPipeline.registry(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), SQLDialect.MYSQL);
        DialectPipeline.writeEvent(registry, "kitchen-light", true);

        var events = DialectPipeline.drain(KAFKA.getBootstrapServers(), "mysql-e2e", 1, Duration.ofSeconds(90));
        assertThat(events)
                .as("no ActionEvent reached Kafka from MySQL - check the connector task status")
                .isNotEmpty();
        event = events.getFirst();
    }

    @AfterAll
    static void tearDown() {
        if (debezium != null) {
            debezium.stop();
        }
        KAFKA.stop();
        DB.stop();
    }

    @Test
    void the_row_survives_the_whole_pipeline() {
        assertThat(event.getNamespace()).isEqualTo(NAMESPACE);
        assertThat(event.getActionName()).isEqualTo("ToggleWidgetAction");
        assertThat(event.getEventType()).isEqualTo(EVENT_TYPE);
        assertThat(event.getModelType()).isEqualTo("Widget");
    }

    /** The regression: Debezium's INT16 for a TINYINT(1) has to arrive as a protobuf bool. */
    @Test
    void delivered_is_a_boolean_not_an_int16() {
        assertThat(event.getDelivered()).isFalse();
    }

    /**
     * Timestamps have to arrive as epoch microseconds. A unit that is wrong by the usual factor of
     * a thousand puts the instant in 1970 or in the far future, so pinning the year catches it.
     */
    @Test
    void timestamps_are_epoch_micros() {
        var thisYear = Instant.now().atZone(ZoneOffset.UTC).getYear();
        for (var micros : new long[] {event.getStartedDate(), event.getCompletionDate(), event.getEventDate()}) {
            var when = Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
            assertThat(when.atZone(ZoneOffset.UTC).getYear())
                    .as("epoch micros %d decodes to %s", micros, when)
                    .isEqualTo(thisYear);
        }
    }

    /** MySQL stores these as CHAR(36) behind a converter; they must survive as UUID strings. */
    @Test
    void identifiers_are_uuid_strings() {
        assertThat(UUID.fromString(event.getId())).isNotNull();
        assertThat(UUID.fromString(event.getActionId())).isNotNull();
        assertThat(UUID.fromString(event.getModelId())).isNotNull();
    }

    @Test
    void the_payload_and_action_params_survive() {
        assertThat(event.getPayload().size()).isPositive();
        assertThat(event.getActionParams()).contains("kitchen-light");
    }

    private static ConnectorConfiguration connectorConfig() {
        return ConnectorConfiguration.create()
                .with("connector.class", "io.debezium.connector.mysql.MySqlConnector")
                .with("database.hostname", "mysql")
                .with("database.port", "3306")
                .with("database.user", "test")
                .with("database.password", "test")
                .with("database.server.id", "184054")
                .with("topic.prefix", TOPIC_PREFIX)
                .with("database.include.list", "eventlog")
                .with("table.include.list", "eventlog.events")
                .with("schema.history.internal.kafka.bootstrap.servers", "kafka:19092")
                .with("schema.history.internal.kafka.topic", "schema-changes.eventlog")
                // Schema-change records have no `after` field, which the SMT still mishandles
                // (audit finding 2 - it passes them through as a Struct that ByteArrayConverter
                // cannot serialize). Turn them off until that is fixed, then flip this to true as
                // the regression test for it.
                .with("include.schema.changes", "false")
                .with("value.converter", "org.apache.kafka.connect.converters.ByteArrayConverter")
                .with("transforms", "encodeProto")
                .with(
                        "transforms.encodeProto.type",
                        "io.ekbatan.events.streaming.debeziumsmt.protobuf.OutboxToProtobufTransform")
                .with(
                        "transforms.encodeProto.payloadDescriptors",
                        EVENT_TYPE + ":" + CONTAINER_DESCRIPTORS_DIR + "/payload.desc")
                .with("transforms.encodeProto.actionEventDescriptor", CONTAINER_DESCRIPTORS_DIR + "/ActionEvent.desc");
    }
}
