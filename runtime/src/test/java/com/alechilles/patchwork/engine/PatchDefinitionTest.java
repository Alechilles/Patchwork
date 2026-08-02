package com.alechilles.patchwork.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Tests parsing and deterministic ordering of patch definitions. */
final class PatchDefinitionTest {

    @Test
    void parsesMultipleTargetsAndSortsByPriorityIdThenSourcePackLoadOrder() {
        List<PatchDefinition> multiTarget = PatchDefinition.parseAll(object("""
                { "Id": "shared", "Targets": ["Server/A.json", "Server/B.json"], "Operations": [] }
                """), "pack-a", "patches/shared.json", 4);
        PatchDefinition lowerPriority = PatchDefinition.parse(object("""
                { "Id": "z", "Target": "Server/C.json", "Priority": 1, "Operations": [] }
                """), "pack-z", "patches/z.json", 9);
        PatchDefinition lowerId = PatchDefinition.parse(object("""
                { "Id": "a", "Target": "Server/D.json", "Priority": 5, "Operations": [] }
                """), "pack-y", "patches/a.json", 9);
        PatchDefinition earlierPack = PatchDefinition.parse(object("""
                { "Id": "b", "Target": "Server/E.json", "Priority": 5, "Operations": [] }
                """), "pack-b", "patches/b.json", 2);
        PatchDefinition laterPack = PatchDefinition.parse(object("""
                { "Id": "b", "Target": "Server/F.json", "Priority": 5, "Operations": [] }
                """), "pack-c", "patches/b.json", 9);
        PatchDefinition disabled = PatchDefinition.parse(object("""
                { "Id": "disabled", "Target": "Server/E.json", "Enabled": false, "Operations": [] }
                """), "pack", "patches/disabled.json");

        assertEquals(List.of("Server/A.json", "Server/B.json"), multiTarget.stream().map(PatchDefinition::target).toList());
        assertEquals(List.of(lowerPriority, lowerId, earlierPack, laterPack),
                List.of(laterPack, earlierPack, lowerId, lowerPriority).stream().sorted(PatchDefinition.ORDERING).toList());
        assertEquals(0, disabled.sourcePackLoadOrder());
    }

    @Test
    void rejectsBothSingleAndMultipleTargetsWithDeterministicMessage() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> PatchDefinition.parse(object("""
                { "Id": "invalid", "Target": "Server/A.json", "Targets": ["Server/B.json"], "Operations": [] }
                """), "pack", "patches/invalid.json"));

        assertEquals("Patch 'invalid' must define either Target or Targets, not both.", exception.getMessage());
    }

    @Test
    void rejectsAbsoluteUnsafeAndNonIntegralDefinitionFields() {
        for (String target : List.of("/Server/A.json", "C:/Server/A.json", "Server/../A.json", "Server//A.json")) {
            assertThrows(IllegalArgumentException.class, () -> PatchDefinition.parse(object("""
                    { "Id": "unsafe", "Target": "%s", "Operations": [] }
                    """.formatted(target)), "pack", "patches/unsafe.json"));
        }
        assertThrows(IllegalArgumentException.class, () -> PatchDefinition.parse(object("""
                { "Id": "fraction", "Target": "Server/A.json", "Priority": 1.9, "Operations": [] }
                """), "pack", "patches/fraction.json"));
        assertThrows(IllegalArgumentException.class, () -> PatchDefinition.parse(object("""
                { "Id": "overflow", "Target": "Server/A.json", "Priority": 2147483648, "Operations": [] }
                """), "pack", "patches/overflow.json"));
    }

    @Test
    void rejectsFormatTwoDefinitionWithoutCompatibilitySentinel() {
        assertThrows(IllegalArgumentException.class, () -> PatchDefinition.parseAll(object("""
                { "FormatVersion": 2, "Id": "v2", "Target": "Server/A.json", "Operations": [] }
                """), "pack", "patch.json"));
    }

    @Test
    void rejectsFormatTwoSentinelRequiredField() {
        assertThrows(IllegalArgumentException.class, () -> PatchDefinition.parseAll(object("""
                { "FormatVersion": 2, "Id": "v2", "Target": "Server/A.json", "Operations": [
                  { "Op": "RequireFormat", "Version": 2, "Required": false }
                ] }
                """), "pack", "patch.json"));
    }

    @Test
    void acceptsFormatTwoCompatibilitySentinelAsFirstOperation() {
        PatchDefinition definition = PatchDefinition.parse(object("""
                { "FormatVersion": 2, "Id": "v2", "Target": "Server/A.json", "Operations": [
                  { "Op": "RequireFormat", "Version": 2 }
                ] }
                """), "pack", "patch.json");

        assertEquals(2, definition.formatVersion());
        assertEquals(2, definition.operations().getFirst().formatVersion());
        assertEquals(2, definition.operations().getFirst().version());
    }

    @Test
    void rejectsMalformedOptionalLegacyOperationInFormatTwoBeforeApplication() {
        assertThrows(IllegalArgumentException.class, () -> PatchDefinition.parseAll(object("""
                { "FormatVersion": 2, "Id": "v2", "Target": "Server/A.json", "Operations": [
                  { "Op": "RequireFormat", "Version": 2 },
                  { "Op": "Add", "Required": false }
                ] }
                """), "pack", "patch.json"));
    }

    @Test
    void rejectsCaseVariantRequireFormatSentinel() {
        assertThrows(IllegalArgumentException.class, () -> PatchDefinition.parseAll(object("""
                { "FormatVersion": 2, "Id": "v2", "Target": "Server/A.json", "Operations": [
                  { "Op": "RequireFormat", "Version": 2 },
                  { "Op": "requireformat", "Version": 2 }
                ] }
                """), "pack", "patch.json"));
    }

    @Test
    void rejectsV2OperationPointerBeforeApplication() {
        assertThrows(IllegalArgumentException.class, () -> PatchDefinition.parse(object("""
                { "FormatVersion": 2, "Id": "v2", "Target": "Server/A.json", "Operations": [
                  { "Op": "RequireFormat", "Version": 2 },
                  { "Op": "Add", "Path": "bad", "Value": 2, "Required": false }
                ] }
                """), "pack", "patch.json"));
    }

    @Test
    void rejectsMalformedMatcherOperationPoliciesAndMoveAnchorShapes() {
        assertThrows(IllegalArgumentException.class, () -> PatchDefinition.parse(object("""
                { "FormatVersion": 2, "Id": "bad-policy", "Target": "Server/A.json", "Operations": [
                  { "Op": "RequireFormat", "Version": 2 },
                  { "Op": "ReplaceMatching", "Path": "/items", "Match": { "id": "x" },
                    "MatchPolicy": "Many", "Value": { "id": "y" }, "Required": false }
                ] }
                """), "pack", "patch.json"));
        assertThrows(IllegalArgumentException.class, () -> PatchDefinition.parse(object("""
                { "FormatVersion": 2, "Id": "bad-position", "Target": "Server/A.json", "Operations": [
                  { "Op": "RequireFormat", "Version": 2 },
                  { "Op": "MoveMatching", "Path": "/items", "Match": { "id": "x" },
                    "Position": "Start", "Find": { "id": "anchor" } }
                ] }
                """), "pack", "patch.json"));
        assertThrows(IllegalArgumentException.class, () -> PatchDefinition.parse(object("""
                { "FormatVersion": 2, "Id": "missing-find", "Target": "Server/A.json", "Operations": [
                  { "Op": "RequireFormat", "Version": 2 },
                  { "Op": "MoveMatching", "Path": "/items", "Match": { "id": "x" },
                    "Position": "Before" }
                ] }
                """), "pack", "patch.json"));
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
