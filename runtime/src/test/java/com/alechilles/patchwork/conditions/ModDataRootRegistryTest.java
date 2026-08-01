package com.alechilles.patchwork.conditions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.nio.file.FileSystem;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
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
    void mapsPluginRootsByExactManifestIdThroughFactoryHelper() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("factory"));
        ModDataRootRegistry registry = ModDataRootRegistry.fromPluginRoots(Map.of("Example:Mod", root));
        assertEquals(root.toAbsolutePath().normalize(), registry.rootFor("Example:Mod").orElseThrow());
        assertTrue(registry.rootFor("example:mod").isEmpty());
    }

    @Test
    void rejectsSymlinkComponentsAndOversizedOrNonRegularFiles() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fs.getPath("/data"));
            Path outside = Files.createDirectories(fs.getPath("/outside"));
            Files.writeString(outside.resolve("outside.json"), "{}", StandardCharsets.UTF_8);
            Files.createSymbolicLink(root.resolve("linked"), outside);
            ModDataRootRegistry.ReadResult result = new ModDataRootRegistry(Map.of("Example:Mod", root)).readJson("Example:Mod", "linked/outside.json");
            assertEquals(ModDataRootRegistry.ReadStatus.FAILED, result.status());
            assertTrue(!result.diagnostic().contains(outside.resolve("outside.json").toString()));
        }
    }

    @Test
    void readsUnchangedFallbackFileWhenInjectedReaderRemovesEveryFileKey() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("data"));
        Path file = root.resolve("settings.json");
        Files.writeString(file, "{}", StandardCharsets.UTF_8);
        NullFileKeyAttributeReader attributes = new NullFileKeyAttributeReader();
        ModDataRootRegistry readable = registry(root, attributes, path -> { });
        assertEquals(ModDataRootRegistry.ReadStatus.FOUND, readable.readJson("Example:Mod", "settings.json").status());
        assertTrue(attributes.readCount > 0);
        assertTrue(attributes.returnedOnlyNullKeys);
    }

    @Test
    void rejectsObservableFinalMutationWhenInjectedReaderRemovesEveryFileKey() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("final-mutation"));
        Path file = root.resolve("settings.json");
        Files.writeString(file, "{}", StandardCharsets.UTF_8);
        NullFileKeyAttributeReader attributes = new NullFileKeyAttributeReader();
        ModDataRootRegistry swapped = registry(root, attributes, path ->
                Files.writeString(path, "{\"changed\":true}", StandardCharsets.UTF_8));
        assertEquals(ModDataRootRegistry.ReadStatus.FAILED, swapped.readJson("Example:Mod", "settings.json").status());
        assertTrue(attributes.readCount > 0);
        assertTrue(attributes.returnedOnlyNullKeys);
    }

    @Test
    void rejectsObservableIntermediateReplacementWhenInjectedReaderRemovesEveryFileKey() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("intermediate-null-key"));
        Path config = Files.createDirectories(root.resolve("config"));
        Files.writeString(config.resolve("settings.json"), "{}", StandardCharsets.UTF_8);
        NullFileKeyAttributeReader attributes = new NullFileKeyAttributeReader();
        ModDataRootRegistry registry = registry(root, attributes, path -> {
            Files.move(config, root.resolve("config-old"));
            Files.createDirectories(config);
            Files.writeString(config.resolve("settings.json"), "{}", StandardCharsets.UTF_8);
            Files.writeString(config.resolve("observable-marker"), "changed", StandardCharsets.UTF_8);
        });
        assertEquals(ModDataRootRegistry.ReadStatus.FAILED, registry.readJson("Example:Mod", "config/settings.json").status());
        assertTrue(attributes.readCount > 0);
        assertTrue(attributes.returnedOnlyNullKeys);
    }

    @Test
    void rejectsObservableRootReplacementWhenInjectedReaderRemovesEveryFileKey() throws Exception {
        Path root = temporaryDirectory.resolve("root-null-key");
        Path config = Files.createDirectories(root.resolve("config"));
        Files.writeString(config.resolve("settings.json"), "{}", StandardCharsets.UTF_8);
        NullFileKeyAttributeReader attributes = new NullFileKeyAttributeReader();
        ModDataRootRegistry registry = registry(root, attributes, path -> {
            Files.move(root, temporaryDirectory.resolve("root-null-key-old"));
            Path replacement = Files.createDirectories(root.resolve("config"));
            Files.writeString(replacement.resolve("settings.json"), "{}", StandardCharsets.UTF_8);
            Files.writeString(root.resolve("observable-marker"), "changed", StandardCharsets.UTF_8);
        });
        assertEquals(ModDataRootRegistry.ReadStatus.FAILED, registry.readJson("Example:Mod", "config/settings.json").status());
        assertTrue(attributes.readCount > 0);
        assertTrue(attributes.returnedOnlyNullKeys);
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

    @Test
    void acceptsExactLimitAndRejectsDirectoryFinalTarget() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("bounded-data"));
        Files.write(root.resolve("exact.json"), new byte[(int) ModDataRootRegistry.MAX_BYTES]);
        Files.createDirectories(root.resolve("directory.json"));
        ModDataRootRegistry registry = new ModDataRootRegistry(Map.of("Example:Mod", root));

        assertEquals(ModDataRootRegistry.ReadStatus.FOUND, registry.readJson("Example:Mod", "exact.json").status());
        assertEquals(ModDataRootRegistry.ReadStatus.FAILED, registry.readJson("Example:Mod", "directory.json").status());
    }

    @Test
    void usesRealSecureDirectoryStreamWithJimfsAndRejectsSymlink() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fs.getPath("/data/config"));
            Files.writeString(root.resolve("settings.json"), "{}", StandardCharsets.UTF_8);
            try (var stream = Files.newDirectoryStream(root.getParent())) {
                assertTrue(stream instanceof java.nio.file.SecureDirectoryStream<?>);
            }
            ModDataRootRegistry registry = new ModDataRootRegistry(Map.of("Example:Mod", root.getParent()));
            assertEquals(ModDataRootRegistry.ReadStatus.FOUND, registry.readJson("Example:Mod", "config/settings.json").status());
            Files.createSymbolicLink(root.resolve("link.json"), fs.getPath("/outside.json"));
            assertEquals(ModDataRootRegistry.ReadStatus.FAILED, registry.readJson("Example:Mod", "config/link.json").status());
        }
    }

    @Test
    void treatsSecureFinalDisappearanceAfterValidationAsFailed() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fs.getPath("/data"));
            Path file = root.resolve("settings.json");
            Files.writeString(file, "{}", StandardCharsets.UTF_8);
            ModDataRootRegistry registry = new ModDataRootRegistry(Map.of("Example:Mod", root), Files::delete);

            assertEquals(ModDataRootRegistry.ReadStatus.FAILED, registry.readJson("Example:Mod", "settings.json").status());
        }
    }

    @Test
    void treatsSecureInitialAbsenceAsMissing() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fs.getPath("/data"));
            assertEquals(ModDataRootRegistry.ReadStatus.MISSING, new ModDataRootRegistry(Map.of("Example:Mod", root)).readJson("Example:Mod", "missing.json").status());
        }
    }

    @Test
    void treatsFallbackPostReadDisappearanceAsFailed() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("post-read"));
        Path file = root.resolve("settings.json"); Files.writeString(file, "{}", StandardCharsets.UTF_8);
        ModDataRootRegistry registry = new ModDataRootRegistry(Map.of("Example:Mod", root), path -> { }, Files::delete);
        assertEquals(ModDataRootRegistry.ReadStatus.FAILED, registry.readJson("Example:Mod", "settings.json").status());
    }

    @Test
    void distinguishesInitialMissingFromPostValidationDisappearance() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("data"));
        ModDataRootRegistry initial = new ModDataRootRegistry(Map.of("Example:Mod", root));
        assertEquals(ModDataRootRegistry.ReadStatus.MISSING, initial.readJson("Example:Mod", "missing.json").status());
        Path file = root.resolve("present.json"); Files.writeString(file, "{}", StandardCharsets.UTF_8);
        ModDataRootRegistry disappearing = new ModDataRootRegistry(Map.of("Example:Mod", root), Files::delete);
        assertEquals(ModDataRootRegistry.ReadStatus.FAILED, disappearing.readJson("Example:Mod", "present.json").status());
    }

    @Test
    void failsWhenAnIntermediateDirectoryChangesAfterValidation() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("intermediate-data"));
        Path config = Files.createDirectories(root.resolve("config"));
        Files.writeString(config.resolve("settings.json"), "{}", StandardCharsets.UTF_8);
        ModDataRootRegistry registry = new ModDataRootRegistry(Map.of("Example:Mod", root), path -> {
            Path moved = root.resolve("config-moved");
            Files.move(config, moved);
            Files.createDirectories(config);
            Files.writeString(config.resolve("settings.json"), "{}", StandardCharsets.UTF_8);
        });

        assertEquals(ModDataRootRegistry.ReadStatus.FAILED, registry.readJson("Example:Mod", "config/settings.json").status());
    }

    @Test
    void failsClosedWhenRegisteredRootIsReplacedByOutsideSymlinkAtOpenHandoff() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fs.getPath("/data"));
            Path outside = Files.createDirectories(fs.getPath("/outside"));
            Files.writeString(root.resolve("settings.json"), "{\"inside\":true}", StandardCharsets.UTF_8);
            Files.writeString(outside.resolve("settings.json"), "{\"outside\":true}", StandardCharsets.UTF_8);
            ModDataRootRegistry registry = new ModDataRootRegistry(Map.of("Example:Mod", root), path -> { }, path -> { }, ModDataRootRegistry.FileAttributes::readSystem, path -> {
                Files.move(root, fs.getPath("/data-old"));
                Files.createSymbolicLink(root, outside);
            });

            ModDataRootRegistry.ReadResult result = registry.readJson("Example:Mod", "settings.json");

            assertEquals(ModDataRootRegistry.ReadStatus.FAILED, result.status());
            assertEquals(null, result.bytes());
        }
    }

    @Test
    void treatsFallbackFinalDisappearanceAfterAttributesAsFailed() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("fallback-final-window"));
        Path file = root.resolve("settings.json");
        Files.writeString(file, "{}", StandardCharsets.UTF_8);
        ModDataRootRegistry registry = new ModDataRootRegistry(Map.of("Example:Mod", root), path -> { }, Files::delete);

        assertEquals(ModDataRootRegistry.ReadStatus.FAILED, registry.readJson("Example:Mod", "settings.json").status());
    }

    @Test
    void treatsFallbackDeletionBetweenFinalPostReadAttributesAndRealPathAsFailed() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("fallback-real-path-window"));
        Path file = root.resolve("settings.json");
        Files.writeString(file, "{}", StandardCharsets.UTF_8);
        int[] fileReads = {0};
        ModDataRootRegistry.AttributeReader attributes = path -> {
            ModDataRootRegistry.FileAttributes value = ModDataRootRegistry.FileAttributes.readSystem(path);
            if (path.equals(file) && ++fileReads[0] == 3) Files.delete(file);
            return value;
        };
        ModDataRootRegistry registry = new ModDataRootRegistry(Map.of("Example:Mod", root), path -> { }, path -> { }, attributes);

        assertEquals(ModDataRootRegistry.ReadStatus.FAILED, registry.readJson("Example:Mod", "settings.json").status());
    }

    @Test
    void treatsSecureIntermediateDisappearanceAfterAttributesAsFailed() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            Path root = Files.createDirectories(fs.getPath("/data/config"));
            Files.writeString(root.resolve("settings.json"), "{}", StandardCharsets.UTF_8);
            ModDataRootRegistry registry = new ModDataRootRegistry(Map.of("Example:Mod", root.getParent()), path -> { }, path -> { }, ModDataRootRegistry.FileAttributes::readSystem, path -> { }, path -> Files.move(path, fs.getPath("/data/config-gone")));

            assertEquals(ModDataRootRegistry.ReadStatus.FAILED, registry.readJson("Example:Mod", "config/settings.json").status());
        }
    }

    @Test
    void defensivelyCopiesResolvedModDataBytes() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("copy-data"));
        Files.writeString(root.resolve("settings.json"), "{}", StandardCharsets.UTF_8);
        ModDataRootRegistry.ReadResult result = new ModDataRootRegistry(Map.of("Example:Mod", root)).readJson("Example:Mod", "settings.json");
        byte[] bytes = result.bytes();
        bytes[0] = 'X';
        assertEquals('{', result.bytes()[0]);
    }

    private static void assertFalseContainsPath(String diagnostic, Path path) {
        assertTrue(!diagnostic.contains(path.toAbsolutePath().toString()));
    }

    private static ModDataRootRegistry registry(Path root, NullFileKeyAttributeReader attributes, ModDataRootRegistry.ReadHook hook) {
        return new ModDataRootRegistry(Map.of("Example:Mod", root), hook, path -> { }, attributes);
    }

    private static final class NullFileKeyAttributeReader implements ModDataRootRegistry.AttributeReader {
        private int readCount;
        private boolean returnedOnlyNullKeys = true;

        @Override
        public ModDataRootRegistry.FileAttributes read(Path path) throws IOException {
            readCount++;
            ModDataRootRegistry.FileAttributes attributes = ModDataRootRegistry.FileAttributes.readSystem(path).withoutFileKey();
            returnedOnlyNullKeys &= attributes.fileKey() == null;
            return attributes;
        }
    }
}
