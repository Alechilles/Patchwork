package com.alechilles.patchwork.format;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Decodes patch definition bytes while retaining duplicate-key information. */
public final class PatchDefinitionReader {
    private PatchDefinitionReader() {
    }

    /**
     * Decodes one definition and rejects duplicate object keys when its root declares format 2.
     *
     * @param bytes UTF-8 definition bytes
     * @param sourcePack source pack identifier used in diagnostics
     * @param sourcePath source path used in diagnostics
     * @param sourcePackLoadOrder source pack load order (accepted for scanner call-site symmetry)
     * @return the parsed JSON object
     */
    public static JsonObject parse(byte[] bytes, String sourcePack, String sourcePath, int sourcePackLoadOrder) {
        if (bytes == null) {
            throw new IllegalArgumentException("Patch source bytes must not be null.");
        }
        String text = decode(bytes, sourcePack, sourcePath);
        ParseResult probe;
        boolean v2Intent = false;
        try {
            // The probe is deliberately lenient so we can preserve Gson's legacy syntax path and
            // inspect a duplicate FormatVersion before Gson overwrites it.
            probe = parseText(text, Strictness.LENIENT, sourcePack, sourcePath);
        } catch (RuntimeException ignored) {
            // If the probe cannot understand a legacy construct, defer to the exact Gson parser
            // that the scanner used before format versioning was introduced.
            JsonObject legacy = parseLegacy(text, sourcePack, sourcePath);
            if (!declaresFormatTwo(legacy)) {
                PatchFormat.fromRoot(legacy);
                return legacy;
            }
            // A v2-intending document must still pass strict syntax validation.
            probe = null;
            v2Intent = true;
        }
        if (probe != null) {
            v2Intent = probe.formatVersion2Candidate() || declaresFormatTwo(probe.element());
        }
        if (!v2Intent) {
            JsonObject legacy = parseLegacy(text, sourcePack, sourcePath);
            PatchFormat.fromRoot(legacy);
            return legacy;
        }

        ParseResult parsed = parseText(text, Strictness.STRICT, sourcePack, sourcePath);
        if (!parsed.element().isJsonObject()) {
            throw new IllegalArgumentException("Patch file must contain a JSON object.");
        }
        JsonObject root = parsed.element().getAsJsonObject();
        if (probe != null && (probe.duplicateKey() || parsed.duplicateKey())) {
            throw new IllegalArgumentException("Format 2 patch definitions must not contain duplicate JSON object keys.");
        }
        PatchFormat format = PatchFormat.fromRoot(root);
        if (!format.isVersion2()) {
            // This is the duplicate FormatVersion 2-then-1 downgrade case. The duplicate was
            // already detected above; retain an explicit guard for a future parser change.
            throw new IllegalArgumentException("Format 2 patch definitions must not downgrade through duplicate FormatVersion keys.");
        }
        return root;
    }

    private static ParseResult parseText(String text, Strictness strictness, String sourcePack, String sourcePath) {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(strictness);
            return new Parser(reader).read();
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IllegalArgumentException illegal) {
                throw illegal;
            }
            throw new IllegalArgumentException("Failed to parse " + sourcePack + ":" + sourcePath + ": " + failure.getMessage(), failure);
        }
    }

    private static JsonObject parseLegacy(String text, String sourcePack, String sourcePath) {
        try {
            JsonElement element = JsonParser.parseString(text);
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Patch file must contain a JSON object.");
            }
            return element.getAsJsonObject();
        } catch (RuntimeException failure) {
            if (failure instanceof IllegalArgumentException illegal) {
                throw illegal;
            }
            throw new IllegalArgumentException("Failed to parse " + sourcePack + ":" + sourcePath + ": " + failure.getMessage(), failure);
        }
    }

    private static boolean declaresFormatTwo(JsonElement element) {
        if (element == null || !element.isJsonObject()) return false;
        return isFormatVersionTwo(element.getAsJsonObject().get("FormatVersion"));
    }

    private static boolean isFormatVersionTwo(JsonElement marker) {
        if (marker == null || marker.isJsonNull() || !marker.isJsonPrimitive() || !marker.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        try {
            return new BigDecimal(marker.getAsString()).compareTo(BigDecimal.valueOf(PatchFormat.FORMAT_VERSION_2)) == 0;
        } catch (NumberFormatException failure) {
            return false;
        }
    }

    private static String decode(byte[] bytes, String sourcePack, String sourcePath) {
        try {
            CharBuffer chars = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return chars.toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Failed to decode " + sourcePack + ":" + sourcePath + " as UTF-8.", failure);
        }
    }

    private record ParseResult(JsonElement element, boolean duplicateKey, boolean formatVersion2Candidate) {
    }

    private static final class Parser {
        private final JsonReader reader;

        private Parser(JsonReader reader) {
            this.reader = reader;
        }

        private ParseResult read() throws IOException {
            ParseResult result = readValue(true);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("Patch file must contain exactly one JSON value.");
            }
            return result;
        }

        private ParseResult readValue(boolean root) throws IOException {
            return switch (reader.peek()) {
                case BEGIN_OBJECT -> readObject(root);
                case BEGIN_ARRAY -> readArray();
                case STRING -> new ParseResult(new JsonPrimitive(reader.nextString()), false, false);
                case NUMBER -> new ParseResult(new JsonPrimitive(new BigDecimal(reader.nextString())), false, false);
                case BOOLEAN -> new ParseResult(new JsonPrimitive(reader.nextBoolean()), false, false);
                case NULL -> {
                    reader.nextNull();
                    yield new ParseResult(JsonNull.INSTANCE, false, false);
                }
                default -> throw new IllegalArgumentException("Patch file must contain a JSON value.");
            };
        }

        private ParseResult readObject(boolean root) throws IOException {
            reader.beginObject();
            JsonObject object = new JsonObject();
            Set<String> names = new HashSet<>();
            boolean duplicate = false;
            boolean formatVersion2Candidate = false;
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!names.add(name)) {
                    duplicate = true;
                }
                ParseResult value = readValue(false);
                duplicate |= value.duplicateKey();
                if (root && "FormatVersion".equals(name)) {
                    formatVersion2Candidate |= isFormatVersionTwo(value.element());
                }
                object.add(name, value.element());
            }
            reader.endObject();
            return new ParseResult(object, duplicate, formatVersion2Candidate);
        }

        private ParseResult readArray() throws IOException {
            reader.beginArray();
            JsonArray array = new JsonArray();
            boolean duplicate = false;
            while (reader.hasNext()) {
                ParseResult value = readValue(false);
                duplicate |= value.duplicateKey();
                array.add(value.element());
            }
            reader.endArray();
            return new ParseResult(array, duplicate, false);
        }
    }
}
