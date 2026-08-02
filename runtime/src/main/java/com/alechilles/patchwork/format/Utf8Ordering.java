package com.alechilles.patchwork.format;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Objects;

/** Provides the portable byte-wise ordering used by Patchwork format boundaries. */
public final class Utf8Ordering {
    /** Compares strings by their raw UTF-8 bytes interpreted as unsigned values. */
    public static final Comparator<String> UNSIGNED_BYTES = (left, right) -> {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        byte[] leftBytes = encode(left);
        byte[] rightBytes = encode(right);
        int sharedLength = Math.min(leftBytes.length, rightBytes.length);
        for (int index = 0; index < sharedLength; index++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(leftBytes[index]), Byte.toUnsignedInt(rightBytes[index]));
            if (comparison != 0) return comparison;
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
    };

    /** Encodes one contract string as UTF-8, rejecting malformed Java strings. */
    public static byte[] encode(String value) {
        Objects.requireNonNull(value, "value");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("String contains a non-scalar Unicode code point.", failure);
        }
    }

    /** Validates one ID-like string at a Patchwork contract boundary. */
    public static void requireValid(String value, String fieldName) {
        if (value == null) throw new IllegalArgumentException(fieldName + " must not be null.");
        encode(value);
    }

    private Utf8Ordering() { }
}
