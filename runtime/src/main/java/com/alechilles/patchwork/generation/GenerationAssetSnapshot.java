package com.alechilles.patchwork.generation;

import com.alechilles.patchwork.discovery.PatchRoot;
import com.alechilles.patchwork.discovery.PatchScanner;
import com.alechilles.patchwork.discovery.PatchSource;
import com.alechilles.patchwork.discovery.PatchTargetResolver;
import com.alechilles.patchwork.format.Utf8Ordering;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
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
 * <p>Each path is represented by the highest-priority source at capture time. Paths and source
 * identities are captured eagerly, while payload bytes are read only when a definition, target,
 * condition, or cross-asset operation actually needs them. A source that changes before its
 * first read is rejected rather than silently contributing newer data to this generation pass.</p>
 */
public final class GenerationAssetSnapshot {
    private final List<PatchSource> sources;
    private final Map<String, AssetRecord> assets;
    private final List<String> paths;
    private final List<String> sourcePackIds;

    private GenerationAssetSnapshot(List<PatchSource> sources, Map<String, AssetRecord> assets) {
        this.sources = List.copyOf(sources);
        this.assets = Collections.unmodifiableMap(new LinkedHashMap<>(assets));
        this.paths = this.assets.keySet().stream().sorted(Utf8Ordering.UNSIGNED_BYTES).toList();
        this.sourcePackIds = this.sources.stream().map(PatchSource::sourcePackId).distinct()
                .sorted(Utf8Ordering.UNSIGNED_BYTES).toList();
    }

    /** Captures all non-generated paths and their winning source identity exactly once. */
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
        final PatchTargetResolver.DirectoryRootSnapshot rootSnapshot;
        try {
            root = source.backingPath().toRealPath();
            rootSnapshot = PatchTargetResolver.snapshotDirectoryRoot(root);
        } catch (IOException missing) {
            return;
        }
        if (!Files.isDirectory(root)) return;
        try (var files = Files.find(root, Integer.MAX_VALUE, (path, attributes) -> attributes.isRegularFile())) {
            files
                    .forEach(file -> captureDirectoryFile(source, root, rootSnapshot, file, winners));
        } catch (IOException ignored) {
            // A source can disappear during a capture.  Unreadable paths simply do not win.
        }
    }

    private static void captureDirectoryFile(PatchSource source, Path root,
                                              PatchTargetResolver.DirectoryRootSnapshot rootSnapshot,
                                              Path file,
                                              Map<String, AssetRecord> winners) {
        try {
            String path = PatchScanner.normalizeAssetPath(root.relativize(file).toString());
            FileIdentity identity = FileIdentity.capture(file);
            putWinner(AssetRecord.directory(source, path, rootSnapshot, file, identity), winners);
        } catch (IOException | IllegalArgumentException ignored) {
            // Ignore an individual disappearing, malformed, or escaping entry and retain others.
        }
    }

    private static void captureArchive(PatchSource source, Map<String, AssetRecord> winners) {
        if (!Files.isRegularFile(source.backingPath())) return;
        try (ZipFile zip = new ZipFile(source.backingPath().toFile())) {
            ArchiveIdentity archive = ArchiveIdentity.capture(source.backingPath());
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
                putWinner(AssetRecord.archive(source, path, archive, entry), winners);
            }
        } catch (IOException ignored) {
            // A missing or malformed archive contributes no assets to this pass.
        }
    }

    private static void putWinner(AssetRecord candidate, Map<String, AssetRecord> winners) {
        String path = candidate.path();
        AssetRecord current = winners.get(path);
        if (current == null || higherPriority(candidate, current)) winners.put(path, candidate);
    }

    private static boolean higherPriority(AssetRecord candidate, AssetRecord current) {
        int loadOrder = Integer.compare(candidate.sourcePackLoadOrder(), current.sourcePackLoadOrder());
        if (loadOrder != 0) return loadOrder > 0;
        return Utf8Ordering.UNSIGNED_BYTES.compare(candidate.sourcePackId(), current.sourcePackId()) > 0;
    }

    /** Immutable source-pack identity with a lazily loaded, defensively copied payload. */
    public static final class AssetRecord {
        private final String sourcePackId;
        private final int sourcePackLoadOrder;
        private final String path;
        private final ByteReader reader;
        private volatile byte[] capturedBytes;
        private volatile IllegalStateException readFailure;

        private AssetRecord(String sourcePackId, int sourcePackLoadOrder, String path, ByteReader reader) {
            this.sourcePackId = Objects.requireNonNull(sourcePackId, "sourcePackId");
            this.sourcePackLoadOrder = sourcePackLoadOrder;
            this.path = PatchScanner.normalizeAssetPath(path);
            this.reader = Objects.requireNonNull(reader, "reader");
        }

        private static AssetRecord directory(PatchSource source, String path,
                                             PatchTargetResolver.DirectoryRootSnapshot root,
                                             Path file, FileIdentity identity) {
            return new AssetRecord(source.sourcePackId(), source.sourcePackLoadOrder(), path, () -> {
                identity.requireUnchanged(file);
                byte[] bytes = PatchTargetResolver.readDirectoryAsset(root, path);
                if (bytes == null) throw new IOException("Asset source disappeared after capture.");
                identity.requireUnchanged(file);
                return bytes;
            });
        }

        private static AssetRecord archive(PatchSource source, String path, ArchiveIdentity archive,
                                           ZipEntry capturedEntry) {
            EntryIdentity entry = EntryIdentity.capture(capturedEntry);
            return new AssetRecord(source.sourcePackId(), source.sourcePackLoadOrder(), path, () -> {
                archive.requireUnchanged();
                try (ZipFile zip = new ZipFile(source.backingPath().toFile())) {
                    ZipEntry current = findCapturedEntry(zip, entry);
                    if (current == null) {
                        throw new IOException("Archive asset changed after capture.");
                    }
                    try (InputStream input = zip.getInputStream(current)) {
                        byte[] bytes = input.readAllBytes();
                        archive.requireUnchanged();
                        return bytes;
                    }
                }
            });
        }

        public String sourcePackId() { return sourcePackId; }
        public int sourcePackLoadOrder() { return sourcePackLoadOrder; }
        public String path() { return path; }

        public byte[] bytes() {
            byte[] bytes = capturedBytes;
            IllegalStateException failure = readFailure;
            if (failure != null) throw failure;
            if (bytes == null) {
                synchronized (this) {
                    bytes = capturedBytes;
                    failure = readFailure;
                    if (failure != null) throw failure;
                    if (bytes == null) {
                        try {
                            bytes = reader.read();
                        } catch (IOException readFailureCause) {
                            IllegalStateException recorded = new IllegalStateException("Captured asset is no longer readable.", readFailureCause);
                            readFailure = recorded;
                            throw recorded;
                        }
                        capturedBytes = bytes;
                    }
                }
            }
            return bytes.clone();
        }

        private static ZipEntry findCapturedEntry(ZipFile zip, EntryIdentity expected) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (expected.matches(entry)) return entry;
            }
            return null;
        }
    }

    @FunctionalInterface
    private interface ByteReader { byte[] read() throws IOException; }

    private record FileIdentity(long size, java.nio.file.attribute.FileTime created,
                                java.nio.file.attribute.FileTime modified, Object fileKey) {
        static FileIdentity capture(Path file) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isOther() || Files.isSymbolicLink(file)) {
                throw new IOException("Asset is not a regular file.");
            }
            return new FileIdentity(attributes.size(), attributes.creationTime(), attributes.lastModifiedTime(), attributes.fileKey());
        }

        void requireUnchanged(Path file) throws IOException {
            if (!equals(capture(file))) throw new IOException("Directory asset changed after capture.");
        }
    }

    private record ArchiveIdentity(Path path, FileIdentity file) {
        static ArchiveIdentity capture(Path path) throws IOException {
            return new ArchiveIdentity(path, FileIdentity.capture(path));
        }

        void requireUnchanged() throws IOException { file.requireUnchanged(path); }
    }

    private record EntryIdentity(String rawName, long size, long compressedSize, long crc, int method, long time) {
        static EntryIdentity capture(ZipEntry entry) {
            return new EntryIdentity(entry.getName(), entry.getSize(), entry.getCompressedSize(), entry.getCrc(), entry.getMethod(), entry.getTime());
        }

        boolean matches(ZipEntry entry) {
            return rawName.equals(entry.getName()) && !entry.isDirectory()
                    && size == entry.getSize() && compressedSize == entry.getCompressedSize()
                    && crc == entry.getCrc() && method == entry.getMethod() && time == entry.getTime();
        }
    }
}
