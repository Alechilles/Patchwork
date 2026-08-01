package com.alechilles.patchwork.reload;

import java.security.MessageDigest;
import java.util.HexFormat;

/** Immutable pre-write backup evidence for one target transaction. */
public record TargetJournalEntry(String target, byte[] oldBytes, String oldHash) {
    /** Hash token used when an expected target is intentionally absent. */
    public static final String REMOVED_HASH = "<removed>";
    public TargetJournalEntry { oldBytes = oldBytes == null ? null : oldBytes.clone(); oldHash = oldHash == null ? REMOVED_HASH : oldHash; }
    @Override public byte[] oldBytes() { return oldBytes == null ? null : oldBytes.clone(); }
    /** Calculates the stable SHA-256 token used for observer correlation. */
    public static String hash(byte[] bytes) {
        if (bytes == null) return REMOVED_HASH;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception error) { throw new IllegalStateException("SHA-256 is unavailable", error); }
    }
}
