package com.alechilles.patchwork.authoring;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.codecs.BsonDocumentCodec;
import com.hypixel.hytale.codec.schema.NamedSchema;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import java.io.IOException;
import java.util.Map;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/** Lossless BSON codec with a recursive schema for Patchwork matchers. */
final class PatchMatcherCodec implements Codec<BsonDocument>, NamedSchema {
    static final PatchMatcherCodec INSTANCE = new PatchMatcherCodec();
    private static final BsonDocumentCodec DOCUMENT_CODEC = new BsonDocumentCodec();

    private PatchMatcherCodec() {
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
        return "PatchMatcher";
    }

    @Nonnull
    @Override
    public Schema toSchema(@Nonnull SchemaContext context) {
        if (context.getRawDefinition(INSTANCE) == null) {
            return context.refDefinition(INSTANCE);
        }
        Schema matcher = documented(new Schema(), "Patch matcher",
                "Choose an exact value, an array-contains matcher, or ordinary object fields to match.");
        matcher.setOneOf(exactValueVariant(context), containsVariant(context), ordinaryFieldsVariant(context));
        return matcher;
    }

    private static ObjectSchema exactValueVariant(SchemaContext context) {
        ObjectSchema exact = documented(closedObject(), "Exact value",
                "Matches only when the candidate is exactly equal to the supplied JSON value.");
        exact.setProperties(Map.of("$Equals",
                documented(context.refDefinition(PatchJsonValueCodec.INSTANCE), "Expected JSON value",
                        "The complete value the candidate must equal.")));
        exact.setRequired("$Equals");
        return exact;
    }

    private static ObjectSchema containsVariant(SchemaContext context) {
        ObjectSchema contains = documented(closedObject(), "Contains",
                "Matches an array when at least one entry satisfies the nested matcher.");
        contains.setProperties(Map.of("$Contains",
                documented(context.refDefinition(INSTANCE), "Nested matcher",
                        "Matcher applied to each candidate array entry.")));
        contains.setRequired("$Contains");
        return contains;
    }

    private static ObjectSchema ordinaryFieldsVariant(SchemaContext context) {
        ObjectSchema fields = documented(new ObjectSchema(), "Object fields",
                "Add one or more target object fields. Every listed field must match.");
        StringSchema propertyNames = new StringSchema();
        propertyNames.setPattern("^(?!\\$).+");
        fields.setPropertyNames(propertyNames);
        fields.setAdditionalProperties(context.refDefinition(PatchMatcherValueSchema.INSTANCE));
        return fields;
    }

    private static ObjectSchema closedObject() {
        ObjectSchema object = new ObjectSchema();
        object.setAdditionalProperties(false);
        return object;
    }

    private static <T extends Schema> T documented(T schema, String title, String documentation) {
        schema.setTitle(title);
        schema.setMarkdownDescription(documentation);
        return schema;
    }
}
