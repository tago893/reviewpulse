package com.reviewpulse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application settings under {@code reviewpulse.*}. All values have defaults in application.yml.
 */
@ConfigurationProperties("reviewpulse")
public record ReviewPulseProperties(Search search, Admin admin, Index index) {

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
     * @param buildFetchSize rows fetched per round trip while streaming reviews for a rebuild
     * @param buildOnStartup build the index from PostgreSQL when the application starts
     */
    public record Index(int buildFetchSize, boolean buildOnStartup) {
    }
}
