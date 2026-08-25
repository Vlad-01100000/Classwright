package com.classwright.runtime;

import com.classwright.runtime.fixtures.DefinitionTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that generated classes can actually be unloaded.
 *
 * <p>This is the test the whole {@code runtime} package exists to pass, and the one CGLib would
 * fail. Its generated classes were defined into the target's class loader and recorded there
 * permanently, so an application that kept creating proxies kept consuming metaspace it never got
 * back, and a container redeploying an application leaked the entire application class loader.
 *
 * <p>The measurement is the same one in {@code docs/RESEARCH.md} §2, promoted from a throwaway
 * probe into a regression test, so that this property cannot quietly stop being true.
 *
 * <p>Thresholds are deliberately loose. Collection is not synchronous and {@code System.gc()} is a
 * request rather than a command, so asserting exactly 100% would make the suite flaky for no gain.
 * The distinction being tested is between "essentially all" and "none at all", which is not subtle.
 */
class ClassUnloadingIT {

    /** Enough for the difference between reclaiming and not reclaiming to be unmistakable. */
    private static final int CLASS_COUNT = 2_000;

    /** Collection is asynchronous; allow several attempts before concluding anything. */
    private static final int COLLECTION_ATTEMPTS = 6;

    @Test
    @DisplayName("hidden classes are reclaimed while their defining loader stays alive")
    void hiddenClassesAreReclaimed() throws Exception {
        DefinitionSite site = DefinitionSite.of(DefinitionTarget.class);
        byte[] bytes = GeneratedBytes.plainSubclass(DefinitionTarget.class, "U");

        long unloadedBefore = unloadedClassCount();
        long metaspaceBefore = metaspaceUsed();

        for (int i = 0; i < CLASS_COUNT; i++) {
            // Each call defines a distinct class even from identical bytes, because a hidden class
            // is not registered under its name. Nothing keeps the result, which is the point.
            DefinitionStrategy.hidden().define(site, bytes);
        }

        long metaspacePeak = metaspaceUsed();
        forceCollection();
        long reclaimed = unloadedClassCount() - unloadedBefore;
        long metaspaceAfter = metaspaceUsed();

        assertTrue(reclaimed >= CLASS_COUNT * 0.9,
                () -> String.format("expected nearly all of %d hidden classes to be reclaimed, "
                                + "got %d. Metaspace %.1f -> %.1f -> %.1f MB",
                        CLASS_COUNT, reclaimed, mb(metaspaceBefore), mb(metaspacePeak),
                        mb(metaspaceAfter)));

        assertFalse(DefinitionTarget.class.getClassLoader() == null,
                "the defining loader is still alive, which is what makes this interesting");
    }

    @Test
    @DisplayName("named classes are never reclaimed, which is CGLib's leak")
    void namedClassesAreNeverReclaimed() throws Exception {
        // Documenting the cost of the compatibility strategy, not a defect. An ordinary class is
        // recorded in its loader's class table for the life of the loader.
        DefinitionSite site = DefinitionSite.of(DefinitionTarget.class);

        long unloadedBefore = unloadedClassCount();
        for (int i = 0; i < 200; i++) {
            DefinitionStrategy.named().define(site,
                    GeneratedBytes.plainSubclass(DefinitionTarget.class, "Named" + i));
        }
        forceCollection();

        assertEquals(0, unloadedClassCount() - unloadedBefore,
                "named classes cannot be unloaded while their loader lives");
    }

    @Test
    @DisplayName("child-loader classes are reclaimed once the loader is dropped")
    void childLoaderClassesAreReclaimedWithTheirLoader() throws Exception {
        // Dedicated loaders, constructed and dropped by this test. An earlier revision defined
        // through the site-decided form and asserted on the global unloaded-class count — but
        // that form places classes into per-parent cohort loaders SHARED with every other
        // definition in the JVM, and "dropping the loader" was a fiction: the first cohort this
        // test touched could be pinned by a neighbouring test's still-live class, which made the
        // assertion fail whenever the whole suite ran in one JVM (an IDE run, most visibly).
        // Ownership semantics can only be asserted on loaders the test actually owns.
        DefinitionSite site = DefinitionSite.of(DefinitionTarget.class);
        java.util.List<WeakReference<Class<?>>> classes = new java.util.ArrayList<>();
        for (int loaderIndex = 0; loaderIndex < 4; loaderIndex++) {
            DefinitionStrategy strategy =
                    new DefinitionStrategy.ChildLoader(DefinitionTarget.class.getClassLoader());
            for (int i = 0; i < 50; i++) {
                classes.add(new WeakReference<>(strategy.define(site,
                        GeneratedBytes.plainSubclass(DefinitionTarget.class,
                                "Child" + loaderIndex + "x" + i)).type()));
            }
            Reference.reachabilityFence(strategy);
        }

        assertTrue(awaitMostlyCleared(classes, 180),
                () -> "dropping the loaders should take their classes with them; only "
                        + clearedCount(classes) + " of " + classes.size() + " were reclaimed");
    }

    @Test
    @DisplayName("site-decided child-loader cohorts are reclaimed as they rotate")
    void siteDecidedCohortsAreReclaimedAsTheyRotate() throws Exception {
        // The shared form, asserted only on what this test can own. Its classes land in
        // per-parent cohort loaders: the first cohort may be shared with — and pinned by — an
        // earlier test's still-reachable class, and the newest is the one the next definition
        // anywhere in the JVM reuses, so neither is this test's to drop. The cohorts in
        // between are: filled entirely with this test's classes and referenced by nothing
        // else once retired, they must collect whatever else the suite JVM holds. With 200
        // definitions at the default cohort size of 64, that is well over half. A retention
        // defect of the strategy's own making — a strong cohort registry, a retired loader
        // kept reachable — would hold every one of them and fail this unmistakably.
        DefinitionSite site = DefinitionSite.of(DefinitionTarget.class);
        DefinitionStrategy strategy = DefinitionStrategy.childLoader();
        java.util.List<WeakReference<Class<?>>> classes = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            classes.add(new WeakReference<>(strategy.define(site,
                    GeneratedBytes.plainSubclass(DefinitionTarget.class, "Rotated" + i)).type()));
        }

        assertTrue(awaitMostlyCleared(classes, 100),
                () -> "rotated-out cohorts hold only this test's classes and must clear; only "
                        + clearedCount(classes) + " of " + classes.size() + " were reclaimed");
    }

    @Test
    @DisplayName("a live instance keeps its class loaded, and only that")
    void classLivesExactlyAsLongAsItsInstances() throws Throwable {
        // The property that lets GenerationCache hold only weak references. If it did not hold,
        // weak caching would evict classes that were still in use.
        DefinedClass defined = DefinitionStrategy.hidden().define(
                DefinitionSite.of(DefinitionTarget.class),
                GeneratedBytes.plainSubclass(DefinitionTarget.class, "Lifetime"));

        Object instance = defined.constructor().invoke();
        WeakReference<Class<?>> classReference = new WeakReference<>(defined.type());
        defined = null;

        forceCollection();
        assertTrue(classReference.get() != null,
                "a live instance must keep its class loaded");
        Reference.reachabilityFence(instance);

        instance = null;
        assertTrue(awaitCleared(classReference),
                "once the last instance is gone the class should become collectible");
    }

    @Test
    @DisplayName("an enqueued cache reference cannot pin a discarded loader; no drain required")
    void collectedValueReferencesDieWithTheirAnchor() throws Exception {
        // The scenario a static reference queue got wrong. A generated class is collected while
        // the application still lives; the collector enqueues its cleanup reference, which
        // strongly holds the cache key so it can remove its own entry — and a stored key
        // strongly holds the anchor. With the queue as a static GC root, the chain
        //   queue -> reference -> key -> anchor -> application loader
        // kept the discarded loader reachable until some FUTURE cache operation drained the
        // queue. Unloading must never depend on future Classwright activity, so this test makes
        // no cache call of any kind after the drop. With the queue owned by the anchor's own
        // cache state, every link of that chain lives inside the anchor's graph and dies with it.
        PlantingLoader loader = new PlantingLoader(DefinitionTarget.class);
        Class<?> anchor = loader.planted();
        record AnchorKey(Class<?> anchor) {
        }

        // The generated class itself lives beside the ordinary fixture — where it goes is
        // irrelevant to the retention chain; what matters is that it is collectible while the
        // key (held by the planted anchor's cache) strongly references the planted anchor,
        // exactly as a stored ProxyKey references its superclass.
        Class<?> generated = GenerationCache.computeIfAbsent(anchor, new AnchorKey(anchor),
                () -> DefinitionStrategy.hidden().define(DefinitionSite.of(DefinitionTarget.class),
                        GeneratedBytes.plainSubclass(DefinitionTarget.class, "QueueRetention"))
                        .type());
        WeakReference<Class<?>> generatedReference = new WeakReference<>(generated);
        generated = null;

        assertTrue(awaitCleared(generatedReference),
                "nothing holds the generated class; it must clear while the anchor lives");

        WeakReference<ClassLoader> loaderReference = new WeakReference<>(loader);
        anchor = null;
        loader = null;

        assertTrue(awaitCleared(loaderReference),
                "the discarded loader must become collectible without any further cache "
                        + "activity; if it does not, a queued value reference is holding the "
                        + "stored key -> anchor -> loader chain from outside the anchor's graph");
    }

    @Test
    @DisplayName("a BulkBean cache key cannot pin a foreign property type's loader")
    void bulkBeanKeyReleasesForeignPropertyTypes() throws Exception {
        // The rule every GenerationCache key must honour: the entry is anchored on one class
        // (here the bean class, which lives as long as this test loader), so the key must not
        // strongly retain some OTHER class for that lifetime. The sharpest case is a skipped
        // slot — both accessor names null — where the property type is part of the positional
        // key identity but the generated bytecode never references it: the stored key was the
        // only thing pinning it. As with the queue test above, no cache operation of any kind
        // happens after the drop; unloading must not depend on future Classwright activity.
        PlantingLoader loader = new PlantingLoader(DefinitionTarget.class);
        Class<?> foreignType = loader.planted();

        com.classwright.beans.BulkBean.create(DefinitionTarget.class,
                new String[]{null}, new String[]{null}, new Class<?>[]{foreignType});

        WeakReference<ClassLoader> loaderReference = new WeakReference<>(loader);
        foreignType = null;
        loader = null;

        assertTrue(awaitCleared(loaderReference),
                "the retained bulk key must hold foreign property types weakly; strongly held, "
                        + "the anchor-lived key pins the foreign loader for the anchor's life");
    }

    /** Defines its own copy of one seed class, so the copy's lifetime is this loader's. */
    private static final class PlantingLoader extends ClassLoader {

        private final Class<?> planted;

        PlantingLoader(Class<?> seed) throws Exception {
            super("classwright-retention-test", ClassUnloadingIT.class.getClassLoader());
            String resource = seed.getName().replace('.', '/') + ".class";
            byte[] bytes;
            try (java.io.InputStream stream = getParent().getResourceAsStream(resource)) {
                if (stream == null) {
                    throw new IllegalStateException("cannot read " + resource);
                }
                bytes = stream.readAllBytes();
            }
            planted = defineClass(seed.getName(), bytes, 0, bytes.length);
        }

        Class<?> planted() {
            return planted;
        }
    }

    @Test
    @DisplayName("cache entries clear themselves when their class is collected")
    void cacheEntriesClearThemselves() throws Exception {
        // The cache holds weak references, so it never keeps a class alive on its own. A strong
        // cache here would silently make every generated class permanent.
        GenerationCache.invalidate(DefinitionTarget.class);
        Object key = "self-clearing";

        GenerationCache.computeIfAbsent(DefinitionTarget.class, key,
                () -> DefinitionStrategy.hidden().define(
                        DefinitionSite.of(DefinitionTarget.class),
                        GeneratedBytes.plainSubclass(DefinitionTarget.class, "Cached")).type());

        assertTrue(GenerationCache.isCached(DefinitionTarget.class, key));

        forceCollection();

        assertFalse(GenerationCache.isCached(DefinitionTarget.class, key),
                "nothing holds the generated class, so the cache entry should have cleared");
        assertEquals(1, GenerationCache.purgeCollectedEntries(DefinitionTarget.class));
    }

    @Test
    @DisplayName("closing a scope releases everything it generated")
    void scopeCloseReleasesClasses() throws Exception {
        long unloadedBefore = unloadedClassCount();

        ClasswrightScope scope = ClasswrightScope.open("test-scope");
        for (int i = 0; i < 200; i++) {
            scope.define(DefinitionTarget.class,
                    GeneratedBytes.plainSubclass(DefinitionTarget.class, "Scoped"));
        }
        assertEquals(200, scope.size());
        assertTrue(scope.isFullyReclaimable());

        forceCollection();
        assertTrue(unloadedClassCount() - unloadedBefore < 100,
                "an open scope should hold its classes loaded");

        scope.close();
        forceCollection();

        assertTrue(unloadedClassCount() - unloadedBefore >= 180,
                "closing the scope should release them");
    }

    @Test
    @DisplayName("a scope containing a named class reports that it cannot be fully reclaimed")
    void scopeReportsUnreclaimableContents() {
        // Worth surfacing: one named class silently defeats a container's undeploy cleanup, and
        // the failure mode is a slow leak rather than an error.
        try (ClasswrightScope scope = ClasswrightScope.open("mixed")) {
            scope.define(ClassDefiner.alongside(DefinitionTarget.class),
                    GeneratedBytes.plainSubclass(DefinitionTarget.class, "Mix1"));
            assertTrue(scope.isFullyReclaimable());

            scope.define(ClassDefiner.using(DefinitionTarget.class, DefinitionStrategy.named()),
                    GeneratedBytes.plainSubclass(DefinitionTarget.class, "MixNamed"));

            assertFalse(scope.isFullyReclaimable());
        }
    }

    // ==========================================================================================

    private static long unloadedClassCount() {
        ClassLoadingMXBean classLoading = ManagementFactory.getClassLoadingMXBean();
        return classLoading.getUnloadedClassCount();
    }

    private static long metaspaceUsed() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getName().contains("Metaspace")) {
                return pool.getUsage().getUsed();
            }
        }
        return 0;
    }

    private static double mb(long bytes) {
        return bytes / 1_048_576.0;
    }

    /** Class unloading needs a full collection cycle, and one request may not deliver it. */
    private static void forceCollection() throws InterruptedException {
        for (int attempt = 0; attempt < COLLECTION_ATTEMPTS; attempt++) {
            System.gc();
            Thread.sleep(100);
        }
    }

    /** Collects until at least {@code threshold} references clear, or attempts run out. */
    private static boolean awaitMostlyCleared(java.util.List<WeakReference<Class<?>>> references,
                                              int threshold) throws InterruptedException {
        for (int attempt = 0; attempt < COLLECTION_ATTEMPTS * 2; attempt++) {
            if (clearedCount(references) >= threshold) {
                return true;
            }
            System.gc();
            Thread.sleep(100);
        }
        return clearedCount(references) >= threshold;
    }

    private static long clearedCount(java.util.List<WeakReference<Class<?>>> references) {
        return references.stream().filter(reference -> reference.get() == null).count();
    }

    /** Collects until the reference clears, rather than assuming one pass is enough. */
    private static boolean awaitCleared(WeakReference<?> reference) throws InterruptedException {
        for (int attempt = 0; attempt < COLLECTION_ATTEMPTS * 2; attempt++) {
            if (reference.get() == null) {
                return true;
            }
            System.gc();
            Thread.sleep(100);
        }
        return reference.get() == null;
    }
}
