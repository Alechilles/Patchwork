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
}
