package com.alechilles.patchwork.authoring;

import com.alechilles.patchwork.engine.PatchDefinition;
import com.alechilles.patchwork.format.PatchFormat;
import com.alechilles.patchwork.format.PatchLanguage;
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
            .documentation("A Patchwork patch definition. Choose one target form, then add operations in the order they should run.")
            .append(new KeyedCodec<>("FormatVersion", Codec.INTEGER),
                    (asset, value) -> asset.formatVersion = value, asset -> asset.formatVersion)
            .documentation("Compatibility marker preserved when reopening an explicit versioned file. New Patchwork definitions use the neutral schema and do not need this field.")
            .add()
            .append(new KeyedCodec<>("Id", Codec.STRING),
                    (asset, value) -> asset.patchId = value, asset -> asset.patchId)
            .documentation("Optional stable name for this patch. If omitted, Patchwork derives one from the source mod and this file's path.")
            .add()
            .append(new KeyedCodec<>("Target", Codec.STRING),
                    (asset, value) -> asset.target = value, asset -> asset.target)
            .documentation("One asset file to patch, using a forward-slash path such as Server/Item/Items/Example.json. Use either Target or Targets, never both.")
            .add()
            .append(new KeyedCodec<>("Targets", Codec.STRING_ARRAY),
                    (asset, value) -> asset.targets = value, asset -> asset.targets)
            .documentation("Multiple unique asset files that receive this same patch. Use either Targets or Target, never both.")
            .add()
            .append(new KeyedCodec<>("Priority", Codec.INTEGER),
                    (asset, value) -> asset.priority = value, asset -> asset.priority)
            .documentation("Controls ordering when several patches affect the same target. Lower numbers run first. Defaults to 0.")
            .add()
            .append(new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (asset, value) -> asset.enabled = value, asset -> asset.enabled)
            .documentation("Whether Patchwork applies this definition. Defaults to true; disable it to keep the file without applying it.")
            .add()
            .append(new KeyedCodec<>("When", PatchConditionCodec.INSTANCE),
                    (asset, value) -> asset.when = value, asset -> asset.when)
            .documentation("Optional condition object that decides whether this patch is eligible. Omit it to always apply the patch when its target exists.")
            .add()
            .append(new KeyedCodec<>("Operations",
                            new ArrayCodec<>(PatchOperationAsset.CODEC, PatchOperationAsset[]::new), true),
                    (asset, value) -> asset.operations = value, asset -> asset.operations)
            .documentation("Patch steps, executed from top to bottom. New files use the neutral operation choices; explicit versioned compatibility fields are preserved when reopened.")
            .add()
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
            parsePortableDefinition(asset.toPortableJson(), "native-asset-store", sourcePath, 0);
        } catch (IllegalArgumentException failure) {
            results.fail(failure.getMessage());
        }
    }

    /** Selects and applies the one portable parser profile used by all native decode paths. */
    static java.util.List<PatchDefinition> parsePortableDefinition(
            JsonObject root,
            String sourcePack,
            String sourcePath,
            int sourcePackLoadOrder) {
        PatchLanguage language = root.has("FormatVersion")
                ? PatchFormat.fromRoot(root).language()
                : PatchLanguage.NEUTRAL;
        return PatchDefinition.parseAll(root, sourcePack, sourcePath, sourcePackLoadOrder, language);
    }
}
