package com.reviewpulse.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.reviewpulse.config.ReviewPulseProperties;

class RateLimitFilterTest {

    private final RateLimitFilter filter = new RateLimitFilter(
            new ReviewPulseProperties.RateLimit(true, 2, 1, 1, 1_000));

    private int status(String method, String uri, String ip, byte[] body) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(ip);
        if (body != null) {
            request.setContent(body);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void limitsEachClientSeparatelyAndReadsAndWritesSeparately() throws Exception {
        assertThat(status("GET", "/api/search", "10.0.0.1", null)).isEqualTo(200);
        assertThat(status("GET", "/api/search", "10.0.0.1", null)).isEqualTo(200);
        assertThat(status("GET", "/api/search", "10.0.0.1", null)).isEqualTo(429);
        assertThat(status("GET", "/api/search", "10.0.0.2", null)).as("other client unaffected").isEqualTo(200);
        assertThat(status("POST", "/api/reviews", "10.0.0.1", new byte[10])).as("separate write bucket").isEqualTo(200);
        assertThat(status("POST", "/api/reviews", "10.0.0.1", new byte[10])).isEqualTo(429);
    }

    @Test
    void healthAndNonApiPathsAreNotLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(status("GET", "/api/health", "10.0.0.3", null)).isEqualTo(200);
            assertThat(status("GET", "/actuator/prometheus", "10.0.0.3", null)).isEqualTo(200);
        }
    }

    @Test
    void oversizedBodiesAreRejected() throws Exception {
        assertThat(status("POST", "/api/reviews", "10.0.0.4", new byte[1_001])).isEqualTo(413);
    }
}
