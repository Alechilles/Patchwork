package com.alechilles.patchwork.conditions;

import com.alechilles.patchwork.format.JsonPointer;
import com.alechilles.patchwork.format.PatchFormat;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Parses the stable Patchwork condition JSON grammar into immutable condition trees. */
public final class PatchConditionParser {
    /** Reads an optional {@code When} field, deriving the format from the enclosing definition root. */
    public PatchCondition parseOptional(JsonObject patchRoot) {
        return parseOptional(patchRoot, PatchFormat.fromRoot(patchRoot).version());
    }

    /** Reads an optional {@code When} field with an explicit enclosing format version. */
    public PatchCondition parseOptional(JsonObject patchRoot, int formatVersion) {
        JsonElement when = patchRoot.get("When");
        return when == null || when.isJsonNull() ? new PatchCondition.Always() : parse(object(when, "When"), formatVersion);
    }

    /** Parses exactly one condition key using legacy format-1 semantics. */
    public PatchCondition parse(JsonObject object) {
        return parse(object, PatchFormat.LEGACY_VERSION);
    }

    /** Parses exactly one condition key using the enclosing definition format. */
    public PatchCondition parse(JsonObject object, int formatVersion) {
        if (PatchFormat.isVersion2(formatVersion)) {
            validateV2Shape(object);
        }
        String key = null;
        JsonElement value = null;
        for (var entry : object.entrySet()) {
            if ("$Comment".equals(entry.getKey())) continue;
            if (key != null) throw bad("Condition object must define exactly one condition key.");
            key = entry.getKey();
            value = entry.getValue();
        }
        if (key == null) throw bad("Condition object must define exactly one condition key.");
        return switch (key) {
            case "ModInstalled" -> new PatchCondition.ModInstalled(text(value, key));
            case "AssetExists" -> new PatchCondition.AssetExists(asset(value, key));
            case "AssetMissing" -> new PatchCondition.AssetMissing(asset(value, key));
            case "TargetExists" -> {
                if (!value.isJsonPrimitive() || !value.getAsBoolean()) throw bad("TargetExists must be true.");
                yield new PatchCondition.TargetExists();
            }
            case "TargetProvidedBy" -> {
                String provider = text(value, key);
                if (PatchFormat.isVersion2(formatVersion) && provider.isBlank()) {
                    throw bad(key + " must be a non-empty string.");
                }
                yield new PatchCondition.TargetProvidedBy(provider);
            }
            case "ModVersion" -> {
                JsonObject c = object(value, key);
                yield new PatchCondition.ModVersion(field(c, key, "Mod"), matcher(c, key));
            }
            case "GameVersion", "ServerVersion" -> new PatchCondition.ServerVersion(matcher(object(value, key), key));
            case "JsonPathExists" -> jsonExists(object(value, key), formatVersion);
            case "JsonPathEquals" -> jsonEquals(object(value, key), formatVersion);
            case "All" -> composite(value, true, formatVersion);
            case "Any" -> composite(value, false, formatVersion);
            case "Not" -> new PatchCondition.Not(parse(object(value, "Not"), formatVersion));
            case "TameworkSetting" -> throw bad("TameworkSetting is retired; migrate it to JsonPathEquals with a ModData Source.");
            default -> throw bad("Unsupported condition key: " + key);
        };
    }

    private PatchCondition jsonExists(JsonObject condition, int formatVersion) {
        return new PatchCondition.JsonPathExists(source(condition, formatVersion), pointer(condition, "JsonPathExists", formatVersion), formatVersion);
    }

    private PatchCondition jsonEquals(JsonObject condition, int formatVersion) {
        JsonElement expected = condition.has("Value") ? condition.get("Value") : condition.get("Equals");
        if (expected == null) throw bad("JsonPathEquals must define Value or Equals.");
        return new PatchCondition.JsonPathEquals(source(condition, formatVersion), pointer(condition, "JsonPathEquals", formatVersion), expected, formatVersion);
    }

    private PatchCondition composite(JsonElement value, boolean all, int formatVersion) {
        if (!value.isJsonArray() || value.getAsJsonArray().isEmpty()) {
            throw bad((all ? "All" : "Any") + " must be a non-empty array.");
        }
        List<PatchCondition> children = new ArrayList<>();
        for (JsonElement child : value.getAsJsonArray()) {
            children.add(parse(object(child, "condition"), formatVersion));
        }
        return all ? new PatchCondition.All(children) : new PatchCondition.Any(children);
    }

    private ConditionSource source(JsonObject condition, int formatVersion) {
        if (condition.has("Source") && condition.has("Asset")) {
            throw bad("Source and legacy Asset are mutually exclusive.");
        }
        if (!condition.has("Source")) {
            String legacy = condition.has("Asset") ? field(condition, "JSON condition", "Asset") : "$Target";
            return "$Target".equals(legacy) ? new ConditionSource.Target() : new ConditionSource.Asset(legacy);
        }
        JsonObject source = object(condition.get("Source"), "Source");
        if (PatchFormat.isVersion2(formatVersion)) {
            validateV2Source(source);
        }
        String type = field(source, "Source", "Type");
        return switch (type) {
            case "Target" -> {
                if (source.size() != 1) throw bad("Target Source may only define Type.");
                yield new ConditionSource.Target();
            }
            case "Asset" -> new ConditionSource.Asset(field(source, "Source", "Path"));
            case "ModData" -> new ConditionSource.ModData(field(source, "Source", "Mod"), field(source, "Source", "Path"));
            default -> throw bad("Unsupported Source.Type: " + type);
        };
    }

    private PatchCondition.VersionMatcher matcher(JsonObject condition, String name) {
        String equals = version(condition, name, "Equals");
        String atLeast = version(condition, name, "AtLeast");
        String atMost = version(condition, name, "AtMost");
        String above = version(condition, name, "Above");
        String below = version(condition, name, "Below");
        if (equals == null && atLeast == null && atMost == null && above == null && below == null) {
            throw bad(name + " must define a version matcher.");
        }
        return new PatchCondition.VersionMatcher(equals, atLeast, atMost, above, below);
    }

    private String version(JsonObject condition, String name, String field) {
        return !condition.has(field) || condition.get(field).isJsonNull()
                ? null : dotted(field(condition, name, field), name + "." + field);
    }

    private static String dotted(String value, String name) {
        if (!value.matches("\\d+(\\.\\d+)*")) throw bad(name + " must be an exact dotted numeric version.");
        return value;
    }

    private static String asset(JsonElement value, String name) {
        if (value.isJsonPrimitive()) return text(value, name);
        return field(object(value, name), name, "Asset");
    }

    private static JsonObject object(JsonElement value, String name) {
        if (value == null || !value.isJsonObject()) throw bad(name + " must be an object.");
        return value.getAsJsonObject();
    }

    private static String field(JsonObject object, String name, String field) {
        return text(object.get(field), name + "." + field);
    }

    private static String pointer(JsonObject condition, String name, int formatVersion) {
        JsonElement value = condition.get("Path");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw bad(name + ".Path must be a string.");
        }
        String path = value.getAsString();
        if (path.isEmpty()) {
            JsonPointer.tokens(path, formatVersion, false);
            return "";
        }
        if (PatchFormat.isVersion2(formatVersion)) {
            JsonPointer.tokens(path, formatVersion, false);
            return path;
        }
        String normalized = text(value, name + ".Path");
        return normalized;
    }

    private static String text(JsonElement value, String name) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().trim().isEmpty()) {
            throw bad(name + " must be a non-empty string.");
        }
        return value.getAsString().trim();
    }

    private static IllegalArgumentException bad(String message) {
        return new IllegalArgumentException(message);
    }

    private static void validateV2Shape(JsonObject condition) {
        if (condition == null) throw bad("Condition must be an object.");
        String key = null;
        for (var entry : condition.entrySet()) {
            if ("$Comment".equals(entry.getKey())) {
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                    throw bad("$Comment must be a string.");
                }
                continue;
            }
            if (key != null) throw bad("Condition object must define exactly one condition key.");
            key = entry.getKey();
        }
        if (key == null) throw bad("Condition object must define exactly one condition key.");
        JsonElement value = condition.get(key);
        switch (key) {
            case "AssetExists", "AssetMissing" -> validateAssetShape(value, key);
            case "ModVersion" -> validateKeys(object(value, key), Set.of("Mod", "Equals", "AtLeast", "AtMost", "Above", "Below"), key);
            case "GameVersion", "ServerVersion" -> validateKeys(object(value, key),
                    Set.of("Equals", "AtLeast", "AtMost", "Above", "Below"), key);
            case "JsonPathExists" -> validateJsonShape(object(value, key), key, false);
            case "JsonPathEquals" -> validateJsonShape(object(value, key), key, true);
            case "Source" -> throw bad("Unsupported condition key: Source");
            default -> {
                // Scalar conditions and composite conditions have no nested descriptor
                // fields to close here; their existing parsing validates their values and
                // recursively validates child condition objects.
            }
        }
    }

    private static void validateAssetShape(JsonElement value, String name) {
        if (value != null && value.isJsonObject()) {
            validateKeys(value.getAsJsonObject(), Set.of("Asset"), name);
        }
    }

    private static void validateJsonShape(JsonObject condition, String name, boolean equals) {
        Set<String> allowed = equals
                ? Set.of("Path", "Asset", "Source", "Value", "Equals")
                : Set.of("Path", "Asset", "Source");
        validateKeys(condition, allowed, name);
        if (condition.has("Source")) validateV2Source(object(condition.get("Source"), "Source"));
    }

    private static void validateV2Source(JsonObject source) {
        String type = field(source, "Source", "Type");
        Set<String> allowed = switch (type) {
            case "Target" -> Set.of("Type");
            case "Asset" -> Set.of("Type", "Path");
            case "ModData" -> Set.of("Type", "Mod", "Path");
            default -> Set.of("Type");
        };
        validateKeys(source, allowed, "Source");
    }

    private static void validateKeys(JsonObject object, Set<String> allowed, String name) {
        for (String field : object.keySet()) {
            if (!allowed.contains(field)) throw bad(name + " contains unknown field: " + field + ".");
        }
    }
}
