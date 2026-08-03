package com.alechilles.patchwork.generation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.alechilles.patchwork.discovery.PatchRoot;
import com.alechilles.patchwork.discovery.PatchScanner;
import com.alechilles.patchwork.discovery.PatchSource;
import com.alechilles.patchwork.format.Utf8Ordering;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GenerationAssetSnapshotTest {
    @TempDir Path temporary;

    @Test
    void snapshotKeepsOriginalWinnerAfterBackingFilesChange() throws Exception {
        Path low = Files.createDirectories(temporary.resolve("low/Server/Test"));
        Path high = Files.createDirectories(temporary.resolve("high/Server/Test"));
        Files.writeString(low.resolve("A.json"), "{\"v\":1}");
        Files.writeString(high.resolve("A.json"), "{\"v\":2}");
        GenerationAssetSnapshot snapshot = GenerationAssetSnapshot.capture(List.of(
                PatchSource.directory("Low", 1, low.getParent().getParent()),
                PatchSource.directory("High", 2, high.getParent().getParent())));

        Files.writeString(high.resolve("A.json"), "{\"v\":3}");

        assertEquals("High", snapshot.require("Server/Test/A.json").sourcePackId());
        assertEquals("{\"v\":2}", new String(snapshot.require("Server/Test/A.json").bytes(), StandardCharsets.UTF_8));
    }

    @Test
    void snapshotExcludesGeneratedPackAndOrdersPathsByUnsignedUtf8() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("pack/Server/Patchwork/Patches"));
        Files.writeString(root.resolve("z.json"), "{}");
        Files.writeString(root.resolve("a.json"), "{}");
        GenerationAssetSnapshot snapshot = GenerationAssetSnapshot.capture(List.of(
                PatchSource.directory(PatchScanner.GENERATED_PACK_ID, 99, root.getParent().getParent().getParent()),
                PatchSource.directory("pack", 0, root.getParent().getParent().getParent())));

        assertFalse(snapshot.sourcePackIds().contains(PatchScanner.GENERATED_PACK_ID));
        assertEquals(snapshot.paths().stream().sorted(Utf8Ordering.UNSIGNED_BYTES).toList(), snapshot.paths());
    }
}
