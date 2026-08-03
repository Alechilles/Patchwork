package com.alechilles.patchwork.generation;

import com.alechilles.patchwork.conflict.ConflictReport;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;

/** Immutable diagnostics produced by one pure patch generation pass. */
public record PatchStatusSnapshot(List<String> skipped, Map<String, String> rejectedTargets,
                                  List<String> scanFailures, ConflictReport conflicts) {
    public PatchStatusSnapshot(List<String> skipped, Map<String, String> rejectedTargets, List<String> scanFailures) {
        this(skipped, rejectedTargets, scanFailures, ConflictReport.empty());
    }

    public PatchStatusSnapshot {
        skipped = List.copyOf(skipped);
        LinkedHashMap<String, String> ordered = new LinkedHashMap<>();
        rejectedTargets.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        rejectedTargets = Collections.unmodifiableMap(ordered);
        scanFailures = List.copyOf(scanFailures);
        conflicts = conflicts == null ? ConflictReport.empty() : conflicts;
    }
}
