package com.reviewpulse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application settings under {@code reviewpulse.*}. All values have defaults in application.yml.
 */
@ConfigurationProperties("reviewpulse")
public record ReviewPulseProperties(Search search, Admin admin, Index index, Kafka kafka, Outbox outbox, Cache cache,
                                    RateLimit rateLimit, Replay replay) {

    /** @param dir the only directory the replay simulator may read files from */
    public record Replay(String dir) {
    }

    /**
     * @param enabled      use Redis for ranked hit-lists
     * @param ttlSeconds   entry lifetime (entries also become unreachable when the index changes)
     * @param bypassMillis how long to skip Redis after an error
     */
    public record Cache(boolean enabled, long ttlSeconds, long bypassMillis) {
    }

    /**
     * Per-client token buckets (client = remote address).
     *
     * @param enabled          apply limits
     * @param readsPerSecond   sustained GET rate per client
     * @param writesPerSecond  sustained POST/PUT/DELETE rate per client
     * @param burstSeconds     bucket capacity, in seconds of sustained rate
     * @param maxBodyBytes     request bodies above this are rejected with 413
     */
    public record RateLimit(boolean enabled, double readsPerSecond, double writesPerSecond, double burstSeconds,
                            long maxBodyBytes) {
    }

    /**
     * @param queryBudgetMillis CPU-time budget per query before it fails with 503
     * @param defaultPageSize   page size when the client sends none
     */
    public record Search(long queryBudgetMillis, int defaultPageSize) {
    }

    /** @param token value required in the X-Admin-Token header; blank disables admin endpoints. */
    public record Admin(String token) {

        public boolean enabled() {
            return token != null && !token.isBlank();
        }

        @Override
        public String toString() {
            return "Admin[token=" + (enabled() ? "<set>" : "<empty>") + "]";
        }
    }

    /**
     * @param buildFetchSize          rows fetched per round trip while streaming reviews for a rebuild
     * @param indexerEnabled          run the Kafka-fed indexer thread (disable only in narrow tests)
     * @param snapshotEnabled         persist checkpoints and recover from them on startup
     * @param snapshotDir             directory holding index.snapshot (must not be shared by two apps)
     * @param snapshotIntervalSeconds minimum seconds between checkpoints while the index is changing
     * @param importCheckSeconds      how often to look for bulk imports newer than the index
     */
    public record Index(int buildFetchSize, boolean indexerEnabled, boolean snapshotEnabled, String snapshotDir,
                        long snapshotIntervalSeconds, long importCheckSeconds) {
    }

    /**
     * @param bootstrapServers   Kafka bootstrap servers
     * @param topic              review change events, keyed by review id
     * @param deadLetterTopic    events that can never be applied
     * @param partitions         partition count used when the backend creates the topic
     * @param consumerGroup      group id used for offset commits (observability only)
     * @param pollTimeoutMillis  consumer poll timeout
     * @param maxPollRecords     upper bound on one indexing batch
     * @param apiTimeoutMillis   timeout for metadata/offset calls, so an outage is detected quickly
     */
    public record Kafka(String bootstrapServers, String topic, String deadLetterTopic, int partitions,
                        String consumerGroup, long pollTimeoutMillis, int maxPollRecords, long apiTimeoutMillis) {
    }

    /**
     * @param pollIntervalMillis delay between publisher runs when idle
     * @param batchSize          rows locked and published per transaction
     * @param sendTimeoutMillis  max wait for broker acknowledgements of one batch
     * @param maxBackoffMillis   cap for exponential backoff while Kafka is failing
     * @param retentionHours     published rows older than this are deleted
     */
    public record Outbox(long pollIntervalMillis, int batchSize, long sendTimeoutMillis, long maxBackoffMillis,
                         long retentionHours) {
    }
}
