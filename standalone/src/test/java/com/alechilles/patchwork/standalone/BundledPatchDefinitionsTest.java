package com.alechilles.patchwork.standalone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Guards the centralized compatibility definitions bundled by standalone Patchwork. */
class BundledPatchDefinitionsTest {
    private static final Path PATCH_ROOT = Path.of("src/main/resources/Server/Patchwork/Patches");

    @Test
    void bundlesEveryMigratedPatchBehindItsOwningModGate() throws Exception {
        assertPatchGroup("AnimalHusbandry", 23, "Alechilles:Alec's Animal Husbandry!");
        assertPatchGroup("HyDragon", 6, "Alechilles:HyDragon");
        assertPatchGroup("Tamework", 5, "Alechilles:Alec's Tamework!");
    }

    private static void assertPatchGroup(String directory, int expectedCount, String pluginId) throws Exception {
        Path root = PATCH_ROOT.resolve(directory);
        List<Path> definitions;
        try (var files = Files.walk(root)) {
            definitions = files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
        assertEquals(expectedCount, definitions.size(), "Unexpected patch count under " + root);
        for (Path definition : definitions) {
            JsonObject patch = JsonParser.parseString(Files.readString(definition)).getAsJsonObject();
            assertTrue(patch.has("Id"), "Missing stable patch ID: " + definition);
            assertEquals(pluginId, modInstalled(patch),
                    "Wrong activation gate: " + definition);
        }
    }

    private static String modInstalled(JsonObject patch) {
        JsonObject when = patch.getAsJsonObject("When");
        if (when.has("ModInstalled")) return when.get("ModInstalled").getAsString();
        for (var condition : when.getAsJsonArray("All")) {
            JsonObject object = condition.getAsJsonObject();
            if (object.has("ModInstalled")) return object.get("ModInstalled").getAsString();
        }
        return null;
    }
}
