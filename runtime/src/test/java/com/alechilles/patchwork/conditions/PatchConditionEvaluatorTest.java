package com.alechilles.patchwork.conditions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.patchwork.discovery.PatchSource;
import com.alechilles.patchwork.discovery.PatchTargetResolver;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Evaluation tests for target, asset, and ModData-backed JSON conditions. */
final class PatchConditionEvaluatorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void evaluatesTypeSensitiveJsonAndSnapshotsEachSourceOnce() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("mod-data"));
        Files.writeString(root.resolve("settings.json"), "{\"enabled\":true,\"count\":1}", StandardCharsets.UTF_8);
        PatchCondition condition = new PatchConditionParser().parse(JsonParser.parseString("""
                {"All":[
                  {"JsonPathEquals":{"Source":{"Type":"Target"},"Path":"/enabled","Equals":true}},
                  {"JsonPathEquals":{"Source":{"Type":"ModData","Mod":"Example:Mod","Path":"settings.json"},"Path":"/count","Equals":1}}
                ]}
                """).getAsJsonObject());
        ConditionSourceResolver resolver = new ConditionSourceResolver(
                new PatchTargetResolver(), new ModDataRootRegistry(Map.of("Example:Mod", root)), new ConditionDocumentCache());
        PatchConditionEvaluator evaluator = new PatchConditionEvaluator();
        PatchConditionEvaluator.EvaluationContext context = new PatchConditionEvaluator.EvaluationContext(
                List.of(), Map.of(), null, "Server/Target.json", "{\"enabled\":true}".getBytes(StandardCharsets.UTF_8), resolver
        );

        PatchConditionEvaluator.Evaluation result = evaluator.evaluate(condition, context);

        assertEquals(PatchConditionEvaluator.Status.MATCHED, result.status(), result.diagnostic());
        assertFalse(evaluator.evaluate(new PatchCondition.JsonPathEquals(new ConditionSource.Target(), "/enabled", JsonParser.parseString("\"true\"")), context).matched());
        assertEquals(2, resolver.documentCache().snapshotCount());
    }

    @Test
    void mapsMissingSourcesToNotMatchedAndUnsafeReadsToFailedWithoutValueLeaks() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("root"));
        Files.writeString(root.resolve("bad.json"), "not json", StandardCharsets.UTF_8);
        ConditionSourceResolver resolver = new ConditionSourceResolver(
                new PatchTargetResolver(), new ModDataRootRegistry(Map.of("Example:Mod", root)), new ConditionDocumentCache());
        PatchConditionEvaluator evaluator = new PatchConditionEvaluator();
        PatchConditionEvaluator.EvaluationContext context = new PatchConditionEvaluator.EvaluationContext(
                List.of(), Map.of(), null, "Server/Target.json", null, resolver);

        PatchConditionEvaluator.Evaluation missing = evaluator.evaluate(
                new PatchCondition.JsonPathExists(new ConditionSource.ModData("Missing:Mod", "secret.json"), "/value"), context);
        PatchConditionEvaluator.Evaluation malformed = evaluator.evaluate(
                new PatchCondition.JsonPathExists(new ConditionSource.ModData("Example:Mod", "bad.json"), "/value"), context);

        assertEquals(PatchConditionEvaluator.Status.NOT_MATCHED, missing.status());
        assertEquals(PatchConditionEvaluator.Status.FAILED, malformed.status());
        assertFalse(malformed.diagnostic().contains("not json"));
        assertFalse(malformed.diagnostic().contains("secret"));
    }

    @Test
    void preservesJsonNullAndComparesUnboundedVersions() {
        ConditionSourceResolver resolver = new ConditionSourceResolver(new PatchTargetResolver(), new ModDataRootRegistry(Map.of()), new ConditionDocumentCache());
        PatchConditionEvaluator.EvaluationContext context = new PatchConditionEvaluator.EvaluationContext(
                List.of("Example:Mod"), Map.of("Example:Mod", "999999999999999999999.0"), "999999999999999999999.0",
                "Server/Target.json", "{\"flag\":null}".getBytes(StandardCharsets.UTF_8), resolver);
        PatchConditionEvaluator evaluator = new PatchConditionEvaluator();
        assertEquals(PatchConditionEvaluator.Status.MATCHED, evaluator.evaluate(
                new PatchCondition.JsonPathExists(new ConditionSource.Target(), "/flag"), context).status());
        assertEquals(PatchConditionEvaluator.Status.MATCHED, evaluator.evaluate(
                new PatchCondition.JsonPathEquals(new ConditionSource.Target(), "/flag", JsonParser.parseString("null")), context).status());
        assertEquals(PatchConditionEvaluator.Status.MATCHED, evaluator.evaluate(
                new PatchCondition.ModVersion("Example:Mod", new PatchCondition.VersionMatcher(null, "999999999999999999999", null, null, null)), context).status());
    }

    @Test
    void evaluatesInstalledAssetsTargetsVersionsAndCompositeOutcomes() throws Exception {
        Path assets = Files.createDirectories(temporaryDirectory.resolve("assets"));
        Files.writeString(assets.resolve("present.bin"), "not-json", StandardCharsets.UTF_8);
        PatchSource source = PatchSource.directory("source", 1, assets);
        ConditionSourceResolver resolver = new ConditionSourceResolver(
                new PatchTargetResolver(), new ModDataRootRegistry(Map.of()), new ConditionDocumentCache());
        PatchConditionEvaluator.EvaluationContext context = new PatchConditionEvaluator.EvaluationContext(
                Set.of("Example:Mod"), Map.of("Example:Mod", "1.2.999999999999999999999"),
                "1.2.999999999999999999999", "Target.json", "{}".getBytes(StandardCharsets.UTF_8), resolver, List.of(source));
        PatchConditionEvaluator evaluator = new PatchConditionEvaluator();

        assertTrue(evaluator.evaluate(new PatchCondition.ModInstalled("Example:Mod"), context).matched());
        assertEquals(PatchConditionEvaluator.Status.NOT_MATCHED, evaluator.evaluate(new PatchCondition.ModInstalled("Missing:Mod"), context).status());
        assertTrue(evaluator.evaluate(new PatchCondition.AssetExists("present.bin"), context).matched());
        assertTrue(evaluator.evaluate(new PatchCondition.AssetMissing("missing.bin"), context).matched());
        assertTrue(evaluator.evaluate(new PatchCondition.TargetExists(), context).matched());
        assertTrue(evaluator.evaluate(new PatchCondition.ServerVersion(new PatchCondition.VersionMatcher(null, "1.2.999999999999999999999", null, null, null)), context).matched());
        assertTrue(evaluator.evaluate(new PatchCondition.All(List.of(new PatchCondition.ModInstalled("Example:Mod"), new PatchCondition.AssetExists("present.bin"))), context).matched());
        assertTrue(evaluator.evaluate(new PatchCondition.Any(List.of(new PatchCondition.ModInstalled("Missing:Mod"), new PatchCondition.AssetExists("present.bin"))), context).matched());
        assertTrue(evaluator.evaluate(new PatchCondition.Not(new PatchCondition.ModInstalled("Missing:Mod")), context).matched());
    }

    @Test
    void snapshotsFailuresAndNormalizesAssetSpellingsWithoutLeakingSecrets() throws Exception {
        Path assets = Files.createDirectories(temporaryDirectory.resolve("assets-cache"));
        Files.createDirectories(assets.resolve("Server"));
        Files.writeString(assets.resolve("Server/secret.json"), "{not-json", StandardCharsets.UTF_8);
        ConditionSourceResolver resolver = new ConditionSourceResolver(new PatchTargetResolver(), new ModDataRootRegistry(Map.of()), new ConditionDocumentCache());
        PatchConditionEvaluator.EvaluationContext context = new PatchConditionEvaluator.EvaluationContext(
                Set.of(), Map.of(), null, "Target.json", null, resolver, List.of(PatchSource.directory("source", 1, assets)));
        PatchConditionEvaluator evaluator = new PatchConditionEvaluator();
        PatchConditionEvaluator.Evaluation first = evaluator.evaluate(new PatchCondition.JsonPathEquals(new ConditionSource.Asset("Server/secret.json"), "/x", JsonParser.parseString("\"expected-secret\"")), context);
        PatchConditionEvaluator.Evaluation second = evaluator.evaluate(new PatchCondition.JsonPathExists(new ConditionSource.Asset("Server\\secret.json"), "/x"), context);

        assertEquals(PatchConditionEvaluator.Status.FAILED, first.status());
        assertEquals(PatchConditionEvaluator.Status.FAILED, second.status());
        assertEquals(1, resolver.documentCache().snapshotCount());
        assertFalse(first.diagnostic().contains("not-json"));
        assertFalse(first.diagnostic().contains("expected-secret"));
    }

    @Test
    void rejectsInvalidAssetAndModDataPathsBeforeCacheLookup() throws Exception {
        Path assets = Files.createDirectories(temporaryDirectory.resolve("assets-invalid"));
        Files.createDirectories(assets.resolve("Server"));
        Files.writeString(assets.resolve("Server/A.json"), "{\"ok\":true}", StandardCharsets.UTF_8);
        ConditionSourceResolver resolver = new ConditionSourceResolver(new PatchTargetResolver(), new ModDataRootRegistry(Map.of()), new ConditionDocumentCache());
        PatchConditionEvaluator.EvaluationContext context = new PatchConditionEvaluator.EvaluationContext(
                Set.of(), Map.of(), null, "Target.json", null, resolver, List.of(PatchSource.directory("source", 1, assets)));
        PatchConditionEvaluator evaluator = new PatchConditionEvaluator();

        assertEquals(PatchConditionEvaluator.Status.FAILED, evaluator.evaluate(new PatchCondition.JsonPathExists(new ConditionSource.Asset("Server//A.json"), "/ok"), context).status());
        assertEquals(PatchConditionEvaluator.Status.FAILED, evaluator.evaluate(new PatchCondition.JsonPathExists(new ConditionSource.ModData("Example:Mod", "../secret.json"), "/ok"), context).status());
        assertEquals(0, resolver.documentCache().snapshotCount());
    }

    @Test
    void treatsUnsafeAndIoAssetExistenceAsFailedAndEvaluatesGameVersionAndMissingTarget() throws Exception {
        Path broken = temporaryDirectory.resolve("broken.zip");
        Files.writeString(broken, "not a zip", StandardCharsets.UTF_8);
        ConditionSourceResolver resolver = new ConditionSourceResolver(new PatchTargetResolver(), new ModDataRootRegistry(Map.of()), new ConditionDocumentCache());
        PatchConditionEvaluator.EvaluationContext context = new PatchConditionEvaluator.EvaluationContext(
                Set.of(), Map.of(), "999999999999999999999.0", "Target.json", null, resolver,
                List.of(PatchSource.archive("broken", 1, broken)));
        PatchConditionEvaluator evaluator = new PatchConditionEvaluator();

        assertEquals(PatchConditionEvaluator.Status.FAILED, evaluator.evaluate(new PatchCondition.AssetExists("../unsafe.json"), context).status());
        assertEquals(PatchConditionEvaluator.Status.FAILED, evaluator.evaluate(new PatchCondition.AssetMissing("Asset.json"), context).status());
        assertEquals(PatchConditionEvaluator.Status.NOT_MATCHED, evaluator.evaluate(new PatchCondition.TargetExists(), context).status());
        PatchCondition game = new PatchConditionParser().parse(JsonParser.parseString("{\"GameVersion\":{\"AtLeast\":\"999999999999999999999.0\"}}").getAsJsonObject());
        assertTrue(evaluator.evaluate(game, context).matched());
    }

    @Test
    void defensivelyCopiesContextInputsAndCachedDocuments() {
        byte[] target = "{\"value\":1}".getBytes(StandardCharsets.UTF_8);
        java.util.HashSet<String> installed = new java.util.HashSet<>(Set.of("Example:Mod"));
        java.util.HashMap<String, String> versions = new java.util.HashMap<>(Map.of("Example:Mod", "1"));
        java.util.ArrayList<PatchSource> sources = new java.util.ArrayList<>();
        PatchConditionEvaluator.EvaluationContext context = new PatchConditionEvaluator.EvaluationContext(
                installed, versions, null, "Target.json", target,
                new ConditionSourceResolver(new PatchTargetResolver(), new ModDataRootRegistry(Map.of()), new ConditionDocumentCache()), sources);
        target[0] = 'X';
        installed.clear(); versions.clear();
        sources.add(PatchSource.directory("later", 1, temporaryDirectory));
        assertEquals('{', context.targetBytes()[0]);
        assertEquals(0, context.sources().size());
        assertTrue(context.installedIds().contains("Example:Mod"));
        assertEquals("1", context.versions().get("Example:Mod"));
        ConditionDocumentCache.Snapshot snapshot = new ConditionDocumentCache.Snapshot(ConditionDocumentCache.Status.FOUND, JsonParser.parseString("{\"x\":1}"), "");
        snapshot.document().getAsJsonObject().addProperty("x", 2);
        assertEquals(1, snapshot.document().getAsJsonObject().get("x").getAsInt());
        ConditionSourceResolver.Result result = new ConditionSourceResolver.Result(ConditionSourceResolver.ResultStatus.FOUND, JsonParser.parseString("{\"x\":1}"), "");
        result.document().getAsJsonObject().addProperty("x", 2);
        assertEquals(1, result.document().getAsJsonObject().get("x").getAsInt());
    }

    @Test
    void evaluatesExplicitAssetJsonPathsAndRedactsNotMatchedSecrets() throws Exception {
        Path assets = Files.createDirectories(temporaryDirectory.resolve("asset-json"));
        Files.writeString(assets.resolve("settings.json"), "{\"enabled\":true}", StandardCharsets.UTF_8);
        ConditionSourceResolver resolver = new ConditionSourceResolver(new PatchTargetResolver(), new ModDataRootRegistry(Map.of()), new ConditionDocumentCache());
        PatchConditionEvaluator.EvaluationContext context = new PatchConditionEvaluator.EvaluationContext(Set.of(), Map.of(), null, "Target.json", null, resolver, List.of(PatchSource.directory("pack", 1, assets)));
        PatchConditionEvaluator evaluator = new PatchConditionEvaluator();
        ConditionSource.Asset source = new ConditionSource.Asset("settings.json");
        assertTrue(evaluator.evaluate(new PatchCondition.JsonPathExists(source, "/enabled"), context).matched());
        assertTrue(evaluator.evaluate(new PatchCondition.JsonPathEquals(source, "/enabled", JsonParser.parseString("true")), context).matched());
        PatchConditionEvaluator.Evaluation miss = evaluator.evaluate(new PatchCondition.JsonPathEquals(source, "/secret-source", JsonParser.parseString("\"expected-secret\"")), context);
        assertEquals(PatchConditionEvaluator.Status.NOT_MATCHED, miss.status());
        assertFalse(miss.diagnostic().contains("expected-secret"));
        assertFalse(miss.diagnostic().contains("secret-source"));
    }

    @Test
    void keepsFoundMissingAndFailedAssetSnapshotsStickyAcrossBackingChanges() throws Exception {
        Path assets = Files.createDirectories(temporaryDirectory.resolve("sticky"));
        Files.writeString(assets.resolve("found.json"), "{\"v\":1}", StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("failed.json"), "{", StandardCharsets.UTF_8);
        ConditionSourceResolver resolver = new ConditionSourceResolver(new PatchTargetResolver(), new ModDataRootRegistry(Map.of()), new ConditionDocumentCache());
        List<PatchSource> sources = List.of(PatchSource.directory("pack", 1, assets));
        assertEquals(ConditionSourceResolver.ResultStatus.FOUND, resolver.resolve(new ConditionSource.Asset("found.json"), "Target.json", null, sources).status());
        Files.writeString(assets.resolve("found.json"), "{", StandardCharsets.UTF_8);
        assertEquals(ConditionSourceResolver.ResultStatus.FOUND, resolver.resolve(new ConditionSource.Asset("found.json"), "Target.json", null, sources).status());
        assertEquals(ConditionSourceResolver.ResultStatus.MISSING, resolver.resolve(new ConditionSource.Asset("missing.json"), "Target.json", null, sources).status());
        Files.writeString(assets.resolve("missing.json"), "{}", StandardCharsets.UTF_8);
        assertEquals(ConditionSourceResolver.ResultStatus.MISSING, resolver.resolve(new ConditionSource.Asset("missing.json"), "Target.json", null, sources).status());
        assertEquals(ConditionSourceResolver.ResultStatus.FAILED, resolver.resolve(new ConditionSource.Asset("failed.json"), "Target.json", null, sources).status());
        Files.writeString(assets.resolve("failed.json"), "{}", StandardCharsets.UTF_8);
        assertEquals(ConditionSourceResolver.ResultStatus.FAILED, resolver.resolve(new ConditionSource.Asset("failed.json"), "Target.json", null, sources).status());
    }

    @Test
    void keepsInitialAssetWinnerWhenCallerSourceListChangesAfterSnapshot() throws Exception {
        Path lower = Files.createDirectories(temporaryDirectory.resolve("winner-lower"));
        Path higher = Files.createDirectories(temporaryDirectory.resolve("winner-higher"));
        Files.writeString(lower.resolve("winner.json"), "{\"winner\":\"lower\"}", StandardCharsets.UTF_8);
        Files.writeString(higher.resolve("winner.json"), "{\"winner\":\"higher\"}", StandardCharsets.UTF_8);
        java.util.ArrayList<PatchSource> callerSources = new java.util.ArrayList<>(List.of(PatchSource.directory("lower", 1, lower)));
        ConditionSourceResolver resolver = new ConditionSourceResolver(new PatchTargetResolver(), new ModDataRootRegistry(Map.of()), new ConditionDocumentCache());
        PatchConditionEvaluator evaluator = new PatchConditionEvaluator();
        PatchConditionEvaluator.EvaluationContext context = new PatchConditionEvaluator.EvaluationContext(Set.of(), Map.of(), null, "Target.json", null, resolver, callerSources);
        ConditionSource.Asset asset = new ConditionSource.Asset("winner.json");
        PatchCondition condition = new PatchCondition.JsonPathEquals(asset, "/winner", JsonParser.parseString("\"lower\""));

        assertTrue(evaluator.evaluate(condition, context).matched());
        callerSources.add(PatchSource.directory("higher", 2, higher));
        Files.writeString(lower.resolve("winner.json"), "{\"winner\":\"mutated\"}", StandardCharsets.UTF_8);

        ConditionSourceResolver.Result cached = resolver.resolve(asset, "Target.json", null, callerSources);
        assertEquals(ConditionSourceResolver.ResultStatus.FOUND, cached.status());
        assertEquals("lower", cached.document().getAsJsonObject().get("winner").getAsString());
        assertTrue(evaluator.evaluate(condition, context).matched());
        assertEquals(1, resolver.documentCache().snapshotCount());
    }

    @Test
    void normalizesModDataAliasesToOneStickySnapshot() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("mod-alias"));
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/settings.json"), "{\"v\":1}", StandardCharsets.UTF_8);
        ConditionSourceResolver resolver = new ConditionSourceResolver(new PatchTargetResolver(), new ModDataRootRegistry(Map.of("Example:Mod", root)), new ConditionDocumentCache());
        assertEquals(1, resolver.resolve(new ConditionSource.ModData("Example:Mod", "config/settings.json"), "Target.json", null, List.of()).document().getAsJsonObject().get("v").getAsInt());
        Files.writeString(root.resolve("config/settings.json"), "{\"v\":2}", StandardCharsets.UTF_8);
        assertEquals(1, resolver.resolve(new ConditionSource.ModData("Example:Mod", "config\\settings.json"), "Target.json", null, List.of()).document().getAsJsonObject().get("v").getAsInt());
        assertEquals(1, resolver.documentCache().snapshotCount());
    }
}
