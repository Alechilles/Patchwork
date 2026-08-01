package com.alechilles.patchwork.conditions;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Exact loaded-plugin data roots and fail-closed, read-only JSON document access. */
public final class ModDataRootRegistry {
    static final long MAX_BYTES = 4L * 1024L * 1024L;
    private final Map<String, Path> roots;
    private final ReadHook readHook;
    private final ReadHook afterReadHook;
    private final AttributeReader attributeReader;

    /** Creates a registry from exact identifiers; primarily useful for deterministic tests. */
    public ModDataRootRegistry(Map<String, Path> roots) {
        this(roots, path -> { }, path -> { }, FileAttributes::readSystem);
    }

    ModDataRootRegistry(Map<String, Path> roots, ReadHook readHook) {
        this(roots, readHook, path -> { }, FileAttributes::readSystem);
    }

    ModDataRootRegistry(Map<String, Path> roots, ReadHook readHook, ReadHook afterReadHook) {
        this(roots, readHook, afterReadHook, FileAttributes::readSystem);
    }

    ModDataRootRegistry(Map<String, Path> roots, ReadHook readHook, ReadHook afterReadHook, AttributeReader attributeReader) {
        Map<String, Path> copy = new LinkedHashMap<>();
        roots.forEach((id, root) -> copy.put(require(id, "mod ID"), root.toAbsolutePath().normalize()));
        this.roots = Map.copyOf(copy);
        this.readHook = readHook;
        this.afterReadHook = afterReadHook;
        this.attributeReader = attributeReader;
    }

    /** Snapshots loaded Java plugins only; content packs cannot become ModData roots. */
    public static ModDataRootRegistry fromPluginManager(PluginManager manager) {
        Map<String, Path> result = new LinkedHashMap<>();
        for (PluginBase plugin : manager.getPlugins()) result.put(plugin.getIdentifier().toString(), plugin.getDataDirectory());
        return fromPluginRoots(result);
    }

    /** Pure exact-ID root mapping seam used by the plugin-manager adapter and regression tests. */
    static ModDataRootRegistry fromPluginRoots(Map<String, Path> roots) {
        return new ModDataRootRegistry(roots);
    }

    /** Looks up a root by exact manifest identifier. */
    public Optional<Path> rootFor(String modId) {
        return Optional.ofNullable(roots.get(modId));
    }

    /** Validates a portable, normalized relative document path. */
    public String validateRelativePath(String path) {
        String value = require(path, "ModData path").replace('\\', '/');
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) throw new IllegalArgumentException("ModData path must be relative.");
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) throw new IllegalArgumentException("ModData path contains an unsafe segment.");
        }
        return value;
    }

    /** Reads one ModData file with no link following and a bounded UTF-8 payload. */
    public ReadResult readJson(String modId, String relativePath) {
        Path root = roots.get(modId);
        if (root == null) return new ReadResult(ReadStatus.MISSING, null, "ModData plugin is not loaded: " + modId);
        final String safe;
        try { safe = validateRelativePath(relativePath); }
        catch (IllegalArgumentException e) { return new ReadResult(ReadStatus.FAILED, null, "Unsafe ModData relative path."); }
        try { return new ReadResult(ReadStatus.FOUND, read(root, safe), ""); }
        catch (java.nio.file.NoSuchFileException e) { return new ReadResult(ReadStatus.MISSING, null, "ModData file is missing: " + modId + "/" + safe); }
        catch (Exception e) { return new ReadResult(ReadStatus.FAILED, null, "Unable to safely read ModData: " + modId + "/" + safe); }
    }

    private byte[] read(Path root, String relative) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) throw new IOException("unsafe root");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            if (stream instanceof SecureDirectoryStream<Path> secure) return secureRead(root, relative, secure);
        }
        return fallbackRead(root, relative);
    }

    /**
     * Re-validates each path component after the read. Without file keys, the only residual race is a parent swap timed
     * between checks that preserves every captured attribute and real path, making it otherwise unobservable.
     */
    private byte[] fallbackRead(Path root, String relative) throws IOException {
        Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        String[] parts = relative.split("/");
        Path current = root;
        List<ComponentAttributes> components = new ArrayList<>();
        components.add(ComponentAttributes.from(attributeReader.read(root)));
        for (int i = 0; i < parts.length; i++) {
            current = current.resolve(parts[i]);
            FileAttributes attributes = attributeReader.read(current);
            if (Files.isSymbolicLink(current) || attributes.other() || (i < parts.length - 1 && !attributes.directory()) || (i == parts.length - 1 && !attributes.regular())) throw new IOException("unsafe component");
            components.add(ComponentAttributes.from(attributes));
        }
        Path realFile = current.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!realFile.startsWith(realRoot)) throw new IOException("escaped root");
        Attributes before = Attributes.from(attributeReader.read(current));
        if (before.size() > MAX_BYTES) throw new IOException("file too large");
        readHook.beforeRead(current);
        byte[] bytes;
        try (SeekableByteChannel channel = Files.newByteChannel(current, java.util.Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            if (channel.size() > MAX_BYTES) throw new IOException("file too large");
            try (InputStream input = java.nio.channels.Channels.newInputStream(channel)) { bytes = bounded(input); }
        } catch (java.nio.file.NoSuchFileException disappeared) {
            throw new IOException("file disappeared after validation", disappeared);
        }
        afterReadHook.beforeRead(current);
        try {
            Attributes after = Attributes.from(attributeReader.read(current));
            Path afterReal = current.toRealPath(LinkOption.NOFOLLOW_LINKS);
            for (int i = 0; i < components.size(); i++) {
                Path component = i == 0 ? root : root.resolve(String.join("/", java.util.Arrays.copyOf(parts, i)));
                if (!components.get(i).sameAs(ComponentAttributes.from(attributeReader.read(component)))) throw new IOException("component changed during read");
            }
            if (!before.sameAs(after) || !afterReal.startsWith(realRoot) || !afterReal.equals(realFile)) throw new IOException("file changed during read");
        } catch (java.nio.file.NoSuchFileException disappeared) {
            throw new IOException("file disappeared after validation", disappeared);
        }
        return bytes;
    }

    private byte[] secureRead(Path root, String relative, SecureDirectoryStream<Path> rootStream) throws IOException {
        String[] parts = relative.split("/");
        List<SecureDirectoryStream<Path>> handles = new ArrayList<>();
        handles.add(rootStream);
        try {
            SecureDirectoryStream<Path> current = rootStream;
            for (int i = 0; i < parts.length - 1; i++) {
                Path part = root.getFileSystem().getPath(parts[i]);
                BasicFileAttributes attrs = current.getFileAttributeView(part, java.nio.file.attribute.BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
                if (!attrs.isDirectory() || attrs.isOther()) throw new IOException("unsafe component");
                current = current.newDirectoryStream(part, LinkOption.NOFOLLOW_LINKS);
                handles.add(current);
            }
            Path file = root.getFileSystem().getPath(parts[parts.length - 1]);
            BasicFileAttributes attrs = current.getFileAttributeView(file, java.nio.file.attribute.BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
            if (!attrs.isRegularFile() || attrs.isOther() || attrs.size() > MAX_BYTES) throw new IOException("unsafe final file");
            readHook.beforeRead(root.resolve(relative));
            try (SeekableByteChannel channel = openSecureFinal(current, file)) {
                if (channel.size() > MAX_BYTES) throw new IOException("file too large");
                try (InputStream input = java.nio.channels.Channels.newInputStream(channel)) { return bounded(input); }
            }
        } finally {
            for (int i = handles.size() - 1; i > 0; i--) handles.get(i).close();
        }
    }

    private static SeekableByteChannel openSecureFinal(SecureDirectoryStream<Path> directory, Path file) throws IOException {
        try { return directory.newByteChannel(file, java.util.Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)); }
        catch (java.nio.file.NoSuchFileException disappeared) { throw new IOException("file disappeared after validation", disappeared); }
    }

    private static byte[] bounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = input.read(buffer)) != -1;) {
            if (output.size() + n > MAX_BYTES) throw new IOException("file too large");
            output.write(buffer, 0, n);
        }
        return output.toByteArray();
    }

    private static String require(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank.");
        return value.trim();
    }

    /** Read outcome that never contains document data in its diagnostic. */
    public record ReadResult(ReadStatus status, byte[] bytes, String diagnostic) {
        public ReadResult { bytes = bytes == null ? null : bytes.clone(); }
        @Override public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
    }

    /** ModData read status. */
    public enum ReadStatus { FOUND, MISSING, FAILED }

    /** Narrow deterministic seam used only to exercise TOCTOU detection. */
    @FunctionalInterface interface ReadHook { void beforeRead(Path path) throws IOException; }

    /** Instance-scoped attribute source; tests may supply a deterministic filesystem view without changing global behavior. */
    @FunctionalInterface interface AttributeReader { FileAttributes read(Path path) throws IOException; }

    /** Snapshot of basic and, when available, DOS attributes read with {@link LinkOption#NOFOLLOW_LINKS}. */
    record FileAttributes(boolean directory, boolean regular, boolean other, long size, java.nio.file.attribute.FileTime created, java.nio.file.attribute.FileTime modified, Object fileKey, boolean hidden, boolean system) {
        static FileAttributes readSystem(Path path) throws IOException {
            BasicFileAttributes basic = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            try {
                DosFileAttributes dos = Files.readAttributes(path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                return new FileAttributes(basic.isDirectory(), basic.isRegularFile(), basic.isOther(), basic.size(), basic.creationTime(), basic.lastModifiedTime(), basic.fileKey(), dos.isHidden(), dos.isSystem());
            } catch (UnsupportedOperationException ignored) {
                return new FileAttributes(basic.isDirectory(), basic.isRegularFile(), basic.isOther(), basic.size(), basic.creationTime(), basic.lastModifiedTime(), basic.fileKey(), false, false);
            }
        }

        FileAttributes withoutFileKey() {
            return new FileAttributes(directory, regular, other, size, created, modified, null, hidden, system);
        }
    }

    private record Attributes(boolean regular, boolean other, long size, java.nio.file.attribute.FileTime created, java.nio.file.attribute.FileTime modified, Object key, boolean hidden, boolean system) {
        static Attributes from(FileAttributes attributes) throws IOException {
            if (!attributes.regular() || attributes.other()) throw new IOException("unsafe final file");
            return new Attributes(attributes.regular(), attributes.other(), attributes.size(), attributes.created(), attributes.modified(), attributes.fileKey(), attributes.hidden(), attributes.system());
        }

        boolean sameAs(Attributes other) {
            return regular == other.regular && this.other == other.other && size == other.size && created.equals(other.created) && modified.equals(other.modified) && (key == null || other.key == null || key.equals(other.key)) && hidden == other.hidden && system == other.system;
        }
    }

    private record ComponentAttributes(boolean directory, boolean regular, boolean other, long size, java.nio.file.attribute.FileTime created, java.nio.file.attribute.FileTime modified, Object key, boolean hidden, boolean system) {
        static ComponentAttributes from(FileAttributes attributes) {
            return new ComponentAttributes(attributes.directory(), attributes.regular(), attributes.other(), attributes.size(), attributes.created(), attributes.modified(), attributes.fileKey(), attributes.hidden(), attributes.system());
        }

        boolean sameAs(ComponentAttributes other) {
            return directory == other.directory && regular == other.regular && this.other == other.other && size == other.size && created.equals(other.created) && modified.equals(other.modified) && (key == null || other.key == null || key.equals(other.key)) && hidden == other.hidden && system == other.system;
        }
    }
}
