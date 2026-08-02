package com.alechilles.patchwork.authoring;

import com.alechilles.patchwork.engine.PatchDefinition;
import com.alechilles.patchwork.format.PatchDefinitionReader;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.codec.AssetCodec;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Set;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/** Asset-codec adapter that publishes only fields belonging to the portable Patchwork format. */
final class PatchDefinitionAssetCodec implements AssetCodec<String, PatchDefinitionAsset> {
    private static final Set<String> PORTABLE_FIELDS = Set.of(
            "FormatVersion", "Id", "Target", "Targets", "Priority", "Enabled", "When", "Operations");
    private final AssetBuilderCodec<String, PatchDefinitionAsset> delegate;

    PatchDefinitionAssetCodec(AssetBuilderCodec<String, PatchDefinitionAsset> delegate) {
        this.delegate = delegate;
    }

    @Override
    public KeyedCodec<String> getKeyCodec() {
        return delegate.getKeyCodec();
    }

    @Override
    public KeyedCodec<String> getParentCodec() {
        return null;
    }

    @Override
    public AssetExtraInfo.Data getData(PatchDefinitionAsset asset) {
        return delegate.getData(asset);
    }

    @Override
    public PatchDefinitionAsset decode(BsonValue value, ExtraInfo extraInfo) {
        validateDocument(value.asDocument());
        return PatchDefinitionAsset.fromPortableJson(
                JsonParser.parseString(value.asDocument().toJson()).getAsJsonObject(), extraInfo);
    }

    @Override
    public BsonValue encode(PatchDefinitionAsset asset, ExtraInfo extraInfo) {
        return new PortableJsonBsonDocument(asset.toPortableJson());
    }

    @Override
    public PatchDefinitionAsset decodeJson(@Nonnull RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
        return decodePortableJson(reader, extraInfo);
    }

    @Override
    public PatchDefinitionAsset decodeAndInherit(
            @Nonnull BsonDocument document,
            PatchDefinitionAsset parent,
            ExtraInfo extraInfo) {
        validateDocument(document);
        return decode(document, extraInfo);
    }

    @Override
    public void decodeAndInherit(
            @Nonnull BsonDocument document,
            PatchDefinitionAsset asset,
            PatchDefinitionAsset parent,
            ExtraInfo extraInfo) {
        validateDocument(document);
        asset.copyFrom(decode(document, extraInfo));
    }

    @Override
    public PatchDefinitionAsset decodeAndInheritJson(
            RawJsonReader reader,
            PatchDefinitionAsset parent,
            ExtraInfo extraInfo) throws IOException {
        return decodePortableJson(reader, extraInfo);
    }

    @Override
    public void decodeAndInheritJson(
            RawJsonReader reader,
            PatchDefinitionAsset asset,
            PatchDefinitionAsset parent,
            ExtraInfo extraInfo) throws IOException {
        asset.copyFrom(decodePortableJson(reader, extraInfo));
    }

    @Override
    public PatchDefinitionAsset decodeJsonAsset(
            RawJsonReader reader,
            AssetExtraInfo<String> extraInfo) throws IOException {
        return decodePortableJson(reader, extraInfo);
    }

    @Override
    public PatchDefinitionAsset decodeAndInheritJsonAsset(
            RawJsonReader reader,
            PatchDefinitionAsset parent,
            AssetExtraInfo<String> extraInfo) throws IOException {
        return decodePortableJson(reader, extraInfo);
    }

    @Override
    public void validate(PatchDefinitionAsset asset, ExtraInfo extraInfo) {
        delegate.validate(asset, extraInfo);
    }

    @Override
    public void validateDefaults(ExtraInfo extraInfo, Set<Codec<?>> tested) {
        delegate.validateDefaults(extraInfo, tested);
    }

    @Nonnull
    @Override
    public ObjectSchema toSchema(@Nonnull SchemaContext context) {
        ObjectSchema schema = delegate.toSchema(context);
        var filtered = new LinkedHashMap<String, com.hypixel.hytale.codec.schema.config.Schema>();
        for (var entry : schema.getProperties().entrySet()) {
            if (PORTABLE_FIELDS.contains(entry.getKey())) filtered.put(entry.getKey(), entry.getValue());
        }
        schema.setProperties(filtered);
        schema.setAdditionalProperties(false);
        return schema;
    }

    private static void validateDocument(BsonDocument document) {
        PatchDefinition.parseAll(
                JsonParser.parseString(document.toJson()).getAsJsonObject(),
                "native-asset-store",
                "definition.json",
                0);
    }

    private static PatchDefinitionAsset decodePortableJson(RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
        reader.consumeWhiteSpace();
        reader.mark();
        reader.skipValue();
        String source = new String(reader.cloneMark());
        reader.unmark();
        reader.consumeWhiteSpace();
        if (reader.peek() != -1) {
            throw new IllegalArgumentException("Patch file must contain exactly one JSON value.");
        }
        var root = PatchDefinitionReader.parse(
                source.getBytes(StandardCharsets.UTF_8), "native-asset-store", "definition.json", 0);
        PatchDefinition.parseAll(root, "native-asset-store", "definition.json", 0);
        return PatchDefinitionAsset.fromPortableJson(root, extraInfo);
    }
}
