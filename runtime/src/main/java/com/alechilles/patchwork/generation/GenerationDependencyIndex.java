package com.alechilles.patchwork.generation;

import com.alechilles.patchwork.discovery.PatchScanner;
import com.alechilles.patchwork.format.Utf8Ordering;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable metadata from one generation pass.  The index is intentionally
 * descriptive only; it does not start a watcher or otherwise perform reloads.
 */
public record GenerationDependencyIndex(
        Set<DefinitionDependency> definitions,
        Set<String> expandedTargets,
        Set<String> sourceAssets,
        Set<GlobRoot> globRoots) {

    public GenerationDependencyIndex {
        definitions = orderedDefinitions(definitions);
        expandedTargets = orderedPaths(expandedTargets);
        sourceAssets = orderedPaths(sourceAssets);
        globRoots = orderedGlobRoots(globRoots);
    }

    /** Returns an immutable empty index for compatibility plan constructors. */
    public static GenerationDependencyIndex empty() {
        return new GenerationDependencyIndex(Set.of(), Set.of(), Set.of(), Set.of());
    }

    public record DefinitionDependency(
            String sourcePackId,
            String assetPath,
            Validity validity,
            Set<String> expandedTargets) {
        public DefinitionDependency {
            sourcePackId = Objects.requireNonNull(sourcePackId, "sourcePackId");
            assetPath = PatchScanner.normalizeAssetPath(assetPath);
            validity = Objects.requireNonNull(validity, "validity");
            expandedTargets = orderedPaths(expandedTargets);
        }
    }

    public enum Validity { VALID, INVALID }

    public record GlobRoot(String selector, String stablePrefix) {
        public GlobRoot {
            selector = Objects.requireNonNull(selector, "selector");
            stablePrefix = Objects.requireNonNull(stablePrefix, "stablePrefix");
            if (!selector.startsWith("glob:")) {
                throw new IllegalArgumentException("Dependency glob selector must begin with glob:.");
            }
            // Validate and canonicalize the selector without exposing a regex
            // compiler to author-provided syntax.
            var parsed = com.alechilles.patchwork.discovery.PatchTargetSelector.parse(selector);
            selector = parsed.expression();
            stablePrefix = parsed.stablePrefix();
        }
    }

    private static Set<String> orderedPaths(Set<String> paths) {
        Objects.requireNonNull(paths, "paths");
        return paths.stream().map(path -> PatchScanner.normalizeAssetPath(path))
                .sorted(Utf8Ordering.UNSIGNED_BYTES)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), Collections::unmodifiableSet));
    }

    private static Set<DefinitionDependency> orderedDefinitions(Set<DefinitionDependency> values) {
        Objects.requireNonNull(values, "definitions");
        Comparator<DefinitionDependency> ordering = Comparator
                .comparing(DefinitionDependency::sourcePackId, Utf8Ordering.UNSIGNED_BYTES)
                .thenComparing(DefinitionDependency::assetPath, Utf8Ordering.UNSIGNED_BYTES)
                .thenComparing(DefinitionDependency::validity);
        return values.stream().map(Objects::requireNonNull).sorted(ordering)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), Collections::unmodifiableSet));
    }

    private static Set<GlobRoot> orderedGlobRoots(Set<GlobRoot> values) {
        Objects.requireNonNull(values, "globRoots");
        Comparator<GlobRoot> ordering = Comparator.comparing(GlobRoot::selector, Utf8Ordering.UNSIGNED_BYTES)
                .thenComparing(GlobRoot::stablePrefix, Utf8Ordering.UNSIGNED_BYTES);
        return values.stream().map(Objects::requireNonNull).sorted(ordering)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), Collections::unmodifiableSet));
    }
}
