package com.reviewpulse.events;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.reviewpulse.config.ReviewPulseProperties;

/**
 * Copies an unprocessable record, unchanged, to the dead-letter topic with headers describing where
 * it came from and why it failed. The send is synchronous: the indexer only moves past a poison
 * record once Kafka has acknowledged the dead-letter copy.
 */
@Component
public class DeadLetterPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPublisher.class);
    private static final int MAX_ERROR_CHARS = 500;

    private final KafkaProducer<String, byte[]> producer;
    private final ReviewPulseProperties.Kafka config;

    public DeadLetterPublisher(KafkaProducer<String, byte[]> producer, ReviewPulseProperties properties) {
        this.producer = producer;
        this.config = properties.kafka();
    }

    /** @return true once the broker acknowledged the dead-letter record */
    public boolean publish(ConsumerRecord<String, byte[]> source, Throwable error) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(config.deadLetterTopic(), null, source.key(),
                source.value());
        header(record, "dlt.original.topic", source.topic());
        header(record, "dlt.original.partition", Integer.toString(source.partition()));
        header(record, "dlt.original.offset", Long.toString(source.offset()));
        header(record, "dlt.error.class", error.getClass().getName());
        String message = String.valueOf(error.getMessage());
        header(record, "dlt.error.message", message.length() > MAX_ERROR_CHARS ? message.substring(0, MAX_ERROR_CHARS) : message);
        try {
            producer.send(record).get(config.apiTimeoutMillis(), TimeUnit.MILLISECONDS);
            log.warn("dead-lettered {}-{}@{}: {}", source.topic(), source.partition(), source.offset(), message);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.error("could not dead-letter {}-{}@{}; will retry: {}", source.topic(), source.partition(),
                    source.offset(), e.toString());
            return false;
        }
    }

    private static void header(ProducerRecord<String, byte[]> record, String key, String value) {
        record.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
    }
}
