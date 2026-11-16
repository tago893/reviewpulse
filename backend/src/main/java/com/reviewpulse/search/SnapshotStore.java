package com.reviewpulse.search;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.reviewpulse.search.index.IndexSnapshotCodec;
import com.reviewpulse.search.index.InvertedIndex;
import com.reviewpulse.search.tokenizer.Tokenizer;

/**
 * Durable storage for one index snapshot file.
 *
 * <p>Write protocol: write to {@code index.snapshot.tmp}, fsync it, then atomically rename over
 * {@code index.snapshot}. A crash at any point leaves either the previous complete snapshot or the new
 * complete snapshot; a torn file is never visible under the final name. (The containing directory is
 * not fsynced, which Windows does not support, so the rename itself may be lost on power failure:
 * recovery then uses the previous snapshot and replays more events, which is safe.)
 */
public class SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(SnapshotStore.class);
    static final String FILE = "index.snapshot";

    private final Path directory;
    private final Tokenizer tokenizer;

    public SnapshotStore(Path directory, Tokenizer tokenizer) {
        this.directory = directory.toAbsolutePath().normalize();
        this.tokenizer = tokenizer;
    }

    /** Result of trying to load the snapshot. */
    public sealed interface LoadResult permits Missing, Corrupt, Found {
    }

    public record Missing() implements LoadResult {
    }

    public record Corrupt(String reason) implements LoadResult {
    }

    public record Found(IndexSnapshotCodec.Loaded snapshot, long bytes, long millis) implements LoadResult {
    }

    public Path file() {
        return directory.resolve(FILE);
    }

    /** @return bytes written */
    public long write(InvertedIndex compacted, IndexSnapshotCodec.Header header) throws IOException {
        Files.createDirectories(directory);
        Path tmp = directory.resolve(FILE + ".tmp");
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            OutputStream out = Channels.newOutputStream(channel);
            IndexSnapshotCodec.write(compacted, header, out);
            out.flush();
            channel.force(true);
        }
        try {
            Files.move(tmp, file(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("filesystem does not support atomic rename for snapshots", e);
        }
        return Files.size(file());
    }

    public LoadResult load() {
        long start = System.nanoTime();
        try (InputStream in = Files.newInputStream(file())) {
            IndexSnapshotCodec.Loaded loaded = IndexSnapshotCodec.read(in, tokenizer);
            long millis = (System.nanoTime() - start) / 1_000_000;
            return new Found(loaded, Files.size(file()), millis);
        } catch (NoSuchFileException e) {
            return new Missing();
        } catch (IndexSnapshotCodec.CorruptSnapshotException e) {
            log.warn("index snapshot {} is corrupt: {}", file(), e.getMessage());
            return new Corrupt(e.getMessage());
        } catch (IOException | RuntimeException e) {
            log.warn("index snapshot {} could not be read: {}", file(), e.toString());
            return new Corrupt("unreadable: " + e.getClass().getSimpleName());
        }
    }

    public Optional<Long> sizeBytes() {
        try {
            return Optional.of(Files.size(file()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
