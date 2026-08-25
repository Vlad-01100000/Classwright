package com.classwright.runtime;

import com.classwright.runtime.fixtures.DefinitionTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests cache behaviour. Reclamation under collection is covered by {@code ClassUnloadingIT}. */
class GenerationCacheTest {

    private static Class<?> generate(String suffix) {
        return DefinitionStrategy.hidden()
                .define(DefinitionSite.of(DefinitionTarget.class),
                        GeneratedBytes.plainSubclass(DefinitionTarget.class, suffix))
                .type();
    }

    @BeforeEach
    void reset() {
        GenerationCache.invalidate(DefinitionTarget.class);
        GenerationCache.resetStatistics();
    }

    @Test
    @DisplayName("generates once and returns the same class thereafter")
    void cachesByKey() {
        AtomicInteger generations = new AtomicInteger();

        Class<?> first = GenerationCache.computeIfAbsent(DefinitionTarget.class, "key",
                () -> { generations.incrementAndGet(); return generate("K1"); });
        Class<?> second = GenerationCache.computeIfAbsent(DefinitionTarget.class, "key",
                () -> { generations.incrementAndGet(); return generate("K2"); });

        assertSame(first, second);
        assertEquals(1, generations.get(), "the second request must not generate");
    }

    @Test
    @DisplayName("different keys get different classes")
    void distinguishesKeys() {
        Class<?> first = GenerationCache.computeIfAbsent(
                DefinitionTarget.class, "a", () -> generate("A"));
        Class<?> second = GenerationCache.computeIfAbsent(
                DefinitionTarget.class, "b", () -> generate("B"));

        assertNotSame(first, second);
    }

    @Test
    @DisplayName("keys are compared by equals, not identity")
    void comparesKeysByEquals() {
        // A key is typically a record or a list built fresh on each request, so identity
        // comparison would mean a 0% hit rate while looking like it worked.
        Object first = List.of(DefinitionTarget.class, "config");
        Object equalButDistinct = List.of(DefinitionTarget.class, "config");
        assertNotSame(first, equalButDistinct);

        Class<?> cached = GenerationCache.computeIfAbsent(
                DefinitionTarget.class, first, () -> generate("E1"));

        assertSame(cached, GenerationCache.computeIfAbsent(
                DefinitionTarget.class, equalButDistinct, () -> generate("E2")));
    }

    @Test
    @DisplayName("caches for different targets are independent")
    void separatesTargets() {
        Class<?> forTarget = GenerationCache.computeIfAbsent(
                DefinitionTarget.class, "shared", () -> generate("T1"));

        assertTrue(GenerationCache.isCached(DefinitionTarget.class, "shared"));
        assertFalse(GenerationCache.isCached(String.class, "shared"),
                "entries live on the target class itself, not in a global map");
        assertSame(forTarget, GenerationCache.computeIfAbsent(
                DefinitionTarget.class, "shared", () -> generate("T2")));
    }

    @Test
    @DisplayName("counts hits and misses")
    void tracksStatistics() {
        GenerationCache.computeIfAbsent(DefinitionTarget.class, "s", () -> generate("S1"));
        GenerationCache.computeIfAbsent(DefinitionTarget.class, "s", () -> generate("S2"));
        GenerationCache.computeIfAbsent(DefinitionTarget.class, "s", () -> generate("S3"));

        GenerationCache.Statistics statistics = GenerationCache.statistics();

        assertEquals(1, statistics.misses());
        assertEquals(2, statistics.hits());
        assertEquals(2.0 / 3.0, statistics.hitRate(), 0.0001);
        assertTrue(statistics.toString().contains("hit rate"), statistics.toString());
    }

    @Test
    @DisplayName("concurrent requests for one key generate exactly once")
    void generatesOnceUnderContention() throws Exception {
        // CGLib serialised all generation behind a single global lock. Here the lock is per key,
        // so different proxies generate in parallel while duplicates of one still collapse to a
        // single generation.
        int threads = 8;
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        AtomicInteger generations = new AtomicInteger();
        Class<?>[] results = new Class<?>[threads];

        for (int i = 0; i < threads; i++) {
            int index = i;
            new Thread(() -> {
                try {
                    startLine.await();
                    results[index] = GenerationCache.computeIfAbsent(
                            DefinitionTarget.class, "contended", () -> {
                                generations.incrementAndGet();
                                return generate("C" + index);
                            });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            }).start();
        }

        startLine.countDown();
        assertTrue(finished.await(30, TimeUnit.SECONDS), "threads did not finish");

        assertEquals(1, generations.get(), "exactly one thread should have generated");
        for (Class<?> result : results) {
            assertSame(results[0], result, "every thread should see the same class");
        }
    }

    @Test
    @DisplayName("invalidate drops the entries for one target")
    void invalidateClearsOneTarget() {
        GenerationCache.computeIfAbsent(DefinitionTarget.class, "i", () -> generate("I1"));
        assertTrue(GenerationCache.isCached(DefinitionTarget.class, "i"));

        GenerationCache.invalidate(DefinitionTarget.class);

        assertFalse(GenerationCache.isCached(DefinitionTarget.class, "i"));
    }
}
