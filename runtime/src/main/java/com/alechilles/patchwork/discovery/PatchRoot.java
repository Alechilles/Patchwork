package com.alechilles.patchwork.discovery;

import java.util.List;
import java.util.Set;

/** Known patch roots and their compatibility activation rules. */
public enum PatchRoot {
    LEGACY("Server/Tamework/Patches", 0),
    NEUTRAL("Server/Patchwork/Patches", 1);

    /** Exact plugin ID that enables the legacy Tamework root. */
    public static final String TAMEWORK_PLUGIN_ID = "Alechilles:Alec's Tamework!";

    private final String path;
    private final int precedence;

    PatchRoot(String path, int precedence) {
        this.path = path;
        this.precedence = precedence;
    }

    /** Returns the slash-separated root path within an asset pack. */
    public String path() {
        return path;
    }

    /** Returns the root's precedence, with neutral patches higher than legacy patches. */
    public int precedence() {
        return precedence;
    }

    /** Returns active roots from lowest to highest precedence. */
    public static List<PatchRoot> activeRoots(Set<String> installedPluginIds) {
        return installedPluginIds.contains(TAMEWORK_PLUGIN_ID) ? List.of(LEGACY, NEUTRAL) : List.of(NEUTRAL);
    }
}
