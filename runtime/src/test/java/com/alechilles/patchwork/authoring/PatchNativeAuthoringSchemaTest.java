package com.alechilles.patchwork.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
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

    private static Set<String> variantTitles(Schema schema) {
        return Arrays.stream(schema.getOneOf())
                .map(Schema::getTitle)
                .collect(Collectors.toUnmodifiableSet());
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

    private static boolean containsRef(Schema[] schemas, String expected) {
        if (schemas == null) return false;
        return Arrays.stream(schemas).anyMatch(schema -> containsRef(schema, expected));
    }
}
