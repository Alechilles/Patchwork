package com.alechilles.patchwork.authoring;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.NullSchema;
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
    @SuppressWarnings("deprecation")
    public BsonValue decodeJson(@Nonnull RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
        return RawJsonReader.readBsonValue(reader);
    }

    @Nonnull
    @Override
    public Schema toSchema(@Nonnull SchemaContext context) {
        return PatchSchemaDefinitions.ref(context, "PatchJsonValue", () -> definitionSchema(context));
    }

    private static Schema definitionSchema(SchemaContext context) {
        Schema value = documented(new Schema(), "JSON value",
                "Choose the JSON kind required by the patch operation or condition.");
        value.setOneOf(
                documented(new NullSchema(), "Null", "An explicit JSON null value."),
                documented(Codec.BOOLEAN.toSchema(context), "Boolean", "A true or false value."),
                documented(Codec.DOUBLE.toSchema(context), "Number", "An integer or decimal JSON number."),
                documented(Codec.STRING.toSchema(context), "String", "A text value."),
                documented(new ArraySchema(INSTANCE.toSchema(context)), "Array",
                        "An ordered list whose entries can be any JSON value."),
                documented(PatchJsonObjectCodec.INSTANCE.toSchema(context), "Object",
                        "A JSON object with arbitrary property names and JSON values."));
        return value;
    }

    private static <T extends Schema> T documented(T schema, String title, String documentation) {
        schema.setTitle(title);
        schema.setMarkdownDescription(documentation);
        return schema;
    }
}
