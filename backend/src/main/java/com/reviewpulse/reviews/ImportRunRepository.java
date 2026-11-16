package com.reviewpulse.reviews;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Bulk imports bypass Kafka, so the indexer watches this table to know when its data is stale. */
@Repository
public class ImportRunRepository {

    private final JdbcClient jdbc;

    public ImportRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Id of the most recent COMPLETED import run, or 0 if none. */
    public long latestCompletedId() {
        return jdbc.sql("SELECT COALESCE(max(id), 0) FROM import_runs WHERE status = 'COMPLETED'")
                .query(Long.class).single();
    }
}
