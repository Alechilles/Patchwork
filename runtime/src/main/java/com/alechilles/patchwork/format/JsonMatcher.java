package com.alechilles.patchwork.format;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/** Format-aware JSON matcher semantics used by array operations. */
public final class JsonMatcher {
    private JsonMatcher() { }

    /** Validates one strict format-2 matcher object. */
    public static void validateV2(JsonObject matcher) {
        validateV2Matcher(matcher);
    }

    /**
     * Tests a candidate against a matcher using the selected definition format.
     * Format 1 retains the original recursive object and {@code $Contains}
     * behavior; format 2 uses exact equality and reserved operator shapes.
     */
    public static boolean matches(JsonElement candidate, JsonObject matcher, int formatVersion) {
        Objects.requireNonNull(matcher, "matcher");
        if (PatchFormat.isVersion2(formatVersion)) {
            validateV2(matcher);
            return matchesV2(candidate, matcher);
        }
        return matchesLegacy(candidate, matcher);
    }

    private static void validateV2Matcher(JsonObject matcher) {
        if (matcher == null || matcher.isEmpty()) {
            throw new IllegalArgumentException("Format 2 matcher must be a non-empty object.");
        }
        if (matcher.size() == 1 && matcher.has("$Equals")) {
            return;
        }
        if (matcher.size() == 1 && matcher.has("$Contains")) {
            JsonElement nested = matcher.get("$Contains");
            if (nested == null || !nested.isJsonObject()) {
                throw new IllegalArgumentException("Format 2 $Contains matcher must contain an object matcher.");
            }
            validateV2Matcher(nested.getAsJsonObject());
            return;
        }
        for (String key : matcher.keySet()) {
            if (key.startsWith("$")) {
                throw new IllegalArgumentException("Format 2 matcher reserved key must be used alone: " + key + ".");
            }
        }
        for (Map.Entry<String, JsonElement> entry : matcher.entrySet()) {
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonObject()) {
                validateV2Matcher(value.getAsJsonObject());
            }
        }
    }

    private static boolean matchesV2(JsonElement candidate, JsonObject matcher) {
        if (matcher.has("$Equals")) {
            return exactEquals(candidate, matcher.get("$Equals"));
        }
        if (matcher.has("$Contains")) {
            if (candidate == null || !candidate.isJsonArray()) {
                return false;
            }
            JsonObject nested = matcher.getAsJsonObject("$Contains");
            for (JsonElement entry : candidate.getAsJsonArray()) {
                if (matchesV2(entry, nested)) {
                    return true;
                }
            }
            return false;
        }
        if (candidate == null || !candidate.isJsonObject()) {
            return false;
        }
        JsonObject actual = candidate.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : matcher.entrySet()) {
            JsonElement value = actual.get(entry.getKey());
            if (value == null) {
                return false;
            }
            JsonElement expected = entry.getValue();
            if (expected != null && expected.isJsonObject()) {
                if (!matchesV2(value, expected.getAsJsonObject())) {
                    return false;
                }
            } else if (!exactEquals(value, expected)) {
                return false;
            }
        }
        return true;
    }

    private static boolean exactEquals(JsonElement actual, JsonElement expected) {
        if (actual == expected) {
            return true;
        }
        if (actual == null || expected == null || actual.isJsonNull() || expected.isJsonNull()) {
            return actual != null && expected != null && actual.isJsonNull() && expected.isJsonNull();
        }
        if (actual.isJsonArray() || expected.isJsonArray()) {
            if (!actual.isJsonArray() || !expected.isJsonArray()) {
                return false;
            }
            JsonArray left = actual.getAsJsonArray();
            JsonArray right = expected.getAsJsonArray();
            if (left.size() != right.size()) {
                return false;
            }
            for (int index = 0; index < left.size(); index++) {
                if (!exactEquals(left.get(index), right.get(index))) {
                    return false;
                }
            }
            return true;
        }
        if (actual.isJsonObject() || expected.isJsonObject()) {
            if (!actual.isJsonObject() || !expected.isJsonObject()) {
                return false;
            }
            JsonObject left = actual.getAsJsonObject();
            JsonObject right = expected.getAsJsonObject();
            if (left.size() != right.size() || !left.keySet().equals(right.keySet())) {
                return false;
            }
            for (String key : left.keySet()) {
                if (!exactEquals(left.get(key), right.get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (!actual.isJsonPrimitive() || !expected.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive left = actual.getAsJsonPrimitive();
        JsonPrimitive right = expected.getAsJsonPrimitive();
        if (left.isNumber() || right.isNumber()) {
            if (!left.isNumber() || !right.isNumber()) {
                return false;
            }
            try {
                return new BigDecimal(left.getAsString()).compareTo(new BigDecimal(right.getAsString())) == 0;
            } catch (NumberFormatException failure) {
                return false;
            }
        }
        if (left.isBoolean() || right.isBoolean()) {
            return left.isBoolean() && right.isBoolean() && left.getAsBoolean() == right.getAsBoolean();
        }
        return left.isString() && right.isString() && left.getAsString().equals(right.getAsString());
    }

    private static boolean matchesLegacy(JsonElement candidate, JsonObject matcher) {
        JsonElement contains = matcher.get("$Contains");
        if (contains != null) {
            if (candidate == null || !candidate.isJsonArray() || !contains.isJsonObject()) {
                return false;
            }
            for (JsonElement entry : candidate.getAsJsonArray()) {
                if (matchesLegacy(entry, contains.getAsJsonObject())) {
                    return true;
                }
            }
            return false;
        }
        if (candidate == null || !candidate.isJsonObject()) {
            return false;
        }
        for (Map.Entry<String, JsonElement> entry : matcher.entrySet()) {
            JsonElement actual = candidate.getAsJsonObject().get(entry.getKey());
            JsonElement expected = entry.getValue();
            if (expected != null && expected.isJsonObject()) {
                if (!matchesLegacy(actual, expected.getAsJsonObject())) {
                    return false;
                }
            } else if (actual == null || !actual.equals(expected)) {
                return false;
            }
        }
        return true;
    }
}
