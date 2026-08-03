package com.alechilles.patchwork.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.patchwork.generation.GenerationDependencyIndex;
import com.alechilles.patchwork.discovery.PatchScanner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class AutomaticReloadControllerTest {
    @Test
    void debouncesRelevantEventsAndRunsOneDirtyFollowUp() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger passes = new AtomicInteger();
        AutomaticReloadController[] holder = new AutomaticReloadController[1];
        holder[0] = new AutomaticReloadController(epoch -> {
            if (passes.incrementAndGet() == 1) holder[0].onSourceEvent(epoch, PatchworkSourceEvent.modified("Pack:Mod", "Server/Item/A.json"));
            return null;
        }, scheduler);
        holder[0].activate(7L, dependencies("Server/Item/A.json"));

        holder[0].onSourceEvent(7L, PatchworkSourceEvent.modified("Pack:Mod", "Server/Item/A.json"));
        holder[0].onSourceEvent(7L, PatchworkSourceEvent.modified("Pack:Mod", "Server/Item/A.json"));
        scheduler.advance(Duration.ofSeconds(1));
        assertEquals(1, passes.get());
        scheduler.advance(Duration.ofSeconds(1));
        assertEquals(2, passes.get());
    }

    @Test
    void rejectsStaleAndGeneratedEventsAndIgnoresImmutableUnrelatedAssets() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger passes = new AtomicInteger();
        AutomaticReloadController controller = new AutomaticReloadController(epoch -> { passes.incrementAndGet(); return null; }, scheduler);
        controller.activate(4L, dependencies("Server/Item/A.json"));

        controller.onSourceEvent(3L, PatchworkSourceEvent.modified("Pack:Mod", "Server/Item/A.json"));
        controller.onSourceEvent(4L, PatchworkSourceEvent.modified(PatchScanner.GENERATED_PACK_ID, "Server/Item/A.json"));
        controller.onSourceEvent(4L, PatchworkSourceEvent.modified("Pack:Mod", "Server/Item/Other.json", true));
        controller.onSourceEvent(4L, PatchworkSourceEvent.modified("Pack:Immutable", "Server/Item/A.json", false));
        scheduler.advance(Duration.ofSeconds(5));
        assertEquals(0, passes.get());

        controller.onSourceEvent(4L, PatchworkSourceEvent.modified("Pack:Mod", "Server/Item/A.json"));
        controller.fence(4L);
        scheduler.advance(Duration.ofSeconds(2));
        assertEquals(0, passes.get());
        assertFalse(controller.running());
    }

    @Test
    void retriesWhenAutomaticAdmissionLosesToAnotherOperation() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger attempts = new AtomicInteger();
        AutomaticReloadController controller = new AutomaticReloadController(epoch -> {
            int attempt = attempts.incrementAndGet();
            return new PatchReloadCoordinator.ReloadOutcome(attempt > 1, attempt,
                    PatchReloadCoordinator.ManifestState.NOT_ATTEMPTED, List.of(),
                    PatchReloadCoordinator.IntegrityState.NOT_ATTEMPTED,
                    attempt > 1 ? "" : "Reload is busy.");
        }, scheduler);
        controller.activate(9L, dependencies("Server/Item/A.json"));

        controller.onSourceEvent(9L, PatchworkSourceEvent.modified("Pack:Mod", "Server/Item/A.json"));
        scheduler.advance(Duration.ofSeconds(1));
        assertEquals(1, attempts.get());
        scheduler.advance(Duration.ofSeconds(1));
        assertEquals(2, attempts.get());
    }

    private static GenerationDependencyIndex dependencies(String... targets) {
        return new GenerationDependencyIndex(Set.of(), Set.of(targets), Set.of(), Set.of());
    }

    private static final class ManualScheduler implements AutomaticReloadController.Scheduler {
        private long now;
        private final List<Task> tasks = new ArrayList<>();

        @Override public AutomaticReloadController.Cancellable schedule(Duration delay, Runnable action) {
            Task task = new Task(now + delay.toNanos(), action);
            tasks.add(task);
            return () -> task.cancelled = true;
        }

        void advance(Duration amount) {
            now += amount.toNanos();
            boolean ran;
            do {
                ran = false;
                for (Task task : List.copyOf(tasks)) {
                    if (!task.cancelled && task.due <= now) {
                        tasks.remove(task);
                        ran = true;
                        task.action.run();
                    } else if (task.cancelled) tasks.remove(task);
                }
            } while (ran);
        }

        private static final class Task {
            private final long due;
            private final Runnable action;
            private boolean cancelled;
            private Task(long due, Runnable action) { this.due = due; this.action = action; }
        }
    }
}
