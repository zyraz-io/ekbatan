package io.ekbatan.test.event_pipeline.protobuf_smt.router;

import io.ekbatan.streaming.actionevent.protobuf.ActionEvent;
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
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Protobuf counterpart of EventRouter. Decodes ActionEvent just enough to route by modelType/eventType. */
public class ProtobufEventRouter implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ProtobufEventRouter.class);

    private final KafkaConsumer<String, byte[]> consumer;
    private final KafkaProducer<String, byte[]> producer;
    private final List<EventRoute> routes;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pollingThread;

    public ProtobufEventRouter(String bootstrapServers, String rawTopic, List<EventRoute> routes) {
        this.routes = routes;

        var consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "protobuf-event-router");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        this.consumer = new KafkaConsumer<>(consumerProps);
        this.consumer.subscribe(List.of(rawTopic));

        var producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        this.producer = new KafkaProducer<>(producerProps);
    }

    public void start() {
        running.set(true);
        pollingThread = Thread.startVirtualThread(() -> {
            while (running.get()) {
                var records = consumer.poll(Duration.ofMillis(100));
                if (records.isEmpty()) continue;
                try {
                    for (var record : records) {
                        if (record.value() == null) continue;
                        routeEvent(record.value());
                    }
                    consumer.commitSync();
                } catch (Exception e) {
                    LOG.error("Failed to route batch", e);
                }
            }
        });
    }

    private void routeEvent(byte[] value) throws Exception {
        var actionEvent = ActionEvent.parseFrom(value);
        var modelType = actionEvent.hasModelType() ? actionEvent.getModelType() : null;
        var eventType = actionEvent.hasEventType() ? actionEvent.getEventType() : null;
        if (eventType == null) return;

        var key = actionEvent.hasModelId() ? actionEvent.getModelId() : actionEvent.getActionId();
        for (var route : routes) {
            if (route.matches(modelType, eventType)) {
                producer.send(new ProducerRecord<>(route.topic, key, value)).get();
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
