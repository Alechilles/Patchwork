package com.alechilles.patchwork.engine;

import java.util.Objects;

/**
 * Sanitized description of one successful patch mutation.
 *
 * <p>Effects deliberately carry only a stable fingerprint of the value.  The
 * patched value itself remains in the candidate JSON and never crosses the
 * diagnostics boundary.</p>
 */
public record MutationEffect(
        String target,
        String patchId,
        String sourcePackId,
        String operationId,
        long operationOrder,
        String path,
        Kind kind,
        String valueFingerprint) {

    /** Marker used for removals, for which there is no resulting JSON value. */
    public static final String REMOVED = "removed";

    public MutationEffect {
        target = Objects.requireNonNull(target, "target");
        patchId = Objects.requireNonNull(patchId, "patchId");
        sourcePackId = Objects.requireNonNull(sourcePackId, "sourcePackId");
        operationId = Objects.requireNonNull(operationId, "operationId");
        path = Objects.requireNonNull(path, "path");
        kind = Objects.requireNonNull(kind, "kind");
        valueFingerprint = Objects.requireNonNull(valueFingerprint, "valueFingerprint");
    }

    /** Kind of state change represented by an effect. */
    public enum Kind {
        WRITE,
        ARRAY_MEMBERSHIP,
        ARRAY_ORDER
    }
}
