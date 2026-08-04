package com.alechilles.patchwork.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.BooleanSchema;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class PatchNativeAuthoringSchemaTest {
    @Test
    void patchworkDefinitionsUseSchemaFileTransmittedToAssetEditor() {
        SchemaContext context = new SchemaContext();
        PatchDefinitionAsset.CODEC.toSchema(context);
        Set<String> patchworkDefinitions = Set.of(
                "Alechilles.Patchwork.PatchCondition", "Alechilles.Patchwork.PatchMatcher",
                "Alechilles.Patchwork.PatchMatcherValue", "Alechilles.Patchwork.PatchJsonValue",
                "Alechilles.Patchwork.PatchJsonObject");

        assertTrue(context.getOtherDefinitions().isEmpty(),
                "Hytale Asset Editor removes other.json before transmitting schemas");
        assertTrue(context.getDefinitions().keySet().containsAll(patchworkDefinitions),
                "Patchwork references must resolve through transmitted common.json definitions");
    }

    @Test
    void patchworkDefinitionsDoNotOverwriteExistingCommonSchemaNames() {
        SchemaContext context = new SchemaContext();
        Schema existing = new Schema();
        context.getDefinitions().put("Alechilles.Patchwork.PatchCondition", existing);

        Schema condition = PatchConditionCodec.INSTANCE.toSchema(context);

        assertSame(existing, context.getDefinitions().get("Alechilles.Patchwork.PatchCondition"));
        assertEquals("common.json#/definitions/Alechilles.Patchwork.PatchCondition@1", condition.getRef());
        assertNotNull(context.getDefinitions().get("Alechilles.Patchwork.PatchCondition@1"));
    }

    @Test
    void whenUsesDocumentedRecursiveConditionFields() {
        SchemaContext context = new SchemaContext();
        ObjectSchema definition = (ObjectSchema) PatchDefinitionAsset.CODEC.toSchema(context);

        Schema when = definition.getProperties().get("When");
        assertTrue(containsRef(when, "common.json#/definitions/Alechilles.Patchwork.PatchCondition"));
        Schema condition = context.getDefinitions().get("Alechilles.Patchwork.PatchCondition");
        assertNotNull(condition);
        assertTrue(condition instanceof ObjectSchema,
                "When must serialize as an object so the Asset Editor can open its condition choices");
        assertTrue(((ObjectSchema) condition).getProperties().keySet().containsAll(Set.of(
                "ModInstalled", "ModVersion", "ServerVersion", "GameVersion",
                "AssetExists", "AssetMissing", "TargetExists", "TargetProvidedBy",
                "JsonPathExists", "JsonPathEquals", "All", "Any", "Not")));
        assertTrue(containsRef(condition, "common.json#/definitions/Alechilles.Patchwork.PatchCondition"));
    }

    @Test
    void conditionChoicesPreventKnownInvalidEmptyStates() {
        SchemaContext context = new SchemaContext();
        PatchDefinitionAsset.CODEC.toSchema(context);
        Schema condition = context.getDefinitions().get("Alechilles.Patchwork.PatchCondition");

        ObjectSchema conditionObject = (ObjectSchema) condition;
        BooleanSchema targetExists = (BooleanSchema) conditionObject.getProperties().get("TargetExists");
        assertEquals(Boolean.TRUE, targetExists.getDefault());

        Set<String> comparisonFields = Set.of("Equals", "AtLeast", "AtMost", "Above", "Below");
        for (String title : List.of("ModVersion", "ServerVersion", "GameVersion")) {
            ObjectSchema comparison = (ObjectSchema) conditionObject.getProperties().get(title);
            assertRequiredAlternatives(comparison, comparisonFields);
        }

        ObjectSchema jsonPathEquals = (ObjectSchema) conditionObject.getProperties().get("JsonPathEquals");
        assertRequiredAlternatives(jsonPathEquals, Set.of("Value", "Equals"));
    }

    @Test
    void conditionSourceUsesAnObjectSchemaThatTheAssetEditorCanOpen() {
        SchemaContext context = new SchemaContext();
        PatchDefinitionAsset.CODEC.toSchema(context);

        Schema condition = context.getDefinitions().get("Alechilles.Patchwork.PatchCondition");
        Schema source = ((ObjectSchema) condition).getProperties().get("JsonPathEquals");
        source = ((ObjectSchema) source).getProperties().get("Source");
        assertTrue(source instanceof ObjectSchema,
                "condition Source must serialize as an object so the Asset Editor can open its choices");
    }

    @Test
    void matcherUsesAnObjectSchemaThatTheAssetEditorCanOpen() {
        SchemaContext context = new SchemaContext();
        PatchOperationAsset.CODEC.toSchema(context);

        Schema matcher = context.getDefinitions().get("Alechilles.Patchwork.PatchMatcher");
        assertTrue(matcher instanceof ObjectSchema,
                "Match, Find, and Existing must serialize as objects so the Asset Editor can open their choices");
    }

    @Test
    void objectOnlyChoicesAreDirectEditorFieldsInsteadOfRawJsonUnions() {
        SchemaContext context = new SchemaContext();
        PatchDefinitionAsset.CODEC.toSchema(context);
        PatchOperationAsset.CODEC.toSchema(context);

        ObjectSchema condition = (ObjectSchema) context.getDefinitions().get("Alechilles.Patchwork.PatchCondition");
        assertNull(condition.getOneOf(), "When must expose direct editor fields, not a raw JSON oneOf");
        assertTrue(condition.getProperties().keySet().containsAll(Set.of(
                "ModInstalled", "ModVersion", "ServerVersion", "GameVersion", "AssetExists", "AssetMissing",
                "TargetExists", "TargetProvidedBy", "JsonPathExists", "JsonPathEquals", "All", "Any", "Not")));

        ObjectSchema jsonPathEquals = (ObjectSchema) condition.getProperties().get("JsonPathEquals");
        ObjectSchema source = (ObjectSchema) jsonPathEquals.getProperties().get("Source");
        assertNull(source.getOneOf(), "condition Source must expose direct editor fields, not a raw JSON oneOf");
        assertTrue(source.getProperties().keySet().containsAll(Set.of("Type", "Mod", "Path")));

        ObjectSchema matcher = (ObjectSchema) context.getDefinitions().get("Alechilles.Patchwork.PatchMatcher");
        assertNull(matcher.getOneOf(), "matchers must expose direct editor fields, not a raw JSON oneOf");
        assertTrue(matcher.getProperties().keySet().containsAll(Set.of("$Equals", "$Contains")));
    }

    @Test
    void matcherFieldsUseDocumentedRecursiveOperatorAndOrdinaryKeyChoices() {
        SchemaContext context = new SchemaContext();
        ObjectSchema operation = PatchOperationAsset.CODEC.toSchema(context);

        for (String field : List.of("Match", "Find", "Existing")) {
            assertTrue(containsRef(operation.getProperties().get(field),
                    "common.json#/definitions/Alechilles.Patchwork.PatchMatcher"), field);
        }
        Schema matcher = context.getDefinitions().get("Alechilles.Patchwork.PatchMatcher");
        assertNotNull(matcher);
        assertTrue(((ObjectSchema) matcher).getProperties().keySet().containsAll(Set.of("$Equals", "$Contains")));
        assertTrue(containsRef(matcher, "common.json#/definitions/Alechilles.Patchwork.PatchMatcher"));
        assertTrue(context.getDefinitions().containsKey("Alechilles.Patchwork.PatchMatcherValue"));
    }

    @Test
    void matchingMergeAndUpsertOperationsAreSelectableAndDocumented() {
        SchemaContext context = new SchemaContext();
        ObjectSchema operation = PatchOperationAsset.CODEC.toSchema(context);
        StringSchema choices = (StringSchema) operation.getProperties().get("Op");

        assertTrue(Arrays.asList(choices.getEnum()).contains("MergeMatching"));
        assertTrue(Arrays.asList(choices.getEnum()).contains("UpsertMatching"));
        assertNotNull(choices.getMarkdownEnumDescriptions());
        for (String description : choices.getMarkdownEnumDescriptions()) {
            assertTrue(description != null && !description.isBlank());
        }
        assertTrue(operation.getProperties().get("Path").getMarkdownDescription() != null);
        assertTrue(operation.getProperties().get("Match").getMarkdownDescription() != null);
        assertTrue(operation.getProperties().get("Value").getMarkdownDescription() != null);
        assertTrue(operation.getProperties().get("MatchPolicy").getMarkdownDescription() != null);
        assertTrue(operation.getProperties().get("Position").getMarkdownDescription() != null);
        assertTrue(operation.getProperties().get("Find").getMarkdownDescription() != null);
    }

    @Test
    void targetFieldsExplainExactAndExplicitGlobSelectors() {
        SchemaContext context = new SchemaContext();
        ObjectSchema definition = (ObjectSchema) PatchDefinitionAsset.CODEC.toSchema(context);
        for (String field : List.of("Target", "Targets")) {
            String description = definition.getProperties().get(field).getMarkdownDescription();
            assertTrue(description != null && description.contains("glob:"), field);
            assertTrue(description.contains("Server/Item/Items/Example.json"), field);
            assertTrue(description.contains("Server/NPC/**/*.json"), field);
        }
    }

    @Test
    void crossAssetOperationsAreSelectableAndDocumented() {
        SchemaContext context = new SchemaContext();
        ObjectSchema operation = PatchOperationAsset.CODEC.toSchema(context);
        StringSchema choices = (StringSchema) operation.getProperties().get("Op");

        assertTrue(Arrays.asList(choices.getEnum()).contains("OverlayFromAsset"));
        assertTrue(Arrays.asList(choices.getEnum()).contains("MergeObjectFromAsset"));
        assertTrue(Arrays.stream(choices.getMarkdownEnumDescriptions())
                .anyMatch(description -> description.contains("Overlay entire asset")));
        assertTrue(Arrays.stream(choices.getMarkdownEnumDescriptions())
                .anyMatch(description -> description.contains("Merge object from asset")));
        assertTrue(operation.getProperties().get("Source").getMarkdownDescription() != null);
        assertTrue(operation.getProperties().get("SourcePath").getMarkdownDescription() != null);
    }

    @Test
    void valueAndOptionsUseRecursiveJsonSchemasWithoutChangingPortableShape() {
        SchemaContext context = new SchemaContext();
        ObjectSchema operation = PatchOperationAsset.CODEC.toSchema(context);

        assertTrue(containsRef(operation.getProperties().get("Value"),
                "common.json#/definitions/Alechilles.Patchwork.PatchJsonValue"));
        assertTrue(containsRef(operation.getProperties().get("Options"),
                "common.json#/definitions/Alechilles.Patchwork.PatchJsonObject"));
        Schema value = context.getDefinitions().get("Alechilles.Patchwork.PatchJsonValue");
        assertNotNull(value);
        assertEquals(Set.of("Null", "Boolean", "Number", "String", "Array", "Object"),
                variantTitles(value));
        assertTrue(containsRef(value, "common.json#/definitions/Alechilles.Patchwork.PatchJsonValue"));
        assertTrue(containsRef(value, "common.json#/definitions/Alechilles.Patchwork.PatchJsonObject"));
        assertEveryChoiceDocumented(value);
    }

    @Test
    void recursiveDefinitionsSerializeAndDocumentEveryExplicitProperty() {
        SchemaContext context = new SchemaContext();
        PatchDefinitionAsset.CODEC.toSchema(context);
        PatchOperationAsset.CODEC.toSchema(context);

        Set<String> patchworkDefinitions = Set.of(
                "Alechilles.Patchwork.PatchCondition", "Alechilles.Patchwork.PatchMatcher",
                "Alechilles.Patchwork.PatchMatcherValue", "Alechilles.Patchwork.PatchJsonValue",
                "Alechilles.Patchwork.PatchJsonObject");
        assertTrue(context.getDefinitions().keySet().containsAll(patchworkDefinitions));
        patchworkDefinitions.forEach(name -> {
            Schema schema = context.getDefinitions().get(name);
            assertNotNull(Schema.CODEC.encode(schema, EmptyExtraInfo.EMPTY), name);
            assertExplicitPropertiesDocumented(schema);
        });
    }

    private static Set<String> variantTitles(Schema schema) {
        return Arrays.stream(schema.getOneOf())
                .map(Schema::getTitle)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void assertRequiredAlternatives(Schema schema, Set<String> expectedFields) {
        assertNotNull(schema.getAnyOf());
        assertEquals(expectedFields, Arrays.stream(schema.getAnyOf())
                .map(alternative -> alternative.getRequired()[0])
                .collect(Collectors.toUnmodifiableSet()));
    }

    private static void assertEveryChoiceDocumented(Schema schema) {
        for (Schema choice : schema.getOneOf()) {
            assertTrue(choice.getTitle() != null && !choice.getTitle().isBlank());
            String documentation = choice.getMarkdownDescription() != null
                    ? choice.getMarkdownDescription() : choice.getDescription();
            assertTrue(documentation != null && !documentation.isBlank(), choice.getTitle());
        }
    }

    private static boolean containsRef(Schema schema, String expected) {
        if (schema == null) return false;
        if (expected.equals(schema.getRef())) return true;
        if (containsRef(schema.getOneOf(), expected)
                || containsRef(schema.getAnyOf(), expected)
                || containsRef(schema.getAllOf(), expected)) {
            return true;
        }
        if (schema instanceof ObjectSchema object) {
            if (object.getProperties() != null
                    && containsRef(object.getProperties().values().toArray(Schema[]::new), expected)) {
                return true;
            }
            if (object.getAdditionalProperties() instanceof Schema additional
                    && containsRef(additional, expected)) {
                return true;
            }
        }
        if (schema instanceof ArraySchema array) {
            Object items = array.getItems();
            if (items instanceof Schema item && containsRef(item, expected)) return true;
            if (items instanceof Schema[] itemArray && containsRef(itemArray, expected)) return true;
        }
        return false;
    }

    private static void assertExplicitPropertiesDocumented(Schema schema) {
        if (schema == null || schema.getRef() != null) return;
        assertExplicitPropertiesDocumented(schema.getOneOf());
        assertExplicitPropertiesDocumented(schema.getAnyOf());
        assertExplicitPropertiesDocumented(schema.getAllOf());
        if (schema instanceof ObjectSchema object) {
            if (object.getProperties() != null) {
                object.getProperties().forEach((name, property) -> {
                    String documentation = property.getMarkdownDescription() != null
                            ? property.getMarkdownDescription() : property.getDescription();
                    assertTrue(documentation != null && !documentation.isBlank(),
                            name + " must have beginner-facing documentation");
                    assertExplicitPropertiesDocumented(property);
                });
            }
            if (object.getAdditionalProperties() instanceof Schema additional) {
                assertExplicitPropertiesDocumented(additional);
            }
        }
        if (schema instanceof ArraySchema array) {
            Object items = array.getItems();
            if (items instanceof Schema item) assertExplicitPropertiesDocumented(item);
            if (items instanceof Schema[] itemArray) assertExplicitPropertiesDocumented(itemArray);
        }
    }

    private static void assertExplicitPropertiesDocumented(Schema[] schemas) {
        if (schemas == null) return;
        Arrays.stream(schemas).forEach(PatchNativeAuthoringSchemaTest::assertExplicitPropertiesDocumented);
    }

    private static boolean containsRef(Schema[] schemas, String expected) {
        if (schemas == null) return false;
        return Arrays.stream(schemas).anyMatch(schema -> containsRef(schema, expected));
    }
}
