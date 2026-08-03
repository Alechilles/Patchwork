package com.alechilles.patchwork.authoring;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.codecs.BsonDocumentCodec;
import com.hypixel.hytale.codec.schema.NamedSchema;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import java.io.IOException;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/** Lossless BSON codec for fields whose portable contract requires a JSON object. */
final class PatchJsonObjectCodec implements Codec<BsonDocument>, NamedSchema {
    static final PatchJsonObjectCodec INSTANCE = new PatchJsonObjectCodec();
    private static final BsonDocumentCodec DOCUMENT_CODEC = new BsonDocumentCodec();

    private PatchJsonObjectCodec() {
    }

    @Override
    public BsonDocument decode(@Nonnull BsonValue value, ExtraInfo extraInfo) {
        return DOCUMENT_CODEC.decode(value, extraInfo);
    }

    @Override
    public BsonValue encode(BsonDocument value, ExtraInfo extraInfo) {
        return DOCUMENT_CODEC.encode(value, extraInfo);
    }

    @Override
    public BsonDocument decodeJson(@Nonnull RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
        return DOCUMENT_CODEC.decodeJson(reader, extraInfo);
    }

    @Override
    public String getSchemaName() {
        return "PatchJsonObject";
    }

    @Nonnull
    @Override
    public Schema toSchema(@Nonnull SchemaContext context) {
        if (context.getRawDefinition(INSTANCE) == null) {
            return context.refDefinition(INSTANCE);
        }
        ObjectSchema object = new ObjectSchema();
        object.setTitle("Object");
        object.setMarkdownDescription(
                "A JSON object. Add any property names required by the target asset or macro.");
        object.setAdditionalProperties(context.refDefinition(PatchJsonValueCodec.INSTANCE));
        return object;
    }
}
