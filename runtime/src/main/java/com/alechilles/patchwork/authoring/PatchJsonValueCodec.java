package com.alechilles.patchwork.authoring;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.NamedSchema;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.NullSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import java.io.IOException;
import javax.annotation.Nonnull;
import org.bson.BsonValue;

/** Codec for Patchwork fields whose portable contract deliberately accepts any JSON value. */
final class PatchJsonValueCodec implements Codec<BsonValue>, NamedSchema {
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

    @Override
    public String getSchemaName() {
        return "PatchJsonValue";
    }

    @Nonnull
    @Override
    public Schema toSchema(@Nonnull SchemaContext context) {
        if (context.getRawDefinition(INSTANCE) == null) {
            return context.refDefinition(INSTANCE);
        }
        Schema value = documented(new Schema(), "JSON value",
                "Choose the JSON kind required by the patch operation or condition.");
        value.setOneOf(
                documented(new NullSchema(), "Null", "An explicit JSON null value."),
                documented(Codec.BOOLEAN.toSchema(context), "Boolean", "A true or false value."),
                documented(Codec.DOUBLE.toSchema(context), "Number", "An integer or decimal JSON number."),
                documented(Codec.STRING.toSchema(context), "String", "A text value."),
                documented(new ArraySchema(context.refDefinition(INSTANCE)), "Array",
                        "An ordered list whose entries can be any JSON value."),
                documented(context.refDefinition(PatchJsonObjectCodec.INSTANCE), "Object",
                        "A JSON object with arbitrary property names and JSON values."));
        return value;
    }

    private static <T extends Schema> T documented(T schema, String title, String documentation) {
        schema.setTitle(title);
        schema.setMarkdownDescription(documentation);
        return schema;
    }
}
