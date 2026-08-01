package com.alechilles.patchwork.embedded;

/** Lifecycle handle for the one standalone Patchwork runtime provider. */
public interface StandalonePatchworkService extends AutoCloseable {
    void start();

    @Override
    void close();
}
