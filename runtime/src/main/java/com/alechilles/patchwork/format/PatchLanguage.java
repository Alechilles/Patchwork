package com.alechilles.patchwork.format;

/**
 * Grammar profile selected for one Patchwork definition root.
 *
 * <p>The compatibility version is retained for embedders that still expose the
 * original integer format API.  Neutral definitions deliberately use version
 * {@code 0}: they have the closed structure of format 2 without requiring a
 * compatibility marker in the document.</p>
 */
public enum PatchLanguage {
    LEGACY_V1(1, false, false),
    STRICT_V2(2, true, true),
    NEUTRAL(0, true, false);

    private final int compatibilityVersion;
    private final boolean closedStructure;
    private final boolean requiresFormatSentinel;

    PatchLanguage(int compatibilityVersion, boolean closedStructure, boolean requiresFormatSentinel) {
        this.compatibilityVersion = compatibilityVersion;
        this.closedStructure = closedStructure;
        this.requiresFormatSentinel = requiresFormatSentinel;
    }

    /** Returns the legacy integer value exposed by existing parser consumers. */
    public int compatibilityVersion() {
        return compatibilityVersion;
    }

    /** Returns whether unknown structural fields and operation names are rejected. */
    public boolean closedStructure() {
        return closedStructure;
    }

    /** Returns whether the root format marker must be echoed by a first sentinel operation. */
    public boolean requiresFormatSentinel() {
        return requiresFormatSentinel;
    }
}
