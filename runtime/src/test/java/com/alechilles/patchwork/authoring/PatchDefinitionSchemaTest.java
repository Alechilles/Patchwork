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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Executable checks that the published draft-2020-12 schema tracks runtime format-2 semantics. */
final class PatchDefinitionSchemaTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JsonSchema schema = loadSchema();

    @Test
    void acceptsRepresentativeCorpusDefinitionsAndFormatTwoDefaults() {
        for (String resource : List.of(
                "authoring-kit/v2/valid/replace-matching.json",
                "authoring-kit/v2/valid/move-matching.json",
                "authoring-kit/v2/valid/remove-matching.json",
                "authoring-kit/v2/valid/strict-equals.json",
                "authoring-kit/v2/valid/strict-contains.json",
                "authoring-kit/v2/valid/target-provided-by.json")) {
            assertValid(readResource(resource), resource);
        }

        assertValid(document("""
                {"FormatVersion":2,"Target":"Server/Test.json","Operations":[
                  {"Op":"RequireFormat","Version":2},
                  {"Op":"Insert","Path":"/items","Value":{"id":"new"}},
                  {"Op":"MoveMatching","Path":"/items","Match":{"id":"moving"}}
                ]}
                """), "omitted-position");
    }

    @Test
    void acceptsCaseInsensitivePoliciesAndPositions() {
        assertValid(document("""
                {"FormatVersion":2,"Target":"Server/Test.json","Operations":[
                  {"Op":"RequireFormat","Version":2},
                  {"Op":"ReplaceMatching","Path":"/items","Match":{"id":"b"},"MatchPolicy":"first","Value":{"id":"changed"}},
                  {"Op":"MoveMatching","Path":"/items","Match":{"id":"a"},"Position":"eNd"}
                ]}
                """), "case-insensitive-enums");
    }

    @Test
    void rejectsSecondSentinelInvalidMatchersAndClosedNestedDescriptors() {
        assertInvalid(document("""
                {"FormatVersion":2,"Target":"Server/Test.json","Operations":[
                  {"Op":"RequireFormat","Version":2},
                  {"Op":"RequireFormat","Version":2}
                ]}
                """), "second-sentinel");
        assertInvalid(document("""
                {"FormatVersion":2,"Target":"Server/Test.json","Operations":[
                  {"Op":"RequireFormat","Version":2},
                  {"Op":"RemoveMatching","Path":"/items","Match":{"$Contains":true}}
                ]}
                """), "invalid-contains");
        assertInvalid(document("""
                {"FormatVersion":2,"Target":"Server/Test.json","Operations":[
                  {"Op":"RequireFormat","Version":2},
                  {"Op":"RemoveMatching","Path":"/items","Match":{"$Equals":1,"id":1}}
                ]}
                """), "reserved-matcher-mix");
        assertInvalid(document("""
                {"FormatVersion":2,"Target":"Server/Test.json","When":{"AssetExists":{"Asset":"Server/A.json","Unexpected":true}},"Operations":[
                  {"Op":"RequireFormat","Version":2}
                ]}
                """), "unknown-condition-field");
        assertInvalid(document("""
                {"FormatVersion":2,"Target":"Server/Test.json","When":{"JsonPathExists":{"Path":"/enabled","Source":{"Type":"Asset","Path":"Server/A.json","Unexpected":true}}},"Operations":[
                  {"Op":"RequireFormat","Version":2}
                ]}
                """), "unknown-source-field");
        assertInvalid(document("""
                {"FormatVersion":2,"Target":"Server/Test.json","When":{"JsonPathExists":{"Path":"/enabled","Source":{"Type":"ModData","Mod":"Example:Mod","Path":"settings.json","Unexpected":true}}},"Operations":[
                  {"Op":"RequireFormat","Version":2}
                ]}
                """), "unknown-mod-data-source-field");
    }

    @Test
    void rejectsWhitespaceOnlyNonblankContractFields() {
        assertInvalid(document("""
                {"FormatVersion":2,"Id":"   ","Target":"Server/Test.json","Operations":[
                  {"Op":"RequireFormat","Version":2}
                ]}
                """), "blank-definition-id");
        assertInvalid(document("""
                {"FormatVersion":2,"Target":"Server/Test.json","Operations":[
                  {"Op":"RequireFormat","Version":2},
                  {"Op":"Macro","Macro":"   "}
                ]}
                """), "blank-macro");
        assertInvalid(document("""
                {"FormatVersion":2,"Target":"Server/Test.json","When":{"TargetProvidedBy":" \\t "},"Operations":[
                  {"Op":"RequireFormat","Version":2}
                ]}
                """), "blank-target-provider");
        assertInvalid(document("""
                {"FormatVersion":2,"Target":"Server/Test.json","Operations":[
                  {"Op":"RequireFormat","Version":2},
                  {"Op":"Add","Id":" \\t ","Path":"/flag","Value":true}
                ]}
                """), "blank-operation-id");
    }

    private static JsonSchema loadSchema() {
        try {
            Path root = Path.of("docs/authoring-kit/v2/patch-definition.schema.json");
            if (!Files.exists(root)) {
                root = Path.of("..", "docs/authoring-kit/v2/patch-definition.schema.json");
            }
            JsonNode schemaNode = JSON.readTree(Files.readString(root));
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode);
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static JsonNode readResource(String resource) {
        try (var stream = PatchDefinitionSchemaTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalArgumentException("Missing resource: " + resource);
            return JSON.readTree(stream);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid resource: " + resource, failure);
        }
    }

    private static JsonNode document(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException failure) {
            throw new IllegalArgumentException(failure);
        }
    }

    private void assertValid(JsonNode document, String name) {
        Set<?> errors = schema.validate(document);
        assertEquals(Set.of(), errors, name);
    }

    private void assertInvalid(JsonNode document, String name) {
        assertTrue(!schema.validate(document).isEmpty(), name);
    }
}
