package com.alechilles.patchwork.format;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON Pointer tokenization shared by operation application and conditions.
 *
 * <p>Format 1 intentionally retains the original Patchwork compatibility
 * behavior. Format 2 uses RFC 6901 escaping and validates array indexes at the
 * point where a token is applied to an array.</p>
 */
public final class JsonPointer {
    private JsonPointer() { }

    /**
     * Splits and decodes a JSON pointer for the selected Patchwork format.
     *
     * @param pointer pointer text
     * @param formatVersion enclosing definition format
     * @param mutation whether the pointer is being used by a mutating operation
     * @return decoded pointer tokens
     */
    public static List<String> tokens(String pointer, int formatVersion, boolean mutation) {
        if (pointer == null) {
            throw new IllegalArgumentException("JSON pointer must not be null.");
        }
        if (!PatchFormat.isVersion2(formatVersion)) {
            return legacyTokens(pointer);
        }
        if (pointer.isEmpty()) {
            if (mutation) {
                throw new IllegalArgumentException("The empty JSON pointer may only be used for inspection.");
            }
            return List.of();
        }
        if (!pointer.startsWith("/")) {
            throw new IllegalArgumentException("JSON pointer must use RFC 6901 syntax and start with '/': " + pointer);
        }
        List<String> result = new ArrayList<>();
        int tokenStart = 1;
        for (int index = 1; index <= pointer.length(); index++) {
            if (index != pointer.length() && pointer.charAt(index) != '/') {
                continue;
            }
            result.add(decode(pointer.substring(tokenStart, index), pointer));
            tokenStart = index + 1;
        }
        return List.copyOf(result);
    }

    /**
     * Resolves one decoded pointer token as an array index.
     *
     * <p>Object tokens are not interpreted here, so a token such as {@code 01}
     * remains valid when it names an object property. The strict index grammar
     * is enforced only after traversal has established that the current value
     * is an array.</p>
     */
    public static int arrayIndex(String token, int size, boolean allowAppend, int formatVersion) {
        if (PatchFormat.isVersion2(formatVersion)) {
            return strictArrayIndex(token, size, allowAppend);
        }
        return legacyArrayIndex(token, size, allowAppend);
    }

    private static String decode(String token, String pointer) {
        StringBuilder decoded = new StringBuilder(token.length());
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            if (character != '~') {
                decoded.append(character);
                continue;
            }
            if (++index >= token.length()) {
                throw invalidEscape(pointer);
            }
            char escape = token.charAt(index);
            if (escape == '0') {
                decoded.append('~');
            } else if (escape == '1') {
                decoded.append('/');
            } else {
                throw invalidEscape(pointer);
            }
        }
        return decoded.toString();
    }

    private static IllegalArgumentException invalidEscape(String pointer) {
        return new IllegalArgumentException("JSON pointer contains an invalid '~' escape: " + pointer);
    }

    private static List<String> legacyTokens(String pointer) {
        if (pointer.isBlank() || "/".equals(pointer)) {
            return List.of();
        }
        if (!pointer.startsWith("/")) {
            throw new IllegalArgumentException("Path must use JSON pointer syntax and start with '/': " + pointer);
        }
        return java.util.Arrays.stream(pointer.substring(1).split("/", -1))
                .map(token -> token.replace("~1", "/").replace("~0", "~"))
                .toList();
    }

    private static int strictArrayIndex(String token, int size, boolean allowAppend) {
        if (allowAppend && "-".equals(token)) {
            return size;
        }
        if (token == null || !token.matches("0|[1-9]\\d*")) {
            throw new IllegalArgumentException("Array path token must be a non-negative integer: " + token + ".");
        }
        final int index;
        try {
            index = Integer.parseInt(token);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Array path token is out of range: " + token + ".", failure);
        }
        int upper = allowAppend ? size : size - 1;
        if (index < 0 || index > upper) {
            throw new IllegalArgumentException("Array index out of bounds: " + token + ".");
        }
        return index;
    }

    private static int legacyArrayIndex(String token, int size, boolean allowAppend) {
        if ("-".equals(token) && allowAppend) {
            return size;
        }
        try {
            int index = Integer.parseInt(token);
            int upper = allowAppend ? size : size - 1;
            if (index < 0 || index > upper) {
                throw new IllegalArgumentException("Array index out of bounds: " + token + ".");
            }
            return index;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Array path token must be an integer: " + token + ".", failure);
        }
    }
}
