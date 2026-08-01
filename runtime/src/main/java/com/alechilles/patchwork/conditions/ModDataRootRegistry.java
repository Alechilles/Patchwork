package com.alechilles.patchwork.conditions;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.channels.SeekableByteChannel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Exact loaded-plugin data roots and fail-closed, read-only JSON document access. */
public final class ModDataRootRegistry {
    static final long MAX_BYTES = 4L * 1024L * 1024L;
    private final Map<String, Path> roots;
    private final ReadHook readHook;
    /** Creates a registry from exact identifiers; primarily useful for deterministic tests. */
    public ModDataRootRegistry(Map<String, Path> roots) { this(roots, path -> { }); }
    ModDataRootRegistry(Map<String, Path> roots, ReadHook readHook) { Map<String, Path> copy = new LinkedHashMap<>(); roots.forEach((id, root) -> copy.put(require(id, "mod ID"), root.toAbsolutePath().normalize())); this.roots = Map.copyOf(copy); this.readHook = readHook; }
    /** Snapshots loaded Java plugins only; content packs cannot become ModData roots. */
    public static ModDataRootRegistry fromPluginManager(PluginManager manager) { Map<String, Path> result = new LinkedHashMap<>(); for (PluginBase plugin : manager.getPlugins()) result.put(plugin.getIdentifier().toString(), plugin.getDataDirectory()); return new ModDataRootRegistry(result); }
    /** Looks up a root by exact manifest identifier. */
    public Optional<Path> rootFor(String modId) { return Optional.ofNullable(roots.get(modId)); }
    /** Validates a portable, normalized relative document path. */
    public String validateRelativePath(String path) { String value = require(path, "ModData path").replace('\\', '/'); if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) throw new IllegalArgumentException("ModData path must be relative."); for (String segment : value.split("/", -1)) if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) throw new IllegalArgumentException("ModData path contains an unsafe segment."); return value; }
    /** Reads one ModData file with no link following and a bounded UTF-8 payload. */
    public ReadResult readJson(String modId, String relativePath) {
        Path root = roots.get(modId); if (root == null) return new ReadResult(ReadStatus.MISSING, null, "ModData plugin is not loaded: " + modId);
        final String safe; try { safe = validateRelativePath(relativePath); } catch (IllegalArgumentException e) { return new ReadResult(ReadStatus.FAILED, null, "Unsafe ModData relative path."); }
        try { return new ReadResult(ReadStatus.FOUND, read(root, safe), ""); } catch (java.nio.file.NoSuchFileException e) { return new ReadResult(ReadStatus.MISSING, null, "ModData file is missing: " + modId + "/" + safe); } catch (Exception e) { return new ReadResult(ReadStatus.FAILED, null, "Unable to safely read ModData: " + modId + "/" + safe); }
    }
    private byte[] read(Path root, String relative) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) throw new IOException("unsafe root");
        Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS); Path current = root;
        String[] parts = relative.split("/");
        for (int i = 0; i < parts.length; i++) { current = current.resolve(parts[i]); BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); if (Files.isSymbolicLink(current) || attrs.isOther() || (i < parts.length - 1 && !attrs.isDirectory()) || (i == parts.length - 1 && !attrs.isRegularFile())) throw new IOException("unsafe component"); }
        Path realFile = current.toRealPath(LinkOption.NOFOLLOW_LINKS); if (!realFile.startsWith(realRoot)) throw new IOException("escaped root");
        Attributes before = Attributes.read(current); if (before.size() > MAX_BYTES) throw new IOException("file too large");
        readHook.beforeRead(current);
        byte[] bytes; try (SeekableByteChannel channel = Files.newByteChannel(current, java.util.Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) { if (channel.size() > MAX_BYTES) throw new IOException("file too large"); try (InputStream input = java.nio.channels.Channels.newInputStream(channel)) { bytes = bounded(input); } }
        Attributes after = Attributes.read(current); Path afterReal = current.toRealPath(LinkOption.NOFOLLOW_LINKS); if (!before.sameAs(after) || !afterReal.startsWith(realRoot) || !afterReal.equals(realFile)) throw new IOException("file changed during read");
        return bytes;
    }
    private static byte[] bounded(InputStream input) throws IOException { ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; for (int n; (n = input.read(buffer)) != -1;) { if (output.size() + n > MAX_BYTES) throw new IOException("file too large"); output.write(buffer, 0, n); } return output.toByteArray(); }
    private static String require(String value, String name) { if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank."); return value.trim(); }
    /** Read outcome that never contains document data in its diagnostic. */
    public record ReadResult(ReadStatus status, byte[] bytes, String diagnostic) { public ReadResult { bytes = bytes == null ? null : bytes.clone(); } @Override public byte[] bytes() { return bytes == null ? null : bytes.clone(); } }
    /** ModData read status. */
    public enum ReadStatus { FOUND, MISSING, FAILED }
    /** Narrow deterministic seam used only to exercise TOCTOU detection. */
    @FunctionalInterface interface ReadHook { void beforeRead(Path path) throws IOException; }
    private record Attributes(boolean regular, boolean other, long size, java.nio.file.attribute.FileTime created, java.nio.file.attribute.FileTime modified, Object key, boolean hidden, boolean system) {
        static Attributes read(Path path) throws IOException { BasicFileAttributes basic = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); if (!basic.isRegularFile() || basic.isOther()) throw new IOException("unsafe final file"); try { DosFileAttributes dos = Files.readAttributes(path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS); return new Attributes(basic.isRegularFile(), basic.isOther(), basic.size(), basic.creationTime(), basic.lastModifiedTime(), basic.fileKey(), dos.isHidden(), dos.isSystem()); } catch (UnsupportedOperationException ignored) { return new Attributes(basic.isRegularFile(), basic.isOther(), basic.size(), basic.creationTime(), basic.lastModifiedTime(), basic.fileKey(), false, false); } }
        boolean sameAs(Attributes other) { return regular == other.regular && this.other == other.other && size == other.size && created.equals(other.created) && modified.equals(other.modified) && (key == null || other.key == null || key.equals(other.key)) && hidden == other.hidden && system == other.system; }
    }
}
