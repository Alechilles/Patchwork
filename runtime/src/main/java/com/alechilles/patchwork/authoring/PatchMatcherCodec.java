package com.alechilles.patchwork.authoring;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/** Lossless BSON codec with a recursive schema for Patchwork matchers. */
final class PatchMatcherCodec implements Codec<BsonDocument> {
    static final PatchMatcherCodec INSTANCE = new PatchMatcherCodec();

    private PatchMatcherCodec() {
    }

    @Override
    public BsonDocument decode(@Nonnull BsonValue value, ExtraInfo extraInfo) {
        return value.asDocument();
    }

    @Override
    public BsonValue encode(BsonDocument value, ExtraInfo extraInfo) {
        return value;
    }

    @Override
    @SuppressWarnings("deprecation")
    public BsonDocument decodeJson(@Nonnull RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
        return RawJsonReader.readBsonValue(reader).asDocument();
    }

    @Nonnull
    @Override
    public Schema toSchema(@Nonnull SchemaContext context) {
        return PatchSchemaDefinitions.ref(context, "PatchMatcher", () -> definitionSchema(context));
    }

    private static Schema definitionSchema(SchemaContext context) {
        ObjectSchema matcher = documented(new ObjectSchema(), "Patch matcher",
                "Use $Equals for an exact value, $Contains for an array entry, or add ordinary object fields to match.");
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("$Equals", documented(PatchJsonValueCodec.INSTANCE.toSchema(context), "Expected JSON value",
                "Matches only when the candidate is exactly equal to this complete JSON value."));
        properties.put("$Contains", documented(INSTANCE.toSchema(context), "Nested matcher",
                "Matches an array when at least one entry satisfies this nested matcher."));
        matcher.setProperties(properties);
        StringSchema propertyNames = new StringSchema();
        propertyNames.setPattern("^(?:\\$(?:Equals|Contains)|[^$].*)$");
        matcher.setPropertyNames(propertyNames);
        matcher.setAdditionalProperties(PatchMatcherValueSchema.INSTANCE.toSchema(context));
        return matcher;
    }

    private static <T extends Schema> T documented(T schema, String title, String documentation) {
        schema.setTitle(title);
        schema.setMarkdownDescription(documentation);
        return schema;
    }
}
