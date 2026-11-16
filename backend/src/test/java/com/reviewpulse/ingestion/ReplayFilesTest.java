package com.reviewpulse.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.reviewpulse.api.RequestValidationException;

class ReplayFilesTest {

    @TempDir
    Path dir;

    @Test
    void listsOnlyValidlyNamedJsonlFilesAndResolvesThem() throws IOException {
        Files.writeString(dir.resolve("live-500.jsonl"), "{}");
        Files.writeString(dir.resolve("Bad Name.jsonl"), "{}");
        Files.writeString(dir.resolve("notes.txt"), "x");
        ReplayFiles files = new ReplayFiles(dir);
        assertThat(files.list()).containsExactly("live-500");
        assertThat(files.resolve("live-500")).isEqualTo(dir.toAbsolutePath().normalize().resolve("live-500.jsonl"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"../secrets", "..\\x", "C:\\windows", "/etc/passwd", "UPPER", "live-500.jsonl", "missing"})
    void rejectsTraversalBadNamesAndMissingFiles(String name) throws IOException {
        Files.writeString(dir.resolve("live-500.jsonl"), "{}");
        assertThatThrownBy(() -> new ReplayFiles(dir).resolve(name)).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void missingDirectoryListsNothing() {
        assertThat(new ReplayFiles(dir.resolve("absent")).list()).isEmpty();
    }
}
