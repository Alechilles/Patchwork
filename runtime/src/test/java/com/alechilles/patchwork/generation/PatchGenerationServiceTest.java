package com.alechilles.patchwork.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.alechilles.patchwork.conditions.ConditionDocumentCache;
import com.alechilles.patchwork.conditions.ConditionSourceResolver;
import com.alechilles.patchwork.conditions.ModDataRootRegistry;
import com.alechilles.patchwork.conditions.PatchCondition;
import com.alechilles.patchwork.conditions.ConditionSource;
import com.alechilles.patchwork.discovery.PatchSource;
import com.alechilles.patchwork.discovery.PatchScanner;
import com.alechilles.patchwork.discovery.PatchTargetResolver;
import com.alechilles.patchwork.engine.PatchDefinition;
import com.alechilles.patchwork.engine.PatchEngine;
import com.alechilles.patchwork.conditions.PatchConditionEvaluator;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests pure generation planning and target isolation. */
final class PatchGenerationServiceTest {
    @TempDir Path temporary;

    @Test
    void plansSuccessfulTargetsDeterministicallyAndRejectsOnlyFailedTarget() throws Exception {
        Path pack = Files.createDirectories(temporary.resolve("pack"));
        Files.createDirectories(pack.resolve("Server/Patchwork/Patches"));
        Files.createDirectories(pack.resolve("Server"));
        Files.writeString(pack.resolve("Server/A.json"), "{\"value\":1}");
        Files.writeString(pack.resolve("Server/B.json"), "{\"value\":1}");
        Files.writeString(pack.resolve("Server/Patchwork/Patches/a.json"), """
                {"Id":"a","Target":"Server/A.json","Operations":[{"Op":"Replace","Path":"/missing","Value":2}]}
                """);
        Files.writeString(pack.resolve("Server/Patchwork/Patches/b.json"), """
                {"Id":"b","Target":"Server/B.json","Operations":[{"Op":"Replace","Path":"/value","Value":2}]}
                """);
        PatchGenerationService service = new PatchGenerationService();
        var request = new PatchGenerationService.GenerationRequest(
                List.of(PatchSource.directory("test", 1, pack)), Set.of(), Map.of(), "1.0", resolver());

        PatchGenerationService.GenerationPlan plan = service.generate(request);

        assertEquals(List.of("Server/B.json"), plan.entries().stream().map(GeneratedPackManifest.Entry::target).toList());
        assertEquals("{\"value\":2}", new String(plan.entries().getFirst().bytes()));
        assertTrue(plan.status().rejectedTargets().containsKey("Server/A.json"));
        assertFalse(plan.status().rejectedTargets().containsKey("Server/B.json"));
        assertEquals(List.of("Server/B.json"), plan.manifest().entries().stream().map(GeneratedPackManifest.Entry::target).toList());
    }

    @Test
    void omitsTargetWhenAllDefinitionsAreNonApplicable() throws Exception {
        Path pack = Files.createDirectories(temporary.resolve("nonapplicable"));
        Files.createDirectories(pack.resolve("Server/Patchwork/Patches"));
        Files.createDirectories(pack.resolve("Server"));
        Files.writeString(pack.resolve("Server/Test.json"), "{\"value\":1}");
        Files.writeString(pack.resolve("Server/Patchwork/Patches/test.json"), """
                {"Id":"conditional","Target":"Server/Test.json","Operations":[{"Op":"Replace","Path":"/value","Value":2}]}
                """);
        var request = new PatchGenerationService.GenerationRequest(List.of(PatchSource.directory("test", 1, pack)), Set.of(), Map.of(), "1.0", resolver(), Map.of("conditional", new PatchCondition.ModInstalled("missing")));
        assertTrue(new PatchGenerationService().generate(request).entries().isEmpty());
    }

    @Test
    void injectedStagesRunScanResolveConditionThenApplyInOrder() {
        List<String> order = new java.util.ArrayList<>();
        PatchDefinition definition = PatchDefinition.parse(JsonParser.parseString("""
                {"Id":"ordered","Target":"Server/Test.json","Operations":[{"Op":"Replace","Path":"/value","Value":2}]}
                """).getAsJsonObject(), "Test:Pack", "patch.json");
        PatchGenerationService service = new PatchGenerationService(
                request -> { order.add("scan"); return new com.alechilles.patchwork.discovery.PatchScanner.ScanResult(List.of(definition), List.of(), List.of()); },
                (request, target) -> { order.add("resolve"); return new PatchTargetResolver.Resolution(PatchTargetResolver.Status.FOUND,
                        new PatchTargetResolver.ResolvedTarget("Test:Pack", 0, target, "{\"value\":1}".getBytes()), ""); },
                (patch, request, target, bytes) -> { order.add("condition"); return new com.alechilles.patchwork.conditions.PatchConditionEvaluator.Evaluation(com.alechilles.patchwork.conditions.PatchConditionEvaluator.Status.MATCHED, ""); },
                (source, definitions) -> { order.add("apply"); return new PatchEngine().apply(source, definitions); });
        service.generate(new PatchGenerationService.GenerationRequest(List.of(), Set.of(), Map.of(), "1", resolver()));
        assertEquals(List.of("scan", "resolve", "condition", "apply"), order);
    }

    @Test
    void conditionDocumentCacheSnapshotsSharedAssetAcrossWholePassDespiteMutation() throws Exception {
        Path pack = Files.createDirectories(temporary.resolve("cached"));
        Files.createDirectories(pack.resolve("Server/Patchwork/Patches"));
        Files.writeString(pack.resolve("Server/A.json"), "{\"value\":1}"); Files.writeString(pack.resolve("Server/B.json"), "{\"value\":1}");
        Files.writeString(pack.resolve("Server/Condition.json"), "{\"enabled\":true}");
        Files.writeString(pack.resolve("Server/Patchwork/Patches/a.json"), "{\"Id\":\"a\",\"Target\":\"Server/A.json\",\"Operations\":[{\"Op\":\"Replace\",\"Path\":\"/value\",\"Value\":2}]}");
        Files.writeString(pack.resolve("Server/Patchwork/Patches/b.json"), "{\"Id\":\"b\",\"Target\":\"Server/B.json\",\"Operations\":[{\"Op\":\"Replace\",\"Path\":\"/value\",\"Value\":2}]}");
        ConditionDocumentCache cache = new ConditionDocumentCache();
        ConditionSourceResolver resolver = new ConditionSourceResolver(new PatchTargetResolver(), new ModDataRootRegistry(Map.of()), cache);
        java.util.concurrent.atomic.AtomicBoolean mutated = new java.util.concurrent.atomic.AtomicBoolean();
        PatchConditionEvaluator real = new PatchConditionEvaluator();
        // Stage injection performs the backing mutation only after the first real evaluation cached the document.
        PatchGenerationService service = new PatchGenerationService(
                request -> new PatchScanner().scan(request.sources(), request.installedIds()),
                (request, target) -> new PatchTargetResolver().resolveDetailed(request.sources(), target),
                (definition, request, target, bytes) -> {
                    var outcome = real.evaluate(request.conditionsByPatchId().get(definition.id()),
                            new PatchConditionEvaluator.EvaluationContext(request.installedIds(), request.versions(), request.serverVersion(), target, bytes, request.conditionResolver(), request.sources()));
                    if (mutated.compareAndSet(false, true)) {
                        try { Files.writeString(pack.resolve("Server/Condition.json"), "{\"enabled\":false}"); }
                        catch (java.io.IOException failed) { throw new IllegalStateException(failed); }
                    }
                    return outcome;
                }, new PatchEngine()::apply);
        PatchCondition condition = new PatchCondition.JsonPathEquals(new ConditionSource.Asset("Server/Condition.json"), "/enabled", JsonParser.parseString("true"));
        var plan = service.generate(new PatchGenerationService.GenerationRequest(List.of(PatchSource.directory("Test:Pack", 0, pack)), Set.of(), Map.of(), "1", resolver, Map.of("a", condition, "b", condition)));
        assertEquals(List.of("Server/A.json", "Server/B.json"), plan.entries().stream().map(GeneratedPackManifest.Entry::target).toList());
        assertEquals(1, cache.snapshotCount());
    }

    @Test
    void resolverIsSharedWithinOnePassButRejectedForAnotherPass() {
        ConditionSourceResolver shared = resolver();
        PatchGenerationService service = testService(List.of(), PatchConditionEvaluator.Status.MATCHED);
        service.generate(new PatchGenerationService.GenerationRequest(List.of(), Set.of(), Map.of(), "1", shared));
        assertThrows(IllegalStateException.class, () -> service.generate(new PatchGenerationService.GenerationRequest(List.of(), Set.of(), Map.of(), "1", shared)));
    }

    @Test
    void matchedConditionAppliesItsTarget() {
        var plan = stagedPlan(PatchConditionEvaluator.Status.MATCHED, true);
        assertEquals(List.of("Server/Test.json"), plan.entries().stream().map(GeneratedPackManifest.Entry::target).toList());
    }

    @Test
    void unmatchedConditionOmitsItsTargetAndRecordsSkip() {
        var plan = stagedPlan(PatchConditionEvaluator.Status.NOT_MATCHED, true);
        assertTrue(plan.entries().isEmpty()); assertEquals(1, plan.status().skipped().size());
    }

    @Test
    void failedConditionRejectsWholeTarget() {
        var plan = stagedPlan(PatchConditionEvaluator.Status.FAILED, true);
        assertTrue(plan.entries().isEmpty()); assertTrue(plan.status().rejectedTargets().containsKey("Server/Test.json"));
    }

    @Test
    void requiredOperationFailureRejectsWholeTarget() {
        PatchDefinition invalid = definition("required", "Server/Test.json", "/missing");
        PatchGenerationService service = testService(List.of(invalid), PatchConditionEvaluator.Status.MATCHED);
        var plan = service.generate(request());
        assertTrue(plan.entries().isEmpty()); assertTrue(plan.status().rejectedTargets().containsKey("Server/Test.json"));
    }

    @Test
    void unrelatedTargetStillGeneratesWhenAnotherTargetFails() {
        PatchDefinition bad = definition("bad", "Server/A.json", "/missing");
        PatchDefinition good = definition("good", "Server/B.json", "/value");
        PatchGenerationService service = new PatchGenerationService(
                request -> new PatchScanner.ScanResult(List.of(bad, good), List.of(), List.of()),
                (request, target) -> new PatchTargetResolver.Resolution(PatchTargetResolver.Status.FOUND, new PatchTargetResolver.ResolvedTarget("test", 0, target, "{\"value\":1}".getBytes()), ""),
                (definition, request, target, bytes) -> new PatchConditionEvaluator.Evaluation(PatchConditionEvaluator.Status.MATCHED, ""), new PatchEngine()::apply);
        var plan = service.generate(request());
        assertEquals(List.of("Server/B.json"), plan.entries().stream().map(GeneratedPackManifest.Entry::target).toList());
        assertTrue(plan.status().rejectedTargets().containsKey("Server/A.json"));
    }

    @Test
    void generationValuesDefensivelyCopyInputsAndAccessors() {
        List<PatchSource> sources = new java.util.ArrayList<>(); Set<String> ids = new java.util.LinkedHashSet<>(List.of("a")); Map<String, String> versions = new java.util.LinkedHashMap<>(Map.of("a", "1"));
        Map<String, PatchCondition> conditions = new java.util.LinkedHashMap<>(Map.of("a", new PatchCondition.Always()));
        var request = new PatchGenerationService.GenerationRequest(sources, ids, versions, "1", resolver(), conditions);
        ids.add("mutated"); versions.put("mutated", "2"); conditions.clear();
        assertFalse(request.installedIds().contains("mutated")); assertFalse(request.versions().containsKey("mutated")); assertTrue(request.conditionsByPatchId().containsKey("a"));
        assertThrows(UnsupportedOperationException.class, () -> request.sources().add(PatchSource.directory("x", 0, temporary)));
        GeneratedPackManifest.Entry entry = new GeneratedPackManifest.Entry("Server/Z.json", new byte[] {1, 2}); byte[] returned = entry.bytes(); returned[0] = 9;
        GeneratedPackManifest manifest = new GeneratedPackManifest(List.of(entry)); assertArrayEquals(new byte[] {1, 2}, manifest.entries().getFirst().bytes());
        var status = new PatchStatusSnapshot(new java.util.ArrayList<>(List.of("skip")), new java.util.LinkedHashMap<>(Map.of("B", "b", "A", "a")), List.of("fail"));
        assertEquals(List.of("A", "B"), status.rejectedTargets().keySet().stream().toList()); assertThrows(UnsupportedOperationException.class, () -> status.skipped().add("x"));
        var plan = new PatchGenerationService.GenerationPlan(manifest.entries(), status, manifest, List.of("Zulu", "Alpha", "Zulu"));
        assertEquals(List.of("Alpha", "Zulu"), plan.sourcePackIds()); assertThrows(UnsupportedOperationException.class, () -> plan.entries().clear());
    }

    @Test
    void realScannerEvaluatesDefinitionLocalModDataAndLegacyConditionsWithoutIdCollisions() throws Exception {
        Path first = Files.createDirectories(temporary.resolve("first/Server/Patchwork/Patches"));
        Path firstRoot = first.getParent().getParent().getParent();
        Path second = Files.createDirectories(temporary.resolve("second/Server/Patchwork/Patches"));
        Path secondRoot = second.getParent().getParent().getParent();
        Path data = Files.createDirectories(temporary.resolve("mod-data"));
        Files.writeString(data.resolve("config.json"), "{\"enabled\":true}");
        Files.writeString(firstRoot.resolve("Server/Test.json"), "{\"value\":0}");
        Files.writeString(secondRoot.resolve("Server/Legacy.json"), "{\"enabled\":true,\"value\":0}");
        String modWhen = "\"When\":{\"JsonPathEquals\":{\"Source\":{\"Type\":\"ModData\",\"Mod\":\"Test:Mod\",\"Path\":\"config.json\"},\"Path\":\"/enabled\",\"Value\":true}}";
        Files.writeString(first.resolve("one.json"), "{\"Id\":\"same\",\"Target\":\"Server/Test.json\"," + modWhen + ",\"Operations\":[{\"Op\":\"Replace\",\"Path\":\"/value\",\"Value\":1}]}");
        Files.writeString(second.resolve("two.json"), "{\"Id\":\"same\",\"Target\":\"Server/Legacy.json\",\"When\":{\"JsonPathEquals\":{\"Asset\":\"$Target\",\"Path\":\"/enabled\",\"Equals\":true}},\"Operations\":[{\"Op\":\"Replace\",\"Path\":\"/value\",\"Value\":2}]}");
        List<PatchSource> sources = List.of(PatchSource.directory("First:Pack", 0, firstRoot), PatchSource.directory("Second:Pack", 1, secondRoot));
        PatchGenerationService service = new PatchGenerationService();
        var truePlan = service.generate(new PatchGenerationService.GenerationRequest(sources, Set.of("Test:Mod"), Map.of(), "1", new ConditionSourceResolver(new PatchTargetResolver(), new ModDataRootRegistry(Map.of("Test:Mod", data)), new ConditionDocumentCache())));
        assertEquals(List.of("Server/Legacy.json", "Server/Test.json"), truePlan.entries().stream().map(GeneratedPackManifest.Entry::target).toList());
        Files.writeString(data.resolve("config.json"), "{\"enabled\":false}");
        var falsePlan = service.generate(new PatchGenerationService.GenerationRequest(sources, Set.of("Test:Mod"), Map.of(), "1", new ConditionSourceResolver(new PatchTargetResolver(), new ModDataRootRegistry(Map.of("Test:Mod", data)), new ConditionDocumentCache())));
        assertEquals(List.of("Server/Legacy.json"), falsePlan.entries().stream().map(GeneratedPackManifest.Entry::target).toList());
    }

    @Test
    void malformedWhenIsReportedWithoutSuppressingUnrelatedValidPatch() throws Exception {
        Path pack = Files.createDirectories(temporary.resolve("invalid-when/Server/Patchwork/Patches"));
        Path root = pack.getParent().getParent().getParent();
        Files.writeString(root.resolve("Server/Good.json"), "{\"value\":0}");
        Files.writeString(pack.resolve("bad.json"), "{\"Id\":\"bad\",\"Target\":\"Server/Bad.json\",\"When\":{\"Unknown\":true},\"Operations\":[]}");
        Files.writeString(pack.resolve("good.json"), "{\"Id\":\"good\",\"Target\":\"Server/Good.json\",\"Operations\":[{\"Op\":\"Replace\",\"Path\":\"/value\",\"Value\":1}]}");
        var plan = new PatchGenerationService().generate(new PatchGenerationService.GenerationRequest(List.of(PatchSource.directory("Test:Pack", 0, root)), Set.of(), Map.of(), "1", resolver()));
        assertEquals(List.of("Server/Good.json"), plan.entries().stream().map(GeneratedPackManifest.Entry::target).toList());
        assertEquals(1, plan.status().scanFailures().size());
        assertTrue(plan.status().scanFailures().getFirst().contains("bad.json"));
    }

    private PatchGenerationService.GenerationPlan stagedPlan(PatchConditionEvaluator.Status status, boolean required) {
        PatchDefinition definition = definition("one", "Server/Test.json", required ? "/value" : "/missing");
        return testService(List.of(definition), status).generate(request());
    }

    private PatchGenerationService testService(List<PatchDefinition> definitions, PatchConditionEvaluator.Status status) {
        return new PatchGenerationService(request -> new PatchScanner.ScanResult(definitions, List.of(), List.of()),
                (request, target) -> new PatchTargetResolver.Resolution(PatchTargetResolver.Status.FOUND, new PatchTargetResolver.ResolvedTarget("test", 0, target, "{\"value\":1}".getBytes()), ""),
                (definition, request, target, bytes) -> new PatchConditionEvaluator.Evaluation(status, "condition"), new PatchEngine()::apply);
    }

    private static PatchDefinition definition(String id, String target, String path) {
        return PatchDefinition.parse(JsonParser.parseString("{\"Id\":\"" + id + "\",\"Target\":\"" + target + "\",\"Operations\":[{\"Op\":\"Replace\",\"Path\":\"" + path + "\",\"Value\":2}]}" ).getAsJsonObject(), "test", id + ".json");
    }

    private PatchGenerationService.GenerationRequest request() {
        return new PatchGenerationService.GenerationRequest(List.of(), Set.of(), Map.of(), "1", resolver());
    }

    private ConditionSourceResolver resolver() {
        return new ConditionSourceResolver(new PatchTargetResolver(), new ModDataRootRegistry(Map.of()), new ConditionDocumentCache());
    }
}
