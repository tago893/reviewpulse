package com.reviewpulse.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL and Kafka for integration tests, same images as docker-compose.yml. One container of
 * each is shared by every Spring test context in the JVM; Testcontainers' Ryuk removes them on exit.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:17.12-alpine");
    public static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:4.2.2");

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);
    public static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE)
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @Bean(destroyMethod = "")
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return POSTGRES;
    }

    public static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.6.8-alpine"))
            .withExposedPorts(6379);

    @Bean(destroyMethod = "")
    KafkaContainer kafkaContainer() {
        return KAFKA;
    }

    @Bean(destroyMethod = "")
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return REDIS;
    }

    @Bean
    DynamicPropertyRegistrar kafkaProperties(KafkaContainer kafka) {
        return registry -> registry.add("reviewpulse.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
}
