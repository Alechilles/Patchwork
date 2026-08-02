package com.alechilles.patchwork.authoring;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import java.io.IOException;
import javax.annotation.Nonnull;
import org.bson.BsonValue;

/** Codec for Patchwork fields whose portable contract deliberately accepts any JSON value. */
final class PatchJsonValueCodec implements Codec<BsonValue> {
    static final PatchJsonValueCodec INSTANCE = new PatchJsonValueCodec();

    private PatchJsonValueCodec() {
    }

    @Override
    public BsonValue decode(@Nonnull BsonValue value, ExtraInfo extraInfo) {
        return value;
    }

    @Override
    public BsonValue encode(BsonValue value, ExtraInfo extraInfo) {
        return value;
    }

    @Override
    public BsonValue decodeJson(@Nonnull RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
        return RawJsonReader.readBsonValue(reader);
    }

    @Nonnull
    @Override
    public Schema toSchema(@Nonnull SchemaContext context) {
        return new Schema();
    }
}
