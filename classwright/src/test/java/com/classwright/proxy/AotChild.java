package com.classwright.proxy;

import com.classwright.ClasswrightException;
import com.classwright.proxy.fixtures.Service;

import java.lang.reflect.Method;

/**
 * Runs in a child JVM to check that an ahead-of-time proxy is really adopted.
 *
 * <p>A separate process because {@link AotProxies} reads its index once per class loader, from the
 * class path as it was at that moment. Any in-process attempt to test this would be testing
 * whichever test happened to touch the class first.
 *
 * <p>Launched by {@code AheadOfTimeIT} with the generated output directory on its class path.
 * Prints what it checked and exits non-zero on the first failure.
 */
public final class AotChild {

    private AotChild() {
    }

    public static void main(String[] args) {
        try {
            run();
            System.out.println("ok");
            System.exit(0);
        } catch (Throwable failure) {
            System.out.println("FAIL " + failure);
            failure.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        require(!AotProxies.isEmpty(Service.class),
                "no ahead-of-time index was found on the class path");
        System.out.println(AotProxies.describe(Service.class));

        String expected = ProxyBlueprint.of(Service.class)
                .callbacks(MethodInterceptor.class)
                .build()
                .generatedClassName();

        // Exactly what an application writes. Nothing here mentions ahead-of-time generation: the
        // point is that ordinary code picks up the pre-generated class without knowing about it.
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Service.class);
        enhancer.setCallback((MethodInterceptor) (self, method, args, superProxy) -> {
            Object result = superProxy.invokeSuper(self, args);
            return result instanceof String text ? text + "!" : result;
        });
        Service proxy = (Service) enhancer.create();

        require(proxy.getClass().getName().equals(expected),
                "expected the pre-generated class " + expected
                        + " but got " + proxy.getClass().getName()
                        + ", so a class was generated at runtime instead of being adopted");
        require(!proxy.getClass().isHidden(),
                "an ahead-of-time proxy must be an ordinary named class; a hidden one cannot exist "
                        + "in a native image");

        require(proxy.greet("world").equals("hello world!"),
                "interception did not run on the pre-generated class");
        require(proxy.add(2, 3) == 5, "primitive dispatch failed on the pre-generated class");
        require(proxy.calls == 2, "invokeSuper did not reach the original implementation");

        // A second Enhancer with the same configuration must reuse it, not generate a rival.
        // Same callback *type*: the type is part of the key, since it is the declared type of the
        // proxy's field. A NoOp here would legitimately be a different configuration.
        Enhancer again = new Enhancer();
        again.setSuperclass(Service.class);
        again.setCallbackTypes(MethodInterceptor.class);
        require(again.createClass().getName().equals(expected),
                "a second identical configuration did not resolve to the same pre-generated class");

        // A configuration that was NOT pre-generated must still work, by generating at runtime.
        Enhancer different = new Enhancer();
        different.setSuperclass(Service.class);
        different.setCallbackTypes(NoOp.class);
        Class<?> generated = different.createClass();
        require(!generated.getName().equals(expected),
                "a different configuration wrongly resolved to the pre-generated class");
        require(generated.isHidden(),
                "a configuration with no pre-generated class should fall back to normal generation");
        System.out.println("ok  fell back to runtime generation for an unregistered configuration");

        checkRoutingFingerprint();
    }

    /**
     * The routing-drift guard: a filter of the right class but the wrong behaviour is refused.
     *
     * <p>Ordering matters and is the point of doing both cases in one process: the drifted filter
     * must be tried <em>first</em>, because verification happens when a class is first adopted in
     * a JVM — once an adoption has succeeded, the class is initialised and trusted, exactly as a
     * generation-cache hit is.
     */
    private static void checkRoutingFingerprint() {
        String expected = ProxyBlueprint.of(Service.class)
                .callbacks(MethodInterceptor.class, NoOp.class)
                .filteredBy(DriftingFilter.class)
                .build()
                .generatedClassName();

        // The index was built with a fresh DriftingFilter — flag down, everything to callback 0.
        // This instance routes everything to callback 1: same filter class, same key, different
        // routing. Adopting the compiled class would silently NoOp every method the build wired
        // to the interceptor.
        Enhancer drifted = new Enhancer();
        drifted.setSuperclass(Service.class);
        drifted.setCallbackTypes(MethodInterceptor.class, NoOp.class);
        drifted.setCallbackFilter(new DriftingFilter(true));
        try {
            drifted.createClass();
            throw new AssertionError("a filter whose routing drifted from the build was adopted "
                    + "silently; the fingerprint check should have refused it");
        } catch (ClasswrightException refusal) {
            require(refusal.getMessage().contains("routing"),
                    "the refusal should name the routing drift: " + refusal.getMessage());
            require(refusal.getMessage().contains(expected),
                    "the refusal should name the proxy class: " + refusal.getMessage());
            System.out.println("ok  refused a filter whose routing drifted from the build");
        }

        // The faithful instance routes as the build did, so the very same entry is adopted.
        MethodInterceptor appending = (self, method, args, superProxy) -> {
            Object result = superProxy.invokeSuper(self, args);
            return result instanceof String text ? text + "!" : result;
        };
        Enhancer faithful = new Enhancer();
        faithful.setSuperclass(Service.class);
        faithful.setCallbacks(appending, NoOp.INSTANCE);
        faithful.setCallbackFilter(new DriftingFilter(false));
        Service proxy = (Service) faithful.create();
        require(proxy.getClass().getName().equals(expected),
                "the matching filter should have adopted the pre-generated class, but got "
                        + proxy.getClass().getName());
        require(proxy.greet("aot").equals("hello aot!"),
                "interception did not run on the fingerprint-verified class");
        System.out.println("ok  adopted the same entry once the routing matched");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * A filter whose routing depends on instance state the key cannot see.
     *
     * <p>{@code equals} is by class alone — deliberately, because that is what makes the drift
     * silent: the blueprint key matches, only the fingerprint can tell the difference. The build
     * instantiates it through the no-argument constructor, flag down.
     */
    public static final class DriftingFilter implements CallbackFilter {

        private final boolean flipped;

        public DriftingFilter() {
            this(false);
        }

        public DriftingFilter(boolean flipped) {
            this.flipped = flipped;
        }

        @Override
        public int accept(Method method) {
            return flipped ? 1 : 0;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DriftingFilter;
        }

        @Override
        public int hashCode() {
            return DriftingFilter.class.hashCode();
        }
    }
}
