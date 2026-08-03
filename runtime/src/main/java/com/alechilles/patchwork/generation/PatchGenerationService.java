package com.alechilles.patchwork.generation;

import com.alechilles.patchwork.conditions.ConditionSourceResolver;
import com.alechilles.patchwork.conditions.PatchCondition;
import com.alechilles.patchwork.conditions.PatchConditionEvaluator;
import com.alechilles.patchwork.discovery.PatchScanner;
import com.alechilles.patchwork.discovery.PatchSource;
import com.alechilles.patchwork.discovery.PatchTargetResolver;
import com.alechilles.patchwork.engine.PatchDefinition;
import com.alechilles.patchwork.engine.PatchEngine;
import com.alechilles.patchwork.engine.PatchMacroRegistry;
import com.alechilles.patchwork.format.Utf8Ordering;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds an immutable patch publication plan without mutating the filesystem or runtime state. */
public final class PatchGenerationService {
    private final ScanStage scanner;
    private final ResolveStage targetResolver;
    private final EvaluateStage conditionEvaluator;
    private final ApplyStage patchEngine;
    /** Creates a service with production discovery, resolution, and JSON application collaborators. */
    public PatchGenerationService() {
        this(new PatchScanner(), new PatchTargetResolver(), new PatchEngine());
    }
    /** Creates a production generator that expands the elected host's contribution macros. */
    public PatchGenerationService(PatchMacroRegistry macros) {
        this(new PatchScanner(), new PatchTargetResolver(), new PatchEngine(Objects.requireNonNull(macros, "macros")));
    }
    PatchGenerationService(PatchScanner scanner, PatchTargetResolver targetResolver, PatchEngine patchEngine) {
        this(request -> scanner.scan(request.assetSnapshot(), request.installedIds()),
                (request, target) -> resolveSnapshot(request.assetSnapshot(), target),
                (definition, request, resolved) -> new PatchConditionEvaluator().evaluate(
                        request.conditionsByPatchId().getOrDefault(definition.id(), definition.condition()),
                        new PatchConditionEvaluator.EvaluationContext(request.installedIds(), request.versions(), request.serverVersion(), resolved.target(), resolved.bytes(), request.conditionResolver(), request.sources(), resolved.sourcePackId())),
                patchEngine::apply);
    }
    PatchGenerationService(ScanStage scanner, ResolveStage targetResolver, EvaluateStage conditionEvaluator, ApplyStage patchEngine) {
        this.scanner = Objects.requireNonNull(scanner); this.targetResolver = Objects.requireNonNull(targetResolver);
        this.conditionEvaluator = Objects.requireNonNull(conditionEvaluator); this.patchEngine = Objects.requireNonNull(patchEngine);
    }
    /** Scans and applies definitions into a pure, deterministic plan; a rejected target never blocks unrelated targets. */
    public GenerationPlan generate(GenerationRequest request) {
        Objects.requireNonNull(request, "request");
        request.conditionResolver().claimGenerationPass();
        PatchScanner.ScanResult scan = scanner.scan(request);
        Map<String, List<PatchDefinition>> targets = new java.util.TreeMap<>();
        for (PatchDefinition definition : scan.definitions()) targets.computeIfAbsent(definition.target(), ignored -> new ArrayList<>()).add(definition);
        List<GeneratedPackManifest.Entry> entries = new ArrayList<>();
        Map<String, String> rejected = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>(scan.skipped());
        for (Map.Entry<String, List<PatchDefinition>> group : targets.entrySet()) planTarget(request, group.getKey(), group.getValue(), entries, rejected, skipped);
        GeneratedPackManifest manifest = new GeneratedPackManifest(entries);
        return new GenerationPlan(manifest.entries(), new PatchStatusSnapshot(skipped, rejected, scan.failures()), manifest,
                request.assetSnapshot().sourcePackIds());
    }
    private void planTarget(GenerationRequest request, String target, List<PatchDefinition> definitions, List<GeneratedPackManifest.Entry> entries, Map<String, String> rejected, List<String> skipped) {
        PatchTargetResolver.Resolution resolved = targetResolver.resolve(request, target);
        if (resolved.status() != PatchTargetResolver.Status.FOUND) { rejected.put(target, resolved.diagnostic()); return; }
        try {
            PatchTargetResolver.ResolvedTarget resolvedTarget = resolved.resolvedTarget();
            var parsed = JsonParser.parseString(new String(resolvedTarget.bytes(), StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("Target JSON must be an object.");
            List<PatchDefinition> eligible = eligibleDefinitions(request, resolvedTarget, definitions, skipped);
            if (eligible.isEmpty()) return;
            PatchEngine.PatchResult result = patchEngine.apply(parsed.getAsJsonObject(), eligible,
                    new PatchEngine.ApplicationContext(target, request.assetSnapshot()));
            skipped.addAll(result.skipped());
            entries.add(new GeneratedPackManifest.Entry(target, result.patched().toString().getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException failure) { rejected.put(target, safeMessage(failure)); }
    }
    private List<PatchDefinition> eligibleDefinitions(GenerationRequest request, PatchTargetResolver.ResolvedTarget resolvedTarget, List<PatchDefinition> definitions, List<String> skipped) {
        List<PatchDefinition> eligible = new ArrayList<>();
        for (PatchDefinition definition : definitions) {
            PatchConditionEvaluator.Evaluation evaluation = conditionEvaluator.evaluate(definition, request, resolvedTarget);
            if (evaluation.status() == PatchConditionEvaluator.Status.FAILED) throw new IllegalArgumentException("Condition failed for " + definition.id() + ": " + evaluation.diagnostic());
            if (evaluation.matched()) eligible.add(definition); else skipped.add(definition.id() + " skipped: " + evaluation.diagnostic());
        }
        return eligible;
    }
    private static String safeMessage(RuntimeException failure) { return failure.getMessage() == null ? "Patch application failed." : failure.getMessage(); }
    private static PatchTargetResolver.Resolution resolveSnapshot(GenerationAssetSnapshot assets, String target) {
        final String normalized;
        try {
            normalized = PatchScanner.normalizeAssetPath(target);
        } catch (IllegalArgumentException unsafe) {
            return new PatchTargetResolver.Resolution(PatchTargetResolver.Status.FAILED, null, "Unsafe asset path.");
        }
        return assets.find(normalized)
                .map(record -> new PatchTargetResolver.Resolution(PatchTargetResolver.Status.FOUND,
                        new PatchTargetResolver.ResolvedTarget(record.sourcePackId(), record.sourcePackLoadOrder(),
                                record.path(), record.bytes()), ""))
                .orElseGet(() -> new PatchTargetResolver.Resolution(PatchTargetResolver.Status.MISSING, null,
                        "Asset source is missing."));
    }
    @FunctionalInterface interface ScanStage { PatchScanner.ScanResult scan(GenerationRequest request); }
    @FunctionalInterface interface ResolveStage { PatchTargetResolver.Resolution resolve(GenerationRequest request, String target); }
    @FunctionalInterface interface EvaluateStage { PatchConditionEvaluator.Evaluation evaluate(PatchDefinition definition, GenerationRequest request, PatchTargetResolver.ResolvedTarget resolvedTarget); }
    @FunctionalInterface interface ApplyStage {
        PatchEngine.PatchResult apply(com.google.gson.JsonObject source, List<PatchDefinition> definitions,
                                      PatchEngine.ApplicationContext context);
    }
    /** Immutable generation inputs; the resolver/cache instance is deliberately shared for the entire pass. */
    public record GenerationRequest(GenerationAssetSnapshot assetSnapshot, Set<String> installedIds, Map<String, String> versions,
                                    String serverVersion, ConditionSourceResolver conditionResolver,
                                    Map<String, PatchCondition> conditionsByPatchId) {
        public GenerationRequest(GenerationAssetSnapshot assetSnapshot, Set<String> installedIds,
                                 Map<String, String> versions, String serverVersion,
                                 ConditionSourceResolver conditionResolver) {
            this(assetSnapshot, installedIds, versions, serverVersion, conditionResolver, Map.of());
        }

        /** Compatibility constructor for callers that still provide live filesystem sources. */
        public GenerationRequest(List<PatchSource> sources, Set<String> installedIds,
                                 Map<String, String> versions, String serverVersion,
                                 ConditionSourceResolver conditionResolver,
                                 Map<String, PatchCondition> conditionsByPatchId) {
            this(GenerationAssetSnapshot.capture(sources), installedIds, versions, serverVersion,
                    conditionResolver, conditionsByPatchId);
        }

        /** Compatibility constructor for callers that still provide live filesystem sources. */
        public GenerationRequest(List<PatchSource> sources, Set<String> installedIds,
                                 Map<String, String> versions, String serverVersion,
                                 ConditionSourceResolver conditionResolver) {
            this(sources, installedIds, versions, serverVersion, conditionResolver, Map.of());
        }

        public GenerationRequest {
            assetSnapshot = Objects.requireNonNull(assetSnapshot, "assetSnapshot");
            installedIds = Set.copyOf(installedIds);
            versions = Collections.unmodifiableMap(new LinkedHashMap<>(versions));
            serverVersion = Objects.requireNonNull(serverVersion);
            conditionResolver = Objects.requireNonNull(conditionResolver, "conditionResolver").withAssets(assetSnapshot);
            conditionsByPatchId = Collections.unmodifiableMap(new LinkedHashMap<>(conditionsByPatchId));
        }

        /** Compatibility view of the captured source descriptors. */
        public List<PatchSource> sources() {
            return assetSnapshot.sources();
        }
    }
    /** Immutable publication payload and its single matching status snapshot. */
    public record GenerationPlan(List<GeneratedPackManifest.Entry> entries, PatchStatusSnapshot status, GeneratedPackManifest manifest, List<String> sourcePackIds) {
        public GenerationPlan(List<GeneratedPackManifest.Entry> entries, PatchStatusSnapshot status, GeneratedPackManifest manifest) { this(entries, status, manifest, List.of()); }
        public GenerationPlan {
            manifest = Objects.requireNonNull(manifest); status = Objects.requireNonNull(status);
            GeneratedPackManifest canonical = new GeneratedPackManifest(entries);
            if (!sameEntries(canonical.entries(), manifest.entries())) throw new IllegalArgumentException("Plan entries must match the manifest.");
            entries = canonical.entries();
            sourcePackIds = sourcePackIds.stream().filter(Objects::nonNull).distinct().sorted(Utf8Ordering.UNSIGNED_BYTES).toList();
        }
        private static boolean sameEntries(List<GeneratedPackManifest.Entry> left, List<GeneratedPackManifest.Entry> right) {
            if (left.size() != right.size()) return false;
            for (int index = 0; index < left.size(); index++) {
                if (!left.get(index).target().equals(right.get(index).target())
                        || !java.util.Arrays.equals(left.get(index).bytes(), right.get(index).bytes())) return false;
            }
            return true;
        }
    }
}
