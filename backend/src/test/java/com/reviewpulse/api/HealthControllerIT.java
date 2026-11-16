package com.reviewpulse.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.reviewpulse.support.IntegrationTest;

@IntegrationTest
class HealthControllerIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void reportsDatabaseUpAndSchemaIsMigrated() {
        assertThat(mvc.get().uri("/api/health"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.data.components.database.status").isEqualTo("UP");

        Integer tables = jdbc.sql("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN ('reviews', 'import_runs')
                """).query(Integer.class).single();
        assertThat(tables).isEqualTo(2);
    }
}
