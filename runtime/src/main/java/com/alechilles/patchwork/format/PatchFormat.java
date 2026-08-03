package com.alechilles.patchwork.format;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;

/** Supported Patchwork definition formats and their root-level version marker. */
public final class PatchFormat {
    /** Definitions without a {@code FormatVersion} use the original grammar. */
    public static final int LEGACY_VERSION = 1;
    /** Strict, sentinel-guarded Patchwork definition format. */
    public static final int FORMAT_VERSION_2 = 2;

    private final int version;

    private PatchFormat(int version) {
        this.version = version;
    }

    /** Reads the format marker from a definition root, defaulting an absent marker to version 1. */
    public static PatchFormat fromRoot(JsonObject root) {
        if (root == null) {
            throw new IllegalArgumentException("Patch definition root must be an object.");
        }
        JsonElement marker = root.get("FormatVersion");
        int version;
        if (marker == null) {
            version = LEGACY_VERSION;
        } else {
            if (marker.isJsonNull() || !marker.isJsonPrimitive() || !marker.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("FormatVersion must be a positive integer.");
            }
            try {
                version = new BigDecimal(marker.getAsString()).intValueExact();
            } catch (NumberFormatException | ArithmeticException failure) {
                throw new IllegalArgumentException("FormatVersion must be a positive integer.", failure);
            }
            if (version <= 0) {
                throw new IllegalArgumentException("FormatVersion must be a positive integer.");
            }
        }
        if (version != LEGACY_VERSION && version != FORMAT_VERSION_2) {
            throw new IllegalArgumentException("Unsupported Patchwork format version: " + version + ".");
        }
        return new PatchFormat(version);
    }

    /** Returns the selected format version. */
    public int version() {
        return version;
    }

    /** Returns whether this format uses the strict version-2 grammar. */
    public boolean isVersion2() {
        return isVersion2(version);
    }

    /** Returns the explicit language represented by this compatibility marker. */
    public PatchLanguage language() {
        return isVersion2() ? PatchLanguage.STRICT_V2 : PatchLanguage.LEGACY_V1;
    }

    /** Returns whether the supplied version is the strict version-2 format. */
    public static boolean isVersion2(int version) {
        return version == FORMAT_VERSION_2;
    }
}
