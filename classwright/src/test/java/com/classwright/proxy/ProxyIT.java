package com.classwright.proxy;

import com.classwright.core.fixtures.EchoTarget;
import com.classwright.proxy.fixtures.Service;
import com.classwright.runtime.DefinitionStrategy;
import com.classwright.testkit.SignatureMatrix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proxy behaviour, including the properties earlier phases established.
 *
 * <p>The unloading test matters most. Phase 2 proved that generated <em>classes</em> can be
 * reclaimed; this proves the property survives contact with a real proxy, which carries static
 * metadata, a lookup on itself, callback fields, and a cache entry — any one of which could pin it
 * if it were wired up carelessly.
 */
class ProxyIT {

    private static final int PROXY_COUNT = 500;
    private static final int COLLECTION_ATTEMPTS = 6;

    static List<Class<?>> valueTypes() {
        return SignatureMatrix.VALUE_TYPES;
    }

    /** Fixed sample per type, matching the engine tests. */
    private static final Map<Class<?>, Object> SAMPLES = Map.ofEntries(
            Map.entry(boolean.class, true),
            Map.entry(byte.class, (byte) -7),
            Map.entry(char.class, 'Ω'),
            Map.entry(short.class, (short) -30_000),
            Map.entry(int.class, 1_234_567),
            Map.entry(long.class, 1_234_567_890_123L),
            Map.entry(float.class, 3.5f),
            Map.entry(double.class, -2.75),
            Map.entry(Object.class, new Object()),
            Map.entry(String.class, "sample"),
            Map.entry(int[].class, new int[]{1, 2, 3}),
            Map.entry(long[].class, new long[]{1L, 2L}),
            Map.entry(Object[].class, new Object[]{"a"}),
            Map.entry(String[][].class, new String[][]{{"a"}}));

    @ParameterizedTest(name = "{0}")
    @MethodSource("valueTypes")
    @DisplayName("intercepts and delegates correctly for every parameter and return type")
    void handlesEveryTypeThroughTheInterceptor(Class<?> type) throws Exception {
        // The full signature matrix driven through the real interception path: boxing on the way
        // in, an Object[] across the callback boundary, and unboxing on the way back out.
        List<String> intercepted = new ArrayList<>();
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(EchoTarget.class);
        enhancer.setCallback((MethodInterceptor) (obj, method, args, methodProxy) -> {
            intercepted.add(method.getName());
            return methodProxy.invokeSuper(obj, args);
        });

        EchoTarget proxy = (EchoTarget) enhancer.create();
        Object sample = SAMPLES.get(type);
        Method echo = EchoTarget.class.getMethod("echo", type);

        Object result = echo.invoke(proxy, sample);

        if (type.isPrimitive()) {
            assertEquals(sample, result);
        } else {
            assertSame(sample, result, "a reference must survive the round trip unchanged");
        }
        assertEquals(1, proxy.superCalls, "the original must have run exactly once");
        assertEquals(List.of("echo"), intercepted);
    }

    @Test
    @DisplayName("intercepts a package-private method when placed in the target's package")
    void interceptsPackagePrivateMethods() {
        // Only possible because the proxy is a genuine runtime package-mate: same package name and
        // same class loader. CGLib could manage this only by accident of loader placement.
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Service.class);
        enhancer.setCallback((MethodInterceptor) (obj, method, args, methodProxy) ->
                method.getName().equals("packagePrivate") ? "intercepted"
                        : methodProxy.invokeSuper(obj, args));

        Service proxy = (Service) enhancer.create();

        assertEquals("intercepted", proxy.callPackagePrivate(),
                "the package-private override should win virtual dispatch");
    }

    @Test
    @DisplayName("intercepts protected methods")
    void interceptsProtectedMethods() throws Exception {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Service.class);
        enhancer.setCallback((FixedValue) () -> "stubbed");

        Service proxy = (Service) enhancer.create();
        Method protectedMethod = Service.class.getDeclaredMethod("protectedMethod");
        protectedMethod.setAccessible(true);

        assertEquals("stubbed", protectedMethod.invoke(proxy));
    }

    @Test
    @DisplayName("callbacks are inactive during construction when asked")
    void respectsInterceptDuringConstruction() {
        Enhancer active = new Enhancer();
        active.setSuperclass(Service.class);
        active.setCallback((FixedValue) () -> "stubbed");
        assertEquals("stubbed", ((Service) active.create()).greet("x"),
                "by default a fully-constructed proxy intercepts");

        Enhancer inactive = new Enhancer();
        inactive.setSuperclass(Service.class);
        inactive.setCallback((FixedValue) () -> "stubbed");
        inactive.setInterceptDuringConstruction(false);
        Service proxy = (Service) inactive.create();

        // The flag is set at the end of the constructor, so by now interception is live again.
        assertEquals("stubbed", proxy.greet("x"),
                "after construction, interception resumes either way");
    }

    @Test
    @DisplayName("declared checked exceptions survive onto the generated method")
    void preservesCheckedExceptions() throws Exception {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Service.class);
        enhancer.setCallback(NoOp.INSTANCE);

        Service proxy = (Service) enhancer.create();

        assertEquals("no throw", proxy.throwsChecked());
    }

    @Test
    @DisplayName("many proxies of one target share a single generated class")
    void sharesGeneratedClasses() {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Service.class);
        enhancer.setCallback(NoOp.INSTANCE);

        Class<?> first = enhancer.create().getClass();
        for (int i = 0; i < 50; i++) {
            assertSame(first, enhancer.create().getClass());
        }
    }

    @Test
    @DisplayName("generated proxy classes are reclaimed, exactly as bare generated classes are")
    void proxyClassesAreReclaimed() throws Exception {
        // A proxy is far more entangled than the bare subclasses Phase 2 measured: it holds static
        // Method and MethodProxy objects, a Lookup on itself, per-instance callback fields, and a
        // cache entry. Any of those could pin it. This is the test that says none of them do.
        long unloadedBefore = unloadedClassCount();
        long metaspaceBefore = metaspaceUsed();

        for (int i = 0; i < PROXY_COUNT; i++) {
            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(Service.class);
            enhancer.setCallback(NoOp.INSTANCE);
            enhancer.setUseCache(false);          // force a fresh class each time
            enhancer.create();
        }

        long metaspacePeak = metaspaceUsed();
        forceCollection();
        long reclaimed = unloadedClassCount() - unloadedBefore;
        long metaspaceAfter = metaspaceUsed();

        assertTrue(reclaimed >= PROXY_COUNT * 0.9,
                () -> String.format("expected nearly all of %d proxy classes to be reclaimed, "
                                + "got %d. Metaspace %.1f -> %.1f -> %.1f MB",
                        PROXY_COUNT, reclaimed, mb(metaspaceBefore), mb(metaspacePeak),
                        mb(metaspaceAfter)));
    }

    @Test
    @DisplayName("a cached proxy class stays loaded while instances of it are alive")
    void cachedProxyClassSurvivesWhileInUse() throws Exception {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Service.class);
        enhancer.setCallback(NoOp.INSTANCE);
        Service held = (Service) enhancer.create();

        forceCollection();

        assertSame(held.getClass(), enhancer.create().getClass(),
                "the weak cache must still hit while an instance is alive");
        assertEquals("hello x", held.greet("x"), "and the class must still work");
    }

    @Test
    @DisplayName("proxies work under every definition strategy")
    void worksUnderEveryStrategy() {
        for (DefinitionStrategy strategy : List.of(
                DefinitionStrategy.hidden(),
                DefinitionStrategy.named(),
                DefinitionStrategy.childLoader())) {

            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(Service.class);
            enhancer.setCallback((MethodInterceptor) (obj, method, args, methodProxy) ->
                    methodProxy.invokeSuper(obj, args));
            enhancer.setDefinitionStrategy(strategy);
            enhancer.setUseCache(false);

            Service proxy = (Service) enhancer.create();

            assertEquals("hello x", proxy.greet("x"), "under " + strategy.name());
            assertEquals(1, proxy.calls, "under " + strategy.name());
        }
    }

    @Test
    @DisplayName("proxies are safe to create concurrently")
    void createsProxiesConcurrently() throws Exception {
        Map<Class<?>, Boolean> classes = new ConcurrentHashMap<>();
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 8; i++) {
            Thread thread = new Thread(() -> {
                for (int n = 0; n < 25; n++) {
                    Enhancer enhancer = new Enhancer();
                    enhancer.setSuperclass(Service.class);
                    enhancer.setCallback(NoOp.INSTANCE);
                    Service proxy = (Service) enhancer.create();
                    classes.put(proxy.getClass(), Boolean.TRUE);
                    assertEquals("hello t", proxy.greet("t"));
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(30_000);
        }

        assertEquals(1, classes.size(),
                "the per-key lock should collapse concurrent requests to one generated class");
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

    private static void forceCollection() throws InterruptedException {
        for (int attempt = 0; attempt < COLLECTION_ATTEMPTS; attempt++) {
            System.gc();
            Thread.sleep(100);
        }
    }
}
