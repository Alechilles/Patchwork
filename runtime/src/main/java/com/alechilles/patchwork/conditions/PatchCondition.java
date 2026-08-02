package com.alechilles.patchwork.conditions;

import com.alechilles.patchwork.format.PatchFormat;
import com.google.gson.JsonElement;
import java.util.List;
import java.util.Objects;

/** Immutable parsed condition tree; JSON compatibility is owned by {@link PatchConditionParser}. */
public sealed interface PatchCondition permits PatchCondition.Always, PatchCondition.ModInstalled, PatchCondition.AssetExists,
        PatchCondition.AssetMissing, PatchCondition.TargetExists, PatchCondition.ModVersion, PatchCondition.ServerVersion,
        PatchCondition.JsonPathExists, PatchCondition.JsonPathEquals, PatchCondition.All, PatchCondition.Any, PatchCondition.Not {
    record Always() implements PatchCondition { }
    record ModInstalled(String modId) implements PatchCondition { public ModInstalled { modId = required(modId); } }
    record AssetExists(String path) implements PatchCondition { public AssetExists { path = required(path); } }
    record AssetMissing(String path) implements PatchCondition { public AssetMissing { path = required(path); } }
    record TargetExists() implements PatchCondition { }
    record ModVersion(String modId, VersionMatcher matcher) implements PatchCondition { public ModVersion { modId = required(modId); matcher = Objects.requireNonNull(matcher); } }
    record ServerVersion(VersionMatcher matcher) implements PatchCondition { public ServerVersion { matcher = Objects.requireNonNull(matcher); } }
    record JsonPathExists(ConditionSource source, String path, int formatVersion) implements PatchCondition {
        public JsonPathExists(ConditionSource source, String path) { this(source, path, PatchFormat.LEGACY_VERSION); }
        public JsonPathExists(ConditionSource source, int formatVersion, String path) { this(source, path, formatVersion); }
        public JsonPathExists {
            source = Objects.requireNonNull(source);
            path = pointer(path);
            formatVersion = format(formatVersion);
        }
    }
    record JsonPathEquals(ConditionSource source, String path, JsonElement expected, int formatVersion) implements PatchCondition {
        public JsonPathEquals(ConditionSource source, String path, JsonElement expected) {
            this(source, path, expected, PatchFormat.LEGACY_VERSION);
        }
        public JsonPathEquals(ConditionSource source, String path, int formatVersion, JsonElement expected) {
            this(source, path, expected, formatVersion);
        }
        public JsonPathEquals {
            source = Objects.requireNonNull(source);
            path = pointer(path);
            expected = Objects.requireNonNull(expected).deepCopy();
            formatVersion = format(formatVersion);
        }
        @Override public JsonElement expected() { return expected.deepCopy(); }
    }
    record All(List<PatchCondition> children) implements PatchCondition { public All { children = List.copyOf(children); } }
    record Any(List<PatchCondition> children) implements PatchCondition { public Any { children = List.copyOf(children); } }
    record Not(PatchCondition child) implements PatchCondition { public Not { child = Objects.requireNonNull(child); } }
    record VersionMatcher(String equals, String atLeast, String atMost, String above, String below) { }
    private static String required(String value) { if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Condition text must not be blank."); return value.trim(); }
    private static String pointer(String value) { if (value == null) throw new IllegalArgumentException("JSON pointer must not be null."); return value.isEmpty() ? "" : required(value); }
    private static int format(int version) {
        if (version != PatchFormat.LEGACY_VERSION && version != PatchFormat.FORMAT_VERSION_2) {
            throw new IllegalArgumentException("Unsupported Patchwork format version: " + version + ".");
        }
        return version;
    }
}
