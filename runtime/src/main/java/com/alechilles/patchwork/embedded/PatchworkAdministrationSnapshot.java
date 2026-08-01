package com.alechilles.patchwork.embedded;

import com.alechilles.patchwork.generation.PatchStatusSnapshot;
import com.alechilles.patchwork.reload.PatchReloadCoordinator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Immutable, sanitized administration view rendered outside the host lifecycle gate. */
record PatchworkAdministrationSnapshot(boolean active, long epoch, Map<String, ?> coordinator,
                                      String neutralRoot, List<String> legacyRoots,
                                      long generationEpoch, List<String> generatedTargets, boolean inventoryKnown,
                                      PatchStatusSnapshot generationStatus,
                                      PatchReloadCoordinator.ReloadOutcome reload) {
    static final int MAX_DETAIL_ROWS = 32;
    static final int MAX_CANDIDATE_ROWS = 32;
    static final int MAX_CONTRIBUTION_ROWS = 32;
    static final int MAX_LEGACY_ROOT_ROWS = 16;

    PatchworkAdministrationSnapshot {
        coordinator = Map.copyOf(coordinator);
        neutralRoot = neutralRoot == null ? "unavailable" : neutralRoot;
        legacyRoots = legacyRoots == null ? List.of() : legacyRoots.stream().sorted().toList();
        generatedTargets = generatedTargets == null ? List.of() : generatedTargets.stream().sorted().toList();
        generationStatus = generationStatus == null ? new PatchStatusSnapshot(List.of(), Map.of(), List.of()) : generationStatus;
    }

    List<String> render() {
        List<String> lines = new ArrayList<>();
        lines.add("Patchwork administration: " + (active ? "active" : "inactive") + " (epoch " + epoch + ")");
        appendCoordinator(lines);
        lines.add("Neutral root: " + neutralRoot);
        for (String root : legacyRoots.stream().limit(MAX_LEGACY_ROOT_ROWS).toList()) lines.add("Legacy root: " + root);
        if (legacyRoots.size() > MAX_LEGACY_ROOT_ROWS) lines.add("Additional legacy roots: " + (legacyRoots.size() - MAX_LEGACY_ROOT_ROWS));
        lines.add("Last generation: epoch " + generationEpoch + ", generated " + (inventoryKnown ? generatedTargets.size() : "inventory unknown")
                + ", skipped " + generationStatus.skipped().size() + ", failed "
                + (generationStatus.rejectedTargets().size() + generationStatus.scanFailures().size()));
        if (reload != null) lines.add("Last reload: epoch " + reload.epoch() + ", " + (reload.started() ? "started" : "not started")
                + ", manifest " + reload.manifestState().name().toLowerCase().replace('_', '-')
                + ", integrity " + reload.integrityState().name().toLowerCase().replace('_', '-'));
        List<Detail> details = details();
        for (String category : List.of("generated", "removed", "hot-reloaded", "adapter-reloaded", "restart-required", "stale", "rollback-failed", "skipped", "failed")) {
            long count = details.stream().filter(detail -> detail.category.equals(category)).count();
            lines.add("Category " + category + ": " + count);
        }
        for (int index = 0; index < Math.min(MAX_DETAIL_ROWS, details.size()); index++) {
            Detail row = details.get(index); lines.add(row.category + ": " + row.target);
        }
        if (details.size() > MAX_DETAIL_ROWS) lines.add("Additional target rows: " + (details.size() - MAX_DETAIL_ROWS));
        return List.copyOf(lines);
    }

    private void appendCoordinator(List<String> lines) {
        Object rows = coordinator.get("candidates");
        if (rows instanceof List<?> candidates) for (Object row : candidates.stream().limit(MAX_CANDIDATE_ROWS).toList()) if (row instanceof Map<?, ?> candidate) {
            Object provider = candidate.get("providerId"), reason = candidate.get("reason"), elected = candidate.get("active");
            if (provider instanceof String id && reason instanceof String state && elected instanceof Boolean winner) {
                if (winner) {
                    lines.add("Active runtime: " + id + " (" + state + "; version " + text(candidate, "runtimeVersion")
                            + ", origin " + text(candidate, "origin") + ", plugin " + text(candidate, "providerPluginId")
                            + "@" + text(candidate, "providerPluginVersion") + ", ABI " + text(candidate, "coordinatorAbi")
                            + ", source " + text(candidate, "sourceJarPath") + ")");
                } else lines.add("Passive runtime: " + id + " (" + state + ")");
            }
        }
        overflow(lines, "Additional candidate rows", coordinator.get("candidateOverflow"));
        Object contributionRows = coordinator.get("contributions");
        if (contributionRows instanceof List<?> contributions) for (Object row : contributions.stream().limit(MAX_CONTRIBUTION_ROWS).toList())
            if (row instanceof Map<?, ?> contribution && contribution.get("contributionId") instanceof String id) lines.add("Contribution: " + id
                    + " (macros " + values(contribution, "macroIds") + ", adapters " + values(contribution, "adapterIds") + ")");
        overflow(lines, "Additional contribution rows", coordinator.get("contributionOverflow"));
    }

    private static void overflow(List<String> lines, String label, Object value) {
        if (value instanceof Number count && count.longValue() > 0) lines.add(label + ": " + count.longValue());
    }
    private static Object values(Map<?, ?> row, String key) { Object value = row.get(key); return value == null ? List.of() : value; }

    private static String text(Map<?, ?> row, String key) {
        Object value = row.get(key); return value == null ? "unavailable" : value.toString();
    }

    private List<Detail> details() {
        List<Detail> rows = new ArrayList<>();
        if (inventoryKnown) for (String target : generatedTargets) rows.add(new Detail("generated", target));
        if (reload != null) for (PatchReloadCoordinator.TargetOutcome target : reload.targets())
            rows.add(new Detail(category(target.state()), target.target()));
        if (!generationStatus.skipped().isEmpty()) rows.add(new Detail("skipped", generationStatus.skipped().size() + " target(s)"));
        if (!generationStatus.rejectedTargets().isEmpty() || !generationStatus.scanFailures().isEmpty())
            rows.add(new Detail("failed", (generationStatus.rejectedTargets().size() + generationStatus.scanFailures().size()) + " target(s)"));
        rows.sort(Comparator.comparing(Detail::category).thenComparing(Detail::target));
        return rows;
    }

    private static String category(PatchReloadCoordinator.TargetState state) {
        return switch (state) {
            case HOT_RELOADED -> "hot-reloaded";
            case ADAPTER_RELOADED -> "adapter-reloaded";
            case RESTART_REQUIRED -> "restart-required";
            case REMOVED -> "removed";
            case STALE -> "stale";
            case ROLLBACK_FAILED -> "rollback-failed";
            case FAILED -> "failed";
        };
    }
    private record Detail(String category, String target) { }
}
