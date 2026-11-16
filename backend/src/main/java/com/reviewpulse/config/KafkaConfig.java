package com.reviewpulse.config;

import java.util.Properties;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.reviewpulse.events.ReviewEventCodec;

import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    /**
     * One thread-safe producer shared by the outbox publisher, the dead-letter path and the simulator.
     * Idempotence + acks=all: a retried send cannot create a duplicate or reorder records within a
     * partition. It does NOT make the outbox exactly-once (a crash after send and before the outbox row
     * is marked published re-sends the event); consumers deduplicate by version.
     */
    @Bean(destroyMethod = "close")
    KafkaProducer<String, byte[]> kafkaProducer(ReviewPulseProperties properties) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.kafka().bootstrapServers());
        p.put(ProducerConfig.CLIENT_ID_CONFIG, "reviewpulse-producer");
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        p.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        p.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15_000);
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        return new KafkaProducer<>(p);
    }

    @Bean
    ReviewEventCodec reviewEventCodec(JsonMapper jsonMapper) {
        return new ReviewEventCodec(jsonMapper);
    }
}
