package com.reviewpulse.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import com.reviewpulse.config.ReviewPulseProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Publisher behavior on success and on every failure branch, with no broker or database. */
class OutboxPublisherTest {

    private static final RecordMetadata ACK = new RecordMetadata(new TopicPartition("review-events", 0), 0, 0, 0, 0, 0);

    private OutboxRepository repository;
    private Producer<String, byte[]> producer;
    private OutboxPublisher publisher;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        repository = mock(OutboxRepository.class);
        producer = mock(Producer.class);
        ReviewPulseProperties properties = new ReviewPulseProperties(null, null, null,
                new ReviewPulseProperties.Kafka("unused:9092", "review-events", "review-events.dlt", 3, "g", 100, 500, 1000),
                new ReviewPulseProperties.Outbox(100, 10, 200, 10_000, 24), null, null, null);
        publisher = new OutboxPublisher(repository, producer, mock(PlatformTransactionManager.class), properties,
                new SimpleMeterRegistry());
    }

    private static OutboxRepository.PendingEvent row(long id, int attempts) {
        return new OutboxRepository.PendingEvent(id, UUID.randomUUID(), 100 + id, 1, "REVIEW_CREATED",
                "{\"x\":" + id + "}", Instant.now(), attempts);
    }

    @Test
    void publishesAllRowsAndMarksThemPublishedOnlyAfterAck() {
        when(repository.lockPending(10)).thenReturn(List.of(row(1, 0), row(2, 0)));
        when(producer.send(any())).thenReturn(CompletableFuture.completedFuture(ACK));

        OutboxPublisher.BatchOutcome outcome = publisher.publishBatch();

        assertThat(outcome).isEqualTo(new OutboxPublisher.BatchOutcome(2, 2, 0));
        verify(repository).markPublished(List.of(1L, 2L));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, byte[]>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer, times(2)).send(sent.capture());
        ProducerRecord<String, byte[]> first = sent.getAllValues().getFirst();
        assertThat(first.key()).as("keyed by review id for per-review ordering").isEqualTo("101");
        assertThat(first.headers().lastHeader("eventType").value()).isEqualTo("REVIEW_CREATED".getBytes());
    }

    @Test
    void retriableFailureKeepsRowPendingAndSucceedingRowsArePublished() {
        when(repository.lockPending(10)).thenReturn(List.of(row(1, 7), row(2, 0)));
        when(producer.send(any()))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("broker down")))
                .thenReturn(CompletableFuture.completedFuture(ACK));

        OutboxPublisher.BatchOutcome outcome = publisher.publishBatch();

        assertThat(outcome.failed()).isEqualTo(1);
        verify(repository).recordFailure(eq(1L), anyString(), eq(false));
        verify(repository).markPublished(List.of(2L));
        assertThat(publisher.lastError()).contains("TimeoutException");
    }

    @Test
    void nonRetriableFailureIsParkedOnlyAfterRepeatedAttempts() {
        when(repository.lockPending(10)).thenReturn(List.of(row(1, 0), row(2, 4)));
        when(producer.send(any())).thenReturn(CompletableFuture.failedFuture(new RecordTooLargeException("too big")));

        publisher.publishBatch();

        verify(repository).recordFailure(eq(1L), anyString(), eq(false));
        verify(repository).recordFailure(eq(2L), anyString(), eq(true));
        verify(repository).markPublished(List.of());
    }

    @Test
    void unacknowledgedSendTimesOutAndStaysPending() {
        when(repository.lockPending(10)).thenReturn(List.of(row(1, 0)));
        when(producer.send(any())).thenReturn(new CompletableFuture<>());

        OutboxPublisher.BatchOutcome outcome = publisher.publishBatch();

        assertThat(outcome.failed()).isEqualTo(1);
        verify(repository).recordFailure(eq(1L), anyString(), eq(false));
    }

    @Test
    void failuresBackOffAndSkipTheNextRun() {
        when(repository.lockPending(10)).thenReturn(List.of(row(1, 0)));
        when(producer.send(any())).thenReturn(CompletableFuture.failedFuture(new TimeoutException("down")));

        publisher.run();
        assertThat(publisher.currentBackoffMillis()).isPositive();
        publisher.run();

        verify(repository, times(1)).lockPending(anyInt());
    }

    @Test
    void unexpectedExceptionIsContainedAndBacksOff() {
        when(repository.lockPending(10)).thenThrow(new IllegalStateException("database exploded"));

        publisher.run();

        assertThat(publisher.lastError()).contains("database exploded");
        assertThat(publisher.currentBackoffMillis()).isPositive();
        verify(repository, never()).markPublished(any());
    }

    @Test
    void idleRunDoesNothingAndPurgeDeletesOldRows() {
        when(repository.lockPending(10)).thenReturn(List.of());
        publisher.run();
        verify(producer, never()).send(any());
        assertThat(publisher.currentBackoffMillis()).isZero();

        publisher.purgePublished();
        verify(repository).deletePublishedBefore(any());
        verify(repository, never()).recordFailure(anyLong(), anyString(), anyBoolean());
    }
}
