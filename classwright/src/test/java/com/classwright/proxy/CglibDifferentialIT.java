package com.classwright.proxy;

import com.classwright.proxy.fixtures.Service;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the same scenario through CGLib and through Classwright, and requires the same answer.
 *
 * <p>The point of a compatibility layer is that migrating code behaves identically, and the only
 * way to know is to ask the original. Reading CGLib's documentation would not settle what it does
 * with a {@code null} returned from an interceptor for a primitive method, or whether an exception
 * from an interceptor is wrapped — but running it does.
 *
 * <p>Each test drives the two libraries through the same closure and compares outcomes, so a
 * divergence names the scenario rather than a library.
 *
 * <p>Runs under Failsafe with {@code --add-opens java.base/java.lang=ALL-UNNAMED}, which CGLib
 * 3.3.0 needs on JDK 16 and later. That requirement is part of what this test documents: it is a
 * permanent tax on every application still depending on CGLib.
 */
class CglibDifferentialIT {

    /** Builds a proxy with CGLib. */
    private static Service cglibProxy(net.sf.cglib.proxy.Callback callback) {
        net.sf.cglib.proxy.Enhancer enhancer = new net.sf.cglib.proxy.Enhancer();
        enhancer.setSuperclass(Service.class);
        enhancer.setCallback(callback);
        return (Service) enhancer.create();
    }

    /** Builds the equivalent proxy with Classwright. */
    private static Service classwrightProxy(Callback callback) {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Service.class);
        enhancer.setCallback(callback);
        return (Service) enhancer.create();
    }

    /** Runs an action against both proxies and requires the same result. */
    private static void assertSameBehaviour(String scenario, Service cglib, Service classwright,
                                            Function<Service, Object> action) {
        Object fromCglib = outcomeOf(cglib, action);
        Object fromClasswright = outcomeOf(classwright, action);

        assertEquals(fromCglib, fromClasswright, scenario);
    }

    /** The result, or a description of the failure, so exceptions compare as values. */
    private static Object outcomeOf(Service proxy, Function<Service, Object> action) {
        try {
            return action.apply(proxy);
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            return "threw " + root.getClass().getName() + ": " + root.getMessage();
        }
    }

    @Test
    @DisplayName("an interceptor that delegates behaves identically")
    void delegatingInterceptor() {
        List<String> cglibSeen = new ArrayList<>();
        List<String> classwrightSeen = new ArrayList<>();

        Service cglib = cglibProxy((net.sf.cglib.proxy.MethodInterceptor)
                (obj, method, args, proxy) -> {
                    cglibSeen.add(method.getName());
                    return proxy.invokeSuper(obj, args);
                });
        Service classwright = classwrightProxy((MethodInterceptor)
                (obj, method, args, methodProxy) -> {
                    classwrightSeen.add(method.getName());
                    return methodProxy.invokeSuper(obj, args);
                });

        assertSameBehaviour("greet", cglib, classwright, service -> service.greet("world"));
        assertSameBehaviour("add", cglib, classwright, service -> service.add(3, 4));
        assertSameBehaviour("total", cglib, classwright, service -> service.total(10L, 2, 3.9));
        assertSameBehaviour("flag", cglib, classwright, Service::flag);

        assertEquals(cglibSeen, classwrightSeen, "both should see the same methods, in order");
        assertEquals(cglib.calls, classwright.calls, "both should have run the original as often");
    }

    @Test
    @DisplayName("an interceptor that replaces the result behaves identically")
    void replacingInterceptor() {
        Service cglib = cglibProxy((net.sf.cglib.proxy.MethodInterceptor)
                (obj, method, args, proxy) -> "replaced");
        Service classwright = classwrightProxy((MethodInterceptor)
                (obj, method, args, methodProxy) -> "replaced");

        assertSameBehaviour("greet", cglib, classwright, service -> service.greet("x"));
        assertEquals(0, cglib.calls);
        assertEquals(0, classwright.calls);
    }

    @Test
    @DisplayName("an exception from the interceptor propagates the same way")
    void interceptorException() {
        Service cglib = cglibProxy((net.sf.cglib.proxy.MethodInterceptor)
                (obj, method, args, proxy) -> {
                    throw new IllegalStateException("from the interceptor");
                });
        Service classwright = classwrightProxy((MethodInterceptor)
                (obj, method, args, methodProxy) -> {
                    throw new IllegalStateException("from the interceptor");
                });

        assertSameBehaviour("greet throws", cglib, classwright, service -> service.greet("x"));
    }

    @Test
    @DisplayName("returning the wrong type fails the same way")
    void wrongReturnType() {
        // Both cast rather than check, so both should surface a ClassCastException.
        Service cglib = cglibProxy((net.sf.cglib.proxy.MethodInterceptor)
                (obj, method, args, proxy) -> "not an int");
        Service classwright = classwrightProxy((MethodInterceptor)
                (obj, method, args, methodProxy) -> "not an int");

        assertSameBehaviour("add returns a String", cglib, classwright,
                service -> service.add(1, 2));
    }

    @Test
    @DisplayName("returning null for a primitive fails the same way")
    void nullForPrimitive() {
        Service cglib = cglibProxy((net.sf.cglib.proxy.MethodInterceptor)
                (obj, method, args, proxy) -> null);
        Service classwright = classwrightProxy((MethodInterceptor)
                (obj, method, args, methodProxy) -> null);

        assertSameBehaviour("add returns null", cglib, classwright, service -> service.add(1, 2));
        assertSameBehaviour("greet returns null", cglib, classwright,
                service -> service.greet("x"));
    }

    @Test
    @DisplayName("NoOp behaves identically")
    void noOp() {
        Service cglib = cglibProxy(net.sf.cglib.proxy.NoOp.INSTANCE);
        Service classwright = classwrightProxy(NoOp.INSTANCE);

        assertSameBehaviour("greet", cglib, classwright, service -> service.greet("x"));
        assertEquals(cglib.calls, classwright.calls);
    }

    @Test
    @DisplayName("FixedValue behaves identically")
    void fixedValue() {
        Service cglib = cglibProxy((net.sf.cglib.proxy.FixedValue) () -> "fixed");
        Service classwright = classwrightProxy((FixedValue) () -> "fixed");

        assertSameBehaviour("greet", cglib, classwright, service -> service.greet("x"));
        assertEquals(0, cglib.calls);
        assertEquals(0, classwright.calls);
    }

    @Test
    @DisplayName("final methods are left alone by both")
    void finalMethodsAreNotIntercepted() {
        Service cglib = cglibProxy((net.sf.cglib.proxy.MethodInterceptor)
                (obj, method, args, proxy) -> "intercepted");
        Service classwright = classwrightProxy((MethodInterceptor)
                (obj, method, args, methodProxy) -> "intercepted");

        assertEquals("final", cglib.cannotOverride());
        assertEquals("final", classwright.cannotOverride());
        assertSameBehaviour("cannotOverride", cglib, classwright, Service::cannotOverride);
    }

    @Test
    @DisplayName("both intercept toString, and the interceptor sees the same Method")
    void interceptsObjectMethods() {
        List<String> cglibMethods = new ArrayList<>();
        List<String> classwrightMethods = new ArrayList<>();

        Service cglib = cglibProxy((net.sf.cglib.proxy.MethodInterceptor)
                (obj, method, args, proxy) -> {
                    cglibMethods.add(method.getName());
                    return "described";
                });
        Service classwright = classwrightProxy((MethodInterceptor)
                (obj, method, args, methodProxy) -> {
                    classwrightMethods.add(method.getName());
                    return "described";
                });

        assertEquals("described", cglib.toString());
        assertEquals("described", classwright.toString());
        assertEquals(cglibMethods, classwrightMethods);
    }

    @Test
    @DisplayName("the Method handed to an interceptor identifies the same method")
    void passesTheSameMethod() {
        List<Method> cglibMethods = new ArrayList<>();
        List<Method> classwrightMethods = new ArrayList<>();

        cglibProxy((net.sf.cglib.proxy.MethodInterceptor) (obj, method, args, proxy) -> {
            cglibMethods.add(method);
            return null;
        }).greet("x");
        classwrightProxy((MethodInterceptor) (obj, method, args, methodProxy) -> {
            classwrightMethods.add(method);
            return null;
        }).greet("x");

        assertEquals(1, cglibMethods.size());
        assertEquals(1, classwrightMethods.size());
        assertEquals(cglibMethods.get(0), classwrightMethods.get(0),
                "both should report the method as declared on Service");
        assertSame(Service.class, classwrightMethods.get(0).getDeclaringClass());
    }

    @Test
    @DisplayName("both produce a subclass whose name marks it as generated")
    void bothProduceRecognisableSubclasses() {
        Service cglib = cglibProxy(net.sf.cglib.proxy.NoOp.INSTANCE);
        Service classwright = classwrightProxy(NoOp.INSTANCE);

        assertSame(Service.class, cglib.getClass().getSuperclass());
        assertSame(Service.class, classwright.getClass().getSuperclass());
        assertTrue(cglib.getClass().getName().contains("$$"));
        assertTrue(classwright.getClass().getName().contains("$$"),
                "framework heuristics look for this marker in both");
    }

    @Test
    @DisplayName("the classes differ exactly where the design differs")
    void differsWhereItIsMeantTo() throws Exception {
        // The one difference that is deliberate rather than incidental, and the reason the library
        // exists. CGLib's proxy is an ordinary named class: resolvable, and permanent. Classwright's
        // is hidden: not resolvable, and collectable.
        Service cglib = cglibProxy(net.sf.cglib.proxy.NoOp.INSTANCE);
        Service classwright = classwrightProxy(NoOp.INSTANCE);

        assertTrue(classwright.getClass().isHidden(), "Classwright's proxies can be unloaded");
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName(classwright.getClass().getName()));

        assertFalse(cglib.getClass().isHidden(), "CGLib's cannot");
        assertSame(cglib.getClass(), Class.forName(cglib.getClass().getName(), false,
                cglib.getClass().getClassLoader()),
                "and CGLib's is resolvable by name, which is precisely why it never unloads");
    }
}
