package com.alechilles.patchwork.conditions;

import java.util.Objects;

/** Immutable location from which a JSON condition obtains one document snapshot. */
public sealed interface ConditionSource permits ConditionSource.Target, ConditionSource.Asset, ConditionSource.ModData {
    /** Uses the bytes supplied for the target currently being patched. */
    record Target() implements ConditionSource { }
    /** Uses a resolved game asset path. */
    record Asset(String path) implements ConditionSource { public Asset { path = text(path, "asset path"); } }
    /** Uses a loaded plugin's private data directory. */
    record ModData(String modId, String path) implements ConditionSource {
        public ModData { modId = text(modId, "mod ID"); path = text(path, "ModData path"); }
    }
    private static String text(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isEmpty()) throw new IllegalArgumentException(name + " must not be blank.");
        return value;
    }
}
