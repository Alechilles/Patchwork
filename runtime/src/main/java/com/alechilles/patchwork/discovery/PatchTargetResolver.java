package com.alechilles.patchwork.discovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Resolves winning, non-generated target assets from filesystem-backed packs. */
public final class PatchTargetResolver {
    /** Resolves a target to the highest-priority available source and copies its bytes before archive closure. */
    public Optional<ResolvedTarget> resolve(List<PatchSource> sources, String target) {
        final String normalized;
        try { normalized = PatchScanner.normalizeAssetPath(target); } catch (IllegalArgumentException exception) { return Optional.empty(); }
        return sources.stream().filter(source -> !PatchScanner.GENERATED_PACK_ID.equals(source.sourcePackId()))
                .sorted(Comparator.comparingInt(PatchSource::sourcePackLoadOrder).thenComparing(PatchSource::sourcePackId).reversed())
                .map(source -> read(source, normalized)).flatMap(Optional::stream).findFirst();
    }

    private static Optional<ResolvedTarget> read(PatchSource source, String target) {
        try {
            byte[] bytes = source.kind() == PatchSource.Kind.DIRECTORY ? readDirectory(source.backingPath(), target) : readArchive(source.backingPath(), target);
            return bytes == null ? Optional.empty() : Optional.of(new ResolvedTarget(source.sourcePackId(), source.sourcePackLoadOrder(), target, bytes));
        } catch (IOException exception) { return Optional.empty(); }
    }

    private static byte[] readDirectory(Path root, String target) throws IOException {
        Path file = root.resolve(target).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) return null;
        Path realRoot = root.toRealPath();
        Path realFile = file.toRealPath();
        return realFile.startsWith(realRoot) ? Files.readAllBytes(realFile) : null;
    }

    private static byte[] readArchive(Path archive, String target) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(target);
            if (entry == null || entry.isDirectory()) return null;
            try (var input = zip.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    /** Immutable resolved target metadata and a defensively copied asset payload. */
    public record ResolvedTarget(String sourcePackId, int sourcePackLoadOrder, String target, byte[] bytes) {
        public ResolvedTarget { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
