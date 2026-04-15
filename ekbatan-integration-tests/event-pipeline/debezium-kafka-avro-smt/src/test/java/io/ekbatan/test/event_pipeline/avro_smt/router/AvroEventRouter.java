package io.ekbatan.test.event_pipeline.avro_smt.router;

import io.ekbatan.streaming.actionevent.avro.ActionEvent;
import io.ekbatan.test.event_pipeline.common.router.EventRoute;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
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

/**
 * Avro counterpart of the JSON {@code EventRouter}. Reads Avro-encoded ActionEvent bytes from a raw
 * topic, decodes just enough to read {@code modelType} / {@code eventType}, then forwards the
 * original bytes to every matching output topic.
 */
public class AvroEventRouter implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AvroEventRouter.class);

    private final KafkaConsumer<String, byte[]> consumer;
    private final KafkaProducer<String, byte[]> producer;
    private final List<EventRoute> routes;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pollingThread;

    public AvroEventRouter(String bootstrapServers, String rawTopic, List<EventRoute> routes) {
        this.routes = routes;

        var consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "avro-event-router");
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

    private void routeEvent(byte[] value) throws Exception {
        var reader = new SpecificDatumReader<>(ActionEvent.class);
        var decoder = DecoderFactory.get().binaryDecoder(value, null);
        var actionEvent = reader.read(null, decoder);

        var modelType = actionEvent.getModelType();
        var eventType = actionEvent.getEventType();
        if (eventType == null) {
            LOG.debug("Skipping sentinel row [action_id={}]", actionEvent.getActionId());
            return;
        }

        var key = actionEvent.getModelId() != null ? actionEvent.getModelId() : actionEvent.getActionId();

        for (var route : routes) {
            if (route.matches(modelType, eventType)) {
                producer.send(new ProducerRecord<>(route.topic, key, value)).get();
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
