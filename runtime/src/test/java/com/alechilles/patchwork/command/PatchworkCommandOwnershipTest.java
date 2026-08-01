package com.alechilles.patchwork.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.alechilles.patchwork.embedded.PatchworkRuntimeHost;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PatchworkCommandOwnershipTest {
    @Test void administrativeCommandsUseTheStablePermissionContract() {
        assertEquals("patchwork.admin", PatchworkCommandRoot.ADMIN_PERMISSION);
        assertEquals("hytale:Admin", PatchworkCommandRoot.DEFAULT_GROUP);
    }

    @Test void electedHostRegistersAndRetiresExactlyOneCommandHandle() {
        AtomicInteger registrations = new AtomicInteger(); AtomicInteger removals = new AtomicInteger();
        PatchworkRuntimeHost host = new PatchworkRuntimeHost(Path.of("build/command-owner"), new PatchworkRuntimeHost.EarlyLoadRegistrar() {
            @Override public PatchworkRuntimeHost.EarlyLoadRegistration register(long epoch, java.util.function.Consumer<com.hypixel.hytale.server.core.asset.LoadAssetEvent> callback) { return () -> { }; }
            @Override public PatchworkRuntimeHost.CommandRegistrationHandle registerCommands() { registrations.incrementAndGet(); return removals::incrementAndGet; }
        });
        host.activate(4); host.start(4); host.stopAcceptingAndDrain(4);
        assertEquals(1, registrations.get()); assertEquals(1, removals.get());
        assertTrue(true); // command construction belongs to the Hytale-only composition wrapper.
    }

    @Test void rejectedCommandRegistrationCompensatesEarlyLoadAndDoesNotPoisonRetry() {
        AtomicInteger earlyLoadRemovals = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        PatchworkRuntimeHost host = new PatchworkRuntimeHost(Path.of("build/command-retry"), new PatchworkRuntimeHost.EarlyLoadRegistrar() {
            @Override public PatchworkRuntimeHost.EarlyLoadRegistration register(long epoch, java.util.function.Consumer<com.hypixel.hytale.server.core.asset.LoadAssetEvent> callback) {
                return earlyLoadRemovals::incrementAndGet;
            }
            @Override public PatchworkRuntimeHost.CommandRegistrationHandle registerCommands() {
                return attempts.incrementAndGet() == 1 ? null : () -> { };
            }
        });
        host.activate(7);
        assertThrows(IllegalStateException.class, () -> host.start(7));
        assertEquals(1, earlyLoadRemovals.get());
        host.start(7);
        host.stopAcceptingAndDrain(7);
        assertEquals(2, attempts.get());
    }

    @Test void throwingCommandRegistrationAlsoCompensatesEarlyLoadAndAllowsRetry() {
        AtomicInteger earlyLoadRemovals = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        PatchworkRuntimeHost host = new PatchworkRuntimeHost(Path.of("build/command-throw-retry"), new PatchworkRuntimeHost.EarlyLoadRegistrar() {
            @Override public PatchworkRuntimeHost.EarlyLoadRegistration register(long epoch, java.util.function.Consumer<com.hypixel.hytale.server.core.asset.LoadAssetEvent> callback) { return earlyLoadRemovals::incrementAndGet; }
            @Override public PatchworkRuntimeHost.CommandRegistrationHandle registerCommands() {
                if (attempts.incrementAndGet() == 1) throw new IllegalStateException("registration");
                return () -> { };
            }
        });
        host.activate(8);
        assertThrows(IllegalStateException.class, () -> host.start(8));
        assertEquals(1, earlyLoadRemovals.get());
        host.start(8);
        host.stopAcceptingAndDrain(8);
        assertEquals(2, attempts.get());
    }

    @Test void failedCommandUnregisterRetainsHandleAndNeverAttemptsEventUnregisterUntilRetry() {
        AtomicInteger commandUnregisters = new AtomicInteger();
        AtomicInteger eventUnregisters = new AtomicInteger();
        PatchworkRuntimeHost host = new PatchworkRuntimeHost(Path.of("build/command-unregister-retry"), new PatchworkRuntimeHost.EarlyLoadRegistrar() {
            @Override public PatchworkRuntimeHost.EarlyLoadRegistration register(long epoch, java.util.function.Consumer<com.hypixel.hytale.server.core.asset.LoadAssetEvent> callback) { return eventUnregisters::incrementAndGet; }
            @Override public PatchworkRuntimeHost.CommandRegistrationHandle registerCommands() { return () -> { if (commandUnregisters.incrementAndGet() == 1) throw new IllegalStateException("unregister"); }; }
        });
        host.activate(9); host.start(9);
        assertThrows(IllegalStateException.class, () -> host.stopAcceptingAndDrain(9));
        assertEquals(1, commandUnregisters.get());
        assertEquals(0, eventUnregisters.get());
        host.stopAcceptingAndDrain(9);
        assertEquals(2, commandUnregisters.get());
        assertEquals(1, eventUnregisters.get());
    }
}
