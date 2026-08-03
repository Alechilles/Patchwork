package com.alechilles.patchwork.authoring;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.EnumCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/** Schema-facing representation of one portable Patchwork operation. */
final class PatchOperationAsset {
    private enum OperationType {
        RequireFormat,
        Add,
        Merge,
        Replace,
        Remove,
        Insert,
        ReplaceMatching,
        RemoveMatching,
        MoveMatching,
        MergeMatching,
        UpsertMatching,
        OverlayFromAsset,
        MergeObjectFromAsset,
        Macro
    }

    private enum Position {
        Start,
        End,
        Before,
        After
    }

    private enum MatchPolicy {
        ExactlyOne,
        First,
        Last,
        All
    }

    private static final EnumCodec<OperationType> OPERATION_TYPE_CODEC = new EnumCodec<>(OperationType.class)
            .documentKey(OperationType.RequireFormat,
                    "Compatibility operation retained when reopening an explicit format-2 file. New neutral files do not need this sentinel.")
            .documentKey(OperationType.Add,
                    "Adds or overwrites an object field, or inserts/appends an array entry at Path.")
            .documentKey(OperationType.Merge,
                    "Recursively merges the object in Value into the existing object at Path.")
            .documentKey(OperationType.Replace,
                    "Replaces an existing object field or array entry at Path with Value.")
            .documentKey(OperationType.Remove,
                    "Removes the existing object field or array entry at Path.")
            .documentKey(OperationType.Insert,
                    "Inserts Value into an existing array, optionally using Position and Find to choose the location.")
            .documentKey(OperationType.ReplaceMatching,
                    "Replaces array entries selected by Match and MatchPolicy with Value.")
            .documentKey(OperationType.RemoveMatching,
                    "Removes array entries selected by Match and MatchPolicy.")
            .documentKey(OperationType.MoveMatching,
                    "Moves exactly one array entry selected by Match to Position, optionally relative to Find.")
            .documentKey(OperationType.MergeMatching,
                    "Deep-merges Value into every object entry selected by Match. Neutral definitions only; MatchPolicy defaults to ExactlyOne.")
            .documentKey(OperationType.UpsertMatching,
                    "Deep-merges Value into selected object entries, or inserts one Value object when no entry matches. Neutral definitions only.")
            .documentKey(OperationType.OverlayFromAsset,
                    "Overlay entire asset — deep-merges an exact source asset onto the target; source leaves win and unrelated target fields remain.")
            .documentKey(OperationType.MergeObjectFromAsset,
                    "Merge object from asset — deep-merges a selected source object into an existing target object; SourcePath defaults to the source root.")
            .documentKey(OperationType.Macro,
                    "Runs a macro supplied by an embedding mod, using Macro as its ID and Options as its input.");
    private static final EnumCodec<Position> POSITION_CODEC = new EnumCodec<>(Position.class)
            .documentKey(Position.Start, "Place the entry at the beginning of the array. Do not set Find.")
            .documentKey(Position.End, "Place the entry at the end of the array. This is the default. Do not set Find.")
            .documentKey(Position.Before, "Place the entry immediately before the first entry matching Find. Find is required.")
            .documentKey(Position.After, "Place the entry immediately after the first entry matching Find. Find is required.");
    private static final EnumCodec<MatchPolicy> MATCH_POLICY_CODEC = new EnumCodec<>(MatchPolicy.class)
            .documentKey(MatchPolicy.ExactlyOne, "Require exactly one matching entry; zero or multiple matches fail for merge, replace, remove, and move operations. UpsertMatching inserts one Value object when zero entries match. This is the default.")
            .documentKey(MatchPolicy.First, "Use only the first matching array entry.")
            .documentKey(MatchPolicy.Last, "Use only the last matching array entry.")
            .documentKey(MatchPolicy.All, "Use every matching array entry.");

    private static final BuilderCodec<PatchOperationAsset> BUILDER_CODEC = BuilderCodec
            .builder(PatchOperationAsset.class, PatchOperationAsset::new)
            .documentation("One ordered Patchwork step. Select Op first, then fill only the fields used by that operation.")
            .append(new KeyedCodec<>("Id", Codec.STRING),
                    (operation, value) -> operation.id = value, operation -> operation.id)
            .documentation("Optional stable name for this step. Defaults to the patch ID followed by the operation's array index.")
            .add()
            .append(new KeyedCodec<>("Op", OPERATION_TYPE_CODEC),
                    (operation, value) -> operation.op = value, operation -> operation.op)
            .documentation("The kind of change this step performs. Select a value to see what the operation does.")
            .add()
            .append(new KeyedCodec<>("Version", Codec.INTEGER),
                    (operation, value) -> operation.version = value, operation -> operation.version)
            .documentation("Compatibility version retained by RequireFormat in explicit format-2 files. New neutral files do not need this field.")
            .add()
            .append(new KeyedCodec<>("Path", Codec.STRING),
                    (operation, value) -> operation.path = value, operation -> operation.path)
            .documentation("Location inside the target JSON, written as a JSON Pointer such as /Container/Capacity. Use ~1 for '/' and ~0 for '~' inside a key.")
            .add()
            .append(new KeyedCodec<>("Source", Codec.STRING),
                    (operation, value) -> operation.source = value, operation -> operation.source)
            .documentation("Exact source asset path for OverlayFromAsset or MergeObjectFromAsset. Globs are not accepted; missing or incompatible sources are ordinary applicability failures when Required is false.")
            .add()
            .append(new KeyedCodec<>("SourcePath", Codec.STRING),
                    (operation, value) -> operation.sourcePath = value, operation -> operation.sourcePath)
            .documentation("Optional JSON Pointer into Source for MergeObjectFromAsset. Defaults to the source root; the selected source value must be an object.")
            .add()
            .append(new KeyedCodec<>("Value", PatchJsonValueCodec.INSTANCE),
                    (operation, value) -> operation.value = value, operation -> operation.value)
            .documentation("JSON value to add, merge, replace, or insert. Its allowed shape depends on Op; Merge, MergeMatching, and UpsertMatching require an object.")
            .add()
            .append(new KeyedCodec<>("Position", POSITION_CODEC),
                    (operation, value) -> operation.position = value, operation -> operation.position)
            .documentation("Where Insert, MoveMatching, or a zero-match UpsertMatching places an array entry. Defaults to End. Before and After require Find; Start and End forbid Find.")
            .add()
            .append(new KeyedCodec<>("Match", PatchMatcherCodec.INSTANCE),
                    (operation, value) -> operation.match = value, operation -> operation.match)
            .documentation("Matcher that selects array entries for ReplaceMatching, RemoveMatching, MoveMatching, MergeMatching, or UpsertMatching. Declare only the fields that must match.")
            .add()
            .append(new KeyedCodec<>("MatchPolicy", MATCH_POLICY_CODEC),
                    (operation, value) -> operation.matchPolicy = value, operation -> operation.matchPolicy)
            .documentation("How ReplaceMatching, RemoveMatching, MergeMatching, or UpsertMatching chooses among matches. Defaults to ExactlyOne; UpsertMatching inserts one Value object on zero matches regardless of policy. MoveMatching always requires exactly one match.")
            .add()
            .append(new KeyedCodec<>("Find", PatchMatcherCodec.INSTANCE),
                    (operation, value) -> operation.find = value, operation -> operation.find)
            .documentation("Anchor matcher used with Position Before or After. Patchwork inserts or moves relative to the first matching array entry, including a zero-match UpsertMatching.")
            .add()
            .append(new KeyedCodec<>("Existing", PatchMatcherCodec.INSTANCE),
                    (operation, value) -> operation.existing = value, operation -> operation.existing)
            .documentation("Optional Insert-only matcher. If an existing array entry matches, Patchwork skips the insertion to avoid a duplicate.")
            .add()
            .append(new KeyedCodec<>("Macro", Codec.STRING),
                    (operation, value) -> operation.macro = value, operation -> operation.macro)
            .documentation("ID of the host-provided macro to run when Op is Macro. The embedding mod defines which macro IDs are available.")
            .add()
            .append(new KeyedCodec<>("Options", PatchJsonObjectCodec.INSTANCE),
                    (operation, value) -> operation.options = value, operation -> operation.options)
            .documentation("Optional object passed to a Macro. The embedding mod that provides the macro defines its available options.")
            .add()
            .append(new KeyedCodec<>("Required", Codec.BOOLEAN),
                    (operation, value) -> operation.required = value, operation -> operation.required)
            .documentation("Whether failure stops this target from being published. Defaults to true; false reports and skips an inapplicable operation.")
            .add()
            .build();
    private static final PortableObjectCodec<PatchOperationAsset> PORTABLE_CODEC = new PortableObjectCodec<>(BUILDER_CODEC, Set.of(
            "Id", "Op", "Version", "Path", "Source", "SourcePath", "Value", "Position", "Match", "MatchPolicy",
            "Find", "Existing", "Macro", "Options", "Required"));

    /**
     * Portable operation codec.  Explicit version-2 sentinels remain decodable,
     * but the native editor's operation picker omits that compatibility-only
     * choice for new neutral definitions.
     */
    static final OperationAssetCodec CODEC = new OperationAssetCodec();

    static final class OperationAssetCodec implements Codec<PatchOperationAsset> {
        @Override
        public PatchOperationAsset decode(BsonValue value, ExtraInfo extraInfo) {
            return PORTABLE_CODEC.decode(value, extraInfo);
        }

        @Override
        public BsonValue encode(PatchOperationAsset value, ExtraInfo extraInfo) {
            return PORTABLE_CODEC.encode(value, extraInfo);
        }

        @Override
        public PatchOperationAsset decodeJson(@Nonnull RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
            return PORTABLE_CODEC.decodeJson(reader, extraInfo);
        }

        @Nonnull
        @Override
        public ObjectSchema toSchema(@Nonnull SchemaContext context) {
            ObjectSchema schema = PORTABLE_CODEC.toSchema(context);
            Schema operation = schema.getProperties().get("Op");
            if (operation instanceof StringSchema choices) {
                String[] values = choices.getEnum();
                String[] descriptions = choices.getMarkdownEnumDescriptions();
                String[] enumDescriptions = choices.getEnumDescriptions();
                int keep = (int) Arrays.stream(values)
                        .filter(value -> !OperationType.RequireFormat.name().equals(value))
                        .count();
                String[] filteredValues = new String[keep];
                String[] filteredDescriptions = descriptions == null ? null : new String[keep];
                String[] filteredEnumDescriptions = enumDescriptions == null ? null : new String[keep];
                int index = 0;
                for (int source = 0; source < values.length; source++) {
                    if (OperationType.RequireFormat.name().equals(values[source])) continue;
                    filteredValues[index] = values[source];
                    if (filteredDescriptions != null) {
                        String description = source < descriptions.length ? descriptions[source] : null;
                        filteredDescriptions[index] = description == null || description.isBlank()
                                ? "Patchwork operation " + values[source] + "."
                                : description;
                    }
                    if (filteredEnumDescriptions != null) {
                        String description = source < enumDescriptions.length ? enumDescriptions[source] : null;
                        filteredEnumDescriptions[index] = description == null || description.isBlank()
                                ? "Patchwork operation " + values[source] + "."
                                : description;
                    }
                    index++;
                }
                choices.setEnum(filteredValues);
                if (filteredDescriptions != null) choices.setMarkdownEnumDescriptions(filteredDescriptions);
                if (filteredEnumDescriptions != null) choices.setEnumDescriptions(filteredEnumDescriptions);
            }
            return schema;
        }
    }

    private String id;
    private OperationType op;
    private Integer version;
    private String path;
    private String source;
    private String sourcePath;
    private BsonValue value;
    private Position position;
    private BsonDocument match;
    private MatchPolicy matchPolicy;
    private BsonDocument find;
    private BsonDocument existing;
    private String macro;
    private BsonDocument options;
    private Boolean required;
    private JsonObject portableSource;

    private PatchOperationAsset() {
    }

    JsonObject toPortableJson() {
        if (portableSource != null) return portableSource.deepCopy();
        JsonObject result = new JsonObject();
        add(result, "Id", id);
        add(result, "Op", name(op));
        if (version != null) result.addProperty("Version", version);
        add(result, "Path", path);
        add(result, "Source", source);
        add(result, "SourcePath", sourcePath);
        if (value != null) result.add("Value", json(value));
        add(result, "Position", name(position));
        add(result, "Match", match);
        add(result, "MatchPolicy", name(matchPolicy));
        add(result, "Find", find);
        add(result, "Existing", existing);
        add(result, "Macro", macro);
        add(result, "Options", options);
        if (required != null) result.addProperty("Required", required);
        return result;
    }

    static PatchOperationAsset fromPortableJson(JsonObject root) {
        PatchOperationAsset operation = new PatchOperationAsset();
        operation.portableSource = root.deepCopy();
        operation.id = string(root, "Id");
        operation.op = enumValue(root, "Op", OperationType.class);
        operation.version = integer(root, "Version");
        operation.path = string(root, "Path");
        operation.source = string(root, "Source");
        operation.sourcePath = string(root, "SourcePath");
        if (root.has("Value")) operation.value = PortableJsonBsonDocument.mirror(root.get("Value"));
        operation.position = enumValue(root, "Position", Position.class);
        operation.match = object(root, "Match");
        operation.matchPolicy = enumValue(root, "MatchPolicy", MatchPolicy.class);
        operation.find = object(root, "Find");
        operation.existing = object(root, "Existing");
        operation.macro = string(root, "Macro");
        operation.options = object(root, "Options");
        if (root.get("Required") != null && root.get("Required").isJsonPrimitive()
                && root.get("Required").getAsJsonPrimitive().isBoolean()) {
            operation.required = root.get("Required").getAsBoolean();
        }
        return operation;
    }

    private static String string(JsonObject root, String name) {
        var value = root.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static Integer integer(JsonObject root, String name) {
        var value = root.get(name);
        try {
            return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    ? value.getAsBigDecimal().intValueExact() : null;
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
    }

    private static <E extends Enum<E>> E enumValue(JsonObject root, String name, Class<E> type) {
        String value = string(root, name);
        if (value == null) return null;
        for (E candidate : type.getEnumConstants()) {
            if (candidate.name().equalsIgnoreCase(value)) return candidate;
        }
        return null;
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static BsonDocument object(JsonObject root, String name) {
        var value = root.get(name);
        return value instanceof JsonObject object
                ? (BsonDocument) PortableJsonBsonDocument.mirror(object) : null;
    }

    private static void add(JsonObject result, String name, String value) {
        if (value != null) result.addProperty(name, value);
    }

    private static void add(JsonObject result, String name, BsonDocument value) {
        if (value != null) result.add(name, JsonParser.parseString(value.toJson()));
    }

    private static com.google.gson.JsonElement json(BsonValue value) {
        return JsonParser.parseString(new BsonDocument("value", value).toJson())
                .getAsJsonObject().get("value");
    }
}
