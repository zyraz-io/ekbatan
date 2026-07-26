package io.ekbatan.test.event_pipeline.dialects;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.persistence.ConnectionProvider.hikariConnectionProvider;
import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import io.ekbatan.core.action.persister.event.single_table_json.SingleTableJsonEventPersister;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.events.streaming.actionevent.protobuf.ActionEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.jooq.SQLDialect;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared scaffolding for the MySQL and MariaDB end-to-end pipelines.
 *
 * <p>Rows are written through the real {@link SingleTableJsonEventPersister}, not hand-rolled
 * SQL. That matters: {@code EventEntityRepository} binds {@code eventlog.events} with three
 * different sets of jOOQ converters depending on the dialect - native {@code UUID} on PostgreSQL
 * and MariaDB, {@code CHAR(36)} plus a converter on MySQL, {@code JSONB} versus {@code JSON} for
 * the two JSON columns. Those converters decide exactly what lands on disk, which is what
 * Debezium then reads and the SMT then converts, so bypassing them with a plain INSERT would let
 * the test choose a representation that production never produces.
 *
 * <p>Because that writer binds its table dynamically ({@code DSL.table(DSL.name(...))}), it needs
 * no generated jOOQ classes, so these tests need no per-dialect codegen module.
 */
final class DialectPipeline {

    // Underscored, not hyphenated: the namespace is used as a protobuf package, and protoc's
    // grammar has no hyphen. This value predates that rule and would now be rejected.
    static final String NAMESPACE = "dialect_e2e";
    static final String EVENT_TYPE = "WidgetToggledEvent";
    static final String TOPIC_PREFIX = "dbserver1";
    static final String OUTBOX_TOPIC = TOPIC_PREFIX + ".eventlog.events";
    static final String CONTAINER_PLUGIN_DIR = "/kafka/connect/ekbatan-smt-protobuf";
    static final String CONTAINER_DESCRIPTORS_DIR = "/opt/ekbatan-descriptors";

    /** Grants Debezium needs on top of the container's default user. */
    static final String INIT_SQL = """
            GRANT ALL PRIVILEGES ON *.* TO 'test'@'%';
            FLUSH PRIVILEGES;
            """;

    /** MySQL: UUIDs are CHAR(36), JSON columns are JSON. Mirrors the published MySQL example. */
    static final String MYSQL_DDL = """
            CREATE TABLE eventlog.events (
                id CHAR(36) CHARACTER SET ascii NOT NULL,
                namespace VARCHAR(255) NOT NULL,
                action_id CHAR(36) CHARACTER SET ascii NOT NULL,
                action_name VARCHAR(255) NOT NULL,
                action_params JSON NOT NULL,
                started_date DATETIME(6) NOT NULL,
                completion_date DATETIME(6) NOT NULL,
                model_id VARCHAR(255),
                model_type VARCHAR(255),
                event_type VARCHAR(255),
                payload JSON,
                event_date DATETIME(6) NOT NULL,
                delivered BOOLEAN NOT NULL,
                PRIMARY KEY (id)
            )
            """;

    /** MariaDB: native UUID columns, which is the case the SMT's byte handling exists for. */
    static final String MARIADB_DDL = """
            CREATE TABLE eventlog.events (
                id UUID PRIMARY KEY,
                namespace VARCHAR(255) NOT NULL,
                action_id UUID NOT NULL,
                action_name VARCHAR(255) NOT NULL,
                action_params JSON NOT NULL,
                started_date DATETIME(6) NOT NULL,
                completion_date DATETIME(6) NOT NULL,
                model_id VARCHAR(255),
                model_type VARCHAR(255),
                event_type VARCHAR(255),
                payload JSON,
                event_date DATETIME(6) NOT NULL,
                delivered BOOLEAN NOT NULL
            )
            """;

    private DialectPipeline() {}

    /** Stand-in aggregate. Only its simple name reaches the outbox, via {@link ModelEvent}. */
    static final class Widget {}

    /** A real {@link ModelEvent}, so the persister serialises it exactly as it would in production. */
    static final class WidgetToggledEvent extends ModelEvent<Widget> {
        public final String label;
        public final boolean on;

        WidgetToggledEvent(String modelId, String label, boolean on) {
            super(modelId, Widget.class);
            this.label = label;
            this.on = on;
        }
    }

    static void createOutboxTable(String jdbcUrl, String user, String password, String ddl) throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, user, password);
                var stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE IF NOT EXISTS eventlog");
            stmt.execute(ddl);
        }
    }

    static DatabaseRegistry registry(String jdbcUrl, String user, String password, SQLDialect dialect) {
        var config = dataSourceConfig()
                .jdbcUrl(jdbcUrl)
                .username(user)
                .password(password)
                .maximumPoolSize(4)
                .build();
        var provider = hikariConnectionProvider(config);
        return databaseRegistry()
                .withDatabase(new TransactionManager(provider, provider, dialect))
                .build();
    }

    /** Writes one action's worth of events through the production persister. */
    static void writeEvent(DatabaseRegistry registry, String label, boolean on) {
        var persister = new SingleTableJsonEventPersister(registry, new ObjectMapper());
        var now = Instant.now();
        persister.persistActionEvents(
                NAMESPACE,
                "ToggleWidgetAction",
                now,
                now,
                new Params(label),
                List.of(new WidgetToggledEvent(UUID.randomUUID().toString(), label, on)),
                ShardIdentifier.DEFAULT,
                UUID.randomUUID());
    }

    /** Action params must serialise to a JSON object, so a simple bean rather than a scalar. */
    record Params(String label) {}

    /**
     * The per-event-type payload descriptor the SMT needs, built in-process so the module needs no
     * protobuf codegen. Field numbers and names mirror the JSON the persister writes.
     */
    static Path writePayloadDescriptor(Path directory) throws Exception {
        var message = DescriptorProto.newBuilder()
                .setName(EVENT_TYPE)
                .addField(stringField("model_id", 1))
                .addField(stringField("model_name", 2))
                .addField(stringField("label", 3))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("on")
                        .setNumber(4)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .setType(FieldDescriptorProto.Type.TYPE_BOOL)
                        .build())
                .build();
        var file = FileDescriptorProto.newBuilder()
                .setName("payload.proto")
                // Messages are bound by fully-qualified name, so the package must be
                // <namespace>.proto for the SMT to find this one.
                .setPackage(NAMESPACE + ".proto")
                .setSyntax("proto3")
                .addMessageType(message)
                .build();
        var path = directory.resolve("payload.desc");
        try (var out = Files.newOutputStream(path)) {
            FileDescriptorSet.newBuilder().addFile(file).build().writeTo(out);
        }
        return path;
    }

    private static FieldDescriptorProto stringField(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .build();
    }

    /** Reads up to {@code count} ActionEvents off the outbox topic, decoding with the real class. */
    static List<ActionEvent> drain(String bootstrapServers, String groupId, int count, Duration timeout)
            throws Exception {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());

        var events = new ArrayList<ActionEvent>();
        var deadline = Instant.now().plus(timeout);
        try (var consumer = new KafkaConsumer<String, byte[]>(props)) {
            consumer.subscribe(List.of(OUTBOX_TOPIC));
            while (Instant.now().isBefore(deadline) && events.size() < count) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value() != null) {
                        events.add(ActionEvent.parseFrom(record.value()));
                    }
                }
            }
        }
        return events;
    }
}
