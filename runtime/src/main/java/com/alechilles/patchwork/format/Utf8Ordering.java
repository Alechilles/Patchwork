package com.alechilles.patchwork.format;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Objects;

/** Provides the portable byte-wise ordering used by Patchwork format boundaries. */
public final class Utf8Ordering {
    /** Compares strings by their raw UTF-8 bytes interpreted as unsigned values. */
    public static final Comparator<String> UNSIGNED_BYTES = (left, right) -> {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        int sharedLength = Math.min(leftBytes.length, rightBytes.length);
        for (int index = 0; index < sharedLength; index++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(leftBytes[index]), Byte.toUnsignedInt(rightBytes[index]));
            if (comparison != 0) return comparison;
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
    };

    private Utf8Ordering() { }
}
