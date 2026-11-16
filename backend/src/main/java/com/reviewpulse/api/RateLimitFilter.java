package com.reviewpulse.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.reviewpulse.config.ReviewPulseProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Per-client token buckets for /api/** (reads and writes limited separately) plus a hard cap on
 * request body size, enforced both from Content-Length and while streaming chunked bodies.
 * The client key is the remote address; behind a proxy this would need a trusted forwarded header.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_TRACKED_CLIENTS = 10_000;
    private static final long IDLE_EVICT_NANOS = 60_000_000_000L;

    private final ReviewPulseProperties.RateLimit config;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(ReviewPulseProperties.RateLimit config) {
        this.config = config;
    }

    /** Classic token bucket refilled continuously. */
    static final class Bucket {
        private final double ratePerNano;
        private final double capacity;
        private double tokens;
        private long last;

        Bucket(double perSecond, double capacity, long now) {
            this.ratePerNano = perSecond / 1e9;
            this.capacity = capacity;
            this.tokens = capacity;
            this.last = now;
        }

        synchronized boolean tryTake(long now) {
            tokens = Math.min(capacity, tokens + (now - last) * ratePerNano);
            last = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized long idleNanos(long now) {
            return now - last;
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !config.enabled() || !path.startsWith("/api/") || path.startsWith("/api/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > config.maxBodyBytes()) {
            reject(response, HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                    "request body exceeds " + config.maxBodyBytes() + " bytes");
            return;
        }
        boolean write = !"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod());
        double rate = write ? config.writesPerSecond() : config.readsPerSecond();
        long now = System.nanoTime();
        String key = request.getRemoteAddr() + (write ? "|w" : "|r");
        if (buckets.size() > MAX_TRACKED_CLIENTS) {
            buckets.entrySet().removeIf(e -> e.getValue().idleNanos(now) > IDLE_EVICT_NANOS);
        }
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(rate, Math.max(1.0, rate * config.burstSeconds()), now));
        if (!bucket.tryTake(now)) {
            response.setHeader("Retry-After", "1");
            reject(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "too many requests; slow down");
            return;
        }
        chain.doFilter(new LimitedBodyRequest(request, config.maxBodyBytes()), response);
    }

    private static void reject(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"success\":false,\"data\":null,\"error\":{\"code\":\"" + code
                + "\",\"message\":\"" + message + "\",\"details\":[]},\"meta\":{}}");
    }

    /** Fails the read (-> 400 malformed body) once more than {@code max} bytes have been streamed. */
    static final class LimitedBodyRequest extends HttpServletRequestWrapper {
        private final long max;

        LimitedBodyRequest(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                @Override
                public int read() throws IOException {
                    int b = delegate.read();
                    if (b >= 0 && ++read > max) {
                        throw new IOException("request body too large");
                    }
                    return b;
                }

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    int n = delegate.read(buf, off, len);
                    if (n > 0 && (read += n) > max) {
                        throw new IOException("request body too large");
                    }
                    return n;
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    delegate.setReadListener(listener);
                }
            };
        }
    }
}
