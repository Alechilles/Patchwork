package com.alechilles.patchwork.reload;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Owns atomic target replacement, deletion, and restoration from journal evidence. */
public final class TargetPatchTransaction {
    /** File move seam used to verify atomic replacement behavior without Hytale APIs. */
    interface MoveStrategy { void atomicMove(Path from, Path to) throws IOException; void nonAtomicMove(Path from, Path to) throws IOException; }
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
        return path;
    }
    private void replace(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        if (bytes == null) { Files.deleteIfExists(target); return; }
        Path temporary = Files.createTempFile(target.getParent(), ".patchwork-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { moves.atomicMove(temporary, target); }
            catch (AtomicMoveNotSupportedException unsupported) { moves.nonAtomicMove(temporary, target); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private static final class FileMoveStrategy implements MoveStrategy {
        @Override public void atomicMove(Path from, Path to) throws IOException { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        @Override public void nonAtomicMove(Path from, Path to) throws IOException { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }
    static MoveStrategy fileMoves() { return new FileMoveStrategy(); }
}
