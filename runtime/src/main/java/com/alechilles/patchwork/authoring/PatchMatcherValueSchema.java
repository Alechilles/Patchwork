package com.alechilles.patchwork.authoring;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.SchemaConvertable;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.NullSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import javax.annotation.Nonnull;

/** Recursive schema for values stored under ordinary matcher property names. */
final class PatchMatcherValueSchema implements SchemaConvertable<Void> {
    static final PatchMatcherValueSchema INSTANCE = new PatchMatcherValueSchema();

    private PatchMatcherValueSchema() {
    }

    @Nonnull
    @Override
    public Schema toSchema(@Nonnull SchemaContext context) {
        return PatchSchemaDefinitions.ref(context, "PatchMatcherValue", () -> definitionSchema(context));
    }

    private static Schema definitionSchema(SchemaContext context) {
        Schema matcherValue = documented(new Schema(), "Matcher value",
                "Use a literal JSON value, an exact array value, or another nested matcher.");
        ArraySchema array = documented(new ArraySchema(PatchJsonValueCodec.INSTANCE.toSchema(context)),
                "Array", "An array that must match exactly, including entry order.");
        matcherValue.setOneOf(
                documented(new NullSchema(), "Null", "Matches JSON null."),
                documented(Codec.BOOLEAN.toSchema(context), "Boolean", "Matches true or false exactly."),
                documented(Codec.DOUBLE.toSchema(context), "Number", "Matches a JSON number exactly."),
                documented(Codec.STRING.toSchema(context), "String", "Matches text exactly."),
                array,
                documented(PatchMatcherCodec.INSTANCE.toSchema(context), "Nested matcher",
                        "Recursively matches fields or uses $Equals or $Contains."));
        return matcherValue;
    }

    private static <T extends Schema> T documented(T schema, String title, String documentation) {
        schema.setTitle(title);
        schema.setMarkdownDescription(documentation);
        return schema;
    }
}
