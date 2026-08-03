package com.alechilles.patchwork.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

final class PatchDefinitionAssetTest {
    @Test
    void nativeStoreContractAndAssetPathIdentityStaySeparateFromPortableId() throws Exception {
        String source = """
                {"FormatVersion":2,"Id":"portable-id","Target":"Server/Test.json",
                 "Operations":[{"Op":"RequireFormat","Version":2}]}
                """;
        var data = new AssetExtraInfo.Data(PatchDefinitionAsset.class, "folder/file-key", null);

        PatchDefinitionAsset asset = PatchDefinitionAsset.CODEC.decodeJsonAsset(
                RawJsonReader.fromBuffer(source.toCharArray()), new AssetExtraInfo<>(data));

        assertEquals("folder/file-key", asset.getId());
        assertEquals("portable-id", asset.toPortableJson().get("Id").getAsString());
        assertEquals("Patchwork/Patches", PatchDefinitionAssetStore.PATH);
        assertEquals(".json", PatchDefinitionAssetStore.EXTENSION);
    }

    @Test
    void productionJsonCodecPreservesNullAndPreciseJsonNumbers() throws Exception {
        String source = """
                {
                  "FormatVersion": 2,
                  "Id": "lossless-values",
                  "Target": "Server/Test.json",
                  "Operations": [
                    {"Op": "RequireFormat", "Version": 2},
                    {"Op": "Replace", "Path": "/Null", "Value": null},
                    {"Op": "Replace", "Path": "/Decimal", "Value": 0.1234567890123456789},
                    {"Op": "Replace", "Path": "/Integer", "Value": 922337203685477580812345}
                  ]
                }
                """;

        PatchDefinitionAsset asset = PatchDefinitionAsset.CODEC.decodeJson(
                RawJsonReader.fromBuffer(source.toCharArray()), EmptyExtraInfo.EMPTY);

        var operations = asset.toPortableJson().getAsJsonArray("Operations");
        assertTrue(operations.get(1).getAsJsonObject().has("Value"));
        assertTrue(operations.get(1).getAsJsonObject().get("Value").isJsonNull());
        assertEquals("0.1234567890123456789",
                operations.get(2).getAsJsonObject().get("Value").getAsString());
        assertEquals("922337203685477580812345",
                operations.get(3).getAsJsonObject().get("Value").getAsString());
        assertTrue(PatchDefinitionAsset.CODEC.encode(asset, EmptyExtraInfo.EMPTY).toString()
                .contains("0.1234567890123456789"));
    }

    @Test
    void nativeCodecPreservesRecursiveAuthoringShapesExactly() throws Exception {
        String source = """
                {
                  "FormatVersion": 2,
                  "Id": "recursive-authoring",
                  "Target": "Server/Test.json",
                  "When": {
                    "All": [
                      {"ModInstalled": "Example:Base"},
                      {"Not": {"JsonPathEquals": {"Path": "/enabled", "Value": false}}}
                    ]
                  },
                  "Operations": [
                    {"Op": "RequireFormat", "Version": 2},
                    {
                      "Op": "ReplaceMatching",
                      "Path": "/items",
                      "Match": {"tags": {"$Contains": {"$Equals": "rare"}}},
                      "MatchPolicy": "All",
                      "Value": {
                        "id": "changed",
                        "meta": {"nullable": null, "ratio": 0.1234567890123456789}
                      }
                    },
                    {
                      "Op": "Macro",
                      "Macro": "Example:Configure",
                      "Options": {
                        "nested": {"enabled": true},
                        "values": [1, null, "x"]
                      }
                    }
                  ]
                }
                """;

        PatchDefinitionAsset asset = PatchDefinitionAsset.CODEC.decodeJson(
                RawJsonReader.fromBuffer(source.toCharArray()), EmptyExtraInfo.EMPTY);

        assertEquals(JsonParser.parseString(source), asset.toPortableJson());
        assertEquals(JsonParser.parseString(source), JsonParser.parseString(
                PatchDefinitionAsset.CODEC.encode(asset, EmptyExtraInfo.EMPTY).toString()));
    }

    @Test
    void productionJsonCodecRejectsDuplicateFormatDowngrade() {
        String source = """
                {
                  "FormatVersion": 2,
                  "FormatVersion": 1,
                  "Id": "duplicate-downgrade",
                  "Target": "Server/Test.json",
                  "Operations": []
                }
                """;

        assertThrows(RuntimeException.class, () -> PatchDefinitionAsset.CODEC.decodeJson(
                RawJsonReader.fromBuffer(source.toCharArray()), EmptyExtraInfo.EMPTY));
    }

    @Test
    void productionJsonCodecRejectsTrailingContentAfterTheDefinition() {
        String source = """
                {"FormatVersion":2,"Id":"trailing-content","Target":"Server/Test.json",
                 "Operations":[{"Op":"RequireFormat","Version":2}]} garbage
                """;

        assertThrows(RuntimeException.class, () -> PatchDefinitionAsset.CODEC.decodeJsonAsset(
                RawJsonReader.fromBuffer(source.toCharArray()),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(
                        PatchDefinitionAsset.class, "trailing-content", null))));
    }

    @Test
    void productionJsonCodecPreservesAcceptedLegacyExtensions() throws Exception {
        String source = """
                {
                  "Id": "legacy-extensions",
                  "Target": "Server/Test.json",
                  "LegacyRoot": {"keep": 1},
                  "Operations": [
                    {"Op": "Replace", "Path": "/Enabled", "Value": true,
                     "Version": "legacy-data", "LegacyOperation": [1, 2, 3]}
                  ]
                }
                """;

        PatchDefinitionAsset asset = PatchDefinitionAsset.CODEC.decodeJson(
                RawJsonReader.fromBuffer(source.toCharArray()), EmptyExtraInfo.EMPTY);
        var portable = asset.toPortableJson();

        assertEquals(1, portable.getAsJsonObject("LegacyRoot").get("keep").getAsInt());
        assertEquals("legacy-data", portable.getAsJsonArray("Operations").get(0)
                .getAsJsonObject().get("Version").getAsString());
        assertEquals(3, portable.getAsJsonArray("Operations").get(0)
                .getAsJsonObject().getAsJsonArray("LegacyOperation").size());
    }

    @Test
    void nativeCodecRoundTripsPortableFieldsAndArbitraryOperationValues() {
        PatchDefinitionAsset asset = PatchDefinitionAsset.CODEC.decode(BsonDocument.parse("""
                {
                  "FormatVersion": 2,
                  "Id": "native-round-trip",
                  "Target": "Server/NPC/Roles/Test.json",
                  "Priority": 20,
                  "Enabled": true,
                  "When": {"TargetProvidedBy": "Example:Dragons"},
                  "Operations": [
                    {"Op": "RequireFormat", "Version": 2},
                    {"Id": "health", "Op": "Replace", "Path": "/MaxHealth", "Value": 275}
                  ]
                }
                """), EmptyExtraInfo.EMPTY);

        var portable = asset.toPortableJson();
        assertEquals(2, portable.get("FormatVersion").getAsInt());
        assertEquals("native-round-trip", portable.get("Id").getAsString());
        assertEquals("Example:Dragons",
                portable.getAsJsonObject("When").get("TargetProvidedBy").getAsString());
        assertEquals(275, portable.getAsJsonArray("Operations").get(1)
                .getAsJsonObject().get("Value").getAsInt());

        BsonDocument encoded = PatchDefinitionAsset.CODEC.encode(asset, EmptyExtraInfo.EMPTY).asDocument();
        assertEquals("native-round-trip", encoded.getString("Id").getValue());
        assertEquals(275, encoded.getArray("Operations").get(1).asDocument().getInt32("Value").getValue());
    }

    @Test
    void nativeCodecRejectsDefinitionsThatViolateThePortableRuntimeContract() {
        assertThrows(RuntimeException.class, () -> PatchDefinitionAsset.CODEC.decode(BsonDocument.parse("""
                {
                  "FormatVersion": 2,
                  "Id": "missing-sentinel",
                  "Target": "Server/Test.json",
                  "Operations": [{"Op": "Replace", "Path": "/Enabled", "Value": true}]
                }
                """), EmptyExtraInfo.EMPTY));
        assertThrows(RuntimeException.class, () -> PatchDefinitionAsset.CODEC.decode(BsonDocument.parse("""
                {
                  "FormatVersion": 2,
                  "Id": "editor-wrapper-field",
                  "Target": "Server/Test.json",
                  "$Title": "must not become part of the portable format",
                  "Operations": [{"Op": "RequireFormat", "Version": 2}]
                }
                """), EmptyExtraInfo.EMPTY));
    }

    @Test
    void nativeSchemasExposeDefinitionAndTypedOperationFields() {
        var definitionSchema = (ObjectSchema) PatchDefinitionAsset.CODEC.toSchema(new SchemaContext());
        var definitionProperties = definitionSchema.getProperties();
        assertEquals(Set.of(
                        "FormatVersion", "Id", "Target", "Targets", "Priority", "Enabled", "When", "Operations"),
                definitionProperties.keySet(),
                "native schema must not expose Hytale Parent or Tags wrapper fields");

        var operationSchema = PatchOperationAsset.CODEC.toSchema(new SchemaContext());
        var operationProperties = operationSchema.getProperties();
        assertTrue(operationProperties.keySet().containsAll(Set.of(
                "Id", "Op", "Version", "Path", "Value", "Position", "Match", "MatchPolicy",
                "Find", "Existing", "Macro", "Options", "Required")));

        assertDocumented(definitionSchema, "Patch definition");
        definitionProperties.forEach(PatchDefinitionAssetTest::assertDocumented);
        assertDocumented(operationSchema, "Patch operation");
        operationProperties.forEach(PatchDefinitionAssetTest::assertDocumented);

        assertEnum(operationProperties.get("Op"), Set.of(
                "RequireFormat", "Add", "Merge", "Replace", "Remove", "Insert",
                "ReplaceMatching", "RemoveMatching", "MoveMatching", "Macro"));
        assertEnum(operationProperties.get("Position"), Set.of("Start", "End", "Before", "After"));
        assertEnum(operationProperties.get("MatchPolicy"), Set.of("ExactlyOne", "First", "Last", "All"));
    }

    private static void assertDocumented(String name, Schema schema) {
        assertDocumented(schema, name);
    }

    private static void assertDocumented(Schema schema, String name) {
        String documentation = schema.getMarkdownDescription() != null
                ? schema.getMarkdownDescription() : schema.getDescription();
        assertTrue(documentation != null && !documentation.isBlank(),
                name + " must have beginner-facing documentation");
    }

    private static void assertEnum(Schema schema, Set<String> expectedValues) {
        var stringSchema = (StringSchema) schema;
        assertEquals(expectedValues, Set.copyOf(Arrays.asList(stringSchema.getEnum())));
        assertEquals("Enum", stringSchema.getHytale().getType());
        assertEquals(expectedValues.size(), stringSchema.getMarkdownEnumDescriptions().length);
        assertTrue(Arrays.stream(stringSchema.getMarkdownEnumDescriptions())
                .allMatch(description -> description != null && !description.isBlank()),
                "every enum choice must explain what it does");
    }

}
