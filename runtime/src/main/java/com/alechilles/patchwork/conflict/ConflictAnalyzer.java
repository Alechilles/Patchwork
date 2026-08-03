package com.alechilles.patchwork.conflict;

import com.alechilles.patchwork.engine.MutationEffect;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure incremental overlap classifier for one target generation fold. */
public final class ConflictAnalyzer {
    /** Compares a candidate definition's successful effects with prior accepted effects. */
    public Analysis analyze(List<MutationEffect> priorAccepted,
                            List<MutationEffect> candidate,
                            ConflictPolicy policy) {
        Objects.requireNonNull(priorAccepted, "priorAccepted");
        Objects.requireNonNull(candidate, "candidate");
        policy = Objects.requireNonNull(policy, "policy");

        List<ConflictRecord> conflicts = new ArrayList<>();
        for (MutationEffect later : candidate) {
            for (MutationEffect earlier : priorAccepted) {
                if (!sameEffectKey(earlier, later) || sameDefinition(earlier, later)) continue;
                conflicts.add(ConflictRecord.from(earlier, later));
            }
        }
        ConflictReport sorted = new ConflictReport(conflicts);
        return switch (policy) {
            case REPORT -> new Analysis(join(priorAccepted, candidate), sorted.records(), false);
            case ALLOW -> new Analysis(join(priorAccepted, candidate), List.of(), false);
            case REJECT -> new Analysis(List.copyOf(priorAccepted), sorted.records(), !sorted.records().isEmpty());
        };
    }

    private static boolean sameEffectKey(MutationEffect left, MutationEffect right) {
        return left.target().equals(right.target())
                && left.path().equals(right.path())
                && left.kind() == right.kind();
    }

    private static boolean sameDefinition(MutationEffect left, MutationEffect right) {
        return left.sourcePackId().equals(right.sourcePackId())
                && left.patchId().equals(right.patchId());
    }

    private static List<MutationEffect> join(List<MutationEffect> prior, List<MutationEffect> candidate) {
        List<MutationEffect> accepted = new ArrayList<>(prior.size() + candidate.size());
        accepted.addAll(prior);
        accepted.addAll(candidate);
        return List.copyOf(accepted);
    }

    /** Result of one policy-aware candidate comparison. */
    public record Analysis(List<MutationEffect> acceptedEffects,
                           List<ConflictRecord> conflicts,
                           boolean rejected) {
        public Analysis {
            acceptedEffects = List.copyOf(acceptedEffects);
            conflicts = List.copyOf(conflicts);
        }
    }
}
