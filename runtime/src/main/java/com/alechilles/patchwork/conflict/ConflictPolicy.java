package com.alechilles.patchwork.conflict;

/** Action to take when a definition overlaps an earlier accepted definition. */
public enum ConflictPolicy {
    /** Keep the candidate and expose the overlap in the report. */
    REPORT,
    /** Keep the candidate while suppressing rows introduced by this definition. */
    ALLOW,
    /** Reject the affected target for this generation pass. */
    REJECT
}
