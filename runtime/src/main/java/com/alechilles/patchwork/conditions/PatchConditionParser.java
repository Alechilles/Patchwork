package com.alechilles.patchwork.conditions;

import com.alechilles.patchwork.format.JsonPointer;
import com.alechilles.patchwork.format.PatchFormat;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

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
        return new PatchCondition.JsonPathExists(source(condition), pointer(condition, "JsonPathExists", formatVersion), formatVersion);
    }

    private PatchCondition jsonEquals(JsonObject condition, int formatVersion) {
        JsonElement expected = condition.has("Value") ? condition.get("Value") : condition.get("Equals");
        if (expected == null) throw bad("JsonPathEquals must define Value or Equals.");
        return new PatchCondition.JsonPathEquals(source(condition), pointer(condition, "JsonPathEquals", formatVersion), expected, formatVersion);
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

    private ConditionSource source(JsonObject condition) {
        if (condition.has("Source") && condition.has("Asset")) {
            throw bad("Source and legacy Asset are mutually exclusive.");
        }
        if (!condition.has("Source")) {
            String legacy = condition.has("Asset") ? field(condition, "JSON condition", "Asset") : "$Target";
            return "$Target".equals(legacy) ? new ConditionSource.Target() : new ConditionSource.Asset(legacy);
        }
        JsonObject source = object(condition.get("Source"), "Source");
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
        String normalized = text(value, name + ".Path");
        JsonPointer.tokens(normalized, formatVersion, false);
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
}
