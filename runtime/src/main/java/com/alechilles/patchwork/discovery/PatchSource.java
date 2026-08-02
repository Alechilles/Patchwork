package com.alechilles.patchwork.discovery;

import com.alechilles.patchwork.format.Utf8Ordering;
import java.nio.file.Path;
import java.util.Objects;

/** Filesystem-backed asset pack available to discovery and target resolution. */
public record PatchSource(String sourcePackId, int sourcePackLoadOrder, Path backingPath, Kind kind) {
    /** Supported backing representations for an asset pack. */
    public enum Kind { DIRECTORY, ARCHIVE }

    /** Validates and normalizes a filesystem source descriptor. */
    public PatchSource {
        sourcePackId = requireText(sourcePackId, "sourcePackId");
        Utf8Ordering.requireValid(sourcePackId, "sourcePackId");
        backingPath = Objects.requireNonNull(backingPath, "backingPath").toAbsolutePath().normalize();
        kind = Objects.requireNonNull(kind, "kind");
    }

    /** Creates a directory-backed source. */
    public static PatchSource directory(String sourcePackId, int sourcePackLoadOrder, Path backingPath) {
        return new PatchSource(sourcePackId, sourcePackLoadOrder, backingPath, Kind.DIRECTORY);
    }

    /** Creates an archive-backed source. */
    public static PatchSource archive(String sourcePackId, int sourcePackLoadOrder, Path backingPath) {
        return new PatchSource(sourcePackId, sourcePackLoadOrder, backingPath, Kind.ARCHIVE);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
