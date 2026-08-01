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
                { "Id": "shared", "Targets": ["/Server/A.json", "Server/B.json"], "Operations": [] }
                """), "pack-a", "patches/shared.json", 4);
        PatchDefinition first = PatchDefinition.parse(object("""
                { "Id": "a", "Target": "Server/C.json", "Priority": 5, "Operations": [] }
                """), "pack-z", "patches/a.json", 9);
        PatchDefinition second = PatchDefinition.parse(object("""
                { "Id": "a", "Target": "Server/D.json", "Priority": 5, "Operations": [] }
                """), "pack-y", "patches/a.json", 2);
        PatchDefinition disabled = PatchDefinition.parse(object("""
                { "Id": "disabled", "Target": "Server/E.json", "Enabled": false, "Operations": [] }
                """), "pack", "patches/disabled.json");

        assertEquals(List.of("Server/A.json", "Server/B.json"), multiTarget.stream().map(PatchDefinition::target).toList());
        assertEquals(List.of(second, first), List.of(first, second).stream().sorted(PatchDefinition.ORDERING).toList());
        assertEquals(0, disabled.sourcePackLoadOrder());
    }

    @Test
    void rejectsBothSingleAndMultipleTargetsWithDeterministicMessage() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> PatchDefinition.parse(object("""
                { "Id": "invalid", "Target": "Server/A.json", "Targets": ["Server/B.json"], "Operations": [] }
                """), "pack", "patches/invalid.json"));

        assertEquals("Patch 'invalid' must define either Target or Targets, not both.", exception.getMessage());
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
