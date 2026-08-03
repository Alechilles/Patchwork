package com.alechilles.patchwork.conflict;

import com.alechilles.patchwork.engine.MutationEffect;
import java.util.Objects;

/** Sanitized, value-redacted description of one overlapping concrete effect. */
public record ConflictRecord(
        String target,
        String path,
        MutationEffect.Kind effectKind,
        EffectRef earlier,
        EffectRef later,
        Scope scope,
        Classification classification) {

    public ConflictRecord {
        target = Objects.requireNonNull(target, "target");
        path = Objects.requireNonNull(path, "path");
        effectKind = Objects.requireNonNull(effectKind, "effectKind");
        earlier = Objects.requireNonNull(earlier, "earlier");
        later = Objects.requireNonNull(later, "later");
        scope = Objects.requireNonNull(scope, "scope");
        classification = Objects.requireNonNull(classification, "classification");
    }

    /** Identifying metadata for one effect, intentionally without its value fingerprint. */
    public record EffectRef(String sourcePackId, String patchId, String operationId, long operationOrder) {
        public EffectRef {
            sourcePackId = Objects.requireNonNull(sourcePackId, "sourcePackId");
            patchId = Objects.requireNonNull(patchId, "patchId");
            operationId = Objects.requireNonNull(operationId, "operationId");
        }
    }

    /** Whether the two definitions came from the same contributing pack. */
    public enum Scope { SAME_PACK, CROSS_PACK }

    /** Whether the later effect writes the same value or changes it. */
    public enum Classification { REDUNDANT_IDENTICAL, MATERIAL_OVERLAP }

    static ConflictRecord from(MutationEffect earlier, MutationEffect later) {
        return new ConflictRecord(
                later.target(),
                later.path(),
                later.kind(),
                ref(earlier),
                ref(later),
                earlier.sourcePackId().equals(later.sourcePackId()) ? Scope.SAME_PACK : Scope.CROSS_PACK,
                earlier.valueFingerprint().equals(later.valueFingerprint())
                        ? Classification.REDUNDANT_IDENTICAL : Classification.MATERIAL_OVERLAP);
    }

    private static EffectRef ref(MutationEffect effect) {
        return new EffectRef(effect.sourcePackId(), effect.patchId(), effect.operationId(), effect.operationOrder());
    }
}
