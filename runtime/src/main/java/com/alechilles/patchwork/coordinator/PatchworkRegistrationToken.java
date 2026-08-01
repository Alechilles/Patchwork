package com.alechilles.patchwork.coordinator;

import java.util.UUID;

/** Opaque, unforgeable registration identity that permits only its owner to unregister. */
final class PatchworkRegistrationToken {
    private final String value = UUID.randomUUID().toString();
    @Override public String toString() { return value; }
}
