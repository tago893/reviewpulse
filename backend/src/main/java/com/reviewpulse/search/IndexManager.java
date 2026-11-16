package com.reviewpulse.search;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.reviewpulse.config.ReviewPulseProperties;
import com.reviewpulse.search.index.SearchIndex;

import jakarta.annotation.PreDestroy;

/**
 * Owns full rebuilds of the index from PostgreSQL. A rebuild runs on its own thread; searches keep
 * using the previous index until the new one is swapped in atomically.
 */
@Component
public class IndexManager {

    private static final Logger log = LoggerFactory.getLogger(IndexManager.class);

    private final IndexBuilder builder;
    private final SearchIndex index;
    private final IndexStatus status;
    private final ReviewPulseProperties.Index config;
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "index-rebuild");
        t.setDaemon(true);
        return t;
    });

    public IndexManager(IndexBuilder builder, SearchIndex index, IndexStatus status, ReviewPulseProperties properties) {
        this.builder = builder;
        this.index = index;
        this.status = status;
        this.config = properties.index();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (config.buildOnStartup()) {
            requestRebuild("startup");
        }
    }

    /** @return false if a rebuild is already running (the request is not queued twice) */
    public boolean requestRebuild(String reason) {
        if (!rebuilding.compareAndSet(false, true)) {
            return false;
        }
        executor.submit(() -> rebuild(reason));
        return true;
    }

    public boolean isRebuilding() {
        return rebuilding.get();
    }

    private void rebuild(String reason) {
        log.info("index rebuild started (reason: {})", reason);
        status.transition(IndexState.BUILDING, "rebuilding from PostgreSQL (" + reason + ")");
        try {
            IndexBuilder.Built built = builder.buildFromDatabase();
            index.replace(built.index());
            status.buildCompleted(built.millis());
        } catch (RuntimeException e) {
            log.error("index rebuild failed", e);
            status.buildFailed("rebuild failed: " + e.getClass().getSimpleName());
        } finally {
            rebuilding.set(false);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
