package com.alechilles.patchwork.generation;

import com.alechilles.patchwork.discovery.PatchRoot;
import com.alechilles.patchwork.discovery.PatchScanner;
import com.alechilles.patchwork.discovery.PatchSource;
import com.alechilles.patchwork.discovery.PatchTargetResolver;
import com.alechilles.patchwork.format.Utf8Ordering;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Immutable view of the assets visible to one generation pass.
 *
 * <p>Each path is represented by the highest-priority source at capture time.  Bytes are copied
 * when records are created and whenever callers access them, so a later source mutation cannot
 * affect discovery, target resolution, or condition evaluation for the pass.</p>
 */
public final class GenerationAssetSnapshot {
    private final List<PatchSource> sources;
    private final Map<String, AssetRecord> assets;
    private final List<String> paths;
    private final List<String> sourcePackIds;

    private GenerationAssetSnapshot(List<PatchSource> sources, Map<String, AssetRecord> assets) {
        this.sources = List.copyOf(sources);
        Map<String, AssetRecord> copied = new LinkedHashMap<>();
        assets.forEach((path, record) -> copied.put(path, new AssetRecord(
                record.sourcePackId(), record.sourcePackLoadOrder(), record.path(), record.bytes())));
        this.assets = Collections.unmodifiableMap(copied);
        this.paths = this.assets.keySet().stream().sorted(Utf8Ordering.UNSIGNED_BYTES).toList();
        this.sourcePackIds = this.sources.stream().map(PatchSource::sourcePackId).distinct()
                .sorted(Utf8Ordering.UNSIGNED_BYTES).toList();
    }

    /** Captures all readable, non-generated assets from the supplied sources exactly once. */
    public static GenerationAssetSnapshot capture(List<PatchSource> sources) {
        Objects.requireNonNull(sources, "sources");
        List<PatchSource> capturedSources = sources.stream()
                .filter(Objects::nonNull)
                .filter(source -> !PatchScanner.GENERATED_PACK_ID.equals(source.sourcePackId()))
                .toList();
        Map<String, AssetRecord> winners = new LinkedHashMap<>();
        for (PatchSource source : capturedSources) captureSource(source, winners);
        return new GenerationAssetSnapshot(capturedSources, winners);
    }

    /** Returns every captured asset path in unsigned UTF-8 order. */
    public List<String> paths() {
        return paths;
    }

    /** Returns all captured source-pack IDs, excluding the generated pack, in unsigned UTF-8 order. */
    public List<String> sourcePackIds() {
        return sourcePackIds;
    }

    /** Returns the source descriptors retained for compatibility callers. */
    public List<PatchSource> sources() {
        return sources;
    }

    /** Finds one normalized asset path in this immutable view. */
    public Optional<AssetRecord> find(String normalizedPath) {
        String path = PatchScanner.normalizeAssetPath(normalizedPath);
        return Optional.ofNullable(assets.get(path));
    }

    /** Requires one normalized asset path in this immutable view. */
    public AssetRecord require(String normalizedPath) {
        return find(normalizedPath).orElseThrow(() -> new IllegalArgumentException("Asset source is missing: " + normalizedPath));
    }

    /** Returns captured JSON definition paths below one patch root in unsigned UTF-8 order. */
    public List<String> definitionPaths(PatchRoot root) {
        Objects.requireNonNull(root, "root");
        String prefix = root.path() + "/";
        return paths.stream().filter(path -> path.startsWith(prefix) && path.endsWith(".json")).toList();
    }

    private static void captureSource(PatchSource source, Map<String, AssetRecord> winners) {
        if (source.kind() == PatchSource.Kind.DIRECTORY) {
            captureDirectory(source, winners);
        } else {
            captureArchive(source, winners);
        }
    }

    private static void captureDirectory(PatchSource source, Map<String, AssetRecord> winners) {
        final Path root;
        try {
            root = source.backingPath().toRealPath();
        } catch (IOException missing) {
            return;
        }
        if (!Files.isDirectory(root)) return;
        try (var files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .forEach(file -> captureDirectoryFile(source, root, file, winners));
        } catch (IOException ignored) {
            // A source can disappear during a capture.  Unreadable paths simply do not win.
        }
    }

    private static void captureDirectoryFile(PatchSource source, Path root, Path file,
                                              Map<String, AssetRecord> winners) {
        try {
            Path realFile = file.toRealPath();
            if (!realFile.startsWith(root)) return;
            String path = PatchScanner.normalizeAssetPath(root.relativize(realFile).toString());
            byte[] bytes = PatchTargetResolver.readDirectoryAsset(source.backingPath(), path);
            if (bytes == null) return;
            putWinner(source, path, bytes, winners);
        } catch (IOException | IllegalArgumentException ignored) {
            // Ignore an individual disappearing, malformed, or escaping entry and retain others.
        }
    }

    private static void captureArchive(PatchSource source, Map<String, AssetRecord> winners) {
        if (!Files.isRegularFile(source.backingPath())) return;
        try (ZipFile zip = new ZipFile(source.backingPath().toFile())) {
            Set<String> seen = new LinkedHashSet<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                final String path;
                try {
                    path = PatchScanner.normalizeAssetPath(entry.getName());
                } catch (IllegalArgumentException unsafe) {
                    continue;
                }
                if (!seen.add(path)) continue;
                try (InputStream input = zip.getInputStream(entry)) {
                    putWinner(source, path, input.readAllBytes(), winners);
                } catch (IOException ignored) {
                    // Keep scanning other archive entries after one unreadable entry.
                }
            }
        } catch (IOException ignored) {
            // A missing or malformed archive contributes no assets to this pass.
        }
    }

    private static void putWinner(PatchSource source, String path, byte[] bytes,
                                  Map<String, AssetRecord> winners) {
        AssetRecord candidate = new AssetRecord(source.sourcePackId(), source.sourcePackLoadOrder(), path, bytes);
        AssetRecord current = winners.get(path);
        if (current == null || higherPriority(candidate, current)) winners.put(path, candidate);
    }

    private static boolean higherPriority(AssetRecord candidate, AssetRecord current) {
        int loadOrder = Integer.compare(candidate.sourcePackLoadOrder(), current.sourcePackLoadOrder());
        if (loadOrder != 0) return loadOrder > 0;
        return Utf8Ordering.UNSIGNED_BYTES.compare(candidate.sourcePackId(), current.sourcePackId()) > 0;
    }

    /** Immutable source-pack asset and defensive byte boundary. */
    public record AssetRecord(String sourcePackId, int sourcePackLoadOrder, String path, byte[] bytes) {
        public AssetRecord {
            sourcePackId = Objects.requireNonNull(sourcePackId, "sourcePackId");
            path = PatchScanner.normalizeAssetPath(path);
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
