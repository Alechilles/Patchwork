package com.alechilles.patchwork.authoring;

import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.NullSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** Registers recursive Patchwork schemas in common.json, which Hytale's Asset Editor transmits. */
final class PatchSchemaDefinitions {
    private static final String PREFIX = "Alechilles.Patchwork.";
    private static final Map<SchemaContext, Map<String, String>> ASSIGNED_NAMES = new WeakHashMap<>();

    private PatchSchemaDefinitions() {
    }

    static synchronized Schema ref(SchemaContext context, String name, Supplier<Schema> definition) {
        Map<String, String> names = ASSIGNED_NAMES.computeIfAbsent(context, ignored -> new HashMap<>());
        String definitionName = names.get(name);
        if (definitionName == null) {
            String baseName = PREFIX + name;
            definitionName = baseName;
            int collision = 1;
            while (context.getDefinitions().containsKey(definitionName)) {
                definitionName = baseName + "@" + collision++;
            }
            names.put(name, definitionName);
            context.getDefinitions().put(definitionName, NullSchema.INSTANCE);
            try {
                context.getDefinitions().put(definitionName, definition.get());
            } catch (RuntimeException | Error failure) {
                context.getDefinitions().remove(definitionName);
                names.remove(name);
                if (names.isEmpty()) ASSIGNED_NAMES.remove(context);
                throw failure;
            }
        }
        return Schema.ref("common.json#/definitions/" + definitionName);
    }
}
