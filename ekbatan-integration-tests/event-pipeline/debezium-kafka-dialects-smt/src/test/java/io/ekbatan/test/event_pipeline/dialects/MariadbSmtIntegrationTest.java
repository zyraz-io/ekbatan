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
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The MariaDB half of the encoding pipeline, end to end against real Debezium.
 *
 * <p>MariaDB is the interesting one. Like MySQL it has no boolean type, so {@code delivered}
 * arrives as an INT16. Unlike MySQL, {@code EventEntityRepository} binds {@code id} and
 * {@code action_id} as <em>native</em> {@code UUID} columns, which MariaDB stores as
 * {@code BINARY(16)} - so this is the deployment where the SMT's binary-to-UUID handling is
 * reachable at all.
 *
 * <p>That branch was written from the specification because it could not be measured at the time.
 * This test is what measures it: whatever Debezium actually sends for a native {@code UUID}
 * column, the identifiers below have to come out as canonical UUID strings.
 */
class MariadbSmtIntegrationTest {

    private static final Path SMT_JAR = Path.of(System.getProperty("smt.plugin.jar"));
    private static final Path ACTION_EVENT_DESCRIPTOR = Path.of(System.getProperty("smt.action.event.descriptor"));

    private static final Network NETWORK = Network.newNetwork();

    private static final MariaDBContainer DB = new MariaDBContainer("mariadb:11.8")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withCopyToContainer(Transferable.of(INIT_SQL), "/docker-entrypoint-initdb.d/init.sql")
            .withEnv("TZ", "UTC")
            // MariaDB does not enable the binary log by default; Debezium cannot stream without it.
            .withCommand("--log-bin=mariadb-bin", "--binlog-format=ROW", "--server-id=1", "--log-basename=mariadb")
            .withNetwork(NETWORK)
            .withNetworkAliases("mariadb");

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.0")
            .withNetwork(NETWORK)
            .withNetworkAliases("kafka")
            .withListener("kafka:19092");

    private static DebeziumContainer debezium;
    private static ActionEvent event;

    @BeforeAll
    static void setUp() throws Exception {
        var descriptors = Files.createTempDirectory("ekbatan-mariadb-smt");
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
                DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), DialectPipeline.MARIADB_DDL);

        debezium.registerConnector("mariadb-outbox", connectorConfig());

        var registry =
                DialectPipeline.registry(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), SQLDialect.MARIADB);
        DialectPipeline.writeEvent(registry, "porch-lamp", false);

        var events = DialectPipeline.drain(KAFKA.getBootstrapServers(), "mariadb-e2e", 1, Duration.ofSeconds(90));
        assertThat(events)
                .as("no ActionEvent reached Kafka from MariaDB - check the connector task status")
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

    /** MariaDB has no boolean type either: TINYINT(1) reaches the SMT as an INT16. */
    @Test
    void delivered_is_a_boolean_not_an_int16() {
        assertThat(event.getDelivered()).isFalse();
    }

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

    /** The measurement this test exists for: a native MariaDB UUID column, whatever its wire form. */
    @Test
    void native_uuid_columns_arrive_as_canonical_uuid_strings() {
        assertThat(UUID.fromString(event.getId())).isNotNull();
        assertThat(UUID.fromString(event.getActionId())).isNotNull();
        assertThat(event.getId()).hasSize(36).isLowerCase();
    }

    @Test
    void the_payload_and_action_params_survive() {
        assertThat(event.getPayload().size()).isPositive();
        assertThat(event.getActionParams()).contains("porch-lamp");
    }

    private static ConnectorConfiguration connectorConfig() {
        return ConnectorConfiguration.create()
                .with("connector.class", "io.debezium.connector.mariadb.MariaDbConnector")
                .with("database.hostname", "mariadb")
                .with("database.port", "3306")
                .with("database.user", "test")
                .with("database.password", "test")
                .with("database.server.id", "184055")
                .with("topic.prefix", TOPIC_PREFIX)
                .with("database.include.list", "eventlog")
                .with("table.include.list", "eventlog.events")
                .with("schema.history.internal.kafka.bootstrap.servers", "kafka:19092")
                .with("schema.history.internal.kafka.topic", "schema-changes.eventlog")
                // See MysqlSmtIntegrationTest: audit finding 2 is still open.
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
