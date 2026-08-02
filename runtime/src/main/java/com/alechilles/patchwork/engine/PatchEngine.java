package com.alechilles.patchwork.engine;

import com.alechilles.patchwork.format.JsonMatcher;
import com.alechilles.patchwork.format.JsonPointer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Applies Patchwork operations to a copied JSON asset without host-plugin dependencies. */
public final class PatchEngine {
    private final PatchMacroRegistry macroRegistry;

    /** Creates an engine with no registered host macros. */
    public PatchEngine() {
        this(new PatchMacroRegistry());
    }

    /** Creates an engine using the supplied host macro registry. */
    public PatchEngine(PatchMacroRegistry macroRegistry) {
        this.macroRegistry = macroRegistry;
    }

    /** Applies enabled definitions in deterministic order and returns patched JSON plus diagnostics. */
    public PatchResult apply(JsonObject source, List<PatchDefinition> definitions) {
        JsonObject working = source.deepCopy();
        List<String> applied = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        definitions.stream().filter(PatchDefinition::enabled).sorted(PatchDefinition.ORDERING).forEach(definition -> {
            for (PatchOperation operation : definition.operations()) {
                for (PatchOperation raw : macroRegistry.expand(operation)) {
                    apply(working, definition, raw, applied, skipped);
                }
            }
        });
        return new PatchResult(working, List.copyOf(applied), List.copyOf(skipped));
    }

    private static void apply(JsonObject root, PatchDefinition definition, PatchOperation operation,
                              List<String> applied, List<String> skipped) {
        String label = definition.id() + ":" + operation.id();
        try {
            String skip = raw(root, definition, operation);
            if (skip == null) applied.add(label);
            else skipped.add(label + " (" + skip + ")");
        } catch (RuntimeException ex) {
            String message = label + " failed: " + ex.getMessage();
            if (operation.required()) throw new PatchFailureException(message, ex);
            skipped.add(message);
        }
    }

    private static String raw(JsonObject root, PatchDefinition definition, PatchOperation operation) {
        return switch (operation.op().toLowerCase(Locale.ROOT)) {
            case "requireformat" -> {
                if (definition.formatVersion() == 2 && operation.formatVersion() == 2
                        && Integer.valueOf(definition.formatVersion()).equals(operation.version())) yield null;
                if (definition.formatVersion() != 2 || operation.formatVersion() != 2) {
                    throw new IllegalArgumentException("Unsupported operation '" + operation.op() + "'.");
                }
                throw new IllegalArgumentException("RequireFormat version does not match definition format version.");
            }
            case "add" -> {
                add(root, operation);
                yield null;
            }
            case "merge" -> {
                merge(root, operation);
                yield null;
            }
            case "replace" -> {
                replace(root, operation);
                yield null;
            }
            case "remove" -> {
                remove(root, operation);
                yield null;
            }
            case "insert" -> insert(root, operation);
            default -> throw new IllegalArgumentException("Unsupported operation '" + operation.op() + "'.");
        };
    }

    private static void add(JsonObject root, PatchOperation operation) {
        PathTarget target = parent(root, operation, true);
        JsonElement value = value(operation);
        if (target.parent().isJsonObject()) {
            target.parent().getAsJsonObject().add(target.leaf(), value);
        } else if (target.parent().isJsonArray()) {
            JsonArray array = target.parent().getAsJsonArray();
            insert(array, JsonPointer.arrayIndex(target.leaf(), array.size(), true, operation.formatVersion()), value);
        } else {
            throw new IllegalArgumentException("Add parent is not an object or array at " + operation.path() + ".");
        }
    }

    private static void merge(JsonObject root, PatchOperation operation) {
        JsonElement value = value(operation);
        if (!value.isJsonObject()) throw new IllegalArgumentException("Merge value must be an object.");
        JsonElement target = resolve(root, operation);
        if (target == null || !target.isJsonObject()) {
            throw new IllegalArgumentException("Merge target must exist and be an object at " + operation.path() + ".");
        }
        merge(target.getAsJsonObject(), value.getAsJsonObject());
    }

    private static void replace(JsonObject root, PatchOperation operation) {
        PathTarget target = parent(root, operation, false);
        JsonElement value = value(operation);
        if (target.parent().isJsonObject()) {
            target.parent().getAsJsonObject().add(target.leaf(), value);
            return;
        }
        if (target.parent().isJsonArray()) {
            JsonArray array = target.parent().getAsJsonArray();
            array.set(JsonPointer.arrayIndex(target.leaf(), array.size(), false, operation.formatVersion()), value);
            return;
        }
        throw new IllegalArgumentException("Replace parent is not an object or array at " + operation.path() + ".");
    }

    private static void remove(JsonObject root, PatchOperation operation) {
        PathTarget target = parent(root, operation, false);
        if (target.parent().isJsonObject()) {
            if (target.parent().getAsJsonObject().remove(target.leaf()) == null) {
                throw new IllegalArgumentException("Remove target does not exist at " + operation.path() + ".");
            }
            return;
        }
        if (target.parent().isJsonArray()) {
            JsonArray array = target.parent().getAsJsonArray();
            array.remove(JsonPointer.arrayIndex(target.leaf(), array.size(), false, operation.formatVersion()));
            return;
        }
        throw new IllegalArgumentException("Remove parent is not an object or array at " + operation.path() + ".");
    }

    private static String insert(JsonObject root, PatchOperation operation) {
        JsonElement target = resolve(root, operation);
        if (target == null || !target.isJsonArray()) {
            throw new IllegalArgumentException("Insert target must be an array at " + operation.path() + ".");
        }
        JsonArray array = target.getAsJsonArray();
        if (operation.formatVersion() == 2) {
            if (operation.existing() != null) JsonMatcher.validateV2(operation.existing());
            if (operation.find() != null) JsonMatcher.validateV2(operation.find());
        }
        if (operation.existing() != null && find(array, operation.existing(), operation.formatVersion()) >= 0) {
            return "existing matcher already present";
        }
        String position = operation.position() == null ? "End" : operation.position();
        int index = switch (position.toLowerCase(Locale.ROOT)) {
            case "start" -> 0;
            case "end" -> array.size();
            case "before" -> anchor(array, operation, false);
            case "after" -> anchor(array, operation, true);
            default -> throw new IllegalArgumentException("Unsupported insert position '" + position + "'.");
        };
        insert(array, index, value(operation));
        return null;
    }

    private static int anchor(JsonArray array, PatchOperation operation, boolean after) {
        if (operation.find() == null) throw new IllegalArgumentException("Insert " + operation.position() + " requires Find.");
        int index = find(array, operation.find(), operation.formatVersion());
        if (index < 0) throw new IllegalArgumentException("Insert anchor not found for " + operation.id() + ".");
        return after ? index + 1 : index;
    }

    /** Legacy matcher entry point retained for package compatibility. */
    static boolean matches(JsonElement candidate, JsonObject matcher) {
        return JsonMatcher.matches(candidate, matcher, 1);
    }

    /** Format-aware matcher entry point used by operations. */
    static boolean matches(JsonElement candidate, JsonObject matcher, int formatVersion) {
        return JsonMatcher.matches(candidate, matcher, formatVersion);
    }

    private static int find(JsonArray array, JsonObject matcher, int formatVersion) {
        for (int index = 0; index < array.size(); index++) {
            if (matches(array.get(index), matcher, formatVersion)) return index;
        }
        return -1;
    }

    private static void merge(JsonObject target, JsonObject value) {
        for (Map.Entry<String, JsonElement> entry : value.entrySet()) {
            JsonElement existing = target.get(entry.getKey());
            JsonElement incoming = entry.getValue();
            if (existing != null && existing.isJsonObject() && incoming != null && incoming.isJsonObject()) {
                merge(existing.getAsJsonObject(), incoming.getAsJsonObject());
            } else {
                target.add(entry.getKey(), incoming == null ? null : incoming.deepCopy());
            }
        }
    }

    private static JsonElement resolve(JsonElement root, PatchOperation operation) {
        JsonElement current = root;
        for (String token : JsonPointer.tokens(path(operation), operation.formatVersion(), true)) {
            if (current == null) return null;
            if (current.isJsonObject()) {
                current = current.getAsJsonObject().get(token);
            } else if (current.isJsonArray()) {
                JsonArray array = current.getAsJsonArray();
                current = array.get(JsonPointer.arrayIndex(token, array.size(), false, operation.formatVersion()));
            } else {
                return null;
            }
        }
        return current;
    }

    private static PathTarget parent(JsonObject root, PatchOperation operation, boolean allowMissingLeaf) {
        List<String> tokens = JsonPointer.tokens(path(operation), operation.formatVersion(), true);
        if (tokens.isEmpty()) throw new IllegalArgumentException("Path must not point to the document root.");
        JsonElement current = root;
        for (int index = 0; index < tokens.size() - 1; index++) {
            String token = tokens.get(index);
            if (current.isJsonObject()) {
                current = current.getAsJsonObject().get(token);
            } else if (current.isJsonArray()) {
                JsonArray array = current.getAsJsonArray();
                current = array.get(JsonPointer.arrayIndex(token, array.size(), false, operation.formatVersion()));
            } else {
                throw new IllegalArgumentException("Path parent is not traversable at " + token + ".");
            }
            if (current == null) throw new IllegalArgumentException("Path parent does not exist at " + token + ".");
        }
        String leaf = tokens.getLast();
        if (!allowMissingLeaf && current.isJsonObject() && !current.getAsJsonObject().has(leaf)) {
            throw new IllegalArgumentException("Path leaf does not exist at " + leaf + ".");
        }
        return new PathTarget(current, leaf);
    }

    private static void insert(JsonArray array, int index, JsonElement value) {
        JsonArray rebuilt = new JsonArray();
        for (int i = 0; i < array.size(); i++) {
            if (i == index) rebuilt.add(value);
            rebuilt.add(array.get(i));
        }
        if (index == array.size()) rebuilt.add(value);
        while (!array.isEmpty()) array.remove(0);
        for (JsonElement element : rebuilt) array.add(element);
    }

    private static String path(PatchOperation operation) {
        if (operation.path() == null || operation.path().isBlank()) {
            throw new IllegalArgumentException("Operation " + operation.id() + " requires Path.");
        }
        return operation.path();
    }

    private static JsonElement value(PatchOperation operation) {
        JsonElement value = operation.value();
        if (value == null) throw new IllegalArgumentException("Operation " + operation.id() + " requires Value.");
        return value;
    }

    /** Patched JSON plus operation diagnostics from a patch run. */
    public record PatchResult(JsonObject patched, List<String> applied, List<String> skipped) { }

    private record PathTarget(JsonElement parent, String leaf) { }

    /** Indicates failure of a required patch operation. */
    public static final class PatchFailureException extends RuntimeException {
        public PatchFailureException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
