package io.ekbatan.test.event_pipeline.avro_smt.streaming;

import io.ekbatan.events.streaming.actionevent.avro.ActionEvent;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
 * Avro counterpart of {@code RetryingEventConsumer}: reads raw byte values from Kafka, decodes
 * them against {@link ActionEvent}, and delivers ActionEvent to the handler with retry and DLQ support.
 */
public class AvroRetryingEventConsumer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AvroRetryingEventConsumer.class);

    private final KafkaConsumer<String, byte[]> consumer;
    private final KafkaProducer<String, byte[]> dlqProducer;
    private final Consumer<ActionEvent> handler;
    private final String dlqTopic;
    private final int maxRetries;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<ActionEvent> handled = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<byte[]> deadLettered = new CopyOnWriteArrayList<>();
    private Thread pollingThread;

    public AvroRetryingEventConsumer(
            String bootstrapServers,
            String topic,
            String groupId,
            Consumer<ActionEvent> handler,
            String dlqTopic,
            int maxRetries) {
        this.handler = handler;
        this.dlqTopic = dlqTopic;
        this.maxRetries = maxRetries;

        var consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        this.consumer = new KafkaConsumer<>(consumerProps);
        this.consumer.subscribe(List.of(topic));

        var producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
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

    private void handleWithRetry(ConsumerRecord<String, byte[]> record) {
        if (record.value() == null) {
            return;
        }
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                var actionEvent = decode(record.value());
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

    private static ActionEvent decode(byte[] bytes) throws java.io.IOException {
        var reader = new SpecificDatumReader<>(ActionEvent.class);
        var decoder = DecoderFactory.get().binaryDecoder(bytes, null);
        return reader.read(null, decoder);
    }

    private void sendToDlq(ConsumerRecord<String, byte[]> record) {
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

    public List<byte[]> getDeadLettered() {
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
