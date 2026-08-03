package com.alechilles.patchwork.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.patchwork.generation.GenerationAssetSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests explicit target selectors against one immutable inventory snapshot. */
final class PatchTargetExpanderTest {
    @TempDir Path temporary;

    @Test
    void expandsExplicitGlobDeduplicatesAndOrdersUnsignedUtf8() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("pack"));
        write(root, "Server/NPC/Bear.json");
        write(root, "Server/NPC/Wolf.json");
        write(root, "Server/NPC/Nested/Badger.json");

        GenerationAssetSnapshot snapshot = GenerationAssetSnapshot.capture(
                List.of(PatchSource.directory("pack", 0, root)));

        List<String> result = new PatchTargetExpander().expand(List.of(
                PatchTargetSelector.parse("glob:Server/NPC/**/*.json"),
                PatchTargetSelector.parse("Server/NPC/Wolf.json")), snapshot);

        assertEquals(List.of("Server/NPC/Bear.json", "Server/NPC/Nested/Badger.json", "Server/NPC/Wolf.json"), result);
    }

    @Test
    void starDoesNotCrossSegmentsAndQuestionMatchesOneCharacter() {
        assertTrue(PatchTargetSelector.parse("glob:Server/Item/?.json")
                .matches("Server/Item/A.json"));
        assertFalse(PatchTargetSelector.parse("glob:Server/Item/*.json")
                .matches("Server/Item/Sub/A.json"));
        assertTrue(PatchTargetSelector.parse("glob:Server/Item/**/A.json")
                .matches("Server/Item/Sub/A.json"));
    }

    @Test
    void exactPathsRejectRawWildcardsAndUnsafeSelectors() {
        assertThrows(IllegalArgumentException.class, () -> PatchTargetSelector.parse("Server/Item/*.json"));
        assertThrows(IllegalArgumentException.class, () -> PatchTargetSelector.parse("glob:/Server/*.json"));
        assertThrows(IllegalArgumentException.class, () -> PatchTargetSelector.parse("glob:Server/../*.json"));
        assertThrows(IllegalArgumentException.class, () -> PatchTargetSelector.parse("glob:Server//*.json"));
    }

    @Test
    void generatedPackAssetsNeverExpand() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("generated"));
        write(root, "Server/NPC/Generated.json");
        GenerationAssetSnapshot snapshot = GenerationAssetSnapshot.capture(List.of(
                PatchSource.directory(PatchScanner.GENERATED_PACK_ID, 1, root)));

        assertEquals(List.of(), new PatchTargetExpander().expand(
                List.of(PatchTargetSelector.parse("glob:Server/NPC/*.json")), snapshot));
        assertEquals(Set.of(), snapshot.sourcePackIds().stream().collect(java.util.stream.Collectors.toSet()));
    }

    private static void write(Path root, String path) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{}");
    }
}
