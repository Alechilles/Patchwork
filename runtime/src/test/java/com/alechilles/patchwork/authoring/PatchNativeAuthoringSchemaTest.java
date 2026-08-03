package com.alechilles.patchwork.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import java.util.Arrays;
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

    private static boolean containsRef(Schema[] schemas, String expected) {
        if (schemas == null) return false;
        return Arrays.stream(schemas).anyMatch(schema -> containsRef(schema, expected));
    }
}
