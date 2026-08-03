package com.alechilles.patchwork.conflict;

import com.alechilles.patchwork.format.Utf8Ordering;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable deterministic collection of value-redacted overlap rows. */
public record ConflictReport(List<ConflictRecord> records) {
    private static final Comparator<ConflictRecord> ORDERING = Comparator
            .comparing(ConflictRecord::target, Utf8Ordering.UNSIGNED_BYTES)
            .thenComparing(ConflictRecord::path, Utf8Ordering.UNSIGNED_BYTES)
            .thenComparingInt(row -> row.effectKind().ordinal())
            .thenComparingLong(row -> row.later().operationOrder())
            .thenComparingLong(row -> row.earlier().operationOrder())
            .thenComparing(row -> row.later().sourcePackId(), Utf8Ordering.UNSIGNED_BYTES)
            .thenComparing(row -> row.later().patchId(), Utf8Ordering.UNSIGNED_BYTES)
            .thenComparing(row -> row.earlier().sourcePackId(), Utf8Ordering.UNSIGNED_BYTES)
            .thenComparing(row -> row.earlier().patchId(), Utf8Ordering.UNSIGNED_BYTES);

    public ConflictReport {
        Objects.requireNonNull(records, "records");
        records = records.stream().filter(Objects::nonNull).sorted(ORDERING).toList();
    }

    public static ConflictReport empty() {
        return new ConflictReport(List.of());
    }

    /** Returns only rows for one exact target. */
    public List<ConflictRecord> forTarget(String target) {
        if (target == null) return records;
        return records.stream().filter(row -> row.target().equals(target)).toList();
    }

    public long materialCount() {
        return records.stream().filter(row -> row.classification() == ConflictRecord.Classification.MATERIAL_OVERLAP).count();
    }

    public long redundantCount() {
        return records.stream().filter(row -> row.classification() == ConflictRecord.Classification.REDUNDANT_IDENTICAL).count();
    }
}
