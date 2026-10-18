package com.alechilles.patchwork.preview;

import com.alechilles.patchwork.PatchworkVersion;
import com.alechilles.patchwork.conditions.ConditionDocumentCache;
import com.alechilles.patchwork.conditions.ConditionSourceResolver;
import com.alechilles.patchwork.conditions.ModDataRootRegistry;
import com.alechilles.patchwork.conditions.PatchConditionEvaluator;
import com.alechilles.patchwork.conflict.ConflictAnalyzer;
import com.alechilles.patchwork.conflict.ConflictRecord;
import com.alechilles.patchwork.discovery.PatchRoot;
import com.alechilles.patchwork.discovery.PatchScanner;
import com.alechilles.patchwork.discovery.PatchSource;
import com.alechilles.patchwork.discovery.PatchTargetExpander;
import com.alechilles.patchwork.discovery.PatchTargetResolver;
import com.alechilles.patchwork.engine.MutationEffect;
import com.alechilles.patchwork.engine.PatchDefinition;
import com.alechilles.patchwork.engine.PatchEngine;
import com.alechilles.patchwork.format.Utf8Ordering;
import com.alechilles.patchwork.generation.GeneratedPackManifest;
import com.alechilles.patchwork.generation.GenerationAssetSnapshot;
import com.alechilles.patchwork.generation.PatchGenerationService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Runs one real Patchwork generation pass over explicit pack roots and explains every overlay.
 *
 * <p>{@link PatchGenerationService#generate} is the authority for the final documents, rejected
 * targets and conflict rows. A second trace pass over the same immutable snapshot uses the same
 * scanner, selector expansion, condition evaluator, engine and conflict analyzer to record why
 * each definition applied or not, and the value each conflicting definition wrote. Unlike the
 * in-game diagnostics, this output is meant for local tooling and does include values.</p>
 */
public final class PatchPreview {
    /** Pack name Hytale gives the base game assets ({@code DefaultAssetMap.DEFAULT_PACK_KEY}). */
    public static final String BASE_PACK_ID = "Hytale:Hytale";

    /** One input asset pack. Packs are passed in load order; a later pack wins a shared path. */
    public record Pack(String id, String version, Path path) {
        public Pack {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(path, "path");
        }
    }

    /** Preview inputs. {@code installed} adds plugin IDs (and optional versions) beyond the packs. */
    public record Request(List<Pack> packs, Map<String, String> installed, String serverVersion,
                          Map<String, Path> modData, Set<String> targetFilter, boolean includeDocuments) {
        public Request {
            packs = List.copyOf(packs);
            installed = Map.copyOf(installed);
            Objects.requireNonNull(serverVersion, "serverVersion");
            modData = Map.copyOf(modData);
            targetFilter = Set.copyOf(targetFilter);
        }
    }

    /** Runs the preview and returns the JSON report. */
    public JsonObject run(Request request) {
        List<PatchSource> sources = new ArrayList<>();
        Set<String> installedIds = new LinkedHashSet<>();
        Map<String, String> versions = new LinkedHashMap<>();
        JsonArray packsJson = new JsonArray();
        for (int order = 0; order < request.packs().size(); order++) {
            Pack pack = request.packs().get(order);
            boolean archive = Files.isRegularFile(pack.path());
            sources.add(archive ? PatchSource.archive(pack.id(), order, pack.path())
                    : PatchSource.directory(pack.id(), order, pack.path()));
            installedIds.add(pack.id());
            if (pack.version() != null && !pack.version().isBlank()) versions.put(pack.id(), pack.version());
            JsonObject row = new JsonObject();
            row.addProperty("id", pack.id());
            row.addProperty("version", pack.version());
            row.addProperty("path", pack.path().toString());
            row.addProperty("kind", archive ? "archive" : "directory");
            row.addProperty("loadOrder", order);
            packsJson.add(row);
        }
        request.installed().forEach((id, version) -> {
            installedIds.add(id);
            if (version != null && !version.isBlank()) versions.put(id, version);
        });

        GenerationAssetSnapshot snapshot = GenerationAssetSnapshot.capture(sources);
        ModDataRootRegistry modData = new ModDataRootRegistry(request.modData());
        PatchGenerationService.GenerationPlan plan = new PatchGenerationService().generate(
                new PatchGenerationService.GenerationRequest(snapshot, installedIds, versions,
                        request.serverVersion(), resolver(modData)));
        Map<String, JsonObject> generated = new HashMap<>();
        for (GeneratedPackManifest.Entry entry : plan.entries()) {
            generated.put(entry.target(), JsonParser.parseString(
                    new String(entry.bytes(), StandardCharsets.UTF_8)).getAsJsonObject());
        }

        Trace trace = new Trace(request, snapshot, installedIds, versions, resolver(modData).withAssets(snapshot));
        PatchScanner.ScanResult scan = new PatchScanner().scan(snapshot, installedIds);
        Map<String, List<PatchDefinition>> targets = trace.groupTargets(scan.definitions());
        for (Map.Entry<String, List<PatchDefinition>> group : targets.entrySet()) {
            String target = group.getKey();
            trace.traceTarget(target, group.getValue(), plan.status().rejectedTargets().get(target),
                    generated.get(target));
        }

        JsonObject report = new JsonObject();
        report.addProperty("tool", "patchwork-preview");
        report.addProperty("patchworkVersion", PatchworkVersion.current());
        report.addProperty("serverVersion", request.serverVersion());
        report.add("packs", packsJson);
        report.add("installed", strings(installedIds));
        report.add("activeRoots", strings(PatchRoot.activeRoots(installedIds).stream().map(PatchRoot::path).toList()));
        JsonObject summary = new JsonObject();
        summary.addProperty("definitions", scan.definitions().size());
        summary.addProperty("targets", targets.size());
        summary.addProperty("generatedTargets", plan.entries().size());
        summary.addProperty("rejectedTargets", plan.status().rejectedTargets().size());
        summary.addProperty("scanFailures", scan.failures().size());
        summary.addProperty("materialConflicts", plan.conflicts().materialCount());
        summary.addProperty("redundantConflicts", plan.conflicts().redundantCount());
        report.add("summary", summary);
        report.add("overlays", trace.overlays);
        report.add("targets", trace.targets);
        report.add("conflicts", trace.conflicts(plan.conflicts().records(), generated));
        report.add("scanSkipped", strings(scan.skipped()));
        report.add("scanFailures", strings(scan.failures()));
        report.add("warnings", strings(trace.warnings));
        return report;
    }

    private static ConditionSourceResolver resolver(ModDataRootRegistry modData) {
        return new ConditionSourceResolver(new PatchTargetResolver(), modData, new ConditionDocumentCache());
    }

    private static JsonArray strings(Iterable<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        return array;
    }

    /** Per-definition trace that mirrors the generation fold without replacing it. */
    private static final class Trace {
        private final Request request;
        private final GenerationAssetSnapshot snapshot;
        private final Set<String> installedIds;
        private final Map<String, String> versions;
        private final ConditionSourceResolver conditions;
        private final PatchEngine engine = new PatchEngine();
        private final ConflictAnalyzer analyzer = new ConflictAnalyzer();
        private final PatchConditionEvaluator evaluator = new PatchConditionEvaluator();
        private final JsonArray overlays = new JsonArray();
        private final JsonArray targets = new JsonArray();
        private final List<String> warnings = new ArrayList<>();
        /** Document right after each definition applied, keyed by target, pack and patch ID. */
        private final Map<String, JsonObject> afterDefinition = new HashMap<>();

        private Trace(Request request, GenerationAssetSnapshot snapshot, Set<String> installedIds,
                      Map<String, String> versions, ConditionSourceResolver conditions) {
            this.request = request;
            this.snapshot = snapshot;
            this.installedIds = installedIds;
            this.versions = versions;
            this.conditions = conditions;
        }

        /** Same binding and neutral-over-legacy preference as {@code PatchGenerationService.generate}. */
        private Map<String, List<PatchDefinition>> groupTargets(List<PatchDefinition> definitions) {
            PatchTargetExpander expander = new PatchTargetExpander();
            Map<List<String>, PatchDefinition> concrete = new LinkedHashMap<>();
            for (PatchDefinition definition : definitions) {
                List<String> expanded = expander.expand(definition.targetSelector(), snapshot);
                if (expanded.isEmpty()) {
                    JsonObject row = overlay(definition, definition.targetSelector().expression());
                    row.addProperty("outcome", "no-match");
                    row.addProperty("reason", "Target selector matched no assets.");
                    if (included(definition.targetSelector().expression())) overlays.add(row);
                }
                for (String target : expanded) {
                    PatchDefinition bound = definition.bindTarget(target);
                    List<String> key = List.of(bound.sourcePack(), bound.id(), bound.target());
                    PatchDefinition previous = concrete.get(key);
                    if (previous == null || (neutral(bound) && !neutral(previous))) concrete.put(key, bound);
                }
            }
            Map<String, List<PatchDefinition>> grouped = new TreeMap<>(Utf8Ordering.UNSIGNED_BYTES);
            for (PatchDefinition bound : concrete.values()) {
                if (included(bound.target())) grouped.computeIfAbsent(bound.target(), ignored -> new ArrayList<>()).add(bound);
            }
            return grouped;
        }

        private boolean included(String target) {
            return request.targetFilter().isEmpty() || request.targetFilter().contains(target);
        }

        private static boolean neutral(PatchDefinition definition) {
            return definition.sourcePath().startsWith(PatchRoot.NEUTRAL.path() + "/");
        }

        private void traceTarget(String target, List<PatchDefinition> definitions, String rejection,
                                 JsonObject generatedDocument) {
            Map<PatchDefinition, JsonObject> rows = new LinkedHashMap<>();
            for (PatchDefinition definition : definitions.stream().sorted(PatchDefinition.ORDERING).toList()) {
                rows.put(definition, overlay(definition, target));
            }
            JsonObject targetRow = new JsonObject();
            targetRow.addProperty("target", target);
            JsonArray applied = new JsonArray();
            JsonObject traced = null;
            var record = snapshot.find(target);
            if (record.isEmpty()) {
                targetRow.add("provider", null);
                rows.values().forEach(row -> outcome(row, "target-missing", "Asset source is missing."));
            } else {
                targetRow.addProperty("provider", record.get().sourcePackId());
                traced = fold(target, record.get(), rows, applied);
            }
            rows.values().forEach(row -> {
                if (!row.has("outcome")) outcome(row, "target-rejected", rejection == null ? "" : rejection);
                overlays.add(row);
            });

            String status;
            if (rejection != null) status = record.isEmpty() ? "missing" : "rejected";
            else status = generatedDocument == null ? "unchanged" : "patched";
            targetRow.addProperty("status", status);
            if (rejection != null) targetRow.addProperty("reason", rejection);
            targetRow.add("applied", applied);
            if (generatedDocument != null) {
                if (traced != null && !traced.equals(generatedDocument)) {
                    warnings.add("Trace document differs from the generated document for " + target
                            + "; the generated document is reported.");
                }
                if (request.includeDocuments()) targetRow.add("document", generatedDocument);
            }
            targets.add(targetRow);
        }

        /** Mirrors the generation fold for one resolved target; returns the traced final document. */
        private JsonObject fold(String target, GenerationAssetSnapshot.AssetRecord record,
                                Map<PatchDefinition, JsonObject> rows, JsonArray applied) {
            byte[] bytes;
            JsonObject working;
            try {
                bytes = record.bytes();
                var parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) throw new IllegalArgumentException("Target JSON must be an object.");
                working = parsed.getAsJsonObject().deepCopy();
            } catch (RuntimeException failure) {
                rows.values().forEach(row -> outcome(row, "target-rejected", message(failure)));
                return null;
            }
            List<PatchDefinition> eligible = new ArrayList<>();
            for (Map.Entry<PatchDefinition, JsonObject> entry : rows.entrySet()) {
                PatchDefinition definition = entry.getKey();
                PatchConditionEvaluator.Evaluation evaluation = evaluator.evaluate(definition.condition(),
                        new PatchConditionEvaluator.EvaluationContext(installedIds, versions, request.serverVersion(),
                                target, bytes, conditions, snapshot.sources(), record.sourcePackId()));
                switch (evaluation.status()) {
                    case MATCHED -> eligible.add(definition);
                    case NOT_MATCHED -> outcome(entry.getValue(), "gated", evaluation.diagnostic());
                    case FAILED -> outcome(entry.getValue(), "condition-failed", evaluation.diagnostic());
                }
            }
            if (rows.values().stream().anyMatch(row -> "condition-failed".equals(outcomeOf(row)))) return null;
            List<MutationEffect> accepted = List.of();
            long order = 0;
            PatchEngine.ApplicationContext context = new PatchEngine.ApplicationContext(target, snapshot);
            for (PatchDefinition definition : eligible) {
                JsonObject row = rows.get(definition);
                PatchEngine.DefinitionResult result;
                try {
                    result = engine.applyDefinition(working, definition, context, order);
                } catch (RuntimeException failure) {
                    outcome(row, "failed", message(failure));
                    return null;
                }
                ConflictAnalyzer.Analysis analysis = analyzer.analyze(accepted, result.effects(), definition.conflictPolicy());
                row.add("appliedOperations", strings(result.applied()));
                row.add("skippedOperations", strings(result.skipped()));
                if (analysis.rejected()) {
                    outcome(row, "conflict-rejected", "ConflictPolicy Reject: this definition overlaps an earlier one.");
                    return null;
                }
                String outcome = result.skipped().isEmpty() ? "applied"
                        : result.applied().isEmpty() ? "all-operations-skipped" : "partially-applied";
                outcome(row, outcome, "");
                applied.add(definition.id());
                working = result.patched();
                afterDefinition.put(key(target, definition.sourcePack(), definition.id()), working);
                accepted = analysis.acceptedEffects();
                order = result.nextOperationOrder();
            }
            return working;
        }

        /** Conflict rows from the generation plan, with the value each side wrote and the final value. */
        private JsonArray conflicts(List<ConflictRecord> records, Map<String, JsonObject> generated) {
            JsonArray array = new JsonArray();
            for (ConflictRecord record : records) {
                if (!included(record.target())) continue;
                JsonObject row = new JsonObject();
                row.addProperty("target", record.target());
                row.addProperty("path", record.path());
                row.addProperty("kind", record.effectKind().name());
                row.addProperty("scope", record.scope().name());
                row.addProperty("classification", record.classification().name());
                row.add("earlier", side(record.target(), record.path(), record.earlier()));
                row.add("later", side(record.target(), record.path(), record.later()));
                JsonObject document = generated.get(record.target());
                if (document == null) {
                    row.add("final", null);
                    row.addProperty("finalPresent", false);
                } else {
                    JsonElement value = valueAt(document, record.path());
                    row.add("final", value);
                    row.addProperty("finalPresent", value != null);
                }
                array.add(row);
            }
            return array;
        }

        private JsonObject side(String target, String path, ConflictRecord.EffectRef ref) {
            JsonObject side = new JsonObject();
            side.addProperty("sourcePack", ref.sourcePackId());
            side.addProperty("patchId", ref.patchId());
            side.addProperty("operationId", ref.operationId());
            JsonObject after = afterDefinition.get(key(target, ref.sourcePackId(), ref.patchId()));
            JsonElement value = after == null ? null : valueAt(after, path);
            side.add("value", value);
            side.addProperty("present", value != null);
            return side;
        }

        private static String key(String target, String pack, String patchId) {
            return target + "\u0000" + pack + "\u0000" + patchId;
        }

        private static JsonObject overlay(PatchDefinition definition, String target) {
            JsonObject row = new JsonObject();
            row.addProperty("patchId", definition.id());
            row.addProperty("sourcePack", definition.sourcePack());
            row.addProperty("sourcePath", definition.sourcePath());
            row.addProperty("target", target);
            row.addProperty("selector", definition.targetSelector().expression());
            row.addProperty("priority", definition.priority());
            row.addProperty("conflictPolicy", definition.conflictPolicy().name());
            return row;
        }

        private static void outcome(JsonObject row, String outcome, String reason) {
            row.addProperty("outcome", outcome);
            row.addProperty("reason", reason == null ? "" : reason);
        }

        private static String outcomeOf(JsonObject row) {
            return row.has("outcome") ? row.get("outcome").getAsString() : null;
        }

        private static String message(RuntimeException failure) {
            return failure.getMessage() == null ? "Patch application failed." : failure.getMessage();
        }
    }

    /** Reads an RFC 6901 pointer; returns null when the path is absent. */
    static JsonElement valueAt(JsonElement document, String pointer) {
        if (pointer == null || pointer.isEmpty()) return document.deepCopy();
        JsonElement current = document;
        for (String raw : pointer.substring(1).split("/", -1)) {
            String token = raw.replace("~1", "/").replace("~0", "~");
            if (current.isJsonObject()) {
                current = current.getAsJsonObject().get(token);
            } else if (current.isJsonArray()) {
                JsonArray array = current.getAsJsonArray();
                try {
                    int index = Integer.parseInt(token);
                    current = index >= 0 && index < array.size() ? array.get(index) : null;
                } catch (NumberFormatException notIndex) {
                    current = null;
                }
            } else {
                current = null;
            }
            if (current == null) return null;
        }
        return current.deepCopy();
    }
}
