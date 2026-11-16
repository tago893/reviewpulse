package com.reviewpulse.reviews;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.reviewpulse.config.ReviewPulseProperties;

/**
 * Read access to reviews. All SQL is parameterized; nothing user-supplied is concatenated.
 */
@Repository
public class ReviewRepository {

    static final String COLUMNS = """
            id, source, parent_asin, asin, title, body, rating, helpful_votes, verified_purchase,
            reviewed_at, created_at, updated_at, version, deleted""";

    static final RowMapper<Review> MAPPER = ReviewRepository::map;

    private final JdbcClient jdbc;
    private final JdbcTemplate streamingJdbc;
    private final TransactionTemplate readOnlyTx;

    public ReviewRepository(JdbcClient jdbc, DataSource dataSource, PlatformTransactionManager txManager,
                            ReviewPulseProperties properties) {
        this.jdbc = jdbc;
        this.streamingJdbc = new JdbcTemplate(dataSource);
        this.streamingJdbc.setFetchSize(properties.index().buildFetchSize());
        this.readOnlyTx = new TransactionTemplate(txManager);
        this.readOnlyTx.setReadOnly(true);
    }

    public Optional<Review> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM reviews WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    /** Includes deleted rows so callers can tell "deleted" from "never existed". */
    public List<Review> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM reviews WHERE id = ANY(:ids)")
                .param("ids", ids.toArray(Long[]::new))
                .query(MAPPER)
                .list();
    }

    public long countLive() {
        return jdbc.sql("SELECT count(*) FROM reviews WHERE NOT deleted").query(Long.class).single();
    }

    /**
     * Streams every review (including deleted ones, which become tombstones in the index) in id order
     * using a server-side cursor, so memory use is bounded by the fetch size, not the table size.
     * The whole scan runs as one statement, i.e. against one consistent snapshot.
     */
    public void streamAll(Consumer<Review> consumer) {
        RowCallbackHandler handler = rs -> consumer.accept(map(rs, 0));
        readOnlyTx.executeWithoutResult(status ->
                streamingJdbc.query("SELECT " + COLUMNS + " FROM reviews ORDER BY id", handler));
    }

    static Review map(ResultSet rs, int rowNum) throws SQLException {
        return new Review(
                rs.getLong("id"),
                rs.getString("source"),
                rs.getString("parent_asin"),
                rs.getString("asin"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getInt("rating"),
                rs.getInt("helpful_votes"),
                rs.getBoolean("verified_purchase"),
                rs.getTimestamp("reviewed_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"),
                rs.getBoolean("deleted"));
    }
}
