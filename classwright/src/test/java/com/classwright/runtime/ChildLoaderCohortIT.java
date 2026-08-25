package com.classwright.runtime;

import com.classwright.runtime.fixtures.DefinitionTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The cohort limit of the site-decided child-loader fallback, which needs its own JVM: the limit
 * is read once into {@code static final} state, so it cannot be shrunk inside the ordinary suite.
 * The {@code cohort-limit} Failsafe execution forks this class with
 * {@code -Dclasswright.childLoaderCohort=1}; under the default execution the assumption below
 * skips everything.
 *
 * <p>Why the bound must be strict and not merely typical: the documentation sells it as a memory
 * guarantee — one live generated class pins at most a cohort's worth of neighbours in its loader.
 * Before slots were reserved at selection time, N threads could each observe the same nearly-full
 * loader and all define into it, quietly exceeding the bound under bursts. With the limit forced
 * to 1, any two classes sharing a loader is a regression, however the threads interleave.
 */
class ChildLoaderCohortIT {

    private static final int THREADS = 16;
    private static final int ROUNDS = 8;

    @Test
    @DisplayName("under contention, no cohort loader ever exceeds the configured limit")
    void cohortLimitHoldsUnderContention() throws Exception {
        assumeTrue(Integer.getInteger("classwright.childLoaderCohort", 64) == 1,
                "runs only in the cohort-limit execution, which sets the cohort property to 1");

        DefinitionStrategy strategy = DefinitionStrategy.childLoader();
        DefinitionSite site = DefinitionSite.of(DefinitionTarget.class);
        Map<ClassLoader, AtomicInteger> occupancy = new ConcurrentHashMap<>();
        AtomicInteger definitions = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                CountDownLatch start = new CountDownLatch(1);
                int roundTag = round;
                List<Future<Object>> futures = IntStream.range(0, THREADS)
                        .mapToObj(thread -> pool.submit(() -> {
                            byte[] bytes = GeneratedBytes.plainSubclass(DefinitionTarget.class,
                                    "Cohort" + roundTag + "T" + thread);
                            start.await();
                            Class<?> defined = strategy.define(site, bytes).type();
                            occupancy.computeIfAbsent(defined.getClassLoader(),
                                    loader -> new AtomicInteger()).incrementAndGet();
                            definitions.incrementAndGet();
                            return null;
                        }))
                        .toList();
                start.countDown();
                for (Future<Object> future : futures) {
                    future.get(30, TimeUnit.SECONDS);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(THREADS * ROUNDS, definitions.get(), "every definition must have completed");
        occupancy.forEach((loader, count) -> assertTrue(count.get() <= 1,
                "cohort limit 1 was exceeded: " + count.get() + " classes landed in " + loader));
        assertEquals(THREADS * ROUNDS, occupancy.size(),
                "with a cohort of one, every definition must get its own loader");
    }
}
