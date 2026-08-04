package com.alechilles.patchwork.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.patchwork.format.PatchDefinitionReader;
import com.alechilles.patchwork.format.PatchLanguage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Keeps the HyCreator handoff fixture aligned with Patchwork's neutral authoring contract. */
final class HyCreatorPatchTestModTest {
    private static final Set<String> PORTABLE_OPERATIONS = Set.of(
            "Add", "Merge", "Replace", "Remove", "Insert",
            "ReplaceMatching", "RemoveMatching", "MoveMatching", "MergeMatching", "UpsertMatching",
            "OverlayFromAsset", "MergeObjectFromAsset", "Macro");

    @Test
    void standaloneTestModContainsOneValidNeutralPatchForEveryPortableOperation() throws Exception {
        Path root = fixtureRoot();
        assertTrue(Files.isDirectory(root), "missing standalone HyCreator test mod");

        JsonObject manifest = JsonParser.parseString(Files.readString(root.resolve("manifest.json"))).getAsJsonObject();
        assertTrue(manifest.get("IncludesAssetPack").getAsBoolean());

        Path patchRoot = root.resolve("Server/Patchwork/Patches");
        List<Path> definitions;
        try (Stream<Path> files = Files.walk(patchRoot)) {
            definitions = files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }

        assertEquals(PORTABLE_OPERATIONS.size(), definitions.size());
        Set<String> operations = new HashSet<>();
        for (Path path : definitions) {
            String sourcePath = root.relativize(path).toString().replace('\\', '/');
            PatchDefinition definition = PatchDefinition.parseAll(
                    PatchDefinitionReader.parse(Files.readAllBytes(path), "Alechilles:HyCreatorPatchTest", sourcePath, 0),
                    "Alechilles:HyCreatorPatchTest", sourcePath, 0, PatchLanguage.NEUTRAL).getFirst();
            assertEquals(0, definition.formatVersion());
            assertTrue(definition.target().startsWith("Server/"));
            assertEquals(1, definition.operations().size());
            operations.add(definition.operations().getFirst().op());
        }

        assertEquals(PORTABLE_OPERATIONS, operations);
    }

    private static Path fixtureRoot() {
        Path root = Path.of("hycreator-patch-test-mod");
        return Files.isDirectory(root) ? root : Path.of("..", "hycreator-patch-test-mod");
    }
}
