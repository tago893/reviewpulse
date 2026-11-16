package com.reviewpulse.ingestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.reviewpulse.api.RequestValidationException;

/**
 * The only files the simulator may read: {@code <replay dir>/<name>.jsonl} where name matches a strict
 * pattern. Names are resolved against the configured directory and the normalized result must stay
 * inside it, so "../", absolute paths and drive letters are impossible.
 */
public final class ReplayFiles {

    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");

    private final Path directory;

    public ReplayFiles(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public Path resolve(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new RequestValidationException(List.of("file must match " + NAME.pattern() + " (without .jsonl)"));
        }
        Path file = directory.resolve(name + ".jsonl").normalize();
        if (!file.startsWith(directory) || !Files.isRegularFile(file)) {
            throw new RequestValidationException(List.of("replay file '" + name + "' not found in the replay directory"));
        }
        return file;
    }

    public List<String> list() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".jsonl"))
                    .map(n -> n.substring(0, n.length() - ".jsonl".length()))
                    .filter(n -> NAME.matcher(n).matches())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
