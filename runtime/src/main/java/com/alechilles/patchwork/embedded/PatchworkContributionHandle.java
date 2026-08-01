package com.alechilles.patchwork.embedded;

/** Opaque, idempotently closeable registration for one host contribution. */
public interface PatchworkContributionHandle extends AutoCloseable { @Override void close(); }
