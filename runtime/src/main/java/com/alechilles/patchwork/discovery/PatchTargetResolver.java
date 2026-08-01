package com.alechilles.patchwork.discovery;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Resolves winning, non-generated target assets from filesystem-backed packs. */
public final class PatchTargetResolver {
    private final ReadHook readHook;
    private final ReadHook rootHandoffHook;
    private final ReadHook beforeComponentOpenHook;

    public PatchTargetResolver() {
        this(path -> { }, path -> { }, path -> { });
    }

    PatchTargetResolver(ReadHook readHook) {
        this(readHook, path -> { }, path -> { });
    }

    PatchTargetResolver(ReadHook readHook, ReadHook rootHandoffHook) {
        this(readHook, rootHandoffHook, path -> { });
    }

    PatchTargetResolver(ReadHook readHook, ReadHook rootHandoffHook, ReadHook beforeComponentOpenHook) {
        this.readHook = readHook;
        this.rootHandoffHook = rootHandoffHook;
        this.beforeComponentOpenHook = beforeComponentOpenHook;
    }

    /** Resolves a target to the highest-priority available source and copies its bytes before archive closure. */
    public Optional<ResolvedTarget> resolve(List<PatchSource> sources, String target) {
        return resolveDetailed(sources, target).target();
    }

    /** Resolves with missing-vs-failure status for callers that must not hide unsafe reads. */
    public Resolution resolveDetailed(List<PatchSource> sources, String target) {
        final String normalized;
        try { normalized = PatchScanner.normalizeAssetPath(target); }
        catch (IllegalArgumentException exception) { return new Resolution(Status.FAILED, null, "Unsafe asset path."); }
        for (PatchSource source : sources.stream().filter(s -> !PatchScanner.GENERATED_PACK_ID.equals(s.sourcePackId())).sorted(Comparator.comparingInt(PatchSource::sourcePackLoadOrder).thenComparing(PatchSource::sourcePackId).reversed()).toList()) {
            try {
                byte[] bytes = source.kind() == PatchSource.Kind.DIRECTORY ? readDirectory(source.backingPath(), normalized) : readArchive(source.backingPath(), normalized);
                if (bytes != null) return new Resolution(Status.FOUND, new ResolvedTarget(source.sourcePackId(), source.sourcePackLoadOrder(), normalized, bytes), "");
            } catch (IOException exception) {
                return new Resolution(Status.FAILED, null, "Unable to read asset source.");
            }
        }
        return new Resolution(Status.MISSING, null, "Asset source is missing.");
    }

    private byte[] readDirectory(Path root, String target) throws IOException {
        try { root = root.toRealPath(); }
        catch (NoSuchFileException missing) { return null; }
        Path file = root.resolve(target).normalize();
        if (!file.startsWith(root)) throw new IOException("unsafe target path");
        SourceAttributes rootAttributes;
        try { rootAttributes = SourceAttributes.read(root); }
        catch (NoSuchFileException missing) { return null; }
        if (!rootAttributes.safeDirectory()) throw new IOException("unsafe source root");
        Path filesystemRoot = root.toAbsolutePath().normalize().getRoot();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(filesystemRoot)) {
            if (stream instanceof SecureDirectoryStream<Path> secure) {
                rootHandoffHook.beforeRead(root);
                try (SecureDirectoryStream<Path> openedRoot = openSecureRoot(root, rootAttributes, secure)) {
                    return secureRead(root, target, openedRoot);
                }
            }
        }
        rootHandoffHook.beforeRead(root);
        try (DirectoryStream<Path> ignored = Files.newDirectoryStream(root)) {
            SourceAttributes reopened;
            try { reopened = SourceAttributes.read(root); }
            catch (NoSuchFileException disappeared) { throw new IOException("source root disappeared after validation", disappeared); }
            if (!rootAttributes.sameAs(reopened) || !reopened.safeDirectory()) throw new IOException("source root changed during open");
        } catch (NoSuchFileException disappeared) {
            throw new IOException("source root disappeared after validation", disappeared);
        }
        return fallbackRead(root, target);
    }

    /** Opens the registered root from the filesystem root so a path handoff cannot redirect child reads. */
    private static SecureDirectoryStream<Path> openSecureRoot(Path root, SourceAttributes expected, SecureDirectoryStream<Path> filesystemRoot) throws IOException {
        SecureDirectoryStream<Path> current = filesystemRoot;
        boolean transferred = false;
        try {
            for (Path segment : root.toAbsolutePath().normalize().getRoot().relativize(root.toAbsolutePath().normalize())) {
                BasicFileAttributes attributes;
                try { attributes = current.getFileAttributeView(segment, java.nio.file.attribute.BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes(); }
                catch (NoSuchFileException disappeared) { throw new IOException("source root disappeared after validation", disappeared); }
                if (!attributes.isDirectory() || attributes.isOther()) throw new IOException("unsafe source root");
                SecureDirectoryStream<Path> next;
                try { next = current.newDirectoryStream(segment, LinkOption.NOFOLLOW_LINKS); }
                catch (NoSuchFileException disappeared) { throw new IOException("source root disappeared after validation", disappeared); }
                if (current != filesystemRoot) current.close();
                current = next;
            }
            if (!expected.sameAs(current.getFileAttributeView(root.getFileSystem().getPath("."), java.nio.file.attribute.BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes())) throw new IOException("source root changed during open");
            transferred = true;
            return current;
        } finally {
            if (!transferred && current != filesystemRoot) current.close();
        }
    }

    /** Uses full pre/post component snapshots because this provider cannot retain descriptor-relative directory handles. */
    private byte[] fallbackRead(Path root, String target) throws IOException {
        String[] parts = target.split("/");
        List<ComponentSnapshot> before = new ArrayList<>();
        Path current = root;
        before.add(new ComponentSnapshot(current, SourceAttributes.read(current)));
        for (int i = 0; i < parts.length; i++) {
            current = current.resolve(parts[i]);
            SourceAttributes attributes;
            try { attributes = SourceAttributes.read(current); }
            catch (NoSuchFileException missing) {
                ComponentSnapshot parent = before.getLast();
                SourceAttributes parentAfter;
                try { parentAfter = SourceAttributes.read(parent.path()); }
                catch (NoSuchFileException disappeared) { throw new IOException("asset component disappeared after validation", disappeared); }
                if (!parent.attributes().sameAs(parentAfter)) throw new IOException("asset component changed during lookup");
                return null;
            }
            if (!attributes.safeComponent(i == parts.length - 1)) throw new IOException("unsafe asset component");
            before.add(new ComponentSnapshot(current, attributes));
        }
        Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path realFile = current.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!realFile.startsWith(realRoot)) throw new IOException("asset escaped source root");
        readHook.beforeRead(current);
        byte[] bytes;
        try (SeekableByteChannel channel = Files.newByteChannel(current, java.util.Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            bytes = java.nio.channels.Channels.newInputStream(channel).readAllBytes();
        } catch (NoSuchFileException disappeared) {
            throw new IOException("asset disappeared after validation", disappeared);
        }
        validateFallbackPostRead(before, realRoot, realFile);
        return bytes;
    }

    private static void validateFallbackPostRead(List<ComponentSnapshot> before, Path realRoot, Path realFile) throws IOException {
        for (int i = 0; i < before.size(); i++) {
            ComponentSnapshot snapshot = before.get(i);
            SourceAttributes after;
            try { after = SourceAttributes.read(snapshot.path()); }
            catch (NoSuchFileException disappeared) { throw new IOException("asset disappeared after validation", disappeared); }
            if (!after.safeComponent(i == before.size() - 1) || !snapshot.attributes().sameAs(after)) throw new IOException("asset component changed during read");
        }
        Path afterRealRoot;
        Path afterRealFile;
        try {
            afterRealRoot = before.getFirst().path().toRealPath(LinkOption.NOFOLLOW_LINKS);
            afterRealFile = before.getLast().path().toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException disappeared) {
            throw new IOException("asset disappeared after validation", disappeared);
        }
        if (!afterRealFile.startsWith(afterRealRoot) || !afterRealRoot.equals(realRoot) || !afterRealFile.equals(realFile)) throw new IOException("asset escaped or changed source root");
    }

    /** Secure providers keep directory descriptors open and never fall back to path-based child resolution. */
    private byte[] secureRead(Path root, String target, SecureDirectoryStream<Path> rootStream) throws IOException {
        String[] parts = target.split("/");
        List<SecureDirectoryStream<Path>> handles = new ArrayList<>();
        handles.add(rootStream);
        try {
            SecureDirectoryStream<Path> current = rootStream;
            for (int i = 0; i < parts.length - 1; i++) {
                Path part = root.getFileSystem().getPath(parts[i]);
                BasicFileAttributes attributes;
                try { attributes = current.getFileAttributeView(part, java.nio.file.attribute.BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes(); }
                catch (NoSuchFileException missing) { return null; }
                if (!attributes.isDirectory() || attributes.isOther()) throw new IOException("unsafe asset component");
                beforeComponentOpenHook.beforeRead(root.resolve(String.join("/", java.util.Arrays.copyOf(parts, i + 1))));
                try { current = current.newDirectoryStream(part, LinkOption.NOFOLLOW_LINKS); }
                catch (NoSuchFileException disappeared) { throw new IOException("asset disappeared after validation", disappeared); }
                handles.add(current);
            }
            Path finalPart = root.getFileSystem().getPath(parts[parts.length - 1]);
            BasicFileAttributes finalAttributes;
            try { finalAttributes = current.getFileAttributeView(finalPart, java.nio.file.attribute.BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes(); }
            catch (NoSuchFileException missing) { return null; }
            if (!finalAttributes.isRegularFile() || finalAttributes.isOther()) throw new IOException("unsafe asset final file");
            readHook.beforeRead(root.resolve(target));
            try (SeekableByteChannel channel = current.newByteChannel(finalPart, java.util.Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                return java.nio.channels.Channels.newInputStream(channel).readAllBytes();
            } catch (NoSuchFileException disappeared) {
                throw new IOException("asset disappeared after validation", disappeared);
            }
        } finally {
            for (int i = handles.size() - 1; i > 0; i--) handles.get(i).close();
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
    public record Resolution(Status status, ResolvedTarget resolvedTarget, String diagnostic) {
        public Optional<ResolvedTarget> target() { return Optional.ofNullable(resolvedTarget); }
    }

    /** Detailed resolution outcome. */
    public enum Status { FOUND, MISSING, FAILED }

    @FunctionalInterface interface ReadHook { void beforeRead(Path file) throws IOException; }

    private record ComponentSnapshot(Path path, SourceAttributes attributes) { }

    /** Basic and available DOS attributes captured with no link following for fallback verification. */
    private record SourceAttributes(boolean directory, boolean regular, boolean other, boolean symbolicLink, long size, java.nio.file.attribute.FileTime created, java.nio.file.attribute.FileTime modified, Object fileKey, boolean hidden, boolean system) {
        static SourceAttributes read(Path path) throws IOException {
            BasicFileAttributes basic = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            try {
                DosFileAttributes dos = Files.readAttributes(path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                return new SourceAttributes(basic.isDirectory(), basic.isRegularFile(), basic.isOther(), Files.isSymbolicLink(path), basic.size(), basic.creationTime(), basic.lastModifiedTime(), basic.fileKey(), dos.isHidden(), dos.isSystem());
            } catch (UnsupportedOperationException ignored) {
                return new SourceAttributes(basic.isDirectory(), basic.isRegularFile(), basic.isOther(), Files.isSymbolicLink(path), basic.size(), basic.creationTime(), basic.lastModifiedTime(), basic.fileKey(), false, false);
            }
        }

        boolean safeDirectory() { return directory && !other && !symbolicLink; }

        boolean safeComponent(boolean finalComponent) {
            return !other && !symbolicLink && (finalComponent ? regular : directory);
        }

        boolean sameAs(SourceAttributes other) {
            return directory == other.directory && regular == other.regular && this.other == other.other && symbolicLink == other.symbolicLink && size == other.size && created.equals(other.created) && modified.equals(other.modified) && (fileKey == null || other.fileKey == null || fileKey.equals(other.fileKey)) && hidden == other.hidden && system == other.system;
        }

        boolean sameAs(BasicFileAttributes other) {
            return directory == other.isDirectory() && regular == other.isRegularFile() && this.other == other.isOther()
                    && size == other.size() && created.equals(other.creationTime()) && modified.equals(other.lastModifiedTime())
                    && (fileKey == null || other.fileKey() == null || fileKey.equals(other.fileKey()));
        }
    }
}
