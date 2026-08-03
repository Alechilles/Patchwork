package com.alechilles.patchwork.engine;

import com.alechilles.patchwork.format.JsonMatcher;
import com.alechilles.patchwork.format.JsonPointer;
import com.alechilles.patchwork.format.PatchFormat;
import com.alechilles.patchwork.format.PatchLanguage;
import com.alechilles.patchwork.discovery.PatchScanner;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Set;

/** One raw or host-expanded operation in a Patchwork definition. */
public final class PatchOperation {
    private final String id;
    private final String op;
    private final String path;
    private final String source;
    private final String sourcePath;
    private final String position;
    private final String macro;
    private final String matchPolicy;
    private final PatchLanguage language;
    private final Integer version;
    private final boolean required;
    private final JsonElement value;
    private final JsonObject find;
    private final JsonObject existing;
    private final JsonObject options;
    private final JsonObject match;

    private PatchOperation(
            String id,
            String op,
            String path,
            String source,
            String sourcePath,
            String position,
            String macro,
            boolean required,
            JsonElement value,
            JsonObject find,
            JsonObject existing,
            JsonObject options,
            PatchLanguage language,
            Integer version,
            JsonObject match,
            String matchPolicy) {
        this.id = id;
        this.op = op;
        this.path = path;
        this.source = source;
        this.sourcePath = sourcePath;
        this.position = position;
        this.macro = macro;
        this.required = required;
        this.value = copy(value);
        this.find = copy(find);
        this.existing = copy(existing);
        this.options = copy(options);
        this.language = java.util.Objects.requireNonNull(language, "language");
        this.version = version;
        this.match = copy(match);
        this.matchPolicy = matchPolicy;
    }

    static PatchOperation parse(JsonObject object, String patchId, int index) {
        return parse(object, patchId, index, PatchLanguage.LEGACY_V1);
    }

    static PatchOperation parse(JsonObject object, String patchId, int index, int formatVersion) {
        return parse(object, patchId, index, languageForVersion(formatVersion));
    }

    static PatchOperation parse(JsonObject object, String patchId, int index, PatchLanguage language) {
        String operation = PatchDefinition.readRequiredString(object, "Op", patchId + " operation " + index);
        if (language.closedStructure()) {
            validateClosedOperation(object, operation, patchId, index, language);
        }
        Integer version = object.has("Version")
                ? (language.closedStructure() ? readVersion(object, patchId, index) : readLegacyVersion(object))
                : null;
        JsonObject match = object(object, "Match");
        String matchPolicy = language.closedStructure() && object.has("MatchPolicy")
                ? strictString(object, "MatchPolicy", patchId + " operation " + index)
                : PatchDefinition.readString(object, "MatchPolicy", null);
        String position = language.closedStructure() && object.has("Position")
                ? strictString(object, "Position", patchId + " operation " + index)
                : PatchDefinition.readString(object, "Position", null);
        String source = crossAssetOperation(operation) && object.has("Source")
                ? normalizeSource(strictString(object, "Source", patchId + " operation " + index), patchId, index)
                : null;
        String sourcePath = crossAssetOperation(operation) && object.has("SourcePath")
                ? readSourcePath(object, patchId, index)
                : null;
        return new PatchOperation(
                PatchDefinition.readString(object, "Id", patchId + "#" + index),
                operation,
                PatchDefinition.readString(object, "Path", null),
                source,
                sourcePath,
                position,
                PatchDefinition.readString(object, "Macro", null),
                language.closedStructure() && object.has("Required")
                        ? strictBoolean(object, "Required", patchId + " operation " + index)
                        : PatchDefinition.readBoolean(object, "Required", true),
                object.get("Value"),
                object(object, "Find"),
                object(object, "Existing"),
                object(object, "Options"),
                language,
                version,
                match,
                matchPolicy);
    }

    /** Parses a host-expanded operation with a stable synthetic source position. */
    public static PatchOperation parseHostOperation(JsonObject object, String patchId) {
        return parseHostOperation(object, patchId, PatchLanguage.LEGACY_V1);
    }

    /** Parses a host-expanded operation using the enclosing compatibility version. */
    public static PatchOperation parseHostOperation(JsonObject object, String patchId, int formatVersion) {
        return parseHostOperation(object, patchId, languageForVersion(formatVersion));
    }

    /** Parses a host-expanded operation using the enclosing root language. */
    public static PatchOperation parseHostOperation(JsonObject object, String patchId, PatchLanguage language) {
        return parse(object, patchId, 0, language);
    }

    /** Serializes this operation for the isolated host macro boundary. */
    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("Id", id);
        object.addProperty("Op", op);
        if (version != null) object.addProperty("Version", version);
        if (path != null) object.addProperty("Path", path);
        if (position != null) object.addProperty("Position", position);
        if (source != null) object.addProperty("Source", source);
        if (sourcePath != null) object.addProperty("SourcePath", sourcePath);
        if (macro != null) object.addProperty("Macro", macro);
        if (!(language.requiresFormatSentinel() && "RequireFormat".equals(op))) {
            object.addProperty("Required", required);
        }
        if (value != null) object.add("Value", value());
        if (match != null) object.add("Match", match());
        if (matchPolicy != null) object.addProperty("MatchPolicy", matchPolicy);
        if (find != null) object.add("Find", find());
        if (existing != null) object.add("Existing", existing());
        if (options != null) object.add("Options", options());
        return object;
    }

    /** Creates an explicit non-macro operation using legacy compatibility semantics. */
    public static PatchOperation raw(
            String id,
            String op,
            String path,
            String position,
            boolean required,
            JsonElement value,
            JsonObject find,
            JsonObject existing) {
        return new PatchOperation(id, op, path, null, null, position, null, required, value, find, existing, null,
                PatchLanguage.LEGACY_V1, null, null, null);
    }

    /** Returns this operation with the supplied macro identifier. */
    public PatchOperation withMacro(String macroId) {
        return new PatchOperation(id, op, path, source, sourcePath, position, macroId, required, value, find, existing, options,
                language, version, match, matchPolicy);
    }

    public String id() { return id; }
    public String op() { return op; }
    public String path() { return path; }
    public String source() { return source; }
    public String sourcePath() { return sourcePath; }
    public String position() { return position; }
    public String macro() { return macro; }
    public boolean required() { return required; }
    public JsonElement value() { return copy(value); }
    public JsonObject find() { return copy(find); }
    public JsonObject existing() { return copy(existing); }
    public JsonObject options() { return copy(options); }
    public PatchLanguage language() { return language; }

    /** Compatibility view retained for existing embedders. */
    public int formatVersion() { return language.compatibilityVersion(); }

    public Integer version() { return version; }
    public JsonObject match() { return copy(match); }
    public String matchPolicy() { return matchPolicy; }

    public String getId() { return id(); }
    public String getOp() { return op(); }
    public String getPath() { return path(); }
    public String getSource() { return source(); }
    public String getSourcePath() { return sourcePath(); }
    public String getPosition() { return position(); }
    public String getMacro() { return macro(); }
    public boolean isRequired() { return required(); }
    public JsonElement getValue() { return value(); }
    public JsonObject getFind() { return find(); }
    public JsonObject getExisting() { return existing(); }
    public JsonObject getOptions() { return options(); }
    public PatchLanguage getLanguage() { return language(); }
    public int getFormatVersion() { return formatVersion(); }
    public Integer getVersion() { return version(); }
    public JsonObject getMatch() { return match(); }
    public String getMatchPolicy() { return matchPolicy(); }

    private static void validateClosedOperation(
            JsonObject operation,
            String op,
            String patchId,
            int index,
            PatchLanguage language) {
        Set<String> allowed;
        Set<String> required;
        switch (op) {
            case "RequireFormat" -> {
                if (language == PatchLanguage.NEUTRAL) {
                    throw structural(patchId, index, "RequireFormat is not part of neutral definitions.");
                }
                allowed = Set.of("Op", "Version", "Id");
                required = Set.of("Op", "Version");
            }
            case "ReplaceMatching" -> {
                allowed = Set.of("Op", "Path", "Match", "Value", "Id", "Required", "MatchPolicy");
                required = Set.of("Op", "Path", "Match", "Value");
            }
            case "RemoveMatching" -> {
                allowed = Set.of("Op", "Path", "Match", "Id", "Required", "MatchPolicy");
                required = Set.of("Op", "Path", "Match");
            }
            case "MoveMatching" -> {
                allowed = Set.of("Op", "Path", "Match", "Id", "Required", "Position", "Find");
                required = Set.of("Op", "Path", "Match");
            }
            case "MergeMatching", "UpsertMatching" -> {
                if (language != PatchLanguage.NEUTRAL) {
                    throw structural(patchId, index, "unsupported operation '" + op + "'.");
                }
                allowed = "MergeMatching".equals(op)
                        ? Set.of("Op", "Path", "Match", "Value", "Id", "Required", "MatchPolicy")
                        : Set.of("Op", "Path", "Match", "Value", "Id", "Required", "MatchPolicy", "Position", "Find");
                required = Set.of("Op", "Path", "Match", "Value");
            }
            case "OverlayFromAsset" -> {
                if (language != PatchLanguage.NEUTRAL) {
                    throw structural(patchId, index, "unsupported operation '" + op + "'.");
                }
                allowed = Set.of("Op", "Source", "Id", "Required");
                required = Set.of("Op", "Source");
            }
            case "MergeObjectFromAsset" -> {
                if (language != PatchLanguage.NEUTRAL) {
                    throw structural(patchId, index, "unsupported operation '" + op + "'.");
                }
                allowed = Set.of("Op", "Source", "SourcePath", "Path", "Id", "Required");
                required = Set.of("Op", "Source", "Path");
            }
            case "Add", "Merge", "Replace" -> {
                allowed = Set.of("Op", "Path", "Value", "Id", "Required");
                required = Set.of("Op", "Path", "Value");
            }
            case "Remove" -> {
                allowed = Set.of("Op", "Path", "Id", "Required");
                required = Set.of("Op", "Path");
            }
            case "Insert" -> {
                allowed = Set.of("Op", "Path", "Position", "Find", "Existing", "Value", "Id", "Required");
                required = Set.of("Op", "Path", "Value");
            }
            case "Macro" -> {
                allowed = Set.of("Op", "Macro", "Options", "Id", "Required");
                required = Set.of("Op", "Macro");
            }
            default -> {
                if ("RequireFormat".equalsIgnoreCase(op)) {
                    throw structural(patchId, index, "Op must use exact spelling RequireFormat.");
                }
                throw structural(patchId, index, "unsupported operation '" + op + "'.");
            }
        }
        for (String name : operation.keySet()) {
            if (!allowed.contains(name)) {
                throw structural(patchId, index, "field '" + name + "' is not allowed for " + op + ".");
            }
        }
        for (String name : required) {
            if (!operation.has(name)) {
                throw structural(patchId, index, "must define " + name + ".");
            }
        }
        if (operation.has("Id")) {
            String id = strictString(operation, "Id", patchId + " operation " + index);
            if (id.isBlank()) throw structural(patchId, index, "Id must be a non-empty string.");
        }
        if ("RequireFormat".equals(op)) {
            int version = readVersion(operation, patchId, index);
            if (version <= 0) throw structural(patchId, index, "Version must be a positive integer.");
            return;
        }
        if (Set.of("Add", "Merge", "Replace", "Remove", "Insert", "ReplaceMatching", "RemoveMatching", "MoveMatching",
                "MergeMatching", "UpsertMatching", "MergeObjectFromAsset")
                .contains(op)) {
            validatePath(operation, patchId, index);
        }
        if (Set.of("OverlayFromAsset", "MergeObjectFromAsset").contains(op)) {
            validateSource(operation, patchId, index);
        }
        if ("MergeObjectFromAsset".equals(op) && operation.has("SourcePath")) {
            validateSourcePath(operation, patchId, index);
        }
        if (Set.of("ReplaceMatching", "RemoveMatching", "MoveMatching", "MergeMatching", "UpsertMatching").contains(op)) {
            if (!operation.get("Match").isJsonObject()) {
                throw structural(patchId, index, "Match must be an object.");
            }
            validateMatcher(operation.get("Match"), "Match", patchId, index);
        }
        if (Set.of("Merge", "MergeMatching", "UpsertMatching").contains(op)
                && !operation.get("Value").isJsonObject()) {
            throw structural(patchId, index, op + " Value must be an object.");
        }
        if ("Macro".equals(op)) {
            String macro = strictString(operation, "Macro", patchId + " operation " + index);
            if (macro.isBlank()) throw structural(patchId, index, "Macro must be a non-empty string.");
            if (operation.has("Options") && !operation.get("Options").isJsonObject()) {
                throw structural(patchId, index, "Options must be an object.");
            }
            return;
        }
        if ("Insert".equals(op)) {
            validateInsert(operation, patchId, index);
            return;
        }
        if (operation.has("MatchPolicy")) {
            String policy = strictString(operation, "MatchPolicy", patchId + " operation " + index);
            if (!Set.of("exactlyone", "first", "last", "all").contains(policy.toLowerCase(Locale.ROOT))) {
                throw structural(patchId, index, "MatchPolicy must be ExactlyOne, First, Last, or All.");
            }
        }
        if ("UpsertMatching".equals(op)) {
            validateRelativePosition(operation, patchId, index, "UpsertMatching");
            return;
        }
        if ("MoveMatching".equals(op)) {
            validateRelativePosition(operation, patchId, index, "MoveMatching");
        }
    }

    private static void validateRelativePosition(JsonObject operation, String patchId, int index, String name) {
        String position = operation.has("Position")
                ? strictString(operation, "Position", patchId + " operation " + index) : "End";
        String normalized = position.toLowerCase(Locale.ROOT);
        if (!Set.of("start", "end", "before", "after").contains(normalized)) {
            throw structural(patchId, index, "Position must be Start, End, Before, or After.");
        }
        boolean hasFind = operation.has("Find");
        if (hasFind && !operation.get("Find").isJsonObject()) {
            throw structural(patchId, index, "Find must be an object.");
        }
        if (hasFind) validateMatcher(operation.get("Find"), "Find", patchId, index);
        if (("before".equals(normalized) || "after".equals(normalized)) != hasFind) {
            throw structural(patchId, index, ("before".equals(normalized) || "after".equals(normalized))
                    ? name + " " + position + " requires Find."
                    : "Find is only allowed with Position Before or After.");
        }
    }

    private static void validatePath(JsonObject operation, String patchId, int index) {
        JsonElement path = operation.get("Path");
        if (path == null || !path.isJsonPrimitive() || !path.getAsJsonPrimitive().isString()
                || path.getAsString().isBlank()) {
            throw structural(patchId, index, "Path must be a non-empty string.");
        }
        String pointer = path.getAsString();
        if (!pointer.startsWith("/")) {
            throw structural(patchId, index, "Path must use JSON pointer syntax and start with '/'.");
        }
        for (int i = 0; i < pointer.length(); i++) {
            if (pointer.charAt(i) == '~' && (i + 1 >= pointer.length()
                    || (pointer.charAt(i + 1) != '0' && pointer.charAt(i + 1) != '1'))) {
                throw structural(patchId, index, "Path contains an invalid JSON pointer escape.");
            }
        }
    }

    private static void validateSource(JsonObject operation, String patchId, int index) {
        final String source;
        try {
            source = strictString(operation, "Source", patchId + " operation " + index);
        } catch (IllegalArgumentException failure) {
            throw structural(patchId, index, "Source must be a string.");
        }
        try {
            normalizeSource(source, patchId, index);
        } catch (IllegalArgumentException failure) {
            if (failure.getMessage() != null && failure.getMessage().startsWith("Patch '")) throw failure;
            throw structural(patchId, index, "Source must be an exact asset path.");
        }
    }

    private static void validateSourcePath(JsonObject operation, String patchId, int index) {
        JsonElement value = operation.get("SourcePath");
        if (value == null || value.isJsonNull()) return;
        final String sourcePath;
        try {
            sourcePath = strictString(operation, "SourcePath", patchId + " operation " + index);
        } catch (IllegalArgumentException failure) {
            throw structural(patchId, index, "SourcePath must be a string.");
        }
        try {
            JsonPointer.tokens(sourcePath, PatchFormat.FORMAT_VERSION_2, false);
        } catch (IllegalArgumentException failure) {
            throw structural(patchId, index, "SourcePath must use RFC 6901 pointer syntax.");
        }
    }

    private static String normalizeSource(String source, String patchId, int index) {
        if (source.startsWith("glob:")) {
            throw structural(patchId, index, "Source must be an exact asset path.");
        }
        try {
            return PatchScanner.normalizeAssetPath(source);
        } catch (IllegalArgumentException failure) {
            throw structural(patchId, index, "Source must be an exact asset path.");
        }
    }

    private static String readSourcePath(JsonObject object, String patchId, int index) {
        JsonElement value = object.get("SourcePath");
        if (value == null || value.isJsonNull()) return null;
        String sourcePath = strictString(object, "SourcePath", patchId + " operation " + index);
        try {
            JsonPointer.tokens(sourcePath, PatchFormat.FORMAT_VERSION_2, false);
            return sourcePath;
        } catch (IllegalArgumentException failure) {
            throw structural(patchId, index, "SourcePath must use RFC 6901 pointer syntax.");
        }
    }

    private static boolean crossAssetOperation(String operation) {
        return "OverlayFromAsset".equals(operation) || "MergeObjectFromAsset".equals(operation);
    }

    private static void validateInsert(JsonObject operation, String patchId, int index) {
        String position = operation.has("Position")
                ? strictString(operation, "Position", patchId + " operation " + index) : "End";
        String normalized = position.toLowerCase(Locale.ROOT);
        if (!Set.of("start", "end", "before", "after").contains(normalized)) {
            throw structural(patchId, index, "Position must be Start, End, Before, or After.");
        }
        boolean hasFind = operation.has("Find");
        if (hasFind && !operation.get("Find").isJsonObject()) {
            throw structural(patchId, index, "Find must be an object.");
        }
        if (hasFind) validateMatcher(operation.get("Find"), "Find", patchId, index);
        boolean hasExisting = operation.has("Existing");
        if (hasExisting && !operation.get("Existing").isJsonObject()) {
            throw structural(patchId, index, "Existing must be an object.");
        }
        if (hasExisting) validateMatcher(operation.get("Existing"), "Existing", patchId, index);
        if (("before".equals(normalized) || "after".equals(normalized)) != hasFind) {
            throw structural(patchId, index, ("before".equals(normalized) || "after".equals(normalized))
                    ? "Position " + position + " requires Find."
                    : "Find is only allowed with Position Before or After.");
        }
    }

    private static void validateMatcher(JsonElement value, String name, String patchId, int index) {
        try {
            JsonMatcher.validateV2(value.getAsJsonObject());
        } catch (IllegalArgumentException failure) {
            throw structural(patchId, index, name + " is not a valid format 2 matcher.");
        }
    }

    private static Integer readVersion(JsonObject object, String patchId, int index) {
        try {
            int value = PatchDefinition.readInt(object, "Version", 0);
            if (value <= 0) throw structural(patchId, index, "Version must be a positive integer.");
            return value;
        } catch (IllegalArgumentException failure) {
            if (failure.getMessage() != null && failure.getMessage().contains("Version must be a positive integer")) {
                throw failure;
            }
            throw structural(patchId, index, "Version must be a positive integer.");
        }
    }

    private static Integer readLegacyVersion(JsonObject object) {
        try {
            return PatchDefinition.readInt(object, "Version", 0);
        } catch (IllegalArgumentException ignored) {
            // Version was not a legacy field; malformed legacy extension data remains ignored.
            return null;
        }
    }

    private static String strictString(JsonObject object, String name, String context) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(context + " " + name + " must be a string.");
        }
        return value.getAsString();
    }

    private static boolean strictBoolean(JsonObject object, String name, String context) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(context + " " + name + " must be a boolean.");
        }
        return value.getAsBoolean();
    }

    private static IllegalArgumentException structural(String patchId, int index, String message) {
        return new IllegalArgumentException("Patch '" + patchId + "' operation " + index + " " + message);
    }

    private static JsonObject object(JsonObject parent, String name) {
        JsonElement value = parent.get(name);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonObject()) throw new IllegalArgumentException(name + " must be an object.");
        return value.getAsJsonObject();
    }

    private static PatchLanguage languageForVersion(int version) {
        return switch (version) {
            case 0 -> PatchLanguage.NEUTRAL;
            case 2 -> PatchLanguage.STRICT_V2;
            default -> PatchLanguage.LEGACY_V1;
        };
    }

    private static JsonElement copy(JsonElement value) { return value == null ? null : value.deepCopy(); }
    private static JsonObject copy(JsonObject value) { return value == null ? null : value.deepCopy(); }
}
