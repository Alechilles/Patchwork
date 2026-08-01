package com.alechilles.patchwork.generation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.UUID;

/** Owns and validates the small directory tree used by generated Patchwork packs. */
public final class GeneratedPackLayout {
    private final Path serverRoot;
    private final Path dataRoot;

    /** Resolves storage below the supplied server or save root only. */
    public GeneratedPackLayout(Path serverOrSaveRoot) {
        serverRoot = Objects.requireNonNull(serverOrSaveRoot).toAbsolutePath().normalize();
        dataRoot = serverRoot.resolve("mods/Alechilles_Patchwork").normalize();
    }

    public Path dataRoot() { return dataRoot; }
    public Path generatedRoot() { return dataRoot.resolve("GeneratedPatches"); }
    public Path selfTestRoot() { return dataRoot.resolve("SelfTest"); }
    public Path diagnosticsRoot() { return dataRoot.resolve("Diagnostics"); }

    /** Returns whether the lexical path is contained by the exact owned data root. */
    public boolean isOwned(Path path) {
        return path != null && path.toAbsolutePath().normalize().startsWith(dataRoot);
    }

    /** Rejects links, special files, and existing real-path escapes before any traversal. */
    public void requireSafeExistingComponents(Path path) throws IOException {
        Path normalized = Objects.requireNonNull(path).toAbsolutePath().normalize();
        if (!isOwned(normalized)) throw new IOException("Path is outside the Patchwork data root.");
        validateExistingPath(normalized, true);
    }

    /** Validates an owned existing file or directory without following its final leaf. */
    public void requireSafeExistingLeaf(Path path) throws IOException {
        Path normalized = Objects.requireNonNull(path).toAbsolutePath().normalize();
        if (!isOwned(normalized)) throw new IOException("Path is outside the Patchwork data root.");
        validateExistingPath(normalized, false);
    }

    /** Creates the root only after its existing ancestors were checked without following links. */
    public void createSafeDataRoot() throws IOException {
        validateExistingPath(dataRoot.getParent(), true);
        Files.createDirectories(dataRoot);
        requireSafeExistingComponents(dataRoot);
    }

    Path stagingRoot(String token) { return dataRoot.resolve(".GeneratedPatches-staging-" + ownedToken(token)).normalize(); }
    Path quarantineRoot(String token) { return diagnosticsRoot().resolve("GeneratedPatches-prior-" + ownedToken(token)).normalize(); }
    Path failedNewRoot(String token) { return diagnosticsRoot().resolve("GeneratedPatches-failed-new-" + ownedToken(token)).normalize(); }

    boolean isOwnedStagingChild(Path path) {
        return directChild(path, ".GeneratedPatches-staging-");
    }

    private boolean directChild(Path path, String prefix) {
        if (path == null) return false;
        Path normalized = path.toAbsolutePath().normalize();
        Path name = normalized.getFileName();
        if (!dataRoot.equals(normalized.getParent()) || name == null || !name.toString().startsWith(prefix)) return false;
        try { String token = name.toString().substring(prefix.length()); return UUID.fromString(token).toString().equals(token); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static String ownedToken(String token) {
        if (token == null || token.isBlank() || token.contains("/") || token.contains("\\")) {
            throw new IllegalArgumentException("Publication token must be non-blank and path-safe.");
        }
        return token;
    }

    private static void validateExistingPath(Path end, boolean requireFinalDirectory) throws IOException {
        Path normalizedEnd = end.toAbsolutePath().normalize();
        Path filesystemRoot = normalizedEnd.getRoot();
        if (filesystemRoot == null) throw new IOException("Path has no filesystem root.");
        Path realRoot = filesystemRoot.toRealPath();
        Path current = filesystemRoot;
        verifyExisting(current, realRoot, true);
        java.util.List<Path> parts = new java.util.ArrayList<>();
        for (Path part : filesystemRoot.relativize(normalizedEnd)) parts.add(part);
        for (int index = 0; index < parts.size(); index++) {
            current = current.resolve(parts.get(index));
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) break;
            verifyExisting(current, realRoot, index < parts.size() - 1 || requireFinalDirectory);
        }
    }

    private static void verifyExisting(Path path, Path root, boolean requireDirectory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther() || (requireDirectory && !attributes.isDirectory())) {
            throw new IOException("Patchwork data path contains a link or unsafe component.");
        }
        if (!path.toRealPath().startsWith(root.toRealPath())) throw new IOException("Patchwork data path escapes its server root.");
    }
}
