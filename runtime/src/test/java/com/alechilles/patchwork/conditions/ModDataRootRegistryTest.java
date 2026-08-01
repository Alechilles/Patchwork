package com.alechilles.patchwork.conditions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Filesystem-boundary tests for secure ModData document reads. */
final class ModDataRootRegistryTest {
    @TempDir Path temporaryDirectory;

    @Test
    void looksUpOnlyExactPluginIdsAndNormalRelativePaths() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("data"));
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/settings.json"), "{}", StandardCharsets.UTF_8);
        ModDataRootRegistry registry = new ModDataRootRegistry(Map.of("Example:Mod", root));

        assertTrue(registry.rootFor("Example:Mod").isPresent());
        assertTrue(registry.rootFor("example:mod").isEmpty());
        assertEquals("config/settings.json", registry.validateRelativePath("config/settings.json"));
        assertThrows(IllegalArgumentException.class, () -> registry.validateRelativePath("../settings.json"));
        assertThrows(IllegalArgumentException.class, () -> registry.validateRelativePath("C:/settings.json"));
        assertThrows(IllegalArgumentException.class, () -> registry.validateRelativePath("/settings.json"));
        assertThrows(IllegalArgumentException.class, () -> registry.validateRelativePath("config/./settings.json"));
    }

    @Test
    void rejectsSymlinkComponentsAndOversizedOrNonRegularFiles() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("data"));
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        Files.writeString(outside.resolve("outside.json"), "{}", StandardCharsets.UTF_8);
        Path link = root.resolve("linked");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            return;
        }
        ModDataRootRegistry registry = new ModDataRootRegistry(Map.of("Example:Mod", root));

        ModDataRootRegistry.ReadResult result = registry.readJson("Example:Mod", "linked/outside.json");

        assertEquals(ModDataRootRegistry.ReadStatus.FAILED, result.status());
        assertFalseContainsPath(result.diagnostic(), outside);
    }

    @Test
    void permitsApprovedNullFileKeyFallbackAndFailsOnObservableSwap() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("data"));
        Path file = root.resolve("settings.json");
        Files.writeString(file, "{}", StandardCharsets.UTF_8);
        ModDataRootRegistry readable = new ModDataRootRegistry(Map.of("Example:Mod", root));
        assertEquals(ModDataRootRegistry.ReadStatus.FOUND, readable.readJson("Example:Mod", "settings.json").status());

        ModDataRootRegistry swapped = new ModDataRootRegistry(Map.of("Example:Mod", root), path ->
                Files.writeString(path, "{\"changed\":true}", StandardCharsets.UTF_8));
        assertEquals(ModDataRootRegistry.ReadStatus.FAILED, swapped.readJson("Example:Mod", "settings.json").status());
    }

    @Test
    void rejectsFilesOverLimitBeforeAndDuringRead() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("data"));
        Path large = root.resolve("large.json");
        Files.write(large, new byte[(int) ModDataRootRegistry.MAX_BYTES + 1]);
        ModDataRootRegistry registry = new ModDataRootRegistry(Map.of("Example:Mod", root));
        assertEquals(ModDataRootRegistry.ReadStatus.FAILED, registry.readJson("Example:Mod", "large.json").status());

        Path small = root.resolve("small.json");
        Files.writeString(small, "{}", StandardCharsets.UTF_8);
        ModDataRootRegistry grown = new ModDataRootRegistry(Map.of("Example:Mod", root), path ->
                Files.write(path, new byte[(int) ModDataRootRegistry.MAX_BYTES + 1]));
        assertEquals(ModDataRootRegistry.ReadStatus.FAILED, grown.readJson("Example:Mod", "small.json").status());
    }

    private static void assertFalseContainsPath(String diagnostic, Path path) {
        assertTrue(!diagnostic.contains(path.toAbsolutePath().toString()));
    }
}
