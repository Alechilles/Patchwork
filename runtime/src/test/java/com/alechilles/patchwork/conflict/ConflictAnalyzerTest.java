package com.alechilles.patchwork.conflict;

import static com.alechilles.patchwork.conflict.ConflictRecord.Classification.MATERIAL_OVERLAP;
import static com.alechilles.patchwork.conflict.ConflictRecord.Classification.REDUNDANT_IDENTICAL;
import static com.alechilles.patchwork.conflict.ConflictRecord.Scope.CROSS_PACK;
import static com.alechilles.patchwork.conflict.ConflictRecord.Scope.SAME_PACK;
import static com.alechilles.patchwork.engine.MutationEffect.Kind.ARRAY_MEMBERSHIP;
import static com.alechilles.patchwork.engine.MutationEffect.Kind.ARRAY_ORDER;
import static com.alechilles.patchwork.engine.MutationEffect.Kind.WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.patchwork.engine.MutationEffect;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ConflictAnalyzerTest {
    private final ConflictAnalyzer analyzer = new ConflictAnalyzer();

    @Test
    void classifiesIdenticalAndMaterialWritesWithoutExposingFingerprints() {
        MutationEffect first = effect("Pack:A", "one", "/Value", WRITE, "same", 0);
        MutationEffect identical = effect("Pack:B", "two", "/Value", WRITE, "same", 1);
        MutationEffect different = effect("Pack:C", "three", "/Value", WRITE, "different", 2);

        ConflictAnalyzer.Analysis firstOverlap = analyzer.analyze(
                List.of(first), List.of(identical), ConflictPolicy.REPORT);
        assertEquals(REDUNDANT_IDENTICAL, firstOverlap.conflicts().getFirst().classification());
        assertEquals(CROSS_PACK, firstOverlap.conflicts().getFirst().scope());

        ConflictAnalyzer.Analysis secondOverlap = analyzer.analyze(
                firstOverlap.acceptedEffects(), List.of(different), ConflictPolicy.REPORT);
        assertEquals(MATERIAL_OVERLAP, secondOverlap.conflicts().getFirst().classification());
        assertFalse(secondOverlap.conflicts().getFirst().toString().contains("different"));
    }

    @Test
    void effectsFromOneDefinitionNeverConflict() {
        assertTrue(analyzer.analyze(
                List.of(effect("Pack:A", "one", "/A", WRITE, "x", 0)),
                List.of(effect("Pack:A", "one", "/A", WRITE, "y", 1)),
                ConflictPolicy.REPORT).conflicts().isEmpty());
    }

    @Test
    void comparesConcreteEffectKindsAndOrdersRowsDeterministically() {
        MutationEffect first = effect("Pack:A", "one", "/Rows", ARRAY_MEMBERSHIP, "a", 0);
        MutationEffect second = effect("Pack:B", "two", "/Rows", ARRAY_MEMBERSHIP, "b", 1);
        MutationEffect third = effect("Pack:C", "three", "/Rows", ARRAY_ORDER, "c", 2);
        MutationEffect fourth = effect("Pack:B", "four", "/Alpha", WRITE, "d", 3);

        ConflictAnalyzer.Analysis rows = analyzer.analyze(
                List.of(first, third, fourth), List.of(second), ConflictPolicy.REPORT);
        assertEquals(List.of("/Rows"), rows.conflicts().stream().map(ConflictRecord::path).toList());
        assertEquals(CROSS_PACK, rows.conflicts().getFirst().scope());

        ConflictAnalyzer.Analysis samePack = analyzer.analyze(
                List.of(first), List.of(effect("Pack:A", "later", "/Rows", ARRAY_MEMBERSHIP, "a", 4)),
                ConflictPolicy.REPORT);
        assertEquals(SAME_PACK, samePack.conflicts().getFirst().scope());
    }

    private static MutationEffect effect(String pack, String patch, String path,
                                         MutationEffect.Kind kind, String fingerprint, long order) {
        return new MutationEffect("Server/Test.json", patch, pack, "op", order, path, kind, fingerprint);
    }
}
