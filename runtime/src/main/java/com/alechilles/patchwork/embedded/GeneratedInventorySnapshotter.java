package com.alechilles.patchwork.embedded;

import com.alechilles.patchwork.generation.GeneratedPackManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads the live generated-target inventory without treating either pack manifest as a target.
 *
 * <p>The identity and no-follow checks fail closed on every observable replacement. They are an
 * integrity boundary against accidental or independently observable filesystem changes, not a
 * sandbox against hostile same-process Java code, which already has equivalent filesystem access.
 * Portable Windows NIO does not provide descriptor-relative traversal for the remaining syscall
 * window.</p>
 */
@FunctionalInterface
interface GeneratedInventorySnapshotter {
    Map<String, byte[]> snapshot() throws IOException;

    static GeneratedInventorySnapshotter from(Path generatedRoot) {
        return from(generatedRoot, ignored -> { });
    }

    static GeneratedInventorySnapshotter from(Path generatedRoot, ScanHook hook) {
        Path root = Objects.requireNonNull(generatedRoot, "generatedRoot").toAbsolutePath().normalize();
        Objects.requireNonNull(hook, "hook");
        return () -> scan(root, hook);
    }

    private static Map<String, byte[]> scan(Path root, ScanHook hook) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        requireDirectory(root);
        List<Component> ancestry = captureAncestry(root);
        hook.beforeWalk(root);
        requireSameAncestry(root, ancestry);
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.comparing(path -> root.relativize(path).toString())).toList()) {
                requireSameAncestry(root, ancestry); requireSafeDescendant(root, path);
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Generated inventory contains an unsafe path.");
                if (!attributes.isRegularFile()) continue;
                String target = root.relativize(path).toString().replace('\\', '/');
                if (target.equals("manifest.json") || target.equals(GeneratedPackManifest.FILE_NAME)) continue;
                result.put(target, stableRead(root, ancestry, path, attributes));
            }
        }
        requireSameAncestry(root, ancestry);
        return Map.copyOf(result);
    }

    private static byte[] stableRead(Path root, List<Component> ancestry, Path path, BasicFileAttributes before) throws IOException {
        requireSameAncestry(root, ancestry); requireSafeDescendant(root, path);
        byte[] bytes = Files.readAllBytes(path);
        requireSameAncestry(root, ancestry); requireSafeDescendant(root, path);
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (after.isSymbolicLink() || !after.isRegularFile() || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime()) || !Objects.equals(before.fileKey(), after.fileKey())
                || !before.creationTime().equals(after.creationTime()) || bytes.length != after.size()) {
            throw new IOException("Generated inventory changed while being read.");
        }
        return bytes;
    }

    private static void requireDirectory(Path root) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isDirectory()) throw new IOException("Generated root is unsafe.");
    }

    private static List<Component> captureAncestry(Path root) throws IOException {
        List<Component> result = new ArrayList<>(); Path current = root.getRoot();
        if (current == null) throw new IOException("Generated root has no filesystem root.");
        for (Path segment : current.relativize(root)) { current = current.resolve(segment); requireDirectory(current); result.add(Component.capture(current)); }
        return List.copyOf(result);
    }

    private static void requireSameAncestry(Path root, List<Component> expected) throws IOException {
        List<Component> actual = captureAncestry(root);
        if (!expected.equals(actual)) throw new IOException("Generated root or ancestor was replaced during inventory scan.");
    }

    private static void requireSafeDescendant(Path root, Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new IOException("Generated inventory path escaped its root.");
        Path current = root;
        for (Path part : root.relativize(normalized)) { current = current.resolve(part); BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Generated inventory contains an unsafe path."); }
    }

    record Component(Path path, String fileKey, long creation, boolean directory) {
        static Component capture(Path path) throws IOException { BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); return new Component(path, String.valueOf(a.fileKey()), a.creationTime().toMillis(), a.isDirectory()); }
    }

    @FunctionalInterface interface ScanHook { void beforeWalk(Path root) throws IOException; }
}
