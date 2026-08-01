package com.alechilles.patchwork.discovery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.nio.file.FileSystem;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests winning target resolution from directory and archive asset packs. */
final class PatchTargetResolverTest {
    @TempDir Path tempDir;

    @Test
    void resolvesHighestLoadOrderWithPackIdTieBreakAndDefensiveBytes() throws Exception {
        Path first = tempDir.resolve("first");
        Path second = tempDir.resolve("second");
        write(first, "Server/Target.json", "first");
        write(second, "Server/Target.json", "second");

        PatchTargetResolver.ResolvedTarget target = new PatchTargetResolver().resolve(List.of(
                PatchSource.directory("z-pack", 4, first),
                PatchSource.directory("a-pack", 4, second)), "Server/Target.json").orElseThrow();

        assertEquals("z-pack", target.sourcePackId());
        assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), target.bytes());
        byte[] copy = target.bytes();
        copy[0] = 'X';
        assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), target.bytes());
    }

    @Test
    void resolvesHigherLoadOrderBeforeSourceIdTieBreak() throws Exception {
        Path lower = tempDir.resolve("lower");
        Path higher = tempDir.resolve("higher");
        write(lower, "Server/Target.json", "lower");
        write(higher, "Server/Target.json", "higher");

        PatchTargetResolver.ResolvedTarget target = new PatchTargetResolver().resolve(List.of(
                PatchSource.directory("z-pack", 1, lower),
                PatchSource.directory("a-pack", 2, higher)), "Server/Target.json").orElseThrow();

        assertEquals("a-pack", target.sourcePackId());
        assertArrayEquals("higher".getBytes(StandardCharsets.UTF_8), target.bytes());
    }

    @Test
    void resolvesArchiveBytesAndRejectsUnsafeTargetPaths() throws Exception {
        Path archive = tempDir.resolve("pack.jar");
        writeArchive(archive, "Server/Target.json", "archive");
        PatchTargetResolver resolver = new PatchTargetResolver();

        assertEquals("archive", new String(resolver.resolve(List.of(PatchSource.archive("pack", 1, archive)), "Server/Target.json").orElseThrow().bytes(), StandardCharsets.UTF_8));
        assertFalse(resolver.resolve(List.of(PatchSource.archive("pack", 1, archive)), "/Server/Target.json").isPresent());
        assertFalse(resolver.resolve(List.of(PatchSource.archive("pack", 1, archive)), "../Target.json").isPresent());
        assertFalse(resolver.resolve(List.of(PatchSource.archive("pack", 1, archive)), "C:/Target.json").isPresent());
    }

    @Test
    void excludesGeneratedPatchworkSource() throws Exception {
        Path generated = tempDir.resolve("generated");
        write(generated, "Server/Target.json", "generated");

        assertTrue(new PatchTargetResolver().resolve(List.of(PatchSource.directory(PatchScanner.GENERATED_PACK_ID, 99, generated)), "Server/Target.json").isEmpty());
    }

    @Test
    void reportsDetailedFoundMissingUnsafeAndIoOutcomesWhileKeepingOptionalCompatibility() throws Exception {
        Path root = tempDir.resolve("detailed");
        write(root, "Server/Target.json", "target");
        PatchTargetResolver resolver = new PatchTargetResolver();
        List<PatchSource> source = List.of(PatchSource.directory("pack", 1, root));

        assertEquals(PatchTargetResolver.Status.FOUND, resolver.resolveDetailed(source, "Server/Target.json").status());
        assertEquals(PatchTargetResolver.Status.MISSING, resolver.resolveDetailed(source, "Server/Missing.json").status());
        assertEquals(PatchTargetResolver.Status.FAILED, resolver.resolveDetailed(source, "../unsafe.json").status());
        assertTrue(resolver.resolve(source, "Server/Target.json").isPresent());
        assertTrue(resolver.resolve(source, "../unsafe.json").isEmpty());
        Path brokenArchive = tempDir.resolve("broken.zip");
        Files.writeString(brokenArchive, "not a zip", StandardCharsets.UTF_8);
        assertEquals(PatchTargetResolver.Status.FAILED, resolver.resolveDetailed(List.of(PatchSource.archive("broken", 1, brokenArchive)), "Server/Target.json").status());
    }

    @Test
    void classifiesDirectorySymlinkEscapeAsFailedRatherThanMissing() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fs.getPath("/pack/Server"));
            Path outside = fs.getPath("/outside.json");
            Files.writeString(outside, "{}", StandardCharsets.UTF_8);
            Files.createSymbolicLink(root.resolve("Target.json"), outside);

            assertEquals(PatchTargetResolver.Status.FAILED, new PatchTargetResolver().resolveDetailed(
                    List.of(PatchSource.directory("pack", 1, root.getParent())), "Server/Target.json").status());
        }
    }

    private static void write(Path root, String path, String content) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void writeArchive(Path archive, String entry, String content) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry(entry));
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }
}
