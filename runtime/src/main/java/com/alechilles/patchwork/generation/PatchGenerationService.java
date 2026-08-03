package com.alechilles.patchwork.generation;

import com.alechilles.patchwork.conditions.ConditionSourceResolver;
import com.alechilles.patchwork.conditions.PatchCondition;
import com.alechilles.patchwork.conditions.PatchConditionEvaluator;
import com.alechilles.patchwork.conflict.ConflictAnalyzer;
import com.alechilles.patchwork.conflict.ConflictRecord;
import com.alechilles.patchwork.conflict.ConflictReport;
import com.alechilles.patchwork.discovery.PatchScanner;
import com.alechilles.patchwork.discovery.PatchSource;
import com.alechilles.patchwork.discovery.PatchTargetResolver;
import com.alechilles.patchwork.discovery.PatchTargetExpander;
import com.alechilles.patchwork.discovery.PatchTargetSelector;
import com.alechilles.patchwork.discovery.PatchRoot;
import com.alechilles.patchwork.engine.PatchDefinition;
import com.alechilles.patchwork.engine.PatchEngine;
import com.alechilles.patchwork.engine.PatchMacroRegistry;
import com.alechilles.patchwork.engine.MutationEffect;
import com.alechilles.patchwork.format.Utf8Ordering;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.HashMap;

/** Builds an immutable patch publication plan without mutating the filesystem or runtime state. */
public final class PatchGenerationService {
    private final ScanStage scanner;
    private final ResolveStage targetResolver;
    private final EvaluateStage conditionEvaluator;
    private final ApplyStage patchEngine;
    private final DefinitionApplyStage definitionApply;
    private final PatchTargetExpander targetExpander;
    private final ConflictAnalyzer conflictAnalyzer;
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
                patchEngine::apply,
                patchEngine::applyDefinition);
    }
    PatchGenerationService(ScanStage scanner, ResolveStage targetResolver, EvaluateStage conditionEvaluator,
                           ApplyStage patchEngine, DefinitionApplyStage definitionApply) {
        this.scanner = Objects.requireNonNull(scanner); this.targetResolver = Objects.requireNonNull(targetResolver);
        this.conditionEvaluator = Objects.requireNonNull(conditionEvaluator); this.patchEngine = Objects.requireNonNull(patchEngine);
        this.definitionApply = Objects.requireNonNull(definitionApply, "definitionApply");
        this.targetExpander = new PatchTargetExpander();
        this.conflictAnalyzer = new ConflictAnalyzer();
    }
    PatchGenerationService(ScanStage scanner, ResolveStage targetResolver, EvaluateStage conditionEvaluator, ApplyStage patchEngine) {
        this.scanner = Objects.requireNonNull(scanner); this.targetResolver = Objects.requireNonNull(targetResolver);
        this.conditionEvaluator = Objects.requireNonNull(conditionEvaluator); this.patchEngine = Objects.requireNonNull(patchEngine);
        this.targetExpander = new PatchTargetExpander();
        this.definitionApply = (source, definition, context, firstOperationOrder) -> {
            PatchEngine.PatchResult result = patchEngine.apply(source, List.of(definition), context);
            long nextOrder = firstOperationOrder + definition.operations().size();
            return new PatchEngine.DefinitionResult(result.patched(), result.applied(), result.skipped(), result.effects(), nextOrder);
        };
        this.conflictAnalyzer = new ConflictAnalyzer();
    }
    /** Scans and applies definitions into a pure, deterministic plan; a rejected target never blocks unrelated targets. */
    public GenerationPlan generate(GenerationRequest request) {
        Objects.requireNonNull(request, "request");
        request.conditionResolver().claimGenerationPass();
        PatchScanner.ScanResult scan = scanner.scan(request);
        List<String> skipped = new ArrayList<>(scan.skipped());
        DependencyBuilder dependencyBuilder = new DependencyBuilder(scan.definitionDependencies());
        Map<String, List<PatchDefinition>> targets = new java.util.TreeMap<>(Utf8Ordering.UNSIGNED_BYTES);
        Map<ConcreteDefinitionKey, PatchDefinition> concreteDefinitions = new LinkedHashMap<>();
        for (PatchDefinition definition : scan.definitions()) {
            PatchTargetSelector selector = definition.targetSelector();
            if (selector.kind() == PatchTargetSelector.Kind.GLOB) {
                dependencyBuilder.addGlob(selector);
            }
            List<String> expanded = targetExpander.expand(selector, request.assetSnapshot());
            if (expanded.isEmpty() && selector.kind() == PatchTargetSelector.Kind.GLOB) {
                skipped.add(definition.id() + " target selector matched no assets: " + selector.expression());
            }
            dependencyBuilder.addDefinitionTargets(definition, expanded);
            for (String concreteTarget : expanded) {
                PatchDefinition bound = definition.bindTarget(concreteTarget);
                ConcreteDefinitionKey key = new ConcreteDefinitionKey(bound.sourcePack(), bound.id(), bound.target());
                PatchDefinition previous = concreteDefinitions.get(key);
                if (previous == null || prefers(bound, previous)) concreteDefinitions.put(key, bound);
            }
        }
        for (PatchDefinition bound : concreteDefinitions.values()) {
            targets.computeIfAbsent(bound.target(), ignored -> new ArrayList<>()).add(bound);
        }
        for (PatchDefinition definition : scan.definitions()) {
            for (var operation : definition.operations()) {
                if (operation.source() != null) dependencyBuilder.addSourceAsset(operation.source());
            }
        }
        List<GeneratedPackManifest.Entry> entries = new ArrayList<>();
        Map<String, String> rejected = new LinkedHashMap<>();
        List<ConflictRecord> conflictRecords = new ArrayList<>();
        for (Map.Entry<String, List<PatchDefinition>> group : targets.entrySet()) {
            planTarget(request, group.getKey(), group.getValue(), entries, rejected, skipped, conflictRecords);
        }
        GeneratedPackManifest manifest = new GeneratedPackManifest(entries);
        ConflictReport conflicts = new ConflictReport(conflictRecords);
        PatchStatusSnapshot status = new PatchStatusSnapshot(skipped, rejected, scan.failures(), conflicts);
        return new GenerationPlan(manifest.entries(), status, manifest,
                request.assetSnapshot().sourcePackIds(), dependencyBuilder.build(), conflicts);
    }
    private void planTarget(GenerationRequest request, String target, List<PatchDefinition> definitions,
                            List<GeneratedPackManifest.Entry> entries, Map<String, String> rejected, List<String> skipped,
                            List<ConflictRecord> conflictRecords) {
        PatchTargetResolver.Resolution resolved = targetResolver.resolve(request, target);
        if (resolved.status() != PatchTargetResolver.Status.FOUND) { rejected.put(target, resolved.diagnostic()); return; }
        try {
            PatchTargetResolver.ResolvedTarget resolvedTarget = resolved.resolvedTarget();
            var parsed = JsonParser.parseString(new String(resolvedTarget.bytes(), StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("Target JSON must be an object.");
            List<PatchDefinition> eligible = eligibleDefinitions(request, resolvedTarget, definitions, skipped);
            if (eligible.isEmpty()) return;
            JsonObject working = parsed.getAsJsonObject().deepCopy();
            List<MutationEffect> acceptedEffects = List.of();
            List<ConflictRecord> targetConflicts = new ArrayList<>();
            long operationOrder = 0;
            PatchEngine.ApplicationContext context = new PatchEngine.ApplicationContext(target, request.assetSnapshot());
            for (PatchDefinition definition : eligible.stream().sorted(PatchDefinition.ORDERING).toList()) {
                PatchEngine.DefinitionResult candidate = definitionApply.apply(working, definition, context, operationOrder);
                ConflictAnalyzer.Analysis analysis = conflictAnalyzer.analyze(
                        acceptedEffects, candidate.effects(), definition.conflictPolicy());
                targetConflicts.addAll(analysis.conflicts());
                skipped.addAll(candidate.skipped());
                if (analysis.rejected()) {
                    conflictRecords.addAll(targetConflicts);
                    rejected.put(target, "Conflict rejected by patch '" + definition.id() + "' (target-local).");
                    return;
                }
                working = candidate.patched();
                acceptedEffects = analysis.acceptedEffects();
                operationOrder = candidate.nextOperationOrder();
            }
            conflictRecords.addAll(targetConflicts);
            entries.add(new GeneratedPackManifest.Entry(target, working.toString().getBytes(StandardCharsets.UTF_8)));
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
    private static boolean prefers(PatchDefinition candidate, PatchDefinition previous) {
        boolean candidateNeutral = candidate.sourcePath().startsWith(PatchRoot.NEUTRAL.path() + "/");
        boolean previousNeutral = previous.sourcePath().startsWith(PatchRoot.NEUTRAL.path() + "/");
        return candidateNeutral && !previousNeutral;
    }
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
    @FunctionalInterface interface DefinitionApplyStage {
        PatchEngine.DefinitionResult apply(com.google.gson.JsonObject source, PatchDefinition definition,
                                           PatchEngine.ApplicationContext context, long firstOperationOrder);
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
    public record GenerationPlan(
            List<GeneratedPackManifest.Entry> entries,
            PatchStatusSnapshot status,
            GeneratedPackManifest manifest,
            List<String> sourcePackIds,
            GenerationDependencyIndex dependencies,
            ConflictReport conflicts) {
        public GenerationPlan(List<GeneratedPackManifest.Entry> entries, PatchStatusSnapshot status, GeneratedPackManifest manifest) {
            this(entries, status, manifest, List.of(), GenerationDependencyIndex.empty(), ConflictReport.empty());
        }
        public GenerationPlan(List<GeneratedPackManifest.Entry> entries, PatchStatusSnapshot status,
                              GeneratedPackManifest manifest, List<String> sourcePackIds) {
            this(entries, status, manifest, sourcePackIds, GenerationDependencyIndex.empty(), ConflictReport.empty());
        }
        public GenerationPlan(List<GeneratedPackManifest.Entry> entries, PatchStatusSnapshot status,
                              GeneratedPackManifest manifest, GenerationDependencyIndex dependencies) {
            this(entries, status, manifest, List.of(), dependencies, ConflictReport.empty());
        }
        public GenerationPlan(List<GeneratedPackManifest.Entry> entries, PatchStatusSnapshot status,
                              GeneratedPackManifest manifest, GenerationDependencyIndex dependencies,
                              List<String> sourcePackIds) {
            this(entries, status, manifest, sourcePackIds, dependencies, ConflictReport.empty());
        }
        public GenerationPlan(List<GeneratedPackManifest.Entry> entries, PatchStatusSnapshot status,
                              GeneratedPackManifest manifest, ConflictReport conflicts) {
            this(entries, status, manifest, List.of(), GenerationDependencyIndex.empty(), conflicts);
        }
        public GenerationPlan {
            manifest = Objects.requireNonNull(manifest); status = Objects.requireNonNull(status);
            dependencies = Objects.requireNonNull(dependencies, "dependencies");
            conflicts = conflicts == null ? ConflictReport.empty() : conflicts;
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

    private record ConcreteDefinitionKey(String sourcePackId, String patchId, String target) { }

    /** Collects dependency metadata while preserving invalid source-file entries. */
    private static final class DependencyBuilder {
        private final Map<DefinitionFileKey, GenerationDependencyIndex.DefinitionDependency> definitions = new HashMap<>();
        private final Set<String> expandedTargets = new LinkedHashSet<>();
        private final Set<String> sourceAssets = new LinkedHashSet<>();
        private final Set<GenerationDependencyIndex.GlobRoot> globRoots = new LinkedHashSet<>();

        private DependencyBuilder(Set<GenerationDependencyIndex.DefinitionDependency> scanned) {
            for (GenerationDependencyIndex.DefinitionDependency dependency : scanned) {
                definitions.put(new DefinitionFileKey(dependency.sourcePackId(), dependency.assetPath()), dependency);
                expandedTargets.addAll(dependency.expandedTargets());
            }
        }

        private void addDefinitionTargets(PatchDefinition definition, List<String> targets) {
            DefinitionFileKey key = new DefinitionFileKey(definition.sourcePack(), definition.sourcePath());
            GenerationDependencyIndex.DefinitionDependency previous = definitions.get(key);
            GenerationDependencyIndex.Validity validity = previous == null
                    ? GenerationDependencyIndex.Validity.VALID : previous.validity();
            Set<String> merged = new LinkedHashSet<>();
            if (previous != null) merged.addAll(previous.expandedTargets());
            merged.addAll(targets);
            definitions.put(key, new GenerationDependencyIndex.DefinitionDependency(
                    definition.sourcePack(), definition.sourcePath(), validity, merged));
            expandedTargets.addAll(targets);
        }

        private void addSourceAsset(String source) {
            sourceAssets.add(source);
        }

        private void addGlob(PatchTargetSelector selector) {
            globRoots.add(new GenerationDependencyIndex.GlobRoot(selector.expression(), selector.stablePrefix()));
        }

        private GenerationDependencyIndex build() {
            return new GenerationDependencyIndex(new LinkedHashSet<>(definitions.values()),
                    expandedTargets, sourceAssets, globRoots);
        }
    }

    private record DefinitionFileKey(String sourcePackId, String assetPath) { }
}
