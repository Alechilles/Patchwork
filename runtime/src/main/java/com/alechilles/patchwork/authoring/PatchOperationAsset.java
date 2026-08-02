package com.alechilles.patchwork.authoring;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.util.Set;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/** Schema-facing representation of one portable Patchwork operation. */
final class PatchOperationAsset {
    private static final BuilderCodec<PatchOperationAsset> BUILDER_CODEC = BuilderCodec
            .builder(PatchOperationAsset.class, PatchOperationAsset::new)
            .addField(new KeyedCodec<>("Id", Codec.STRING),
                    (operation, value) -> operation.id = value, operation -> operation.id)
            .addField(new KeyedCodec<>("Op", Codec.STRING),
                    (operation, value) -> operation.op = value, operation -> operation.op)
            .addField(new KeyedCodec<>("Version", Codec.INTEGER),
                    (operation, value) -> operation.version = value, operation -> operation.version)
            .addField(new KeyedCodec<>("Path", Codec.STRING),
                    (operation, value) -> operation.path = value, operation -> operation.path)
            .addField(new KeyedCodec<>("Value", PatchJsonValueCodec.INSTANCE),
                    (operation, value) -> operation.value = value, operation -> operation.value)
            .addField(new KeyedCodec<>("Position", Codec.STRING),
                    (operation, value) -> operation.position = value, operation -> operation.position)
            .addField(new KeyedCodec<>("Match", Codec.BSON_DOCUMENT),
                    (operation, value) -> operation.match = value, operation -> operation.match)
            .addField(new KeyedCodec<>("MatchPolicy", Codec.STRING),
                    (operation, value) -> operation.matchPolicy = value, operation -> operation.matchPolicy)
            .addField(new KeyedCodec<>("Find", Codec.BSON_DOCUMENT),
                    (operation, value) -> operation.find = value, operation -> operation.find)
            .addField(new KeyedCodec<>("Existing", Codec.BSON_DOCUMENT),
                    (operation, value) -> operation.existing = value, operation -> operation.existing)
            .addField(new KeyedCodec<>("Macro", Codec.STRING),
                    (operation, value) -> operation.macro = value, operation -> operation.macro)
            .addField(new KeyedCodec<>("Options", Codec.BSON_DOCUMENT),
                    (operation, value) -> operation.options = value, operation -> operation.options)
            .addField(new KeyedCodec<>("Required", Codec.BOOLEAN),
                    (operation, value) -> operation.required = value, operation -> operation.required)
            .build();
    static final PortableObjectCodec<PatchOperationAsset> CODEC = new PortableObjectCodec<>(BUILDER_CODEC, Set.of(
            "Id", "Op", "Version", "Path", "Value", "Position", "Match", "MatchPolicy",
            "Find", "Existing", "Macro", "Options", "Required"));

    private String id;
    private String op;
    private Integer version;
    private String path;
    private BsonValue value;
    private String position;
    private BsonDocument match;
    private String matchPolicy;
    private BsonDocument find;
    private BsonDocument existing;
    private String macro;
    private BsonDocument options;
    private Boolean required;
    private JsonObject portableSource;

    private PatchOperationAsset() {
    }

    JsonObject toPortableJson() {
        if (portableSource != null) return portableSource.deepCopy();
        JsonObject result = new JsonObject();
        add(result, "Id", id);
        add(result, "Op", op);
        if (version != null) result.addProperty("Version", version);
        add(result, "Path", path);
        if (value != null) result.add("Value", json(value));
        add(result, "Position", position);
        add(result, "Match", match);
        add(result, "MatchPolicy", matchPolicy);
        add(result, "Find", find);
        add(result, "Existing", existing);
        add(result, "Macro", macro);
        add(result, "Options", options);
        if (required != null) result.addProperty("Required", required);
        return result;
    }

    static PatchOperationAsset fromPortableJson(JsonObject root) {
        PatchOperationAsset operation = new PatchOperationAsset();
        operation.portableSource = root.deepCopy();
        operation.id = string(root, "Id");
        operation.op = string(root, "Op");
        operation.version = integer(root, "Version");
        operation.path = string(root, "Path");
        if (root.has("Value")) operation.value = PortableJsonBsonDocument.mirror(root.get("Value"));
        operation.position = string(root, "Position");
        operation.match = object(root, "Match");
        operation.matchPolicy = string(root, "MatchPolicy");
        operation.find = object(root, "Find");
        operation.existing = object(root, "Existing");
        operation.macro = string(root, "Macro");
        operation.options = object(root, "Options");
        if (root.get("Required") != null && root.get("Required").isJsonPrimitive()
                && root.get("Required").getAsJsonPrimitive().isBoolean()) {
            operation.required = root.get("Required").getAsBoolean();
        }
        return operation;
    }

    private static String string(JsonObject root, String name) {
        var value = root.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static Integer integer(JsonObject root, String name) {
        var value = root.get(name);
        try {
            return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    ? value.getAsBigDecimal().intValueExact() : null;
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
    }

    private static BsonDocument object(JsonObject root, String name) {
        var value = root.get(name);
        return value instanceof JsonObject object
                ? (BsonDocument) PortableJsonBsonDocument.mirror(object) : null;
    }

    private static void add(JsonObject result, String name, String value) {
        if (value != null) result.addProperty(name, value);
    }

    private static void add(JsonObject result, String name, BsonDocument value) {
        if (value != null) result.add(name, JsonParser.parseString(value.toJson()));
    }

    private static com.google.gson.JsonElement json(BsonValue value) {
        return JsonParser.parseString(new BsonDocument("value", value).toJson())
                .getAsJsonObject().get("value");
    }
}
