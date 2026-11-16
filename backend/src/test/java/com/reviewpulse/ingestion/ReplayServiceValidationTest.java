package com.reviewpulse.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;

import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.reviewpulse.api.ConflictException;
import com.reviewpulse.api.RequestValidationException;
import com.reviewpulse.config.ReviewPulseProperties;
import com.reviewpulse.reviews.ReviewService;

import tools.jackson.databind.json.JsonMapper;

/** Every invalid replay request is rejected before anything is read or written. */
class ReplayServiceValidationTest {

    @TempDir
    Path dir;

    @SuppressWarnings("unchecked")
    private ReplayService service() {
        ReviewPulseProperties properties = new ReviewPulseProperties(null, null, null,
                new ReviewPulseProperties.Kafka("x:9092", "review-events", "dlt", 3, "g", 100, 500, 500),
                null, null, null, null);
        return new ReplayService(properties, mock(ReviewService.class), mock(Producer.class), mock(JdbcClient.class),
                JsonMapper.builder().build(), new ReplayService.ReplayFilesHolder(new ReplayFiles(dir)));
    }

    private static ReplayService.StartRequest request(Double rate, Long limit, Double dup, Double ooo, Integer writers) {
        return new ReplayService.StartRequest("slice", rate, limit, 1L, dup, ooo, writers);
    }

    @Test
    void rejectsOutOfRangeParameters() {
        ReplayService service = service();
        for (ReplayService.StartRequest bad : new ReplayService.StartRequest[] {
                request(0.0, null, null, null, null),
                request(-5.0, null, null, null, null),
                request(ReplayService.MAX_RATE + 1, null, null, null, null),
                request(10.0, 0L, null, null, null),
                request(10.0, null, -0.1, null, null),
                request(10.0, null, null, 0.6, null),
                request(10.0, null, null, null, 0),
                request(10.0, null, null, null, ReplayService.MAX_WRITERS + 1),
                request(10.0, null, null, null, 2)}) {
            assertThatThrownBy(() -> service.start(bad)).isInstanceOf(RequestValidationException.class);
        }
    }

    @Test
    void idleServiceReportsStateAndRefusesToResume() {
        ReplayService service = service();
        assertThat(service.status().state()).isEqualTo("IDLE");
        assertThat(service.availableFiles()).isEmpty();
        assertThatThrownBy(service::resume).isInstanceOf(ConflictException.class);
        assertThat(service.stop().state()).isEqualTo("IDLE");
        assertThatThrownBy(() -> service.setRate(null)).isInstanceOf(RequestValidationException.class);
        assertThat(service.setRate(25.0).targetRate()).isEqualTo(25.0);
    }
}
