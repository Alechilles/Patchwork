package com.alechilles.patchwork.discovery;

import com.alechilles.patchwork.format.PatchFormat;
import com.alechilles.patchwork.format.PatchLanguage;
import com.google.gson.JsonObject;
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

    /**
     * Selects the grammar profile for one definition read from this root.
     *
     * <p>The neutral root is the authoring default.  A neutral definition with
     * no compatibility marker therefore uses the closed, sentinel-free neutral
     * grammar, while an explicit marker keeps its historical meaning.  The
     * legacy root always follows the explicit/legacy marker rules.</p>
     */
    public PatchLanguage languageFor(JsonObject definitionRoot) {
        if (this == NEUTRAL && !definitionRoot.has("FormatVersion")) {
            return PatchLanguage.NEUTRAL;
        }
        return PatchFormat.fromRoot(definitionRoot).language();
    }

    /** Returns active roots from lowest to highest precedence. */
    public static List<PatchRoot> activeRoots(Set<String> installedPluginIds) {
        return installedPluginIds.contains(TAMEWORK_PLUGIN_ID) ? List.of(LEGACY, NEUTRAL) : List.of(NEUTRAL);
    }
}
