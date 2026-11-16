package com.reviewpulse.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Full application against real PostgreSQL + Kafka. Every IT uses the same configuration, so Spring
 * caches and shares one context (and one set of containers) across test classes.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "reviewpulse.admin.token=" + IntegrationTest.ADMIN_TOKEN,
        "reviewpulse.outbox.poll-interval-millis=50",
        "reviewpulse.kafka.poll-timeout-millis=100",
        "reviewpulse.index.snapshot-dir=target/it-index",
        "reviewpulse.index.import-check-seconds=1",
        "reviewpulse.rate-limit.reads-per-second=100000",
        "reviewpulse.rate-limit.writes-per-second=100000",
        "reviewpulse.cache.enabled=true",
        "reviewpulse.cache.bypass-millis=1000",
        "reviewpulse.replay.dir=target/it-replay"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {

    String ADMIN_TOKEN = "test-admin-token";
}
