package com.alechilles.patchwork.authoring;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.BooleanSchema;
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

/** Lossless BSON codec with a beginner-facing schema for Patchwork conditions. */
final class PatchConditionCodec implements Codec<BsonDocument> {
    static final PatchConditionCodec INSTANCE = new PatchConditionCodec();

    private PatchConditionCodec() {
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
        return PatchSchemaDefinitions.ref(context, "PatchCondition", () -> definitionSchema(context));
    }

    private static Schema definitionSchema(SchemaContext context) {
        ObjectSchema condition = documented(new ObjectSchema(), "Patch condition",
                "Set exactly one condition property. Add $Comment when you want to explain why the condition exists.");
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("$Comment", documented(Codec.STRING.toSchema(context), "Comment",
                "Optional note for people reading this patch. It does not affect evaluation."));
        properties.put("ModInstalled", nonblankString("Mod ID",
                "Namespaced ID of the mod that must be installed, such as Example:Livestock."));
        properties.put("ModVersion", modVersionSchema());
        properties.put("ServerVersion", versionComparisonSchema("Server version comparison"));
        properties.put("GameVersion", versionComparisonSchema("Game version comparison"));
        properties.put("AssetExists", assetReferenceSchema());
        properties.put("AssetMissing", assetReferenceSchema());
        properties.put("TargetExists", targetExistsSchema(context));
        properties.put("TargetProvidedBy", nonblankString("Provider mod ID",
                "Namespaced ID of the mod that must currently provide the target asset."));
        properties.put("JsonPathExists", jsonPathFields(context, false));
        properties.put("JsonPathEquals", jsonPathFields(context, true));
        properties.put("All", recursiveList(context, "All conditions",
                "Applies only when every nested condition is true."));
        properties.put("Any", recursiveList(context, "Any conditions",
                "Applies when at least one nested condition is true."));
        properties.put("Not", documented(INSTANCE.toSchema(context), "Nested condition",
                "The condition whose result should be inverted."));
        condition.setProperties(properties);
        condition.setAdditionalProperties(false);
        return condition;
    }

    private static ArraySchema recursiveList(SchemaContext context, String title, String documentation) {
        ArraySchema values = new ArraySchema(INSTANCE.toSchema(context));
        values.setMinItems(1);
        return documented(values, title, documentation + " Add one or more conditions to this group.");
    }

    private static BooleanSchema targetExistsSchema(SchemaContext context) {
        BooleanSchema targetExists = documented((BooleanSchema) Codec.BOOLEAN.toSchema(context), "Must exist",
                "Keep enabled to require the patch target to exist before this patch is eligible.");
        targetExists.setDefault(true);
        return targetExists;
    }

    private static Schema assetReferenceSchema() {
        Schema asset = documented(new Schema(), "Asset reference",
                "Enter an asset path directly, or use the expanded Asset object form.");
        ObjectSchema expanded = documented(closedObject(), "Expanded asset reference",
                "References one asset using its forward-slash path.");
        expanded.setProperties(Map.of("Asset", nonblankString("Asset path",
                "Forward-slash asset path such as Server/Item/Items/Example.json.")));
        expanded.setRequired("Asset");
        asset.setOneOf(
                nonblankString("Asset path", "Forward-slash asset path such as Server/Item/Items/Example.json."),
                expanded);
        return asset;
    }

    private static ObjectSchema modVersionSchema() {
        ObjectSchema version = versionComparisonSchema("Mod version comparison");
        Map<String, Schema> properties = new LinkedHashMap<>(version.getProperties());
        properties.put("Mod", nonblankString("Mod ID", "Namespaced ID of the installed mod to inspect."));
        version.setProperties(properties);
        version.setRequired("Mod");
        return version;
    }

    private static ObjectSchema versionComparisonSchema(String title) {
        ObjectSchema comparison = documented(closedObject(), title,
                "Set at least one comparison. Versions use numeric dot-separated components such as 1.2.0.");
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("Equals", versionString("Exact version", "Requires exactly this version."));
        properties.put("AtLeast", versionString("Minimum version", "Requires this version or a newer one."));
        properties.put("AtMost", versionString("Maximum version", "Requires this version or an older one."));
        properties.put("Above", versionString("Exclusive minimum", "Requires a version newer than this one."));
        properties.put("Below", versionString("Exclusive maximum", "Requires a version older than this one."));
        comparison.setProperties(properties);
        comparison.setAnyOf(
                required("Equals"),
                required("AtLeast"),
                required("AtMost"),
                required("Above"),
                required("Below"));
        return comparison;
    }

    private static StringSchema versionString(String title, String documentation) {
        StringSchema version = nonblankString(title, documentation);
        version.setPattern("^[0-9]+(?:\\.[0-9]+)*$");
        return version;
    }

    private static ObjectSchema jsonPathFields(SchemaContext context, boolean includeExpectedValue) {
        ObjectSchema fields = documented(closedObject(),
                includeExpectedValue ? "JSON value comparison" : "JSON path lookup",
                "Reads from this patch's target by default. Set either Asset or Source to read elsewhere.");
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("Path", documented(Codec.STRING.toSchema(context), "JSON Pointer",
                "Path to inspect, such as /Container/Capacity. Use an empty string for the source root."));
        properties.put("Asset", nonblankString("Asset path",
                "Legacy shorthand for reading another asset. Do not combine it with Source."));
        properties.put("Source", sourceSchema());
        if (includeExpectedValue) {
            properties.put("Value", documented(PatchJsonValueCodec.INSTANCE.toSchema(context), "Expected value",
                    "The JSON value that must be present at Path."));
            properties.put("Equals", documented(PatchJsonValueCodec.INSTANCE.toSchema(context), "Expected value (legacy)",
                    "Accepted legacy alias for Value. Prefer Value in new patches."));
        }
        fields.setProperties(properties);
        fields.setRequired("Path");
        if (includeExpectedValue) {
            fields.setAnyOf(required("Value"), required("Equals"));
        }
        return fields;
    }

    private static Schema required(String field) {
        Schema schema = new Schema();
        schema.setRequired(field);
        return schema;
    }

    private static ObjectSchema sourceSchema() {
        ObjectSchema source = documented(new ObjectSchema(), "Source", "Choose where the JSON path should be read from.");
        Map<String, Schema> properties = new LinkedHashMap<>();
        StringSchema sourceType = new StringSchema();
        sourceType.setEnum(new String[] {"Target", "Asset", "ModData"});
        sourceType.setMarkdownEnumDescriptions(new String[] {
                "Read this patch's elected target asset.",
                "Read another elected asset at Source path.",
                "Read a registered mod data file using Mod ID and Source path."
        });
        properties.put("Type", documented(sourceType, "Source type",
                "Target reads this patch's elected target. Asset reads the path below. ModData reads a registered mod data file."));
        properties.put("Mod", nonblankString("Mod ID",
                "Required only for ModData: namespaced ID of the mod whose data file should be read."));
        properties.put("Path", nonblankString("Source path",
                "Required for Asset or ModData. Leave blank for Target."));
        source.setProperties(properties);
        source.setRequired("Type");
        source.setAdditionalProperties(false);
        return source;
    }

    private static ObjectSchema closedObject() {
        ObjectSchema object = new ObjectSchema();
        object.setAdditionalProperties(false);
        return object;
    }

    private static StringSchema nonblankString(String title, String documentation) {
        StringSchema value = new StringSchema();
        value.setMinLength(1);
        return documented(value, title, documentation);
    }

    private static <T extends Schema> T documented(T schema, String title, String documentation) {
        schema.setTitle(title);
        schema.setMarkdownDescription(documentation);
        return schema;
    }
}
