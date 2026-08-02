package com.alechilles.patchwork.authoring;

import com.alechilles.patchwork.engine.PatchDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.codec.AssetCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import org.bson.BsonDocument;

/** Native Hytale asset facade for the portable Patchwork definition format. */
public final class PatchDefinitionAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, PatchDefinitionAsset>> {
    private static final AssetBuilderCodec<String, PatchDefinitionAsset> BUILDER_CODEC = AssetBuilderCodec
            .builder(PatchDefinitionAsset.class, PatchDefinitionAsset::new, Codec.STRING,
                    (asset, value) -> asset.assetId = value, asset -> asset.assetId,
                    (asset, value) -> asset.extraData = value, asset -> asset.extraData)
            .addField(new KeyedCodec<>("FormatVersion", Codec.INTEGER),
                    (asset, value) -> asset.formatVersion = value, asset -> asset.formatVersion)
            .addField(new KeyedCodec<>("Id", Codec.STRING),
                    (asset, value) -> asset.patchId = value, asset -> asset.patchId)
            .addField(new KeyedCodec<>("Target", Codec.STRING),
                    (asset, value) -> asset.target = value, asset -> asset.target)
            .addField(new KeyedCodec<>("Targets", Codec.STRING_ARRAY),
                    (asset, value) -> asset.targets = value, asset -> asset.targets)
            .addField(new KeyedCodec<>("Priority", Codec.INTEGER),
                    (asset, value) -> asset.priority = value, asset -> asset.priority)
            .addField(new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (asset, value) -> asset.enabled = value, asset -> asset.enabled)
            .addField(new KeyedCodec<>("When", Codec.BSON_DOCUMENT),
                    (asset, value) -> asset.when = value, asset -> asset.when)
            .addField(new KeyedCodec<>("Operations",
                            new ArrayCodec<>(PatchOperationAsset.CODEC, PatchOperationAsset[]::new), true),
                    (asset, value) -> asset.operations = value, asset -> asset.operations)
            .validator(PatchDefinitionAsset::validatePortableDefinition)
            .build();
    public static final AssetCodec<String, PatchDefinitionAsset> CODEC =
            new PatchDefinitionAssetCodec(BUILDER_CODEC);

    private String assetId;
    private AssetExtraInfo.Data extraData;
    private Integer formatVersion;
    private String patchId;
    private String target;
    private String[] targets;
    private Integer priority;
    private Boolean enabled;
    private BsonDocument when;
    private PatchOperationAsset[] operations;
    private JsonObject portableSource;

    private PatchDefinitionAsset() {
    }

    @Override
    public String getId() {
        return assetId;
    }

    /** Reconstructs the exact portable definition shape consumed by the Patchwork runtime. */
    public JsonObject toPortableJson() {
        if (portableSource != null) return portableSource.deepCopy();
        JsonObject result = new JsonObject();
        if (formatVersion != null) result.addProperty("FormatVersion", formatVersion);
        if (patchId != null) result.addProperty("Id", patchId);
        if (target != null) result.addProperty("Target", target);
        if (targets != null) {
            JsonArray values = new JsonArray();
            for (String value : targets) values.add(value);
            result.add("Targets", values);
        }
        if (priority != null) result.addProperty("Priority", priority);
        if (enabled != null) result.addProperty("Enabled", enabled);
        if (when != null) result.add("When", JsonParser.parseString(when.toJson()));
        if (operations != null) {
            JsonArray values = new JsonArray();
            for (PatchOperationAsset operation : operations) values.add(operation.toPortableJson());
            result.add("Operations", values);
        }
        return result;
    }

    static PatchDefinitionAsset fromPortableJson(JsonObject root, com.hypixel.hytale.codec.ExtraInfo extraInfo) {
        PatchDefinitionAsset asset = new PatchDefinitionAsset();
        asset.portableSource = root.deepCopy();
        if (extraInfo instanceof AssetExtraInfo<?> assetInfo) {
            Object key = assetInfo.getKey();
            asset.assetId = key == null ? null : key.toString();
            asset.extraData = assetInfo.getData();
        }
        asset.formatVersion = integer(root.get("FormatVersion"));
        asset.patchId = string(root.get("Id"));
        asset.target = string(root.get("Target"));
        if (root.get("Targets") instanceof JsonArray values) {
            asset.targets = new String[values.size()];
            for (int index = 0; index < values.size(); index++) asset.targets[index] = string(values.get(index));
        }
        asset.priority = integer(root.get("Priority"));
        asset.enabled = bool(root.get("Enabled"));
        if (root.get("When") instanceof JsonObject value) {
            asset.when = (BsonDocument) PortableJsonBsonDocument.mirror(value);
        }
        if (root.get("Operations") instanceof JsonArray values) {
            asset.operations = new PatchOperationAsset[values.size()];
            for (int index = 0; index < values.size(); index++) {
                asset.operations[index] = PatchOperationAsset.fromPortableJson(values.get(index).getAsJsonObject());
            }
        }
        return asset;
    }

    void copyFrom(PatchDefinitionAsset source) {
        assetId = source.assetId;
        extraData = source.extraData;
        formatVersion = source.formatVersion;
        patchId = source.patchId;
        target = source.target;
        targets = source.targets;
        priority = source.priority;
        enabled = source.enabled;
        when = source.when;
        operations = source.operations;
        portableSource = source.portableSource;
    }

    private static String string(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static Integer integer(JsonElement value) {
        try {
            return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    ? value.getAsBigDecimal().intValueExact() : null;
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
    }

    private static Boolean bool(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                ? value.getAsBoolean() : null;
    }

    private static void validatePortableDefinition(
            PatchDefinitionAsset asset,
            com.hypixel.hytale.codec.validation.ValidationResults results) {
        try {
            String sourcePath = asset.assetId == null ? "definition.json" : asset.assetId + ".json";
            PatchDefinition.parseAll(asset.toPortableJson(), "native-asset-store", sourcePath, 0);
        } catch (IllegalArgumentException failure) {
            results.fail(failure.getMessage());
        }
    }
}
