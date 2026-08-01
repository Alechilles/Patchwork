package com.alechilles.patchwork.reload;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Owns atomic target replacement, deletion, and restoration from journal evidence. */
public final class TargetPatchTransaction {
    /** File move seam used to verify atomic replacement behavior without Hytale APIs. */
    interface MoveStrategy { void atomicMove(Path from, Path to) throws IOException; void nonAtomicMove(Path from, Path to) throws IOException; default void beforeMutation(Path target) throws IOException { } }
    private final Path root;
    private final MoveStrategy moves;
    public TargetPatchTransaction(Path root) { this(root, new FileMoveStrategy()); }
    TargetPatchTransaction(Path root, MoveStrategy moves) { this.root = Objects.requireNonNull(root).toAbsolutePath().normalize(); this.moves = Objects.requireNonNull(moves); }

    /** Captures the target's exact prior bytes and hash before a mutation. */
    public TargetJournalEntry journal(String target) throws IOException {
        Path path = resolve(target); byte[] old = Files.exists(path) ? Files.readAllBytes(path) : null;
        return new TargetJournalEntry(target, old, TargetJournalEntry.hash(old));
    }
    /** Atomically writes replacement bytes, or deletes the target when bytes are absent. */
    public void apply(String target, byte[] bytes) throws IOException { replace(resolve(target), bytes); }
    /** Atomically restores the prior target state recorded by {@link #journal(String)}. */
    public void rollback(TargetJournalEntry entry) throws IOException { replace(resolve(entry.target()), entry.oldBytes()); }
    private Path resolve(String target) throws IOException {
        Path path = root.resolve(target).normalize();
        if (!path.startsWith(root)) throw new IOException("Reload target escapes the generated root.");
        verifyNoFollow(root);
        verifyNoFollow(path);
        return path;
    }
    private void replace(Path target, byte[] bytes) throws IOException {
        verifyNoFollow(target.getParent());
        Files.createDirectories(target.getParent());
        verifyNoFollow(target.getParent());
        AncestryIdentity before = captureAncestry(target.getParent());
        if (bytes == null) {
            moves.beforeMutation(target);
            requireSameAncestry(target.getParent(), before);
            Files.deleteIfExists(target);
            requireSameAncestry(target.getParent(), before);
            return;
        }
        Path temporary = Files.createTempFile(target.getParent(), ".patchwork-", ".tmp");
        try {
            Files.write(temporary, bytes);
            moves.beforeMutation(target);
            requireSameAncestry(target.getParent(), before);
            try { moves.atomicMove(temporary, target); }
            catch (AtomicMoveNotSupportedException unsupported) { moves.nonAtomicMove(temporary, target); }
            requireSameAncestry(target.getParent(), before);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static final class FileMoveStrategy implements MoveStrategy {
        @Override public void atomicMove(Path from, Path to) throws IOException { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        @Override public void nonAtomicMove(Path from, Path to) throws IOException { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }
    static MoveStrategy fileMoves() { return new FileMoveStrategy(); }
    static void verifySafePath(Path path) throws IOException { verifyNoFollow(path); }
    /** Captures observable no-follow ancestry identity, including a creation-time fallback for null file keys. */
    static AncestryIdentity captureAncestry(Path path) throws IOException { verifyNoFollow(path); return new AncestryIdentity(snapshot(path)); }
    static void requireSameAncestry(Path path, AncestryIdentity identity) throws IOException { verifyNoFollow(path); if (!identity.equals(captureAncestry(path))) throw new IOException("Reload ancestry changed before mutation."); }
    /** Practical portable fallback: validates observable ancestors before each mutation; mkdir races remain OS-level residuals. */
    private static void verifyNoFollow(Path end) throws IOException {
        Path absolute = end.toAbsolutePath().normalize(); Path root = absolute.getRoot();
        if (root == null) throw new IOException("Reload path has no filesystem root.");
        Path current = root;
        for (Path part : root.relativize(absolute)) {
            current = current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) break;
            BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Reload path contains a link or special component.");
            try { if (Files.readAttributes(current, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isSystem()) throw new IOException("Reload path contains a reparse-like component."); }
            catch (UnsupportedOperationException ignored) { }
        }
    }
    private static List<Component> snapshot(Path end) throws IOException {
        Path absolute = end.toAbsolutePath().normalize(); Path filesystemRoot = absolute.getRoot(); List<Component> result = new ArrayList<>(); Path current = filesystemRoot;
        for (Path part : filesystemRoot.relativize(absolute)) { current = current.resolve(part); if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) break; BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); result.add(new Component(current, String.valueOf(attributes.fileKey()), attributes.isDirectory(), attributes.creationTime().toMillis())); }
        return List.copyOf(result);
    }
    record AncestryIdentity(List<Component> components) { }
    private record Component(Path path, String fileKey, boolean directory, long creationTime) { }
}
