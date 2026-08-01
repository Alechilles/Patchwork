package com.alechilles.patchwork.embedded;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GeneratedInventorySnapshotterTest {
    @TempDir Path temporary;

    @Test void rejectsARootReplacementBeforeReadingTheReplacementTree() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated"));
        Files.writeString(root.resolve("safe.json"), "safe");
        Path attacker = Files.createDirectories(temporary.resolve("attacker"));
        Path marker = attacker.resolve("must-not-read.json");
        Files.writeString(marker, "attacker");

        GeneratedInventorySnapshotter snapshotter = GeneratedInventorySnapshotter.from(root, ignored -> {
            Files.move(root, temporary.resolve("parked-generated"));
            Files.createDirectories(root);
            Files.copy(marker, root.resolve(marker.getFileName()));
        });

        assertThrows(IOException.class, snapshotter::snapshot);
        assertTrue(Files.exists(marker));
        assertTrue(Files.exists(root.resolve(marker.getFileName())));
    }
}
