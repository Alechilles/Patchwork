package com.alechilles.patchwork.engine;

import com.alechilles.patchwork.conditions.PatchCondition;
import com.alechilles.patchwork.conditions.PatchConditionParser;
import com.alechilles.patchwork.conflict.ConflictPolicy;
import com.alechilles.patchwork.discovery.PatchTargetSelector;
import com.alechilles.patchwork.format.PatchFormat;
import com.alechilles.patchwork.format.PatchLanguage;
import com.alechilles.patchwork.format.Utf8Ordering;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Objects;

/** Parsed Patchwork definition associated with one target asset and source location. */
public final class PatchDefinition {
    /** Stable definition ordering: priority, patch ID, then contributing pack load order and ID. */
    public static final Comparator<PatchDefinition> ORDERING = Comparator.comparingInt(PatchDefinition::priority)
            .thenComparing(PatchDefinition::id, Utf8Ordering.UNSIGNED_BYTES)
            .thenComparingInt(PatchDefinition::sourcePackLoadOrder)
            .thenComparing(PatchDefinition::sourcePack, Utf8Ordering.UNSIGNED_BYTES);

    private final String id;
    private final String target;
    private final PatchTargetSelector targetSelector;
    private final String sourcePack;
    private final String sourcePath;
    private final int priority;
    private final int sourcePackLoadOrder;
    private final boolean enabled;
    private final ConflictPolicy conflictPolicy;
    private final List<PatchOperation> operations;
    private final PatchCondition condition;
    private final PatchLanguage language;

    private PatchDefinition(
            String id,
            String target,
            int priority,
            boolean enabled,
            ConflictPolicy conflictPolicy,
            List<PatchOperation> operations,
            String sourcePack,
            String sourcePath,
            int sourcePackLoadOrder,
            PatchCondition condition,
            PatchLanguage language) {
        this.id = id;
        this.targetSelector = PatchTargetSelector.parse(target);
        this.target = this.targetSelector.expression();
        this.priority = priority;
        this.enabled = enabled;
        this.conflictPolicy = Objects.requireNonNull(conflictPolicy, "conflictPolicy");
        this.operations = List.copyOf(operations);
        this.sourcePack = sourcePack;
        this.sourcePath = sourcePath;
        this.sourcePackLoadOrder = sourcePackLoadOrder;
        this.condition = condition;
        this.language = java.util.Objects.requireNonNull(language, "language");
    }

    /** Parses one target definition, defaulting source pack order to zero. */
    public static PatchDefinition parse(JsonObject root, String sourcePack, String sourcePath) {
        return parse(root, sourcePack, sourcePath, 0);
    }

    /** Parses one target definition with a deterministic source pack load order. */
    public static PatchDefinition parse(
            JsonObject root,
            String sourcePack,
            String sourcePath,
            int sourcePackLoadOrder) {
        List<PatchDefinition> definitions = parseAll(root, sourcePack, sourcePath, sourcePackLoadOrder);
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("Patch '" + sourcePack + ":" + sourcePath + "' produced no definitions.");
        }
        return definitions.getFirst();
    }

    /** Parses a definition for each declared target, defaulting source pack order to zero. */
    public static List<PatchDefinition> parseAll(JsonObject root, String sourcePack, String sourcePath) {
        return parseAll(root, sourcePack, sourcePath, 0);
    }

    /**
     * Parses a definition for each declared target with a deterministic source pack load order.
     *
     * <p>This compatibility overload follows the historical root marker: a
     * missing marker means legacy format 1, while an explicit format-2 marker
     * selects the strict language.</p>
     */
    public static List<PatchDefinition> parseAll(
            JsonObject root,
            String sourcePack,
            String sourcePath,
            int sourcePackLoadOrder) {
        return parseAll(root, sourcePack, sourcePath, sourcePackLoadOrder,
                PatchFormat.fromRoot(root).language());
    }

    /** Parses a definition using an explicit root-selected grammar profile. */
    public static List<PatchDefinition> parseAll(
            JsonObject root,
            String sourcePack,
            String sourcePath,
            int sourcePackLoadOrder,
            PatchLanguage language) {
        java.util.Objects.requireNonNull(root, "root");
        java.util.Objects.requireNonNull(language, "language");
        if (language == PatchLanguage.STRICT_V2) {
            PatchFormat format = PatchFormat.fromRoot(root);
            if (!format.isVersion2()) {
                throw new IllegalArgumentException("Strict format 2 parsing requires FormatVersion 2.");
            }
        }
        if (language.closedStructure()) {
            validateClosedRootFields(root, language);
        }

        String id = readString(root, "Id", sourcePack + ":" + sourcePath);
        if (language.closedStructure() && root.has("Id")) {
            readRequiredString(root, "Id", sourcePack + ":" + sourcePath);
        }
        Utf8Ordering.requireValid(id, "Patch ID");
        Utf8Ordering.requireValid(sourcePack, "Source pack ID");

        List<PatchOperation> operations = readOperations(root, id, language);
        validateSentinel(operations, id, language);

        // PatchConditionParser predates PatchLanguage and accepts the integer
        // compatibility value.  Neutral has strict condition structure too,
        // so it uses the strict validator while retaining language 0 on the
        // definition itself.
        int conditionFormat = language.closedStructure()
                ? PatchFormat.FORMAT_VERSION_2 : language.compatibilityVersion();
        PatchCondition condition = new PatchConditionParser().parseOptional(root, conditionFormat);

        List<PatchDefinition> result = new ArrayList<>();
        for (String target : readTargets(root, id)) {
            result.add(new PatchDefinition(
                    id,
                    target,
                    readInt(root, "Priority", 0),
                    readBoolean(root, "Enabled", true),
                    readConflictPolicy(root, language),
                    operations,
                    sourcePack,
                    sourcePath,
                    sourcePackLoadOrder,
                    condition,
                    language));
        }
        return result;
    }

    public String id() { return id; }
    public String target() { return target; }
    /** Parsed exact or explicit-glob selector for this definition. */
    public PatchTargetSelector targetSelector() { return targetSelector; }
    /** Short alias for callers that refer to the target as a selector. */
    public PatchTargetSelector selector() { return targetSelector(); }
    public PatchTargetSelector getTargetSelector() { return targetSelector(); }
    public PatchTargetSelector getSelector() { return selector(); }
    /** Returns whether this definition still needs inventory expansion. */
    public boolean isGlobTarget() { return targetSelector.kind() == PatchTargetSelector.Kind.GLOB; }

    /**
     * Binds an expanded concrete path while preserving all definition metadata.
     * A path that does not match the parsed selector is rejected rather than
     * silently producing a definition for an unrelated asset.
     */
    public PatchDefinition bindTarget(String concreteTarget) {
        PatchTargetSelector bound = PatchTargetSelector.parse(concreteTarget);
        if (bound.kind() != PatchTargetSelector.Kind.EXACT || !targetSelector.matches(bound.expression())) {
            throw new IllegalArgumentException("Concrete target does not match selector " + target + ".");
        }
        return new PatchDefinition(
                id,
                bound.expression(),
                priority,
                enabled,
                conflictPolicy,
                operations,
                sourcePack,
                sourcePath,
                sourcePackLoadOrder,
                condition,
                language);
    }
    public int priority() { return priority; }
    public boolean enabled() { return enabled; }
    /** Returns the action for overlaps introduced by this definition. */
    public ConflictPolicy conflictPolicy() { return conflictPolicy; }
    public List<PatchOperation> operations() { return operations; }
    public String sourcePack() { return sourcePack; }
    public String sourcePath() { return sourcePath; }
    public int sourcePackLoadOrder() { return sourcePackLoadOrder; }
    public PatchCondition condition() { return condition; }
    public PatchLanguage language() { return language; }

    /** Compatibility view retained for existing embedders. */
    public int formatVersion() { return language.compatibilityVersion(); }

    public String getId() { return id(); }
    public String getTarget() { return target(); }
    public int getPriority() { return priority(); }
    public boolean isEnabled() { return enabled(); }
    public ConflictPolicy getConflictPolicy() { return conflictPolicy(); }
    public List<PatchOperation> getOperations() { return operations(); }
    public String getSourcePack() { return sourcePack(); }
    public String getSourcePath() { return sourcePath(); }
    public int getSourcePackLoadOrder() { return sourcePackLoadOrder(); }
    public int getFormatVersion() { return formatVersion(); }
    public PatchLanguage getLanguage() { return language(); }

    static String readRequiredString(JsonObject object, String name, String context) {
        String value = readString(object, name, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(context + " must define non-empty " + name + ".");
        }
        return value;
    }

    static String readString(JsonObject object, String name, String fallback) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " must be a string.");
        }
        return value.getAsString();
    }

    static int readInt(JsonObject object, String name, int fallback) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(name + " must be an integer.");
        }
        try {
            return new BigDecimal(value.getAsString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException failure) {
            throw new IllegalArgumentException(name + " must be an integer.", failure);
        }
    }

    static boolean readBoolean(JsonObject object, String name, boolean fallback) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(name + " must be a boolean.");
        }
        return value.getAsBoolean();
    }

    private static List<PatchOperation> readOperations(JsonObject root, String id, PatchLanguage language) {
        JsonElement raw = root.get("Operations");
        if (raw == null || !raw.isJsonArray()) {
            throw new IllegalArgumentException("Patch '" + id + "' must define an Operations array.");
        }
        JsonArray array = raw.getAsJsonArray();
        List<PatchOperation> result = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonObject()) {
                throw new IllegalArgumentException("Patch '" + id + "' operation " + i + " must be an object.");
            }
            result.add(PatchOperation.parse(array.get(i).getAsJsonObject(), id, i, language));
        }
        return result;
    }

    private static void validateSentinel(List<PatchOperation> operations, String id, PatchLanguage language) {
        if (!language.requiresFormatSentinel()) return;
        if (operations.isEmpty() || !"RequireFormat".equals(operations.getFirst().op())) {
            throw new IllegalArgumentException("Patch '" + id + "' format 2 requires RequireFormat as operation index zero.");
        }
        PatchOperation sentinel = operations.getFirst();
        if (!Integer.valueOf(language.compatibilityVersion()).equals(sentinel.version())) {
            throw new IllegalArgumentException("Patch '" + id + "' RequireFormat version must equal FormatVersion.");
        }
        for (int i = 1; i < operations.size(); i++) {
            String op = operations.get(i).op();
            if ("RequireFormat".equals(op)) {
                throw new IllegalArgumentException("Patch '" + id + "' must not contain a second RequireFormat operation.");
            }
            if ("RequireFormat".equalsIgnoreCase(op)) {
                throw new IllegalArgumentException("Patch '" + id + "' RequireFormat operation must use exact spelling.");
            }
        }
    }

    private static void validateClosedRootFields(JsonObject root, PatchLanguage language) {
        Set<String> allowed = language == PatchLanguage.NEUTRAL
                ? Set.of("Id", "Target", "Targets", "Priority", "Enabled", "ConflictPolicy", "When", "Operations", "$Comment")
                : Set.of("FormatVersion", "Id", "Target", "Targets", "Priority", "Enabled", "When", "Operations");
        for (String name : root.keySet()) {
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException(
                        (language == PatchLanguage.NEUTRAL ? "Neutral" : "Format 2")
                                + " patch definition contains unknown root field '" + name + "'.");
            }
        }
    }

    private static ConflictPolicy readConflictPolicy(JsonObject root, PatchLanguage language) {
        if (language != PatchLanguage.NEUTRAL) return ConflictPolicy.REPORT;
        String value = readString(root, "ConflictPolicy", "Report");
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "report" -> ConflictPolicy.REPORT;
            case "allow" -> ConflictPolicy.ALLOW;
            case "reject" -> ConflictPolicy.REJECT;
            default -> throw new IllegalArgumentException("ConflictPolicy must be Report, Allow, or Reject.");
        };
    }

    private static List<String> readTargets(JsonObject root, String id) {
        JsonElement single = root.get("Target");
        JsonElement multiple = root.get("Targets");
        if (single != null && !single.isJsonNull() && multiple != null && !multiple.isJsonNull()) {
            throw new IllegalArgumentException("Patch '" + id + "' must define either Target or Targets, not both.");
        }
        if (multiple == null || multiple.isJsonNull()) {
            return List.of(normalize(readRequiredString(root, "Target", "Patch '" + id + "'"), id));
        }
        if (!multiple.isJsonArray()) {
            throw new IllegalArgumentException("Patch '" + id + "' Targets must be an array.");
        }
        if (multiple.getAsJsonArray().isEmpty()) {
            throw new IllegalArgumentException("Patch '" + id + "' Targets must not be empty.");
        }
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < multiple.getAsJsonArray().size(); i++) {
            JsonElement target = multiple.getAsJsonArray().get(i);
            if (!target.isJsonPrimitive() || !target.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Patch '" + id + "' Targets entry " + i + " must be a string.");
            }
            String normalized = normalize(target.getAsString(), id);
            if (!result.add(normalized)) {
                throw new IllegalArgumentException("Patch '" + id + "' Targets contains duplicate target " + normalized + ".");
            }
        }
        return List.copyOf(result);
    }

    private static String normalize(String target, String id) {
        try {
            return PatchTargetSelector.parse(target).expression();
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Patch '" + id + "' target is unsafe: " + target, failure);
        }
    }
}
