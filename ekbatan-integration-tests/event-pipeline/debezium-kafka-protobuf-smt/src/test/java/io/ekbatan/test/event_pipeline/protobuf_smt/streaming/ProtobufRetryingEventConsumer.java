package io.ekbatan.test.event_pipeline.protobuf_smt.streaming;

import io.ekbatan.streaming.actionevent.protobuf.ActionEvent;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Test scaffolding: reads protobuf ActionEvent bytes from Kafka, decodes, delivers to handler. */
public class ProtobufRetryingEventConsumer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ProtobufRetryingEventConsumer.class);

    private final KafkaConsumer<String, byte[]> consumer;
    private final Consumer<ActionEvent> handler;
    private final int maxRetries;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<ActionEvent> handled = new CopyOnWriteArrayList<>();
    private Thread pollingThread;

    public ProtobufRetryingEventConsumer(
            String bootstrapServers, String topic, String groupId, Consumer<ActionEvent> handler, int maxRetries) {
        this.handler = handler;
        this.maxRetries = maxRetries;

        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        this.consumer = new KafkaConsumer<>(props);
        this.consumer.subscribe(List.of(topic));
    }

    public void start() {
        running.set(true);
        pollingThread = Thread.startVirtualThread(() -> {
            while (running.get()) {
                var records = consumer.poll(Duration.ofMillis(100));
                if (records.isEmpty()) continue;
                for (var record : records) handleWithRetry(record);
                consumer.commitSync();
            }
        });
    }

    private void handleWithRetry(ConsumerRecord<String, byte[]> record) {
        if (record.value() == null) return;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                var actionEvent = ActionEvent.parseFrom(record.value());
                handler.accept(actionEvent);
                handled.add(actionEvent);
                return;
            } catch (Exception e) {
                LOG.warn("Handler failed [attempt={}/{}]: {}", attempt, maxRetries, e.getMessage());
            }
        }
    }

    public List<ActionEvent> getHandled() {
        return List.copyOf(handled);
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
    }
}
