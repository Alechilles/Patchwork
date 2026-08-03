package com.alechilles.patchwork.engine;

import com.google.gson.JsonElement;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Produces deterministic, type-preserving SHA-256 fingerprints for JSON values. */
public final class JsonValueFingerprint {
    private JsonValueFingerprint() {
    }

    /**
     * Returns a lower-case SHA-256 fingerprint of one JSON value.
     *
     * <p>Object keys are ordered by their unsigned UTF-8 bytes, arrays retain
     * their source order, and numeric spellings are normalized to their
     * mathematical decimal value.</p>
     */
    public static String sha256(JsonElement value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteArrayOutputStream canonical = new ByteArrayOutputStream();
            writeCanonical(value, canonical);
            return java.util.HexFormat.of().formatHex(digest.digest(canonical.toByteArray()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void writeCanonical(JsonElement value, ByteArrayOutputStream output) {
        if (value == null || value.isJsonNull()) {
            output.write('n');
            return;
        }
        if (value.isJsonPrimitive()) {
            var primitive = value.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                output.write('b');
                writeBytes(output, primitive.getAsBoolean() ? "1" : "0");
            } else if (primitive.isNumber()) {
                output.write('d');
                writeBytes(output, normalizeNumber(primitive.getAsString()));
            } else {
                output.write('s');
                writeBytes(output, primitive.getAsString());
            }
            return;
        }
        if (value.isJsonArray()) {
            output.write('a');
            var array = value.getAsJsonArray();
            writeLength(output, array.size());
            array.forEach(entry -> writeCanonical(entry, output));
            return;
        }

        output.write('o');
        List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(value.getAsJsonObject().entrySet());
        entries.sort(Comparator.comparing(entry -> entry.getKey().getBytes(StandardCharsets.UTF_8),
                JsonValueFingerprint::compareUnsigned));
        writeLength(output, entries.size());
        for (Map.Entry<String, JsonElement> entry : entries) {
            writeBytes(output, entry.getKey());
            writeCanonical(entry.getValue(), output);
        }
    }

    private static String normalizeNumber(String raw) {
        try {
            BigDecimal decimal = new BigDecimal(raw);
            if (decimal.compareTo(BigDecimal.ZERO) == 0) return "0";
            return decimal.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException malformed) {
            // Gson accepts only JSON numbers, but retain a deterministic value
            // if an embedding supplies a custom JsonPrimitive implementation.
            return raw;
        }
    }

    private static void writeBytes(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeLength(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void writeLength(ByteArrayOutputStream output, int length) {
        output.write((length >>> 24) & 0xff);
        output.write((length >>> 16) & 0xff);
        output.write((length >>> 8) & 0xff);
        output.write(length & 0xff);
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        int length = Math.min(left.length, right.length);
        for (int index = 0; index < length; index++) {
            int comparison = Integer.compare(left[index] & 0xff, right[index] & 0xff);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(left.length, right.length);
    }
}
