package io.ekbatan.test.event_pipeline.json.streaming;

import io.ekbatan.events.streaming.actionevent.json.ActionEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A Kafka consumer with retry and dead letter queue (DLQ) support.
 * Delivers an ActionEvent containing all outbox fields with raw JSON payload.
 * The handler decides how to interpret the payload.
 */
public class RetryingEventConsumer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RetryingEventConsumer.class);

    private final KafkaConsumer<String, String> consumer;
    private final KafkaProducer<String, String> dlqProducer;
    private final Consumer<ActionEvent> handler;
    private final String dlqTopic;
    private final int maxRetries;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<ActionEvent> handled = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> deadLettered = new CopyOnWriteArrayList<>();
    private Thread pollingThread;

    public RetryingEventConsumer(
            String bootstrapServers,
            String topic,
            String groupId,
            Consumer<ActionEvent> handler,
            String dlqTopic,
            int maxRetries) {
        this.handler = handler;
        this.dlqTopic = dlqTopic;
        this.maxRetries = maxRetries;
        this.objectMapper = new ObjectMapper();

        var consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        this.consumer = new KafkaConsumer<>(consumerProps);
        this.consumer.subscribe(List.of(topic));

        var producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        this.dlqProducer = new KafkaProducer<>(producerProps);
    }

    public void start() {
        running.set(true);
        pollingThread = Thread.startVirtualThread(() -> {
            while (running.get()) {
                var records = consumer.poll(Duration.ofMillis(100));
                if (records.isEmpty()) {
                    continue;
                }
                for (var record : records) {
                    handleWithRetry(record);
                }
                consumer.commitSync();
            }
        });
    }

    private void handleWithRetry(ConsumerRecord<String, String> record) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                var actionEvent = deserialize(record.value());
                handler.accept(actionEvent);
                handled.add(actionEvent);
                return;
            } catch (Exception e) {
                LOG.warn("Handler failed [attempt={}/{}]: {}", attempt, maxRetries, e.getMessage());
                if (attempt == maxRetries) {
                    sendToDlq(record);
                }
            }
        }
    }

    private ActionEvent deserialize(String value) {
        var json = (ObjectNode) objectMapper.readTree(value);

        var payloadNode = json.get("payload");
        ObjectNode payload = null;
        if (payloadNode != null && !payloadNode.isNull()) {
            payload = payloadNode.isString()
                    ? (ObjectNode) objectMapper.readTree(payloadNode.asString())
                    : (ObjectNode) payloadNode;
        }

        var actionParamsNode = json.get("action_params");
        ObjectNode actionParams = null;
        if (actionParamsNode != null && !actionParamsNode.isNull()) {
            actionParams = actionParamsNode.isString()
                    ? (ObjectNode) objectMapper.readTree(actionParamsNode.asString())
                    : (ObjectNode) actionParamsNode;
        }

        return new ActionEvent(
                UUID.fromString(json.get("id").asString()),
                json.get("namespace").asString(),
                UUID.fromString(json.get("action_id").asString()),
                json.get("action_name").asString(),
                actionParams,
                parseTimestamp(json.get("started_date")),
                parseTimestamp(json.get("completion_date")),
                json.has("model_id") && !json.get("model_id").isNull()
                        ? json.get("model_id").asString()
                        : null,
                json.has("model_type") && !json.get("model_type").isNull()
                        ? json.get("model_type").asString()
                        : null,
                json.has("event_type") && !json.get("event_type").isNull()
                        ? json.get("event_type").asString()
                        : null,
                payload,
                parseTimestamp(json.get("event_date")));
    }

    private Instant parseTimestamp(tools.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) {
            return Instant.now();
        }
        if (node.isNumber()) {
            return Instant.ofEpochMilli(node.asLong() / 1000);
        }
        return Instant.parse(node.asString());
    }

    private void sendToDlq(ConsumerRecord<String, String> record) {
        try {
            dlqProducer
                    .send(new ProducerRecord<>(dlqTopic, record.key(), record.value()))
                    .get();
            deadLettered.add(record.value());
            LOG.info("Sent to DLQ: {}", dlqTopic);
        } catch (Exception e) {
            LOG.error("Failed to send to DLQ", e);
        }
    }

    public List<ActionEvent> getHandled() {
        return List.copyOf(handled);
    }

    public List<String> getDeadLettered() {
        return List.copyOf(deadLettered);
    }

    @Override
    public void close() {
        running.set(false);
        if (pollingThread != null) {
            try {
                pollingThread.join(5000);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
        consumer.close();
        dlqProducer.close();
    }
}
