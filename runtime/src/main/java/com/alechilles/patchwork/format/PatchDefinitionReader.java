package com.alechilles.patchwork.format;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
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
        ParseResult parsed;
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            // Keep Gson's established legacy syntax behavior for version-1 definitions.
            reader.setStrictness(Strictness.LEGACY_STRICT);
            parsed = new Parser(reader).read();
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IllegalArgumentException illegal) {
                throw illegal;
            }
            throw new IllegalArgumentException("Failed to parse " + sourcePack + ":" + sourcePath + ": " + failure.getMessage(), failure);
        }
        if (!parsed.element().isJsonObject()) {
            throw new IllegalArgumentException("Patch file must contain a JSON object.");
        }
        JsonObject root = parsed.element().getAsJsonObject();
        PatchFormat format = PatchFormat.fromRoot(root);
        if (format.isVersion2() && parsed.duplicateKey()) {
            throw new IllegalArgumentException("Format 2 patch definitions must not contain duplicate JSON object keys.");
        }
        return root;
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

    private record ParseResult(JsonElement element, boolean duplicateKey) {
    }

    private static final class Parser {
        private final JsonReader reader;

        private Parser(JsonReader reader) {
            this.reader = reader;
        }

        private ParseResult read() throws IOException {
            ParseResult result = readValue();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("Patch file must contain exactly one JSON value.");
            }
            return result;
        }

        private ParseResult readValue() throws IOException {
            return switch (reader.peek()) {
                case BEGIN_OBJECT -> readObject();
                case BEGIN_ARRAY -> readArray();
                case STRING -> new ParseResult(new JsonPrimitive(reader.nextString()), false);
                case NUMBER -> new ParseResult(new JsonPrimitive(new java.math.BigDecimal(reader.nextString())), false);
                case BOOLEAN -> new ParseResult(new JsonPrimitive(reader.nextBoolean()), false);
                case NULL -> {
                    reader.nextNull();
                    yield new ParseResult(JsonNull.INSTANCE, false);
                }
                default -> throw new IllegalArgumentException("Patch file must contain a JSON value.");
            };
        }

        private ParseResult readObject() throws IOException {
            reader.beginObject();
            JsonObject object = new JsonObject();
            Set<String> names = new HashSet<>();
            boolean duplicate = false;
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!names.add(name)) {
                    duplicate = true;
                }
                ParseResult value = readValue();
                duplicate |= value.duplicateKey();
                object.add(name, value.element());
            }
            reader.endObject();
            return new ParseResult(object, duplicate);
        }

        private ParseResult readArray() throws IOException {
            reader.beginArray();
            JsonArray array = new JsonArray();
            boolean duplicate = false;
            while (reader.hasNext()) {
                ParseResult value = readValue();
                duplicate |= value.duplicateKey();
                array.add(value.element());
            }
            reader.endArray();
            return new ParseResult(array, duplicate);
        }
    }
}
