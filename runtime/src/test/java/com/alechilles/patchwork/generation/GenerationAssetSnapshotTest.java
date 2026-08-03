package com.alechilles.patchwork.generation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.alechilles.patchwork.discovery.PatchRoot;
import com.alechilles.patchwork.discovery.PatchScanner;
import com.alechilles.patchwork.discovery.PatchSource;
import com.alechilles.patchwork.discovery.PatchTargetResolver;
import com.alechilles.patchwork.format.Utf8Ordering;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
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

    @Test
    void snapshotRejectsDirectoryEntriesThatEscapeThroughSymlinks() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fs.getPath("/pack/Server"));
            Path outside = fs.getPath("/outside.json");
            Files.writeString(outside, "{\"outside\":true}");
            Files.createSymbolicLink(root.resolve("Target.json"), outside);

            GenerationAssetSnapshot snapshot = GenerationAssetSnapshot.capture(List.of(
                    PatchSource.directory("pack", 1, root.getParent())));

            assertFalse(snapshot.find("Server/Target.json").isPresent());
        }
    }

    @Test
    void resolvedRootReadStaysOnCapturedRootAfterRegisteredSymlinkSwap() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            Path rootA = Files.createDirectories(fs.getPath("/packs/a/Server"));
            Path rootB = Files.createDirectories(fs.getPath("/packs/b/Server"));
            Files.writeString(rootA.resolve("Target.json"), "A");
            Files.writeString(rootB.resolve("Target.json"), "B");
            Path registered = fs.getPath("/packs/registered");
            Files.createSymbolicLink(registered, rootA.getParent());

            PatchTargetResolver.DirectoryRootSnapshot captured = PatchTargetResolver.snapshotDirectoryRoot(
                    registered.toRealPath());
            Files.delete(registered);
            Files.createSymbolicLink(registered, rootB.getParent());

            assertEquals("A", new String(PatchTargetResolver.readDirectoryAsset(captured, "Server/Target.json"),
                    StandardCharsets.UTF_8));
        }
    }
}
