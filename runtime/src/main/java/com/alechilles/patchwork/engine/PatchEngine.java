package com.alechilles.patchwork.engine;

import com.alechilles.patchwork.format.JsonMatcher;
import com.alechilles.patchwork.format.JsonPointer;
import com.alechilles.patchwork.generation.GenerationAssetSnapshot;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.nio.charset.StandardCharsets;

/** Applies Patchwork operations to a copied JSON asset without host-plugin dependencies. */
public final class PatchEngine {
    private final PatchMacroRegistry macroRegistry;

    /** Creates an engine with no registered host macros. */
    public PatchEngine() {
        this(new PatchMacroRegistry());
    }

    /** Creates an engine using the supplied host macro registry. */
    public PatchEngine(PatchMacroRegistry macroRegistry) {
        this.macroRegistry = Objects.requireNonNull(macroRegistry, "macroRegistry");
    }

    /** Applies enabled definitions in deterministic order and returns patched JSON plus diagnostics. */
    public PatchResult apply(JsonObject source, List<PatchDefinition> definitions) {
        return apply(source, definitions,
                new ApplicationContext("", GenerationAssetSnapshot.capture(List.of())));
    }

    /** Applies enabled definitions with the immutable context bound to one target. */
    public PatchResult apply(JsonObject source, List<PatchDefinition> definitions, ApplicationContext context) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(context, "context");
        JsonObject working = source.deepCopy();
        List<String> applied = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<MutationEffect> effects = new ArrayList<>();
        long operationOrder = 0;
        for (PatchDefinition definition : definitions.stream()
                .filter(PatchDefinition::enabled).sorted(PatchDefinition.ORDERING).toList()) {
            DefinitionResult result = applyDefinition(working, definition, context, operationOrder);
            working = result.patched();
            applied.addAll(result.applied());
            skipped.addAll(result.skipped());
            effects.addAll(result.effects());
            operationOrder = result.nextOperationOrder();
        }
        return new PatchResult(working, List.copyOf(applied), List.copyOf(skipped), List.copyOf(effects));
    }

    /** Applies one definition to an isolated copy, preserving a caller-supplied operation order. */
    public DefinitionResult applyDefinition(
            JsonObject source,
            PatchDefinition definition,
            ApplicationContext context,
            long firstOperationOrder) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(context, "context");
        if (firstOperationOrder < 0) throw new IllegalArgumentException("firstOperationOrder must not be negative.");

        JsonObject working = source.deepCopy();
        List<String> applied = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<MutationEffect> effects = new ArrayList<>();
        long operationOrder = firstOperationOrder;
        for (PatchOperation operation : definition.operations()) {
            for (PatchOperation raw : macroRegistry.expand(operation)) {
                EffectTrace trace = new EffectTrace(context.target(), definition, operation.id(), operationOrder);
                apply(working, definition, raw, context, applied, skipped, trace);
                effects.addAll(trace.effects);
                operationOrder++;
            }
        }
        return new DefinitionResult(working, List.copyOf(applied), List.copyOf(skipped),
                List.copyOf(effects), operationOrder);
    }

    private static void apply(JsonObject root, PatchDefinition definition, PatchOperation operation,
                              ApplicationContext context,
                              List<String> applied, List<String> skipped, EffectTrace trace) {
        String label = definition.id() + ":" + operation.id();
        try {
            String skip = raw(root, definition, operation, context, trace);
            if (skip == null) {
                applied.add(label);
                return;
            }
            skipped.add(label + " (" + skip + ")");
            trace.effects.clear();
        } catch (JsonPointer.StructuralException ex) {
            String message = label + " failed: " + ex.getMessage();
            throw new PatchFailureException(message, ex);
        } catch (RuntimeException ex) {
            String message = label + " failed: " + ex.getMessage();
            trace.effects.clear();
            if (operation.required()) throw new PatchFailureException(message, ex);
            skipped.add(message);
        }
    }

    private static String raw(JsonObject root, PatchDefinition definition, PatchOperation operation,
                              ApplicationContext context,
                              EffectTrace trace) {
        return switch (operation.op().toLowerCase(Locale.ROOT)) {
            case "requireformat" -> {
                if (definition.language().requiresFormatSentinel()
                        && operation.language().requiresFormatSentinel()
                        && Integer.valueOf(definition.language().compatibilityVersion()).equals(operation.version())) {
                    yield null;
                }
                if (!definition.language().requiresFormatSentinel()
                        || !operation.language().requiresFormatSentinel()) {
                    throw new IllegalArgumentException("Unsupported operation '" + operation.op() + "'.");
                }
                throw new IllegalArgumentException("RequireFormat version does not match definition format version.");
            }
            case "add" -> {
                add(root, operation, trace);
                yield null;
            }
            case "merge" -> {
                merge(root, operation, trace);
                yield null;
            }
            case "replace" -> {
                replace(root, operation, trace);
                yield null;
            }
            case "remove" -> {
                remove(root, operation, trace);
                yield null;
            }
            case "insert" -> insert(root, operation, trace);
            case "replacematching" -> {
                requireClosedMatcher(operation);
                replaceMatching(root, operation, trace);
                yield null;
            }
            case "removematching" -> {
                requireClosedMatcher(operation);
                removeMatching(root, operation, trace);
                yield null;
            }
            case "movematching" -> {
                requireClosedMatcher(operation);
                yield moveMatching(root, operation, trace);
            }
            case "mergematching" -> {
                requireNeutralMatching(operation);
                mergeMatching(root, operation, trace);
                yield null;
            }
            case "upsertmatching" -> {
                requireNeutralMatching(operation);
                yield upsertMatching(root, operation, trace);
            }
            case "overlayfromasset" -> {
                requireNeutralCrossAsset(operation);
                overlayFromAsset(root, operation, context, trace);
                yield null;
            }
            case "mergeobjectfromasset" -> {
                requireNeutralCrossAsset(operation);
                mergeObjectFromAsset(root, operation, context, trace);
                yield null;
            }
            default -> throw new IllegalArgumentException("Unsupported operation '" + operation.op() + "'.");
        };
    }

    private static void requireClosedMatcher(PatchOperation operation) {
        if (!operation.language().closedStructure()) {
            throw new IllegalArgumentException("Unsupported operation '" + operation.op() + "'.");
        }
    }

    private static void requireNeutralMatching(PatchOperation operation) {
        if (operation.language() != com.alechilles.patchwork.format.PatchLanguage.NEUTRAL) {
            throw new IllegalArgumentException("Unsupported operation '" + operation.op() + "'.");
        }
        requireClosedMatcher(operation);
    }

    private static void requireNeutralCrossAsset(PatchOperation operation) {
        if (operation.language() != com.alechilles.patchwork.format.PatchLanguage.NEUTRAL) {
            throw new IllegalArgumentException("Unsupported operation '" + operation.op() + "'.");
        }
    }

    private static void overlayFromAsset(JsonObject root, PatchOperation operation,
                                         ApplicationContext context, EffectTrace trace) {
        String source = source(operation);
        if (source.equals(context.target())) {
            throw new IllegalArgumentException("OverlayFromAsset cannot overlay the current target asset.");
        }
        JsonObject sourceRoot = sourceObject(context, source, "OverlayFromAsset");
        mergeWithTrace(root, sourceRoot, "", trace);
    }

    private static void mergeObjectFromAsset(JsonObject root, PatchOperation operation,
                                             ApplicationContext context, EffectTrace trace) {
        JsonObject sourceRoot = sourceObject(context, source(operation), "MergeObjectFromAsset");
        JsonElement selectedSource = resolveSourcePath(sourceRoot, operation.sourcePath());
        if (selectedSource == null) {
            throw new IllegalArgumentException("MergeObjectFromAsset source path does not exist"
                    + (operation.sourcePath() == null ? "." : " at " + operation.sourcePath() + "."));
        }
        if (!selectedSource.isJsonObject()) {
            throw new IllegalArgumentException("MergeObjectFromAsset source path must select an object.");
        }
        JsonElement target = resolve(root, operation);
        if (target == null) {
            throw new IllegalArgumentException("MergeObjectFromAsset target does not exist at " + operation.path() + ".");
        }
        if (!target.isJsonObject()) {
            throw new IllegalArgumentException("MergeObjectFromAsset target must be an object at " + operation.path() + ".");
        }
        mergeWithTrace(target.getAsJsonObject(), selectedSource.getAsJsonObject(), operation.path(), trace);
    }

    private static String source(PatchOperation operation) {
        if (operation.source() == null || operation.source().isBlank()) {
            throw new IllegalArgumentException("Operation " + operation.id() + " requires Source.");
        }
        return operation.source();
    }

    private static JsonObject sourceObject(ApplicationContext context, String source, String operationName) {
        GenerationAssetSnapshot.AssetRecord record = context.assets().find(source)
                .orElseThrow(() -> new IllegalArgumentException("Asset source is missing: " + source));
        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(new String(record.bytes(), StandardCharsets.UTF_8));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(operationName + " source is not valid JSON: " + source, failure);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException(operationName + " source must be an object: " + source);
        }
        return parsed.getAsJsonObject();
    }

    private static JsonElement resolveSourcePath(JsonObject sourceRoot, String sourcePath) {
        if (sourcePath == null) return sourceRoot;
        JsonElement current = sourceRoot;
        for (String token : JsonPointer.tokens(sourcePath, 2, false)) {
            if (current == null) return null;
            if (current.isJsonObject()) {
                current = current.getAsJsonObject().get(token);
            } else if (current.isJsonArray()) {
                JsonArray array = current.getAsJsonArray();
                current = array.get(JsonPointer.arrayIndex(token, array.size(), false, 2));
            } else {
                return null;
            }
        }
        return current;
    }

    private static void add(JsonObject root, PatchOperation operation, EffectTrace trace) {
        PathTarget target = parent(root, operation, true);
        JsonElement incoming = value(operation);
        if (target.parent().isJsonObject()) {
            target.parent().getAsJsonObject().add(target.leaf(), incoming.deepCopy());
            traceWrites(incoming, operation.path(), trace);
            return;
        }
        if (target.parent().isJsonArray()) {
            JsonArray array = target.parent().getAsJsonArray();
            int index = JsonPointer.arrayIndex(target.leaf(), array.size(), true, semanticVersion(operation));
            insert(array, index, incoming.deepCopy());
            traceWrites(incoming, childPath(parentPath(operation.path()), index), trace);
            trace.membership(parentPath(operation.path()), JsonValueFingerprint.sha256(incoming));
            return;
        }
        throw new IllegalArgumentException("Add parent is not an object or array at " + operation.path() + ".");
    }

    private static void merge(JsonObject root, PatchOperation operation, EffectTrace trace) {
        JsonElement incoming = value(operation);
        if (!incoming.isJsonObject()) throw new IllegalArgumentException("Merge value must be an object.");
        JsonElement target = resolve(root, operation);
        if (target == null || !target.isJsonObject()) {
            throw new IllegalArgumentException("Merge target must exist and be an object at " + operation.path() + ".");
        }
        mergeWithTrace(target.getAsJsonObject(), incoming.getAsJsonObject(), operation.path(), trace);
    }

    private static void replace(JsonObject root, PatchOperation operation, EffectTrace trace) {
        PathTarget target = parent(root, operation, false);
        JsonElement incoming = value(operation);
        if (target.parent().isJsonObject()) {
            target.parent().getAsJsonObject().add(target.leaf(), incoming.deepCopy());
            traceWrites(incoming, operation.path(), trace);
            return;
        }
        if (target.parent().isJsonArray()) {
            JsonArray array = target.parent().getAsJsonArray();
            int index = JsonPointer.arrayIndex(target.leaf(), array.size(), false, semanticVersion(operation));
            array.set(index, incoming.deepCopy());
            traceWrites(incoming, childPath(parentPath(operation.path()), index), trace);
            return;
        }
        throw new IllegalArgumentException("Replace parent is not an object or array at " + operation.path() + ".");
    }

    private static void remove(JsonObject root, PatchOperation operation, EffectTrace trace) {
        PathTarget target = parent(root, operation, false);
        if (target.parent().isJsonObject()) {
            JsonElement removed = target.parent().getAsJsonObject().remove(target.leaf());
            if (removed == null) {
                throw new IllegalArgumentException("Remove target does not exist at " + operation.path() + ".");
            }
            traceRemoved(removed, operation.path(), trace);
            return;
        }
        if (target.parent().isJsonArray()) {
            JsonArray array = target.parent().getAsJsonArray();
            int index = JsonPointer.arrayIndex(target.leaf(), array.size(), false, semanticVersion(operation));
            JsonElement removed = array.remove(index);
            traceRemoved(removed, childPath(parentPath(operation.path()), index), trace);
            trace.membership(parentPath(operation.path()), MutationEffect.REMOVED);
            return;
        }
        throw new IllegalArgumentException("Remove parent is not an object or array at " + operation.path() + ".");
    }

    private static String insert(JsonObject root, PatchOperation operation, EffectTrace trace) {
        JsonElement target = resolve(root, operation);
        if (target == null || !target.isJsonArray()) {
            throw new IllegalArgumentException("Insert target must be an array at " + operation.path() + ".");
        }
        JsonArray array = target.getAsJsonArray();
        if (operation.language().closedStructure()) {
            if (operation.existing() != null) JsonMatcher.validateV2(operation.existing());
            if (operation.find() != null) JsonMatcher.validateV2(operation.find());
        }
        if (operation.existing() != null && find(array, operation.existing(), semanticVersion(operation)) >= 0) {
            return "existing matcher already present";
        }
        String position = operation.position() == null ? "End" : operation.position();
        int index = switch (position.toLowerCase(Locale.ROOT)) {
            case "start" -> 0;
            case "end" -> array.size();
            case "before" -> anchor(array, operation, false);
            case "after" -> anchor(array, operation, true);
            default -> throw new IllegalArgumentException("Unsupported insert position '" + position + "'.");
        };
        JsonElement incoming = value(operation);
        insert(array, index, incoming.deepCopy());
        traceWrites(incoming, childPath(operation.path(), index), trace);
        trace.membership(operation.path(), JsonValueFingerprint.sha256(incoming));
        return null;
    }

    private static int anchor(JsonArray array, PatchOperation operation, boolean after) {
        if (operation.find() == null) throw new IllegalArgumentException("Insert " + operation.position() + " requires Find.");
        int index = find(array, operation.find(), semanticVersion(operation));
        if (index < 0) throw new IllegalArgumentException("Insert anchor not found for " + operation.id() + ".");
        return after ? index + 1 : index;
    }

    private static void replaceMatching(JsonObject root, PatchOperation operation, EffectTrace trace) {
        JsonArray array = matchingTarget(root, operation, "ReplaceMatching");
        JsonArray snapshot = array.deepCopy();
        List<Integer> indexes = matchingIndexes(snapshot, operation.match(), operation.matchPolicy(), operation, false);
        JsonElement replacement = value(operation);
        for (int index : indexes) {
            array.set(index, replacement.deepCopy());
            traceWrites(replacement, childPath(operation.path(), index), trace);
        }
    }

    private static void removeMatching(JsonObject root, PatchOperation operation, EffectTrace trace) {
        JsonArray array = matchingTarget(root, operation, "RemoveMatching");
        JsonArray snapshot = array.deepCopy();
        List<Integer> indexes = matchingIndexes(snapshot, operation.match(), operation.matchPolicy(), operation, false);
        for (int offset = indexes.size() - 1; offset >= 0; offset--) {
            int index = indexes.get(offset);
            array.remove(index);
            traceRemoved(snapshot.get(index), childPath(operation.path(), index), trace);
            trace.membership(operation.path(), MutationEffect.REMOVED);
        }
    }

    private static String moveMatching(JsonObject root, PatchOperation operation, EffectTrace trace) {
        JsonArray array = matchingTarget(root, operation, "MoveMatching");
        JsonArray snapshot = array.deepCopy();
        int movingIndex = matchingIndexes(snapshot, operation.match(), "ExactlyOne", operation, false).getFirst();
        String position = operation.position() == null ? "End" : operation.position();
        String normalizedPosition = position.toLowerCase(Locale.ROOT);
        JsonObject find = operation.find();
        int anchorIndex = -1;
        if ("before".equals(normalizedPosition) || "after".equals(normalizedPosition)) {
            if (find == null) throw new IllegalArgumentException("MoveMatching " + position + " requires Find.");
            anchorIndex = matchingIndexes(snapshot, find, "ExactlyOne", operation, false).getFirst();
            if (movingIndex == anchorIndex) {
                throw new IllegalArgumentException("MoveMatching cannot use the moving entry as its own anchor.");
            }
        } else if (find != null) {
            throw new IllegalArgumentException("Find is only allowed with Position Before or After.");
        } else if (!"start".equals(normalizedPosition) && !"end".equals(normalizedPosition)) {
            throw new IllegalArgumentException("Position must be Start, End, Before, or After.");
        }

        JsonArray reordered = snapshot.deepCopy();
        JsonElement moving = reordered.remove(movingIndex);
        int insertionIndex = switch (normalizedPosition) {
            case "start" -> 0;
            case "end" -> reordered.size();
            case "before", "after" -> {
                int adjustedAnchor = movingIndex < anchorIndex ? anchorIndex - 1 : anchorIndex;
                yield "before".equals(normalizedPosition) ? adjustedAnchor : adjustedAnchor + 1;
            }
            default -> throw new IllegalArgumentException("Position must be Start, End, Before, or After.");
        };
        insert(reordered, insertionIndex, moving);
        if (snapshot.equals(reordered)) return "already in requested position";
        while (!array.isEmpty()) array.remove(0);
        for (JsonElement element : reordered) array.add(element.deepCopy());
        trace.order(operation.path(), JsonValueFingerprint.sha256(reordered));
        return null;
    }

    private static void mergeMatching(JsonObject root, PatchOperation operation, EffectTrace trace) {
        JsonArray array = matchingTarget(root, operation, "MergeMatching");
        JsonArray snapshot = array.deepCopy();
        List<Integer> indexes = matchingIndexes(snapshot, operation.match(), operation.matchPolicy(), operation, false);
        JsonElement incoming = value(operation);
        if (!incoming.isJsonObject()) throw new IllegalArgumentException("MergeMatching value must be an object.");
        for (int index : indexes) {
            JsonElement selected = array.get(index);
            if (!selected.isJsonObject()) {
                throw new IllegalArgumentException("MergeMatching selected entry must be an object at index " + index + ".");
            }
        }
        for (int index : indexes) {
            mergeWithTrace(selectedObject(array.get(index)), incoming.getAsJsonObject(),
                    childPath(operation.path(), index), trace);
        }
    }

    private static String upsertMatching(JsonObject root, PatchOperation operation, EffectTrace trace) {
        JsonArray array = matchingTarget(root, operation, "UpsertMatching");
        JsonArray snapshot = array.deepCopy();
        List<Integer> indexes = matchingIndexes(snapshot, operation.match(), operation.matchPolicy(), operation, true);
        JsonElement incoming = value(operation);
        if (!incoming.isJsonObject()) throw new IllegalArgumentException("UpsertMatching value must be an object.");
        if (!indexes.isEmpty()) {
            for (int index : indexes) {
                if (!array.get(index).isJsonObject()) {
                    throw new IllegalArgumentException("UpsertMatching selected entry must be an object at index " + index + ".");
                }
            }
            for (int index : indexes) {
                mergeWithTrace(selectedObject(array.get(index)), incoming.getAsJsonObject(),
                        childPath(operation.path(), index), trace);
            }
            return null;
        }

        String position = operation.position() == null ? "End" : operation.position();
        int index = switch (position.toLowerCase(Locale.ROOT)) {
            case "start" -> 0;
            case "end" -> array.size();
            case "before" -> anchor(snapshot, operation, false);
            case "after" -> anchor(snapshot, operation, true);
            default -> throw new IllegalArgumentException("Unsupported UpsertMatching position '" + position + "'.");
        };
        insert(array, index, incoming.deepCopy());
        traceWrites(incoming, childPath(operation.path(), index), trace);
        trace.membership(operation.path(), JsonValueFingerprint.sha256(incoming));
        return null;
    }

    private static JsonObject selectedObject(JsonElement value) {
        return value.getAsJsonObject();
    }

    private static JsonArray matchingTarget(JsonObject root, PatchOperation operation, String name) {
        JsonElement target = resolve(root, operation);
        if (target == null || !target.isJsonArray()) {
            throw new IllegalArgumentException(name + " target must be an array at " + operation.path() + ".");
        }
        return target.getAsJsonArray();
    }

    private static List<Integer> matchingIndexes(JsonArray snapshot, JsonObject matcher, String requestedPolicy,
                                                  PatchOperation operation, boolean allowZero) {
        if (matcher == null) throw new IllegalArgumentException(operation.op() + " requires Match.");
        List<Integer> matches = new ArrayList<>();
        for (int index = 0; index < snapshot.size(); index++) {
            if (matches(snapshot.get(index), matcher, semanticVersion(operation))) matches.add(index);
        }
        if (matches.isEmpty()) {
            if (allowZero) return List.of();
            throw new IllegalArgumentException(operation.op() + " found no matching entries for " + operation.id() + ".");
        }
        String policy = requestedPolicy == null ? "exactlyone" : requestedPolicy.toLowerCase(Locale.ROOT);
        return switch (policy) {
            case "exactlyone" -> {
                if (matches.size() > 1) {
                    throw new IllegalArgumentException(operation.op() + " found multiple matching entries for " + operation.id() + ".");
                }
                yield List.of(matches.getFirst());
            }
            case "first" -> List.of(matches.getFirst());
            case "last" -> List.of(matches.getLast());
            case "all" -> List.copyOf(matches);
            default -> throw new IllegalArgumentException("MatchPolicy must be ExactlyOne, First, Last, or All.");
        };
    }

    /** Legacy matcher entry point retained for package compatibility. */
    static boolean matches(JsonElement candidate, JsonObject matcher) {
        return JsonMatcher.matches(candidate, matcher, 1);
    }

    /** Format-aware matcher entry point used by operations. */
    static boolean matches(JsonElement candidate, JsonObject matcher, int formatVersion) {
        return JsonMatcher.matches(candidate, matcher, formatVersion);
    }

    /** Applies strict pointer and matcher semantics to both strict v2 and neutral operations. */
    private static int semanticVersion(PatchOperation operation) {
        return operation.language().closedStructure() ? 2 : operation.formatVersion();
    }

    private static int find(JsonArray array, JsonObject matcher, int formatVersion) {
        for (int index = 0; index < array.size(); index++) {
            if (matches(array.get(index), matcher, formatVersion)) return index;
        }
        return -1;
    }

    private static void mergeWithTrace(JsonObject target, JsonObject incoming, String basePath, EffectTrace trace) {
        if (incoming.isEmpty()) {
            traceWrites(incoming, basePath, trace);
            return;
        }
        for (Map.Entry<String, JsonElement> entry : incoming.entrySet()) {
            String entryPath = childPath(basePath, entry.getKey());
            JsonElement existing = target.get(entry.getKey());
            JsonElement value = entry.getValue();
            if (existing != null && existing.isJsonObject() && value != null && value.isJsonObject()) {
                mergeWithTrace(existing.getAsJsonObject(), value.getAsJsonObject(), entryPath, trace);
            } else {
                target.add(entry.getKey(), value == null ? null : value.deepCopy());
                traceWrites(value, entryPath, trace);
            }
        }
    }

    private static JsonElement resolve(JsonElement root, PatchOperation operation) {
        JsonElement current = root;
        for (String token : JsonPointer.tokens(path(operation), semanticVersion(operation), true)) {
            if (current == null) return null;
            if (current.isJsonObject()) {
                current = current.getAsJsonObject().get(token);
            } else if (current.isJsonArray()) {
                JsonArray array = current.getAsJsonArray();
                current = array.get(JsonPointer.arrayIndex(token, array.size(), false, semanticVersion(operation)));
            } else {
                return null;
            }
        }
        return current;
    }

    private static PathTarget parent(JsonObject root, PatchOperation operation, boolean allowMissingLeaf) {
        List<String> tokens = JsonPointer.tokens(path(operation), semanticVersion(operation), true);
        if (tokens.isEmpty()) throw new IllegalArgumentException("Path must not point to the document root.");
        JsonElement current = root;
        for (int index = 0; index < tokens.size() - 1; index++) {
            String token = tokens.get(index);
            if (current.isJsonObject()) {
                current = current.getAsJsonObject().get(token);
            } else if (current.isJsonArray()) {
                JsonArray array = current.getAsJsonArray();
                current = array.get(JsonPointer.arrayIndex(token, array.size(), false, semanticVersion(operation)));
            } else {
                throw new IllegalArgumentException("Path parent is not traversable at " + token + ".");
            }
            if (current == null) throw new IllegalArgumentException("Path parent does not exist at " + token + ".");
        }
        String leaf = tokens.getLast();
        if (!allowMissingLeaf && current.isJsonObject() && !current.getAsJsonObject().has(leaf)) {
            throw new IllegalArgumentException("Path leaf does not exist at " + leaf + ".");
        }
        return new PathTarget(current, leaf);
    }

    private static void insert(JsonArray array, int index, JsonElement value) {
        JsonArray rebuilt = new JsonArray();
        for (int i = 0; i < array.size(); i++) {
            if (i == index) rebuilt.add(value);
            rebuilt.add(array.get(i));
        }
        if (index == array.size()) rebuilt.add(value);
        while (!array.isEmpty()) array.remove(0);
        for (JsonElement element : rebuilt) array.add(element);
    }

    private static String path(PatchOperation operation) {
        if (operation.path() == null || operation.path().isBlank()) {
            throw new IllegalArgumentException("Operation " + operation.id() + " requires Path.");
        }
        return operation.path();
    }

    private static JsonElement value(PatchOperation operation) {
        JsonElement value = operation.value();
        if (value == null) throw new IllegalArgumentException("Operation " + operation.id() + " requires Value.");
        return value;
    }

    private static void traceWrites(JsonElement value, String path, EffectTrace trace) {
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) {
            trace.write(path, value);
            return;
        }
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            if (array.isEmpty()) {
                trace.write(path, value);
                return;
            }
            for (int index = 0; index < array.size(); index++) {
                traceWrites(array.get(index), childPath(path, index), trace);
            }
            return;
        }
        JsonObject object = value.getAsJsonObject();
        if (object.isEmpty()) {
            trace.write(path, value);
            return;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            traceWrites(entry.getValue(), childPath(path, entry.getKey()), trace);
        }
    }

    private static void traceRemoved(JsonElement value, String path, EffectTrace trace) {
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) {
            trace.removed(path);
            return;
        }
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            if (array.isEmpty()) {
                trace.removed(path);
                return;
            }
            for (int index = 0; index < array.size(); index++) {
                traceRemoved(array.get(index), childPath(path, index), trace);
            }
            return;
        }
        JsonObject object = value.getAsJsonObject();
        if (object.isEmpty()) {
            trace.removed(path);
            return;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            traceRemoved(entry.getValue(), childPath(path, entry.getKey()), trace);
        }
    }

    private static String parentPath(String path) {
        int separator = path.lastIndexOf('/');
        if (separator <= 0) return "";
        return path.substring(0, separator);
    }

    private static String childPath(String basePath, Object token) {
        String escaped = String.valueOf(token).replace("~", "~0").replace("/", "~1");
        if (basePath == null || basePath.isEmpty()) return "/" + escaped;
        return basePath.endsWith("/") ? basePath + escaped : basePath + "/" + escaped;
    }

    /** Context bound to one target and one immutable source snapshot. */
    public record ApplicationContext(String target, GenerationAssetSnapshot assets) {
        public ApplicationContext {
            target = Objects.requireNonNull(target, "target");
            assets = Objects.requireNonNull(assets, "assets");
        }
    }

    /** Result of applying one definition to an isolated source copy. */
    public record DefinitionResult(JsonObject patched, List<String> applied,
                                   List<String> skipped, List<MutationEffect> effects,
                                   long nextOperationOrder) {
        public DefinitionResult {
            patched = Objects.requireNonNull(patched, "patched");
            applied = List.copyOf(applied);
            skipped = List.copyOf(skipped);
            effects = List.copyOf(effects);
        }
    }

    /** Patched JSON plus operation diagnostics from a patch run. */
    public record PatchResult(JsonObject patched, List<String> applied, List<String> skipped,
                              List<MutationEffect> effects) {
        public PatchResult(JsonObject patched, List<String> applied, List<String> skipped) {
            this(patched, applied, skipped, List.of());
        }

        public PatchResult {
            patched = Objects.requireNonNull(patched, "patched");
            applied = List.copyOf(applied);
            skipped = List.copyOf(skipped);
            effects = List.copyOf(effects);
        }
    }

    private static final class EffectTrace {
        private final String target;
        private final PatchDefinition definition;
        private final String operationId;
        private final long operationOrder;
        private final List<MutationEffect> effects = new ArrayList<>();

        private EffectTrace(String target, PatchDefinition definition, String operationId, long operationOrder) {
            this.target = target;
            this.definition = definition;
            this.operationId = operationId;
            this.operationOrder = operationOrder;
        }

        private void write(String path, JsonElement value) {
            effects.add(effect(path, MutationEffect.Kind.WRITE,
                    JsonValueFingerprint.sha256(value)));
        }

        private void removed(String path) {
            effects.add(effect(path, MutationEffect.Kind.WRITE, MutationEffect.REMOVED));
        }

        private void membership(String path, String fingerprint) {
            effects.add(effect(path, MutationEffect.Kind.ARRAY_MEMBERSHIP, fingerprint));
        }

        private void order(String path, String fingerprint) {
            effects.add(effect(path, MutationEffect.Kind.ARRAY_ORDER, fingerprint));
        }

        private MutationEffect effect(String path, MutationEffect.Kind kind, String fingerprint) {
            return new MutationEffect(target, definition.id(), definition.sourcePack(), operationId,
                    operationOrder, path, kind, fingerprint);
        }
    }

    private record PathTarget(JsonElement parent, String leaf) {
    }

    /** Indicates failure of a required patch operation. */
    public static final class PatchFailureException extends RuntimeException {
        public PatchFailureException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
