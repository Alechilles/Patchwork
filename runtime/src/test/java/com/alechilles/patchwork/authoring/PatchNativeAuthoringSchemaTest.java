package com.alechilles.patchwork.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.BooleanSchema;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class PatchNativeAuthoringSchemaTest {
    @Test
    void whenUsesDocumentedNamedRecursiveConditionChoices() {
        SchemaContext context = new SchemaContext();
        ObjectSchema definition = (ObjectSchema) PatchDefinitionAsset.CODEC.toSchema(context);

        Schema when = definition.getProperties().get("When");
        assertTrue(containsRef(when, "other.json#/definitions/PatchCondition"));
        Schema condition = context.getOtherDefinitions().get("PatchCondition");
        assertNotNull(condition);
        assertEquals(Set.of(
                        "ModInstalled", "ModVersion", "ServerVersion", "GameVersion",
                        "AssetExists", "AssetMissing", "TargetExists", "TargetProvidedBy",
                        "JsonPathExists", "JsonPathEquals", "All", "Any", "Not"),
                variantTitles(condition));
        assertTrue(containsRef(condition, "other.json#/definitions/PatchCondition"));
        assertEveryChoiceDocumented(condition);
    }

    @Test
    void conditionChoicesPreventKnownInvalidEmptyStates() {
        SchemaContext context = new SchemaContext();
        PatchDefinitionAsset.CODEC.toSchema(context);
        Schema condition = context.getOtherDefinitions().get("PatchCondition");

        BooleanSchema targetExists = (BooleanSchema) variant(condition, "TargetExists")
                .getProperties().get("TargetExists");
        assertEquals(Boolean.TRUE, targetExists.getDefault());

        Set<String> comparisonFields = Set.of("Equals", "AtLeast", "AtMost", "Above", "Below");
        for (String title : List.of("ModVersion", "ServerVersion", "GameVersion")) {
            ObjectSchema comparison = (ObjectSchema) variant(condition, title).getProperties().get(title);
            assertRequiredAlternatives(comparison, comparisonFields);
        }

        ObjectSchema jsonPathEquals = (ObjectSchema) variant(condition, "JsonPathEquals")
                .getProperties().get("JsonPathEquals");
        assertRequiredAlternatives(jsonPathEquals, Set.of("Value", "Equals"));
    }

    @Test
    void matcherFieldsUseDocumentedRecursiveOperatorAndOrdinaryKeyChoices() {
        SchemaContext context = new SchemaContext();
        ObjectSchema operation = PatchOperationAsset.CODEC.toSchema(context);

        for (String field : List.of("Match", "Find", "Existing")) {
            assertTrue(containsRef(operation.getProperties().get(field),
                    "other.json#/definitions/PatchMatcher"), field);
        }
        Schema matcher = context.getOtherDefinitions().get("PatchMatcher");
        assertNotNull(matcher);
        assertEquals(Set.of("Exact value", "Contains", "Object fields"), variantTitles(matcher));
        assertTrue(containsProperty(matcher, "$Equals"));
        assertTrue(containsProperty(matcher, "$Contains"));
        assertTrue(containsRef(matcher, "other.json#/definitions/PatchMatcher"));
        assertTrue(context.getOtherDefinitions().containsKey("PatchMatcherValue"));
        assertEveryChoiceDocumented(matcher);
    }

    @Test
    void valueAndOptionsUseRecursiveJsonSchemasWithoutChangingPortableShape() {
        SchemaContext context = new SchemaContext();
        ObjectSchema operation = PatchOperationAsset.CODEC.toSchema(context);

        assertTrue(containsRef(operation.getProperties().get("Value"),
                "other.json#/definitions/PatchJsonValue"));
        assertTrue(containsRef(operation.getProperties().get("Options"),
                "other.json#/definitions/PatchJsonObject"));
        Schema value = context.getOtherDefinitions().get("PatchJsonValue");
        assertNotNull(value);
        assertEquals(Set.of("Null", "Boolean", "Number", "String", "Array", "Object"),
                variantTitles(value));
        assertTrue(containsRef(value, "other.json#/definitions/PatchJsonValue"));
        assertTrue(containsRef(value, "other.json#/definitions/PatchJsonObject"));
        assertEveryChoiceDocumented(value);
    }

    @Test
    void namedRecursiveDefinitionsSerializeAndDocumentEveryExplicitProperty() {
        SchemaContext context = new SchemaContext();
        PatchDefinitionAsset.CODEC.toSchema(context);
        PatchOperationAsset.CODEC.toSchema(context);

        assertEquals(Set.of(
                        "PatchCondition", "PatchMatcher", "PatchMatcherValue",
                        "PatchJsonValue", "PatchJsonObject"),
                context.getOtherDefinitions().keySet());
        context.getOtherDefinitions().forEach((name, schema) -> {
            assertNotNull(Schema.CODEC.encode(schema, EmptyExtraInfo.EMPTY), name);
            assertExplicitPropertiesDocumented(schema);
        });
    }

    private static Set<String> variantTitles(Schema schema) {
        return Arrays.stream(schema.getOneOf())
                .map(Schema::getTitle)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static ObjectSchema variant(Schema schema, String title) {
        return (ObjectSchema) Arrays.stream(schema.getOneOf())
                .filter(choice -> title.equals(choice.getTitle()))
                .findFirst()
                .orElseThrow();
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

    private static boolean containsProperty(Schema schema, String expected) {
        if (schema == null) return false;
        if (schema instanceof ObjectSchema object
                && object.getProperties() != null
                && object.getProperties().containsKey(expected)) {
            return true;
        }
        if (containsProperty(schema.getOneOf(), expected)
                || containsProperty(schema.getAnyOf(), expected)
                || containsProperty(schema.getAllOf(), expected)) {
            return true;
        }
        if (schema instanceof ObjectSchema object && object.getProperties() != null) {
            return object.getProperties().values().stream()
                    .anyMatch(property -> containsProperty(property, expected));
        }
        return false;
    }

    private static boolean containsProperty(Schema[] schemas, String expected) {
        if (schemas == null) return false;
        return Arrays.stream(schemas).anyMatch(schema -> containsProperty(schema, expected));
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
