package com.alechilles.patchwork.discovery;

import com.alechilles.patchwork.format.Utf8Ordering;
import com.alechilles.patchwork.generation.GenerationAssetSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Expands target selectors against one immutable generation inventory. */
public final class PatchTargetExpander {
    /** Returns concrete paths for selectors, deduplicated and sorted by unsigned UTF-8 bytes. */
    public List<String> expand(List<PatchTargetSelector> selectors, GenerationAssetSnapshot snapshot) {
        Objects.requireNonNull(selectors, "selectors");
        Objects.requireNonNull(snapshot, "snapshot");
        Set<String> expanded = new LinkedHashSet<>();
        List<String> inventory = snapshot.paths();
        for (PatchTargetSelector selector : selectors) {
            Objects.requireNonNull(selector, "selector");
            if (selector.kind() == PatchTargetSelector.Kind.EXACT) {
                if (!isGenerated(snapshot, selector.expression())) expanded.add(selector.expression());
                continue;
            }
            for (String path : inventory) {
                if (isGenerated(snapshot, path)) continue;
                if (selector.matches(path)) expanded.add(path);
            }
        }
        return expanded.stream().sorted(Utf8Ordering.UNSIGNED_BYTES).toList();
    }

    /** Expands one selector without requiring callers to allocate a singleton list. */
    public List<String> expand(PatchTargetSelector selector, GenerationAssetSnapshot snapshot) {
        return expand(List.of(Objects.requireNonNull(selector, "selector")), snapshot);
    }

    private static boolean isGenerated(GenerationAssetSnapshot snapshot, String path) {
        return snapshot.find(path)
                .map(record -> PatchScanner.GENERATED_PACK_ID.equals(record.sourcePackId()))
                .orElse(false);
    }
}
