package com.alechilles.patchwork.conditions;

import com.google.gson.JsonElement;
import java.util.HashMap;
import java.util.Map;

/** Per-generation immutable-document cache; each source identity is resolved at most once. */
public final class ConditionDocumentCache {
    private final Map<SourceKey, Snapshot> snapshots = new HashMap<>();
    /** Returns the snapshot recorded for this source, computing it only on first use. */
    public synchronized Snapshot getOrResolve(SourceKey key, java.util.function.Supplier<Snapshot> reader) { return snapshots.computeIfAbsent(key, ignored -> reader.get()); }
    /** Number of distinct source snapshots recorded during this pass. */
    public synchronized int snapshotCount() { return snapshots.size(); }
    /** Stable source identity that never contains target document content. */
    public record SourceKey(String kind, String first, String second) { }
    /** Cached document result. Diagnostics are intentionally content-free. */
    public record Snapshot(Status status, JsonElement document, String diagnostic) {
        public Snapshot { document = document == null ? null : document.deepCopy(); diagnostic = diagnostic == null ? "" : diagnostic; }
        @Override public JsonElement document() { return document == null ? null : document.deepCopy(); }
    }
    /** Source-resolution status. */
    public enum Status { FOUND, MISSING, FAILED }
}
