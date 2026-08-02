package com.alechilles.patchwork.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.math.BigInteger;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.json.JsonWriterSettings;

/** BSON-compatible editor document whose disk representation retains portable JSON numbers. */
final class PortableJsonBsonDocument extends BsonDocument {
    private final JsonObject portable;

    PortableJsonBsonDocument(JsonObject portable) {
        this.portable = portable.deepCopy();
        for (var entry : portable.entrySet()) {
            put(entry.getKey(), mirror(entry.getValue()));
        }
    }

    @Override
    public String toJson() {
        return portable.toString();
    }

    @Override
    public String toJson(JsonWriterSettings settings) {
        return portable.toString();
    }

    @Override
    public String toString() {
        return portable.toString();
    }

    static BsonValue mirror(JsonElement element) {
        if (element == null || element instanceof JsonNull) return BsonNull.VALUE;
        if (element.isJsonObject()) return new PortableJsonBsonDocument(element.getAsJsonObject());
        if (element.isJsonArray()) {
            BsonArray result = new BsonArray();
            for (JsonElement child : element.getAsJsonArray()) result.add(mirror(child));
            return result;
        }
        var primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) return BsonBoolean.valueOf(primitive.getAsBoolean());
        if (primitive.isString()) return new BsonString(primitive.getAsString());

        String token = primitive.getAsString();
        if (!token.contains(".") && !token.contains("e") && !token.contains("E")) {
            try {
                BigInteger integer = new BigInteger(token);
                if (integer.bitLength() < 31) return new BsonInt32(integer.intValue());
                if (integer.bitLength() < 63) return new BsonInt64(integer.longValue());
            } catch (NumberFormatException ignored) {
                // The portable parser owns validation; this value is only an editor-side mirror.
            }
        }
        try {
            return new BsonDouble(Double.parseDouble(token));
        } catch (NumberFormatException ignored) {
            return new BsonString(token);
        }
    }
}
