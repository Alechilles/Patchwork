package com.alechilles.patchwork.generation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Fail-closed observable fallback guard for owned paths when descriptor-relative NIO is unavailable. */
final class OwnedPathAccess {
    private final GeneratedPackLayout layout;
    private final MutationHook hook;

    OwnedPathAccess(GeneratedPackLayout layout) { this(layout, path -> { }); }
    OwnedPathAccess(GeneratedPackLayout layout, MutationHook hook) { this.layout = Objects.requireNonNull(layout); this.hook = Objects.requireNonNull(hook); }

    /** Captures all existing components, permits a deterministic race seam, then rejects any observable change before mutation. */
    void guard(Path path) throws IOException {
        List<Component> before = snapshot(path);
        hook.beforeMutation(path);
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) SecureOwnedDirectories.verifyNoFollow(parent);
        if (!before.equals(snapshot(path))) throw new IOException("Owned path changed before mutation.");
    }
    void afterCreation(Path path) throws IOException { hook.afterCreation(path); }
    Identity captureIdentity(Path path) throws IOException {
        layout.requireSafeExistingComponents(path);
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return new Identity(String.valueOf(attributes.fileKey()), attributes.isDirectory(), attributes.size(), attributes.creationTime().toMillis());
    }
    void requireSameIdentity(Path path, Identity identity) throws IOException {
        layout.requireSafeExistingComponents(path);
        if (!identity.equals(captureIdentity(path))) throw new IOException("Created owned directory was replaced before use.");
    }

    private List<Component> snapshot(Path path) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        layout.requireSafeExistingLeaf(target);
        List<Component> result = new ArrayList<>();
        Path current = target.getRoot();
        for (Path segment : target.getRoot().relativize(target)) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) break;
            BasicFileAttributes basic = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (basic.isSymbolicLink() || basic.isOther()) throw new IOException("Owned path contains a link or special component.");
            boolean reparse = false;
            try { reparse = Files.readAttributes(current, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isSystem(); } catch (UnsupportedOperationException ignored) { }
            if (reparse) throw new IOException("Owned path contains a reparse-like component.");
            result.add(new Component(current, String.valueOf(basic.fileKey()), basic.size(), basic.isDirectory()));
        }
        return List.copyOf(result);
    }

    private record Component(Path path, String fileKey, long size, boolean directory) { }
    record Identity(String fileKey, boolean directory, long size, long created) { }
    @FunctionalInterface interface MutationHook { void beforeMutation(Path path) throws IOException; default void afterCreation(Path path) throws IOException { } }
}
