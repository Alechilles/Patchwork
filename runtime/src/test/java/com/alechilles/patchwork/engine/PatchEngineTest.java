package com.alechilles.patchwork.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Tests raw Patchwork JSON patch operation behavior. */
final class PatchEngineTest {

    private final PatchEngine engine = new PatchEngine();

    @Test
    void appliesAddRemoveReplaceMergeAndInsertOperations() {
        PatchDefinition definition = definition("""
                { "Id": "operations", "Target": "Server/Test.json", "Operations": [
                  { "Id": "add", "Op": "Add", "Path": "/added", "Value": true },
                  { "Id": "merge", "Op": "Merge", "Path": "/nested", "Value": { "new": 2 } },
                  { "Id": "replace", "Op": "Replace", "Path": "/nested/old", "Value": 3 },
                  { "Id": "insert", "Op": "Insert", "Path": "/items", "Position": "After", "Find": { "id": "anchor" }, "Value": { "id": "inserted" } },
                  { "Id": "remove", "Op": "Remove", "Path": "/obsolete" }
                ] }
                """);

        PatchEngine.PatchResult result = engine.apply(object("""
                { "nested": { "old": 1 }, "items": [{ "id": "anchor" }], "obsolete": true }
                """), List.of(definition));

        assertTrue(result.patched().get("added").getAsBoolean());
        assertEquals(3, result.patched().getAsJsonObject("nested").get("old").getAsInt());
        assertEquals(2, result.patched().getAsJsonObject("nested").get("new").getAsInt());
        assertEquals("inserted", result.patched().getAsJsonArray("items").get(1).getAsJsonObject().get("id").getAsString());
        assertFalse(result.patched().has("obsolete"));
        assertEquals(5, result.applied().size());
    }

    @Test
    void skipsDisabledDefinitionsAndReportsInvalidJsonPointerPath() {
        PatchDefinition disabled = definition("""
                { "Id": "disabled", "Target": "Server/Test.json", "Enabled": false,
                  "Operations": [{ "Id": "add", "Op": "Add", "Path": "/added", "Value": true }] }
                """);
        PatchDefinition invalidPath = definition("""
                { "Id": "invalid", "Target": "Server/Test.json",
                  "Operations": [{ "Id": "add", "Op": "Add", "Path": "added", "Value": true }] }
                """);

        assertFalse(engine.apply(object("{}"), List.of(disabled)).patched().has("added"));
        PatchEngine.PatchFailureException exception = assertThrows(
                PatchEngine.PatchFailureException.class,
                () -> engine.apply(object("{}"), List.of(invalidPath))
        );
        assertEquals("invalid:add failed: Path must use JSON pointer syntax and start with '/': added", exception.getMessage());
    }

    private static PatchDefinition definition(String json) {
        return PatchDefinition.parse(object(json), "test-pack", "patches/test.json");
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
