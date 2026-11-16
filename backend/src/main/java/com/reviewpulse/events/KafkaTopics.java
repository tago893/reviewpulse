package com.reviewpulse.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.errors.TopicExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.reviewpulse.config.ReviewPulseProperties;

/**
 * Creates the event topics explicitly (broker auto-creation is disabled so a typo cannot silently
 * create a new topic). Replication factor 1: this is a single-broker local deployment.
 */
@Component
public class KafkaTopics {

    private static final Logger log = LoggerFactory.getLogger(KafkaTopics.class);

    private final ReviewPulseProperties.Kafka config;

    public KafkaTopics(ReviewPulseProperties properties) {
        this.config = properties.kafka();
    }

    /** Ensures both topics exist and returns the event topic's id (changes if the topic is recreated). */
    public Uuid ensureTopics() throws InterruptedException {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) config.apiTimeoutMillis());
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) config.apiTimeoutMillis());
        try (Admin admin = Admin.create(p)) {
            long timeout = config.apiTimeoutMillis();
            Set<String> existing = admin.listTopics().names().get(timeout, TimeUnit.MILLISECONDS);
            List<NewTopic> missing = new ArrayList<>();
            if (!existing.contains(config.topic())) {
                missing.add(new NewTopic(config.topic(), config.partitions(), (short) 1));
            }
            if (!existing.contains(config.deadLetterTopic())) {
                missing.add(new NewTopic(config.deadLetterTopic(), 1, (short) 1));
            }
            if (!missing.isEmpty()) {
                try {
                    admin.createTopics(missing).all().get(timeout, TimeUnit.MILLISECONDS);
                    log.info("created Kafka topics {}", missing.stream().map(NewTopic::name).toList());
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) {
                        throw e;
                    }
                }
            }
            Map<String, TopicDescription> described = admin.describeTopics(List.of(config.topic()))
                    .allTopicNames().get(timeout, TimeUnit.MILLISECONDS);
            return described.get(config.topic()).topicId();
        } catch (ExecutionException | TimeoutException e) {
            throw new org.apache.kafka.common.KafkaException("Kafka topic setup failed: " + e.getMessage(), e);
        }
    }
}
