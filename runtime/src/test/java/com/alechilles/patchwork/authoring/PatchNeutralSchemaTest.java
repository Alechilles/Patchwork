package com.alechilles.patchwork.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

/** Executable checks for the marker-free neutral authoring kit. */
final class PatchNeutralSchemaTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JsonSchema schema = loadSchema();

    @Test
    void acceptsMarkerFreeNeutralFixtureAndRejectsFormatMarkers() throws IOException {
        assertValid(readResource("authoring-kit/neutral/valid/marker-free.json"), "marker-free");
        assertValid(readResource("authoring-kit/neutral/valid/merge-matching.json"), "merge-matching");
        assertValid(readResource("authoring-kit/neutral/valid/upsert-matching.json"), "upsert-matching");
        assertValid(readResource("authoring-kit/neutral/valid/overlay-from-asset.json"), "overlay-from-asset");
        assertValid(readResource("authoring-kit/neutral/valid/merge-object-from-asset.json"), "merge-object-from-asset");
        assertValid(readResource("authoring-kit/neutral/valid/glob-targets.json"), "glob-targets");
        assertInvalid(readResource("authoring-kit/neutral/invalid/upsert-relative-without-find.json"),
                "upsert-relative-without-find");
        assertInvalid(readResource("authoring-kit/neutral/invalid/source-glob.json"), "source-glob");
        assertInvalid(readResource("authoring-kit/neutral/invalid/raw-wildcard-target.json"), "raw-wildcard-target");
        assertInvalid(JSON.readTree("""
                {"FormatVersion":2,"Target":"Server/Test/A.json","Operations":[]}
                """), "format-marker");
    }

    @Test
    void rejectsUnknownRootFieldsAndOperationNames() {
        assertInvalid(readResource("authoring-kit/neutral/invalid/unknown-root-field.json"), "unknown-root");
        assertInvalid(readResource("authoring-kit/neutral/invalid/unknown-operation.json"), "unknown-operation");
        assertInvalid(readResource("authoring-kit/neutral/invalid/upsert-invalid-match-policy.json"),
                "upsert-invalid-match-policy");
    }

    @Test
    void capabilitiesDeclareNeutralProfileWithoutCompatibilityOperation() throws IOException {
        Path path = Path.of("docs/authoring-kit/neutral/capabilities.json");
        if (!Files.exists(path)) path = Path.of("..", "docs/authoring-kit/neutral/capabilities.json");
        JsonNode capabilities = JSON.readTree(Files.readString(path));
        assertEquals("neutral", capabilities.get("profile").asText());
        assertEquals(false, capabilities.get("versionFieldRequired").asBoolean());
        Set<String> operations = StreamSupport.stream(capabilities.get("operations").spliterator(), false)
                .map(JsonNode::asText)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertEquals(Set.of("Add", "Merge", "Replace", "Remove", "Insert",
                "ReplaceMatching", "RemoveMatching", "MoveMatching", "MergeMatching", "UpsertMatching",
                "OverlayFromAsset", "MergeObjectFromAsset", "Macro"), operations);
    }

    private static JsonSchema loadSchema() {
        try {
            Path root = Path.of("docs/authoring-kit/neutral/patch-definition.schema.json");
            if (!Files.exists(root)) root = Path.of("..", "docs/authoring-kit/neutral/patch-definition.schema.json");
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(JSON.readTree(Files.readString(root)));
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static JsonNode readResource(String resource) {
        try (var stream = PatchNeutralSchemaTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalArgumentException("Missing resource: " + resource);
            return JSON.readTree(stream);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid resource: " + resource, failure);
        }
    }

    private void assertValid(JsonNode document, String name) {
        assertEquals(Set.of(), schema.validate(document), name);
    }

    private void assertInvalid(JsonNode document, String name) {
        assertTrue(!schema.validate(document).isEmpty(), name);
    }
}
