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
    void addsToArrayWithJsonPointerAppendToken() {
        PatchEngine.PatchResult result = engine.apply(object("""
                { "items": ["first"] }
                """), List.of(definition("""
                { "Id": "append", "Target": "Server/Test.json", "Operations": [
                  { "Id": "append", "Op": "Add", "Path": "/items/-", "Value": "second" }
                ] }
                """)));

        assertEquals(List.of("first", "second"), result.patched().getAsJsonArray("items").asList().stream()
                .map(element -> element.getAsString()).toList());
    }

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

    @Test
    void permitsV2ObjectPropertyTokenWithLeadingZero() {
        PatchDefinition definition = definition("""
                { "FormatVersion": 2, "Id": "leading-zero", "Target": "Server/Test.json", "Operations": [
                  { "Op": "RequireFormat", "Version": 2 },
                  { "Op": "Add", "Path": "/01", "Value": 2 }
                ] }
                """);

        PatchEngine.PatchResult result = engine.apply(object("{ \"01\": 1 }"), List.of(definition));

        assertEquals(2, result.patched().get("01").getAsInt());
    }

    @Test
    void keepsLegacyOptionalMalformedPathAsExecutionTimeSkip() {
        PatchDefinition definition = definition("""
                { "Id": "legacy-optional", "Target": "Server/Test.json", "Operations": [
                  { "Id": "bad-path", "Op": "Add", "Path": "bad", "Value": 2, "Required": false }
                ] }
                """);

        PatchEngine.PatchResult result = engine.apply(object("{}"), List.of(definition));

        assertEquals(List.of("legacy-optional:bad-path failed: Path must use JSON pointer syntax and start with '/': bad"), result.skipped());
        assertFalse(result.patched().has("bad"));
    }

    @Test
    void addsReplacesAndRemovesArrayEntriesByJsonPointerIndex() {
        PatchEngine.PatchResult result = engine.apply(object("""
                { "items": ["zero", "one", "two"] }
                """), List.of(definition("""
                { "Id": "array", "Target": "Server/Test.json", "Operations": [
                  { "Id": "add", "Op": "Add", "Path": "/items/1", "Value": "inserted" },
                  { "Id": "replace", "Op": "Replace", "Path": "/items/2", "Value": "replaced" },
                  { "Id": "remove", "Op": "Remove", "Path": "/items/3" }
                ] }
                """)));

        assertEquals(List.of("zero", "inserted", "replaced"), strings(result.patched(), "items"));
    }

    @Test
    void deepMergesNestedObjectsWithoutDiscardingExistingFields() {
        PatchEngine.PatchResult result = engine.apply(object("""
                { "config": { "nested": { "kept": true, "changed": 1 }, "top": "kept" } }
                """), List.of(definition("""
                { "Id": "merge", "Target": "Server/Test.json", "Operations": [
                  { "Id": "merge", "Op": "Merge", "Path": "/config", "Value": { "nested": { "changed": 2, "added": true } } }
                ] }
                """)));

        JsonObject nested = result.patched().getAsJsonObject("config").getAsJsonObject("nested");
        assertTrue(nested.get("kept").getAsBoolean());
        assertEquals(2, nested.get("changed").getAsInt());
        assertTrue(nested.get("added").getAsBoolean());
        assertEquals("kept", result.patched().getAsJsonObject("config").get("top").getAsString());
    }

    @Test
    void insertsAtStartEndAndBeforeAnAnchorAndSkipsExistingValues() {
        PatchEngine.PatchResult result = engine.apply(object("""
                { "items": [{ "id": "anchor" }, { "id": "present" }] }
                """), List.of(definition("""
                { "Id": "positions", "Target": "Server/Test.json", "Operations": [
                  { "Id": "start", "Op": "Insert", "Path": "/items", "Position": "Start", "Value": { "id": "start" } },
                  { "Id": "before", "Op": "Insert", "Path": "/items", "Position": "Before", "Find": { "id": "anchor" }, "Value": { "id": "before" } },
                  { "Id": "end", "Op": "Insert", "Path": "/items", "Position": "End", "Value": { "id": "end" } },
                  { "Id": "existing", "Op": "Insert", "Path": "/items", "Position": "End", "Existing": { "id": "present" }, "Value": { "id": "duplicate" } }
                ] }
                """)));

        assertEquals(List.of("start", "before", "anchor", "present", "end"), objectIds(result.patched(), "items"));
        assertEquals(3, result.applied().size());
        assertEquals(List.of("positions:existing (existing matcher already present)"), result.skipped());
    }

    @Test
    void failsRequiredMissingAnchorAndSkipsOptionalMissingAnchor() {
        PatchDefinition required = definition("""
                { "Id": "required", "Target": "Server/Test.json", "Operations": [
                  { "Id": "anchor", "Op": "Insert", "Path": "/items", "Position": "After", "Find": { "id": "missing" }, "Value": { "id": "new" } }
                ] }
                """);
        PatchDefinition optional = definition("""
                { "Id": "optional", "Target": "Server/Test.json", "Operations": [
                  { "Id": "anchor", "Op": "Insert", "Path": "/items", "Position": "After", "Required": false, "Find": { "id": "missing" }, "Value": { "id": "new" } }
                ] }
                """);

        PatchEngine.PatchFailureException failure = assertThrows(
                PatchEngine.PatchFailureException.class, () -> engine.apply(object("{ \"items\": [] }"), List.of(required))
        );
        assertEquals("required:anchor failed: Insert anchor not found for anchor.", failure.getMessage());
        PatchEngine.PatchResult optionalResult = engine.apply(object("{ \"items\": [] }"), List.of(optional));
        assertEquals(List.of("optional:anchor failed: Insert anchor not found for anchor."), optionalResult.skipped());
    }

    private static PatchDefinition definition(String json) {
        return PatchDefinition.parse(object(json), "test-pack", "patches/test.json");
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static List<String> strings(JsonObject object, String arrayName) {
        return object.getAsJsonArray(arrayName).asList().stream().map(element -> element.getAsString()).toList();
    }

    private static List<String> objectIds(JsonObject object, String arrayName) {
        return object.getAsJsonArray(arrayName).asList().stream()
                .map(element -> element.getAsJsonObject().get("id").getAsString()).toList();
    }
}
