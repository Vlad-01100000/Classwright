package com.classwright.runtime;

import com.classwright.runtime.fixtures.DefinitionTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The opt-in MRU retention mode, which needs its own JVM: the retention policy is read once
 * into {@code static final} state, so it cannot be toggled inside the ordinary suite. The
 * {@code mru-retention} Failsafe execution forks this class with
 * {@code -Dclasswright.cacheRetention=mru}; under the default execution the assumption below
 * skips everything.
 */
class MruRetentionIT {

    @Test
    @DisplayName("the ring retains hot shapes across GC, and invalidate() releases them")
    void invalidateReleasesMruRetention() throws Exception {
        assumeTrue("mru".equalsIgnoreCase(System.getProperty("classwright.cacheRetention", "")),
                "runs only in the mru-retention execution, which sets the retention property");

        Class<?> generated = GenerationCache.computeIfAbsent(DefinitionTarget.class,
                "mru-invalidate",
                () -> DefinitionStrategy.hidden().define(DefinitionSite.of(DefinitionTarget.class),
                        GeneratedBytes.plainSubclass(DefinitionTarget.class, "MruRetained"))
                        .type());
        WeakReference<Class<?>> reference = new WeakReference<>(generated);
        generated = null;

        // First, the mode's whole point: with no live instance anywhere, the ring alone must
        // keep the shape from being collected — this is what the weak default does not do.
        for (int attempt = 0; attempt < 3; attempt++) {
            System.gc();
            Thread.sleep(50);
        }
        assertNotNull(reference.get(),
                "under cacheRetention=mru the ring must keep the last generated classes alive");

        // Then the fix under test: invalidation discards the whole per-target state, ring
        // included. Clearing only the lookup map left the class unreachable through the cache
        // yet still strongly retained — the worst of both.
        GenerationCache.invalidate(DefinitionTarget.class);

        assertTrue(awaitCleared(reference),
                "invalidate() must release the MRU ring's strong retention");
    }

    @Test
    @DisplayName("a generation in flight across invalidate() cannot resurrect ring retention")
    void inFlightGenerationCannotResurrectRetention() throws Exception {
        assumeTrue("mru".equalsIgnoreCase(System.getProperty("classwright.cacheRetention", "")),
                "runs only in the mru-retention execution, which sets the retention property");

        // The race a ClassValue-owned ring lost: invalidate() removed the ring, and a generation
        // already past the removal minted a fresh ring on completion — the invalidated class
        // stayed invisible through the cache yet strongly retained. With the ring inside the
        // per-anchor state, the late retain lands in the detached state and dies with it.
        java.util.concurrent.CountDownLatch generating = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Class<?>> result =
                new java.util.concurrent.atomic.AtomicReference<>();

        Thread generation = new Thread(() -> result.set(GenerationCache.computeIfAbsent(
                DefinitionTarget.class, "mru-race", () -> {
                    generating.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return DefinitionStrategy.hidden().define(
                            DefinitionSite.of(DefinitionTarget.class),
                            GeneratedBytes.plainSubclass(DefinitionTarget.class, "MruRaced"))
                            .type();
                })), "mru-race-generation");
        generation.start();
        assertTrue(generating.await(10, java.util.concurrent.TimeUnit.SECONDS),
                "the generator must have started before the invalidation races it");

        GenerationCache.invalidate(DefinitionTarget.class);
        release.countDown();
        generation.join(10_000);

        assertTrue(!GenerationCache.isCached(DefinitionTarget.class, "mru-race"),
                "the invalidation must win: the late install lands in the detached state");

        WeakReference<Class<?>> reference = new WeakReference<>(result.get());
        result.set(null);
        assertTrue(awaitCleared(reference),
                "once the caller drops the returned class, nothing — ring included — may "
                        + "still retain what an invalidation already dropped");
    }

    private static boolean awaitCleared(WeakReference<?> reference) throws InterruptedException {
        for (int attempt = 0; attempt < 6; attempt++) {
            if (reference.get() == null) {
                return true;
            }
            System.gc();
            Thread.sleep(100);
        }
        return reference.get() == null;
    }
}
