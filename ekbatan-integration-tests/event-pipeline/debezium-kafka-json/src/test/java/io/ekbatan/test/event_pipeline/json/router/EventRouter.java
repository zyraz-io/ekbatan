package io.ekbatan.test.event_pipeline.json.router;

import io.ekbatan.test.event_pipeline.common.router.EventRoute;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerConfig;
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
 * Config-driven router that reads from a raw topic and publishes
 * each event to all matching output topics based on model_type and event_type.
 * One event can go to multiple topics if it matches multiple routes.
 */
public class EventRouter implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EventRouter.class);

    private final KafkaConsumer<String, String> consumer;
    private final KafkaProducer<String, String> producer;
    private final List<EventRoute> routes;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pollingThread;

    public EventRouter(String bootstrapServers, String rawTopic, List<EventRoute> routes) {
        this.routes = routes;
        this.objectMapper = new ObjectMapper();

        var consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "event-router");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        this.consumer = new KafkaConsumer<>(consumerProps);
        this.consumer.subscribe(List.of(rawTopic));

        var producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        this.producer = new KafkaProducer<>(producerProps);
    }

    public void start() {
        running.set(true);
        pollingThread = Thread.startVirtualThread(() -> {
            while (running.get()) {
                var records = consumer.poll(Duration.ofMillis(100));
                if (records.isEmpty()) {
                    continue;
                }
                try {
                    for (var record : records) {
                        if (record.value() == null) continue;
                        routeEvent(record.value());
                    }
                    consumer.commitSync();
                } catch (Exception e) {
                    LOG.error("Failed to route batch, offsets not committed — will retry on next poll", e);
                }
            }
        });
    }

    private void routeEvent(String value) throws Exception {
        var root = (ObjectNode) objectMapper.readTree(value);

        // Unwrap Debezium envelope — the actual row data is in "after"
        // Debezium sends: { "before": null, "after": { ...row... }, "op": "c", ... }
        var event = root.has("after") ? (ObjectNode) root.get("after") : root;

        if (event == null || event.isNull()) {
            return; // delete event or tombstone
        }

        var modelType = event.has("model_type") && !event.get("model_type").isNull()
                ? event.get("model_type").asString()
                : null;
        var eventType = event.has("event_type") && !event.get("event_type").isNull()
                ? event.get("event_type").asString()
                : null;

        // Skip sentinel rows (no event_type)
        if (eventType == null) {
            LOG.debug(
                    "Skipping sentinel row [action_id={}]",
                    event.path("action_id").asString());
            return;
        }

        // Forward the unwrapped event data (not the Debezium envelope)
        var eventJson = objectMapper.writeValueAsString(event);
        var eventKey = event.has("model_id") && !event.get("model_id").isNull()
                ? event.get("model_id").asString()
                : event.path("action_id").asString();

        for (var route : routes) {
            if (route.matches(modelType, eventType)) {
                producer.send(new ProducerRecord<>(route.topic, eventKey, eventJson))
                        .get();
                LOG.debug("Routed {} to topic {}", eventType, route.topic);
            }
        }
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
        producer.close();
    }
}
