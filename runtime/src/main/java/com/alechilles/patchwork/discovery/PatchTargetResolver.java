package com.alechilles.patchwork.discovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Resolves winning, non-generated target assets from filesystem-backed packs. */
public final class PatchTargetResolver {
    /** Resolves a target to the highest-priority available source and copies its bytes before archive closure. */
    public Optional<ResolvedTarget> resolve(List<PatchSource> sources, String target) {
        return resolveDetailed(sources, target).target();
    }
    /** Resolves with missing-vs-failure status for callers that must not hide unsafe reads. */
    public Resolution resolveDetailed(List<PatchSource> sources, String target) {
        final String normalized;
        try { normalized = PatchScanner.normalizeAssetPath(target); } catch (IllegalArgumentException exception) { return new Resolution(Status.FAILED, null, "Unsafe asset path."); }
        for (PatchSource source : sources.stream().filter(s -> !PatchScanner.GENERATED_PACK_ID.equals(s.sourcePackId())).sorted(Comparator.comparingInt(PatchSource::sourcePackLoadOrder).thenComparing(PatchSource::sourcePackId).reversed()).toList()) {
            try { byte[] bytes = source.kind() == PatchSource.Kind.DIRECTORY ? readDirectory(source.backingPath(), normalized) : readArchive(source.backingPath(), normalized); if (bytes != null) return new Resolution(Status.FOUND, new ResolvedTarget(source.sourcePackId(), source.sourcePackLoadOrder(), normalized, bytes), ""); }
            catch (IOException exception) { return new Resolution(Status.FAILED, null, "Unable to read asset source."); }
        }
        return new Resolution(Status.MISSING, null, "Asset source is missing.");
    }

    private static Optional<ResolvedTarget> read(PatchSource source, String target) {
        try {
            byte[] bytes = source.kind() == PatchSource.Kind.DIRECTORY ? readDirectory(source.backingPath(), target) : readArchive(source.backingPath(), target);
            return bytes == null ? Optional.empty() : Optional.of(new ResolvedTarget(source.sourcePackId(), source.sourcePackLoadOrder(), target, bytes));
        } catch (IOException exception) { return Optional.empty(); }
    }

    private static byte[] readDirectory(Path root, String target) throws IOException {
        Path file = root.resolve(target).normalize();
        if (!file.startsWith(root)) throw new IOException("unsafe target path");
        try {
            BasicFileAttributes rootAttributes = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!rootAttributes.isDirectory() || rootAttributes.isOther() || Files.isSymbolicLink(root)) throw new IOException("unsafe source root");
            Path current = root;
            for (Path part : root.relativize(file)) {
                current = current.resolve(part);
                BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isOther() || Files.isSymbolicLink(current)) throw new IOException("unsafe asset component");
            }
            BasicFileAttributes finalAttributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!finalAttributes.isRegularFile() || finalAttributes.isOther()) throw new IOException("unsafe asset final file");
            Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path realFile = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!realFile.startsWith(realRoot)) throw new IOException("asset escaped source root");
            return Files.readAllBytes(file);
        } catch (NoSuchFileException missing) {
            return null;
        }
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
    /** Detailed resolution status without sensitive filesystem details. */
    public record Resolution(Status status, ResolvedTarget resolvedTarget, String diagnostic) { public Optional<ResolvedTarget> target() { return Optional.ofNullable(resolvedTarget); } }
    /** Detailed resolution outcome. */
    public enum Status { FOUND, MISSING, FAILED }
}
