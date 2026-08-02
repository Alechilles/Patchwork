package com.alechilles.patchwork.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.alechilles.patchwork.conditions.PatchCondition;
import com.alechilles.patchwork.format.PatchDefinitionReader;
import com.alechilles.patchwork.format.Utf8Ordering;
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
    void appliesLowerUnsignedSourcePackIdFirstWhenOtherOrderingFieldsTie() {
        PatchDefinition zPack = PatchDefinition.parse(object("""
                { "Id": "shared", "Target": "Server/A.json", "Priority": 5, "Operations": [] }
                """), "z-pack", "patches/z.json", 3);
        PatchDefinition aPack = PatchDefinition.parse(object("""
                { "Id": "shared", "Target": "Server/A.json", "Priority": 5, "Operations": [] }
                """), "a-pack", "patches/a.json", 3);

        assertEquals(List.of(aPack, zPack), List.of(zPack, aPack).stream()
                .sorted(PatchDefinition.ORDERING).toList());
    }

    @Test
    void comparesSourcePackIdsByUnsignedUtf8Bytes() {
        String privateUsePack = new String(Character.toChars(0xE000)) + "-pack";
        String supplementaryPack = new String(Character.toChars(0x10000)) + "-pack";
        PatchDefinition privateUse = PatchDefinition.parse(object("""
                { "Id": "shared", "Target": "Server/A.json", "Priority": 5, "Operations": [] }
                """), privateUsePack, "patches/private.json", 3);
        PatchDefinition supplementary = PatchDefinition.parse(object("""
                { "Id": "shared", "Target": "Server/A.json", "Priority": 5, "Operations": [] }
                """), supplementaryPack, "patches/supplementary.json", 3);

        assertEquals(List.of(privateUse, supplementary), List.of(supplementary, privateUse).stream()
                .sorted(PatchDefinition.ORDERING).toList());
    }

    @Test
    void rejectsUnpairedSurrogatesAtUtf8OrderingAndDefinitionBoundaries() {
        String first = "\uD800";
        String second = "\uD801";

        assertThrows(IllegalArgumentException.class, () -> Utf8Ordering.UNSIGNED_BYTES.compare(first, second));
        assertThrows(IllegalArgumentException.class, () -> PatchDefinition.parse(object("""
                { "Id": "shared", "Target": "Server/A.json", "Operations": [] }
                """), first, "patch.json"));
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

    @Test
    void rejectsWhitespaceOnlyFormatTwoOperationIds() {
        assertThrows(IllegalArgumentException.class, () -> PatchDefinition.parse(object("""
                { "FormatVersion": 2, "Id": "v2", "Target": "Server/A.json", "Operations": [
                  { "Op": "RequireFormat", "Version": 2 },
                  { "Op": "Add", "Id": " \t ", "Path": "/flag", "Value": true }
                ] }
        """), "pack", "patch.json"));
    }

    @Test
    void rejectsWhitespaceOnlyFormatTwoDefinitionIdButKeepsLegacyCompatibility() {
        assertThrows(IllegalArgumentException.class, () -> PatchDefinition.parse(object("""
                { "FormatVersion": 2, "Id": "   ", "Target": "Server/A.json", "Operations": [
                  { "Op": "RequireFormat", "Version": 2 }
                ] }
                """), "pack", "patch.json"));

        PatchDefinition legacy = PatchDefinition.parse(object("""
                { "Id": "   ", "Target": "Server/A.json", "Operations": [] }
                """), "pack", "patch.json");
        assertEquals("   ", legacy.id());
    }

    @Test
    void rejectsWhitespaceOnlyFormatTwoMacroButKeepsLegacyParsing() {
        assertThrows(IllegalArgumentException.class, () -> PatchDefinition.parse(object("""
                { "FormatVersion": 2, "Id": "v2", "Target": "Server/A.json", "Operations": [
                  { "Op": "RequireFormat", "Version": 2 },
                  { "Op": "Macro", "Macro": "   " }
                ] }
                """), "pack", "patch.json"));

        PatchDefinition legacy = PatchDefinition.parse(object("""
                { "Id": "legacy", "Target": "Server/A.json", "Operations": [
                  { "Op": "Macro", "Macro": "   " }
                ] }
                """), "pack", "patch.json");
        assertEquals("   ", legacy.operations().getFirst().macro());
    }

    @Test
    void parsesEveryShippedFormatTwoValidAuthoringFixtureThroughTheByteReader() throws Exception {
        for (String name : fixtureNames("authoring-kit/v2/valid")) {
            byte[] bytes = fixtureBytes("authoring-kit/v2/valid/" + name);
            JsonObject root = PatchDefinitionReader.parse(bytes, "Fixture:Authoring", name, 0);
            List<PatchDefinition> definitions = PatchDefinition.parseAll(root, "Fixture:Authoring", name);

            assertFalse(definitions.isEmpty(), name);
            assertEquals(2, definitions.getFirst().formatVersion(), name);
        }
    }

    @Test
    void rejectsEveryShippedInvalidAuthoringFixtureBeforeEngineApplication() throws Exception {
        for (String name : fixtureNames("authoring-kit/v2/invalid")) {
            if ("ambiguous-match.json".equals(name)) {
                continue;
            }
            byte[] bytes = fixtureBytes("authoring-kit/v2/invalid/" + name);
            assertThrows(IllegalArgumentException.class, () -> {
                JsonObject root = PatchDefinitionReader.parse(bytes, "Fixture:Authoring", name, 0);
                PatchDefinition.parseAll(root, "Fixture:Authoring", name);
            }, name);
        }
    }

    @Test
    void parsesTargetProvidedByConditionFromAuthoringKitFixture() throws Exception {
        byte[] bytes = fixtureBytes("authoring-kit/v2/valid/target-provided-by.json");
        JsonObject root = PatchDefinitionReader.parse(bytes, "Fixture:Authoring", "target-provided-by.json", 0);
        PatchCondition condition = PatchDefinition.parse(root, "Fixture:Authoring", "target-provided-by.json").condition();

        PatchCondition.TargetProvidedBy provider = assertInstanceOf(PatchCondition.TargetProvidedBy.class, condition);
        assertEquals("Fixture:Provider", provider.sourcePackId());
    }

    @Test
    void duplicateKeyFixtureIsRejectedByByteReaderBeforeDefinitionParsing() throws Exception {
        byte[] bytes = fixtureBytes("authoring-kit/v2/invalid/duplicate-key.json");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PatchDefinitionReader.parse(bytes, "Fixture:Authoring", "duplicate-key.json", 0));

        assertEquals("Format 2 patch definitions must not contain duplicate JSON object keys.", failure.getMessage());
    }

    private static List<String> fixtureNames(String directory) throws IOException, URISyntaxException {
        var resource = PatchDefinitionTest.class.getClassLoader().getResource(directory);
        if (resource == null) {
            throw new AssertionError("Missing fixture directory: " + directory);
        }
        try (Stream<Path> paths = Files.list(Path.of(resource.toURI()))) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> path.getFileName().toString())
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    private static byte[] fixtureBytes(String resource) throws IOException {
        try (InputStream stream = PatchDefinitionTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new AssertionError("Missing fixture: " + resource);
            }
            return stream.readAllBytes();
        }
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
