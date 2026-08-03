package com.alechilles.patchwork.authoring;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.codecs.BsonDocumentCodec;
import com.hypixel.hytale.codec.schema.NamedSchema;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
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
final class PatchConditionCodec implements Codec<BsonDocument>, NamedSchema {
    static final PatchConditionCodec INSTANCE = new PatchConditionCodec();
    private static final BsonDocumentCodec DOCUMENT_CODEC = new BsonDocumentCodec();

    private PatchConditionCodec() {
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
        return "PatchCondition";
    }

    @Nonnull
    @Override
    public Schema toSchema(@Nonnull SchemaContext context) {
        if (context.getRawDefinition(INSTANCE) == null) {
            return context.refDefinition(INSTANCE);
        }
        Schema condition = documented(new Schema(), "Patch condition",
                "Choose one condition. Add $Comment when you want to explain why the condition exists.");
        condition.setOneOf(
                conditionVariant("ModInstalled", nonblankString("Mod ID",
                                "Namespaced ID of the mod that must be installed, such as Example:Livestock."),
                        "ModInstalled", "Applies only when the named mod is installed."),
                conditionVariant("ModVersion", modVersionSchema(),
                        "ModVersion", "Compares the installed version of a named mod."),
                conditionVariant("ServerVersion", versionComparisonSchema("Server version comparison"),
                        "ServerVersion", "Compares the running Hytale server version."),
                conditionVariant("GameVersion", versionComparisonSchema("Game version comparison"),
                        "GameVersion", "Portable alias for ServerVersion."),
                conditionVariant("AssetExists", assetReferenceSchema(),
                        "AssetExists", "Applies only when the referenced asset exists."),
                conditionVariant("AssetMissing", assetReferenceSchema(),
                        "AssetMissing", "Applies only when the referenced asset is missing."),
                conditionVariant("TargetExists", documented(Codec.BOOLEAN.toSchema(context), "Must exist",
                                "Set to true to require the patch target to exist before this patch is eligible."),
                        "TargetExists", "Applies only when this patch's target asset exists."),
                conditionVariant("TargetProvidedBy", nonblankString("Provider mod ID",
                                "Namespaced ID of the mod that must currently provide the target asset."),
                        "TargetProvidedBy", "Applies only when the target asset is provided by the named mod."),
                conditionVariant("JsonPathExists", jsonPathFields(context, false),
                        "JsonPathExists", "Checks whether a JSON Pointer exists in the selected source."),
                conditionVariant("JsonPathEquals", jsonPathFields(context, true),
                        "JsonPathEquals", "Compares the value at a JSON Pointer in the selected source."),
                recursiveList(context, "All", "All",
                        "Applies only when every nested condition is true."),
                recursiveList(context, "Any", "Any",
                        "Applies when at least one nested condition is true."),
                conditionVariant("Not", documented(context.refDefinition(INSTANCE), "Nested condition",
                                "The condition whose result should be inverted."),
                        "Not", "Applies only when the nested condition is false."));
        return condition;
    }

    private static ObjectSchema recursiveList(
            SchemaContext context, String key, String title, String documentation) {
        ArraySchema values = new ArraySchema(context.refDefinition(INSTANCE));
        values.setMinItems(1);
        documented(values, "Nested conditions", "Add one or more conditions to this group.");
        return conditionVariant(key, values, title, documentation);
    }

    private static ObjectSchema conditionVariant(
            String key, Schema value, String title, String documentation) {
        ObjectSchema variant = documented(closedObject(), title, documentation);
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put(key, value);
        properties.put("$Comment", documented(Codec.STRING.toSchema(new SchemaContext()), "Comment",
                "Optional note for people reading this patch. It does not affect evaluation."));
        variant.setProperties(properties);
        variant.setRequired(key);
        return variant;
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
            properties.put("Value", documented(context.refDefinition(PatchJsonValueCodec.INSTANCE), "Expected value",
                    "The JSON value that must be present at Path."));
            properties.put("Equals", documented(context.refDefinition(PatchJsonValueCodec.INSTANCE), "Expected value (legacy)",
                    "Accepted legacy alias for Value. Prefer Value in new patches."));
        }
        fields.setProperties(properties);
        fields.setRequired("Path");
        return fields;
    }

    private static Schema sourceSchema() {
        Schema source = documented(new Schema(), "Source", "Choose where the JSON path should be read from.");
        source.setOneOf(
                sourceVariant("Target", Map.of(), "Target source",
                        "Reads from this patch's elected target asset."),
                sourceVariant("Asset", Map.of("Path", nonblankString("Asset path",
                                "Forward-slash path of the asset to read.")),
                        "Asset source", "Reads from another elected asset."),
                sourceVariant("ModData", Map.of(
                                "Mod", nonblankString("Mod ID", "Namespaced ID of the mod whose data file should be read."),
                                "Path", nonblankString("Data path", "Forward-slash path inside that mod's data root.")),
                        "ModData source", "Reads a data file registered by another mod."));
        return source;
    }

    private static ObjectSchema sourceVariant(
            String type, Map<String, Schema> fields, String title, String documentation) {
        ObjectSchema source = documented(closedObject(), title, documentation);
        Map<String, Schema> properties = new LinkedHashMap<>();
        StringSchema sourceType = new StringSchema();
        sourceType.setConst(type);
        properties.put("Type", documented(sourceType, "Source type",
                "Selects the " + type + " source form."));
        properties.putAll(fields);
        source.setProperties(properties);
        String[] required = new String[fields.size() + 1];
        required[0] = "Type";
        int index = 1;
        for (String field : fields.keySet()) required[index++] = field;
        source.setRequired(required);
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
