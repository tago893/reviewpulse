package com.reviewpulse.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.reviewpulse.config.ReviewPulseProperties;

class DeadLetterPublisherTest {

    @SuppressWarnings("unchecked")
    private final Producer<String, byte[]> producer = mock(Producer.class);
    private final DeadLetterPublisher publisher = new DeadLetterPublisher(producer, new ReviewPulseProperties(
            null, null, null, new ReviewPulseProperties.Kafka("x:9092", "review-events", "review-events.dlt", 3, "g", 100, 500, 500),
            null, null, null, null));
    private final ConsumerRecord<String, byte[]> poison =
            new ConsumerRecord<>("review-events", 2, 41L, "123", "garbage".getBytes(StandardCharsets.UTF_8));

    @Test
    void copiesRecordUnchangedWithProvenanceHeaders() {
        when(producer.send(any())).thenReturn(CompletableFuture.completedFuture(
                new RecordMetadata(new TopicPartition("review-events.dlt", 0), 0, 0, 0, 0, 0)));

        assertThat(publisher.publish(poison, new PoisonEventException("x".repeat(600)))).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, byte[]>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(sent.capture());
        ProducerRecord<String, byte[]> r = sent.getValue();
        assertThat(r.topic()).isEqualTo("review-events.dlt");
        assertThat(r.key()).isEqualTo("123");
        assertThat(r.value()).isEqualTo(poison.value());
        assertThat(new String(r.headers().lastHeader("dlt.original.offset").value(), StandardCharsets.UTF_8)).isEqualTo("41");
        assertThat(r.headers().lastHeader("dlt.error.message").value()).hasSize(500);
    }

    @Test
    void reportsFailureInsteadOfPretendingTheRecordIsSafe() {
        when(producer.send(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        assertThat(publisher.publish(poison, new PoisonEventException("bad"))).isFalse();

        when(producer.send(any())).thenReturn(new CompletableFuture<>());
        assertThat(publisher.publish(poison, new PoisonEventException("bad"))).as("ack timeout").isFalse();
    }
}
