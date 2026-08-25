package net.sf.cglib;

import net.sf.cglib.proxy.Callback;
import net.sf.cglib.proxy.CallbackFilter;
import net.sf.cglib.proxy.Dispatcher;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.Factory;
import net.sf.cglib.proxy.FixedValue;
import net.sf.cglib.proxy.InvocationHandler;
import net.sf.cglib.proxy.LazyLoader;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;
import net.sf.cglib.proxy.NoOp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test that decides whether the compatibility layer works.
 *
 * <p>Everything below is written the way an existing CGLib application is written: it imports
 * {@code net.sf.cglib.*} and nothing else, and it does not know Classwright exists. If it passes,
 * migrating is a change of dependency coordinate.
 *
 * <p>Deliberately no {@code com.classwright} import anywhere in this file. That constraint is the
 * assertion.
 */
class DropInCompatibilityTest {

    /** A conventional service, as an application would have. */
    public static class Service {

        public int calls;

        public Service() {
        }

        public Service(int initialCalls) {
            this.calls = initialCalls;
        }

        public String greet(String name) {
            calls++;
            return "hello " + name;
        }

        public int add(int a, int b) {
            calls++;
            return a + b;
        }

        public void touch() {
            calls++;
        }

        public final String cannotOverride() {
            return "final";
        }
    }

    @Nested
    @DisplayName("the usual CGLib patterns")
    class UsualPatterns {

        @Test
        @DisplayName("intercept and delegate")
        void interceptAndDelegate() {
            List<String> seen = new ArrayList<>();

            Service proxy = (Service) Enhancer.create(Service.class,
                    (MethodInterceptor) (obj, method, args, proxyRef) -> {
                        seen.add(method.getName());
                        return proxyRef.invokeSuper(obj, args);
                    });

            assertEquals("hello world", proxy.greet("world"));
            assertEquals(7, proxy.add(3, 4));
            assertEquals(List.of("greet", "add"), seen);
            assertEquals(2, proxy.calls);
        }

        @Test
        @DisplayName("replace the result")
        void replaceResult() {
            Service proxy = (Service) Enhancer.create(Service.class,
                    (MethodInterceptor) (obj, method, args, proxyRef) -> "intercepted");

            assertEquals("intercepted", proxy.greet("x"));
            assertEquals(0, proxy.calls);
        }

        @Test
        @DisplayName("NoOp, FixedValue, Dispatcher, LazyLoader and InvocationHandler")
        void everyCallbackKind() {
            assertEquals("hello x", ((Service) Enhancer.create(Service.class, NoOp.INSTANCE))
                    .greet("x"));

            assertEquals("fixed", ((Service) Enhancer.create(Service.class,
                    (FixedValue) () -> "fixed")).greet("x"));

            Service target = new Service();
            assertEquals("hello x", ((Service) Enhancer.create(Service.class,
                    (Dispatcher) () -> target)).greet("x"));
            assertEquals(1, target.calls);

            AtomicInteger loads = new AtomicInteger();
            Service lazy = (Service) Enhancer.create(Service.class, (LazyLoader) () -> {
                loads.incrementAndGet();
                return target;
            });
            lazy.greet("a");
            lazy.greet("b");
            assertEquals(1, loads.get(), "a LazyLoader resolves once");

            assertEquals("greet", ((Service) Enhancer.create(Service.class,
                    (InvocationHandler) (proxy, method, args) -> method.getName())).greet("x"));
        }

        @Test
        @DisplayName("MethodProxy reports its signature")
        void methodProxyReportsSignature() {
            List<String> signatures = new ArrayList<>();

            Service proxy = (Service) Enhancer.create(Service.class,
                    (MethodInterceptor) (obj, method, args, proxyRef) -> {
                        signatures.add(proxyRef.getSignature().toString());
                        return proxyRef.invokeSuper(obj, args);
                    });
            proxy.add(1, 2);

            assertEquals(List.of("add(II)I"), signatures);
        }

        @Test
        @DisplayName("a CallbackFilter routes methods to different callbacks")
        void callbackFilter() {
            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(Service.class);
            enhancer.setCallbacks(new Callback[]{NoOp.INSTANCE, (FixedValue) () -> "stubbed"});
            enhancer.setCallbackFilter(new GreetIsStubbed());

            Service proxy = (Service) enhancer.create();

            assertEquals("stubbed", proxy.greet("x"));
            assertEquals(3, proxy.add(1, 2));
            assertEquals(1, proxy.calls);
        }

        @Test
        @DisplayName("constructor arguments are forwarded")
        void constructorArguments() {
            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(Service.class);
            enhancer.setCallback(NoOp.INSTANCE);

            Service proxy = (Service) enhancer.create(new Class[]{int.class}, new Object[]{42});

            assertEquals(42, proxy.calls);
        }

        @Test
        @DisplayName("final methods are left alone, as they always were")
        void finalMethodsUntouched() {
            Service proxy = (Service) Enhancer.create(Service.class,
                    (MethodInterceptor) (obj, method, args, proxyRef) -> "intercepted");

            assertEquals("final", proxy.cannotOverride());
        }

        @Test
        @DisplayName("createClass produces the class without an instance")
        void createClassWorks() {
            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(Service.class);
            enhancer.setCallbackType(MethodInterceptor.class);

            Class type = enhancer.createClass();

            assertTrue(Service.class.isAssignableFrom(type));
            assertTrue(Enhancer.isEnhanced(type));
        }
    }

    @Nested
    @DisplayName("Factory, which frameworks cast to")
    class FactoryPatterns {

        @Test
        @DisplayName("a proxy is a Factory and reports the callback it was given")
        void exposesItsCallback() {
            Callback callback = NoOp.INSTANCE;

            Service proxy = (Service) Enhancer.create(Service.class, callback);

            Factory factory = assertInstanceOf(Factory.class, proxy);
            assertSame(callback, factory.getCallback(0),
                    "it should hand back the very object supplied, not an adapter");
            assertEquals(1, factory.getCallbacks().length,
                    "the bridge slot must not be visible to the caller");
        }

        @Test
        @DisplayName("a callback can be swapped on a live proxy")
        void swapsCallback() {
            Service proxy = (Service) Enhancer.create(Service.class,
                    (MethodInterceptor) (obj, method, args, proxyRef) ->
                            proxyRef.invokeSuper(obj, args));
            assertEquals("hello x", proxy.greet("x"));

            ((Factory) proxy).setCallback(0, (MethodInterceptor)
                    (obj, method, args, proxyRef) -> "replaced");

            assertEquals("replaced", proxy.greet("x"));
        }

        @Test
        @DisplayName("setCallbacks replaces them all")
        void swapsAllCallbacks() {
            Service proxy = (Service) Enhancer.create(Service.class,
                    (MethodInterceptor) (obj, method, args, proxyRef) ->
                            proxyRef.invokeSuper(obj, args));

            ((Factory) proxy).setCallbacks(new Callback[]{
                    (MethodInterceptor) (obj, method, args, proxyRef) -> "all replaced"});

            assertEquals("all replaced", proxy.greet("x"));
        }

        @Test
        @DisplayName("newInstance creates another proxy of the same class")
        void createsMoreInstances() {
            Service first = (Service) Enhancer.create(Service.class,
                    (MethodInterceptor) (obj, method, args, proxyRef) ->
                            proxyRef.invokeSuper(obj, args));

            Service second = (Service) ((Factory) first).newInstance(
                    (MethodInterceptor) (obj, method, args, proxyRef) -> "from the factory");

            assertNotSame(first, second);
            assertSame(first.getClass(), second.getClass());
            assertEquals("from the factory", second.greet("x"));
            assertEquals("hello x", first.greet("x"));
        }

        @Test
        @DisplayName("newInstance can choose a constructor")
        void createsWithConstructor() {
            Service first = (Service) Enhancer.create(Service.class, NoOp.INSTANCE);

            Service second = (Service) ((Factory) first).newInstance(
                    new Class[]{int.class}, new Object[]{99}, new Callback[]{NoOp.INSTANCE});

            assertEquals(99, second.calls);
        }

        @Test
        @DisplayName("Factory can be switched off")
        void factoryIsOptional() {
            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(Service.class);
            enhancer.setCallback(NoOp.INSTANCE);
            enhancer.setUseFactory(false);

            assertFalse(enhancer.create() instanceof Factory);
        }
    }

    @Nested
    @DisplayName("names, which frameworks inspect")
    class Naming {

        @Test
        @DisplayName("the class name carries CGLib's markers")
        void keepsCglibNaming() {
            // Spring's ClassUtils.getUserClass tests for "$$" then walks to the superclass; other
            // code matches "EnhancerByCGLIB" specifically. Both keep working.
            Service proxy = (Service) Enhancer.create(Service.class, NoOp.INSTANCE);
            String name = proxy.getClass().getName();

            assertTrue(name.startsWith(Service.class.getName() + "$$"), name);
            assertTrue(name.contains("EnhancerByCGLIB"), name);
            assertSame(Service.class, proxy.getClass().getSuperclass());
        }

        @Test
        @DisplayName("generated members carry the CGLIB$ prefix")
        void keepsCglibMemberPrefix() {
            // Frameworks routinely skip members with a generator's prefix so that a proxy's
            // plumbing is not mistaken for state.
            Service proxy = (Service) Enhancer.create(Service.class, NoOp.INSTANCE);

            assertTrue(java.util.Arrays.stream(proxy.getClass().getDeclaredFields())
                            .anyMatch(field -> field.getName().startsWith("CGLIB$")),
                    java.util.Arrays.toString(proxy.getClass().getDeclaredFields()));
        }

        @Test
        @DisplayName("proxies are hidden classes, which is the reason to migrate")
        void proxiesAreUnloadable() {
            Service proxy = (Service) Enhancer.create(Service.class, NoOp.INSTANCE);

            assertTrue(proxy.getClass().isHidden(),
                    "CGLib's proxies were permanent; these are not");
        }

        @Test
        @DisplayName("a resolvable name is available for tooling that needs one")
        void resolvableNamesAreAvailable() throws Exception {
            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(Service.class);
            enhancer.setCallback(NoOp.INSTANCE);
            enhancer.setUseHiddenClasses(false);

            Class type = enhancer.create().getClass();

            assertFalse(type.isHidden());
            assertSame(type, Class.forName(type.getName(), false, type.getClassLoader()));
        }
    }

    @Test
    @DisplayName("an interface passed as a superclass is treated as an interface, as CGLib did")
    void interfaceAsSuperclass() {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Runnable.class);
        enhancer.setCallback(NoOp.INSTANCE);

        assertInstanceOf(Runnable.class, enhancer.create());
    }

    @Test
    @DisplayName("exceptions from an interceptor propagate unchanged")
    void exceptionsPropagate() {
        Service proxy = (Service) Enhancer.create(Service.class,
                (MethodInterceptor) (obj, method, args, proxyRef) -> {
                    throw new IllegalStateException("from the interceptor");
                });

        assertEquals("from the interceptor",
                assertThrows(IllegalStateException.class, () -> proxy.greet("x")).getMessage());
    }

    /** Stateless and equal-comparable, as CGLib required. */
    private static final class GreetIsStubbed implements CallbackFilter {

        @Override
        public int accept(Method method) {
            return method.getName().equals("greet") ? 1 : 0;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof GreetIsStubbed;
        }

        @Override
        public int hashCode() {
            return GreetIsStubbed.class.hashCode();
        }
    }

    @Nested
    @DisplayName("deferred binding and validation, the framework patterns")
    class DeferredBindingAndValidation {

        @Test
        @DisplayName("createClass, instantiate directly, then Factory.setCallbacks — Spring's flow")
        void createClassThenSetCallbacks() throws Exception {
            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(Service.class);
            enhancer.setCallbackType(MethodInterceptor.class);
            Class<?> proxyClass = enhancer.createClass();

            // Constructed without Classwright's help, exactly as Objenesis-based frameworks do.
            Object proxy = proxyClass.getDeclaredConstructor().newInstance();

            ((Factory) proxy).setCallbacks(new Callback[]{
                    (MethodInterceptor) (obj, method, args, proxyRef) -> "intercepted"});
            assertEquals("intercepted", ((Service) proxy).greet("x"));
        }

        @Test
        @DisplayName("registerCallbacks binds instances created after it")
        void registerCallbacksFlow() throws Exception {
            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(Service.class);
            enhancer.setCallbackType(MethodInterceptor.class);
            Class<?> proxyClass = enhancer.createClass();

            Enhancer.registerCallbacks(proxyClass, new Callback[]{
                    (MethodInterceptor) (obj, method, args, proxyRef) -> "registered"});
            try {
                Object proxy = proxyClass.getDeclaredConstructor().newInstance();
                assertEquals("registered", ((Service) proxy).greet("x"));
            } finally {
                Enhancer.registerCallbacks(proxyClass, null);
            }
        }

        @Test
        @DisplayName("registerStaticCallbacks survives a later create() of the same configuration")
        void staticRegistrationSurvivesLaterCreate() throws Exception {
            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(Service.class);
            enhancer.setCallbackType(MethodInterceptor.class);
            Class<?> proxyClass = enhancer.createClass();

            Enhancer.registerStaticCallbacks(proxyClass, new Callback[]{
                    (MethodInterceptor) (obj, method, args, proxyRef) -> "static"});
            try {
                // The same configuration resolves to the same cached class. Its create() once
                // overwrote the static registration with the bridge-only defaults, and every
                // instance created afterwards silently lost its interception.
                Enhancer again = new Enhancer();
                again.setSuperclass(Service.class);
                again.setCallback((MethodInterceptor) (obj, method, args, proxyRef) -> "own");
                again.create();

                Object proxy = proxyClass.getDeclaredConstructor().newInstance();
                assertEquals("static", ((Service) proxy).greet("x"));
            } finally {
                Enhancer.registerStaticCallbacks(proxyClass, null);
            }
        }

        @Test
        @DisplayName("several callbacks without a filter are refused, as CGLib refused them")
        void multipleCallbacksNeedAFilter() {
            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(Service.class);
            enhancer.setCallbacks(new Callback[]{NoOp.INSTANCE,
                    (FixedValue) () -> "stub"});

            IllegalStateException refusal =
                    assertThrows(IllegalStateException.class, enhancer::create);
            assertTrue(refusal.getMessage().contains("filter"));
        }

        @Test
        @DisplayName("create() after setCallbackTypes() names the missing callbacks")
        void createWithoutCallbacksIsRefused() {
            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(Service.class);
            enhancer.setCallbackType(MethodInterceptor.class);

            assertEquals("Callbacks are required",
                    assertThrows(IllegalStateException.class, enhancer::create).getMessage());
        }

        @Test
        @DisplayName("an empty callback array is refused with CGLib's exception type")
        void emptyCallbacksAreRefused() {
            Enhancer enhancer = new Enhancer();
            assertThrows(IllegalArgumentException.class,
                    () -> enhancer.setCallbacks(new Callback[0]));
        }

        @Test
        @DisplayName("setClassLoader before setSuperclass works — CGLib imposed no setter order")
        void setClassLoaderBeforeSuperclass() {
            Enhancer enhancer = new Enhancer();
            enhancer.setClassLoader(Service.class.getClassLoader());
            enhancer.setSuperclass(Service.class);
            enhancer.setCallback(NoOp.INSTANCE);

            assertEquals("hello x", ((Service) enhancer.create()).greet("x"));
        }

        @Test
        @DisplayName("a loader that cannot see the superclass is refused at create(), whatever the setter order")
        void unusableClassLoaderIsRefusedAtCreate() {
            Enhancer enhancer = new Enhancer();
            // Parented on bootstrap only, so it cannot resolve Service. Set before the
            // superclass, which is exactly the ordering that used to slip through unvalidated.
            enhancer.setClassLoader(new ClassLoader(null) {
            });
            enhancer.setSuperclass(Service.class);
            enhancer.setCallback(NoOp.INSTANCE);

            IllegalArgumentException refusal =
                    assertThrows(IllegalArgumentException.class, enhancer::create);
            assertTrue(refusal.getMessage().contains("cannot see"), refusal.getMessage());
        }

        @Test
        @DisplayName("an InvocationHandler's undeclared checked exception arrives wrapped")
        void invocationHandlerWrapsUndeclared() {
            Service proxy = (Service) Enhancer.create(Service.class,
                    (InvocationHandler) (obj, method, args) -> {
                        throw new java.io.IOException("undeclared");
                    });

            net.sf.cglib.proxy.UndeclaredThrowableException wrapped = assertThrows(
                    net.sf.cglib.proxy.UndeclaredThrowableException.class,
                    () -> proxy.greet("x"));
            assertInstanceOf(java.io.IOException.class, wrapped.getUndeclaredThrowable());
        }

        @Test
        @DisplayName("a MethodInterceptor's undeclared checked exception propagates unwrapped")
        void interceptorDoesNotWrap() {
            Service proxy = (Service) Enhancer.create(Service.class,
                    (MethodInterceptor) (obj, method, args, proxyRef) -> {
                        throw new java.io.IOException("undeclared");
                    });

            assertThrows(java.io.IOException.class, () -> proxy.greet("x"));
        }

        @Test
        @DisplayName("KeyFactory keys compare by value, arrays by content")
        void keyFactoryValueEquality() {
            KeyRecipe factory = (KeyRecipe) net.sf.cglib.core.KeyFactory.create(KeyRecipe.class);

            Object first = factory.newInstance("EU", new int[]{1, 2});
            Object second = factory.newInstance("EU", new int[]{1, 2});
            Object different = factory.newInstance("US", new int[]{1, 2});

            assertEquals(first, second);
            assertEquals(first.hashCode(), second.hashCode());
            assertFalse(first.equals(different));
        }
    }

    /** The KeyFactory key-interface shape, as CGLib users declare it. */
    public interface KeyRecipe {
        Object newInstance(String region, int[] weights);
    }
}
