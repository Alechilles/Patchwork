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
}
