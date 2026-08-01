package com.alechilles.patchwork.conditions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Parses the stable Patchwork condition JSON grammar into immutable condition trees. */
public final class PatchConditionParser {
    /** Reads an optional {@code When} field, returning an unconditional condition when absent. */
    public PatchCondition parseOptional(JsonObject patchRoot) { JsonElement when = patchRoot.get("When"); return when == null || when.isJsonNull() ? new PatchCondition.Always() : parse(object(when, "When")); }
    /** Parses exactly one condition key, ignoring {@code $Comment}. */
    public PatchCondition parse(JsonObject object) {
        String key = null; JsonElement value = null;
        for (var entry : object.entrySet()) { if ("$Comment".equals(entry.getKey())) continue; if (key != null) throw bad("Condition object must define exactly one condition key."); key = entry.getKey(); value = entry.getValue(); }
        if (key == null) throw bad("Condition object must define exactly one condition key.");
        return switch (key) {
            case "ModInstalled" -> new PatchCondition.ModInstalled(text(value, key));
            case "AssetExists" -> new PatchCondition.AssetExists(asset(value, key));
            case "AssetMissing" -> new PatchCondition.AssetMissing(asset(value, key));
            case "TargetExists" -> { if (!value.isJsonPrimitive() || !value.getAsBoolean()) throw bad("TargetExists must be true."); yield new PatchCondition.TargetExists(); }
            case "ModVersion" -> { JsonObject c = object(value, key); yield new PatchCondition.ModVersion(field(c, key, "Mod"), matcher(c, key)); }
            case "GameVersion", "ServerVersion" -> new PatchCondition.ServerVersion(matcher(object(value, key), key));
            case "JsonPathExists" -> jsonExists(object(value, key));
            case "JsonPathEquals" -> jsonEquals(object(value, key));
            case "All" -> composite(value, true); case "Any" -> composite(value, false);
            case "Not" -> new PatchCondition.Not(parse(object(value, "Not")));
            case "TameworkSetting" -> throw bad("TameworkSetting is retired; migrate it to JsonPathEquals with a ModData Source.");
            default -> throw bad("Unsupported condition key: " + key);
        };
    }
    private PatchCondition jsonExists(JsonObject c) { return new PatchCondition.JsonPathExists(source(c), field(c, "JsonPathExists", "Path")); }
    private PatchCondition jsonEquals(JsonObject c) { JsonElement expected = c.has("Value") ? c.get("Value") : c.get("Equals"); if (expected == null) throw bad("JsonPathEquals must define Value or Equals."); return new PatchCondition.JsonPathEquals(source(c), field(c, "JsonPathEquals", "Path"), expected); }
    private PatchCondition composite(JsonElement value, boolean all) { if (!value.isJsonArray() || value.getAsJsonArray().isEmpty()) throw bad((all ? "All" : "Any") + " must be a non-empty array."); List<PatchCondition> children = new ArrayList<>(); for (JsonElement child : value.getAsJsonArray()) children.add(parse(object(child, "condition"))); return all ? new PatchCondition.All(children) : new PatchCondition.Any(children); }
    private ConditionSource source(JsonObject c) { if (c.has("Source") && c.has("Asset")) throw bad("Source and legacy Asset are mutually exclusive."); if (!c.has("Source")) { String legacy = c.has("Asset") ? field(c, "JSON condition", "Asset") : "$Target"; return "$Target".equals(legacy) ? new ConditionSource.Target() : new ConditionSource.Asset(legacy); } JsonObject s = object(c.get("Source"), "Source"); String type = field(s, "Source", "Type"); return switch (type) { case "Target" -> { if (s.size() != 1) throw bad("Target Source may only define Type."); yield new ConditionSource.Target(); } case "Asset" -> new ConditionSource.Asset(field(s, "Source", "Path")); case "ModData" -> new ConditionSource.ModData(field(s, "Source", "Mod"), field(s, "Source", "Path")); default -> throw bad("Unsupported Source.Type: " + type); }; }
    private PatchCondition.VersionMatcher matcher(JsonObject c, String name) { String e = version(c, name, "Equals"), a = version(c, name, "AtLeast"), m = version(c, name, "AtMost"), b = version(c, name, "Above"), l = version(c, name, "Below"); if (e == null && a == null && m == null && b == null && l == null) throw bad(name + " must define a version matcher."); return new PatchCondition.VersionMatcher(e, a, m, b, l); }
    private String version(JsonObject c, String n, String f) { return !c.has(f) || c.get(f).isJsonNull() ? null : dotted(field(c, n, f), n + "." + f); }
    private static String dotted(String value, String name) { if (!value.matches("\\d+(\\.\\d+)*")) throw bad(name + " must be an exact dotted numeric version."); return value; }
    private static String asset(JsonElement e, String n) { if (e.isJsonPrimitive()) return text(e, n); return field(object(e, n), n, "Asset"); }
    private static JsonObject object(JsonElement value, String name) { if (value == null || !value.isJsonObject()) throw bad(name + " must be an object."); return value.getAsJsonObject(); }
    private static String field(JsonObject o, String n, String f) { return text(o.get(f), n + "." + f); }
    private static String text(JsonElement e, String n) { if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().trim().isEmpty()) throw bad(n + " must be a non-empty string."); return e.getAsString().trim(); }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
