package com.classwright.proxy;

import com.classwright.ClasswrightException;
import com.classwright.proxy.fixtures.Fixtures;
import com.classwright.proxy.fixtures.Service;
import com.classwright.runtime.DefinitionStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the proxy API: every callback kind, filters, factories, and the failure messages. */
class EnhancerTest {

    private static Service proxyWith(Callback callback) {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Service.class);
        enhancer.setCallback(callback);
        return (Service) enhancer.create();
    }

    // ==========================================================================================
    // Callback kinds
    // ==========================================================================================

    @Nested
    @DisplayName("MethodInterceptor")
    class Interception {

        @Test
        @DisplayName("sees the call and can delegate to the original")
        void interceptsAndDelegates() {
            List<String> seen = new ArrayList<>();
            Service proxy = proxyWith((MethodInterceptor) (obj, method, args, methodProxy) -> {
                seen.add(method.getName());
                return methodProxy.invokeSuper(obj, args);
            });

            assertEquals("hello world", proxy.greet("world"));
            assertEquals(7, proxy.add(3, 4));

            assertEquals(List.of("greet", "add"), seen);
            assertEquals(2, proxy.calls, "the originals must actually have run");
        }

        @Test
        @DisplayName("can replace the result without calling the original")
        void canShortCircuit() {
            Service proxy = proxyWith(
                    (MethodInterceptor) (obj, method, args, methodProxy) -> "intercepted");

            assertEquals("intercepted", proxy.greet("world"));
            assertEquals(0, proxy.calls, "the original must not have run");
        }

        @Test
        @DisplayName("can modify arguments before delegating")
        void canModifyArguments() {
            Service proxy = proxyWith((MethodInterceptor) (obj, method, args, methodProxy) -> {
                if (args.length == 2 && args[0] instanceof Integer) {
                    args[0] = 100;
                }
                return methodProxy.invokeSuper(obj, args);
            });

            assertEquals(104, proxy.add(3, 4));
        }

        @Test
        @DisplayName("handles primitives, void, arrays, and mixed slot widths")
        void handlesEveryReturnShape() {
            Service proxy = proxyWith((MethodInterceptor) (obj, method, args, methodProxy) ->
                    methodProxy.invokeSuper(obj, args));

            assertEquals(7, proxy.add(3, 4));
            assertEquals(15L, proxy.total(10L, 2, 3.9));   // long + int + double, then truncated
            assertTrue(proxy.flag());
            assertArrayEquals(new int[]{1, 2, 3}, proxy.numbers());
            proxy.touch();

            assertEquals(5, proxy.calls);
        }

        @Test
        @DisplayName("lets exceptions from the original propagate unchanged")
        void propagatesExceptions() {
            Service proxy = proxyWith((MethodInterceptor) (obj, method, args, methodProxy) -> {
                throw new IllegalStateException("from the interceptor");
            });

            IllegalStateException failure =
                    assertThrows(IllegalStateException.class, () -> proxy.greet("x"));
            assertEquals("from the interceptor", failure.getMessage());
        }

        @Test
        @DisplayName("MethodProxy reports the method it belongs to")
        void methodProxyDescribesItself() {
            List<String> descriptors = new ArrayList<>();
            Service proxy = proxyWith((MethodInterceptor) (obj, method, args, methodProxy) -> {
                descriptors.add(methodProxy.getName() + methodProxy.getDescriptor());
                return methodProxy.invokeSuper(obj, args);
            });

            proxy.add(1, 2);

            assertEquals(List.of("add(II)I"), descriptors);
        }

        @Test
        @DisplayName("invokeSuper on a non-proxy is reported clearly")
        void invokeSuperRejectsForeignObjects() {
            List<MethodProxy> captured = new ArrayList<>();
            Service proxy = proxyWith((MethodInterceptor) (obj, method, args, methodProxy) -> {
                captured.add(methodProxy);
                return methodProxy.invokeSuper(obj, args);
            });
            proxy.add(1, 2);

            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> captured.get(0).invokeSuper(new Service(), new Object[]{1, 2}));

            assertTrue(failure.getMessage().contains("not a Classwright proxy"),
                    failure.getMessage());
        }
    }

    @Test
    @DisplayName("NoOp leaves the original completely alone")
    void noOpDelegates() {
        Service proxy = proxyWith(NoOp.INSTANCE);

        assertEquals("hello world", proxy.greet("world"));
        assertEquals(1, proxy.calls);
    }

    @Test
    @DisplayName("FixedValue replaces the result and never loads the arguments")
    void fixedValueReplacesResult() {
        Service proxy = proxyWith((FixedValue) () -> "always this");

        assertEquals("always this", proxy.greet("ignored"));
        assertEquals(0, proxy.calls);
    }

    @Test
    @DisplayName("FixedValue unboxes for primitive returns")
    void fixedValueUnboxes() {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Service.class);
        enhancer.setCallback((FixedValue) () -> 42);
        // Only the int-returning method makes sense here; the others would fail the cast, which
        // is the documented behaviour of a FixedValue applied indiscriminately.
        Service proxy = (Service) enhancer.create();

        assertEquals(42, proxy.add(1, 1));
    }

    @Test
    @DisplayName("InvocationHandler receives the method and boxed arguments")
    void invocationHandlerReceivesTheCall() {
        Service proxy = proxyWith((InvocationHandler) (proxyObject, method, args) ->
                method.getName() + "/" + args.length);

        assertEquals("greet/1", proxy.greet("x"));
        assertEquals(0, proxy.calls);
    }

    @Test
    @DisplayName("Dispatcher resolves a delegate on every call")
    void dispatcherResolvesEveryTime() {
        AtomicInteger resolutions = new AtomicInteger();
        Service delegate = new Service();
        Service proxy = proxyWith((Dispatcher) () -> {
            resolutions.incrementAndGet();
            return delegate;
        });

        assertEquals("hello a", proxy.greet("a"));
        assertEquals("hello b", proxy.greet("b"));

        assertEquals(2, resolutions.get(), "a Dispatcher resolves per call");
        assertEquals(2, delegate.calls, "the delegate ran, not the proxy's own super");
        assertEquals(0, proxy.calls);
    }

    @Test
    @DisplayName("LazyLoader resolves once and caches")
    void lazyLoaderResolvesOnce() {
        AtomicInteger resolutions = new AtomicInteger();
        Service delegate = new Service();
        Service proxy = proxyWith((LazyLoader) () -> {
            resolutions.incrementAndGet();
            return delegate;
        });

        assertEquals(0, resolutions.get(), "nothing should be resolved before the first call");

        proxy.greet("a");
        proxy.greet("b");
        proxy.add(1, 2);

        assertEquals(1, resolutions.get(), "a LazyLoader resolves once per instance");
        assertEquals(3, delegate.calls);
    }

    @Test
    @DisplayName("ProxyRefDispatcher is told which proxy asked")
    void proxyRefDispatcherReceivesTheProxy() {
        List<Object> proxiesSeen = new ArrayList<>();
        Service delegate = new Service();
        Service proxy = proxyWith((ProxyRefDispatcher) askingProxy -> {
            proxiesSeen.add(askingProxy);
            return delegate;
        });

        proxy.greet("a");

        assertEquals(1, proxiesSeen.size());
        assertSame(proxy, proxiesSeen.get(0));
    }

    // ==========================================================================================
    // Filters and multiple callbacks
    // ==========================================================================================

    @Test
    @DisplayName("a CallbackFilter routes each method to its own callback")
    void filterRoutesPerMethod() {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Service.class);
        enhancer.setCallbacks(
                NoOp.INSTANCE,
                (FixedValue) () -> "stubbed");
        enhancer.setCallbackFilter(new GreetIsStubbed());

        Service proxy = (Service) enhancer.create();

        assertEquals("stubbed", proxy.greet("x"), "greet goes to the FixedValue");
        assertEquals(3, proxy.add(1, 2), "add goes to the NoOp");
        assertEquals(1, proxy.calls, "only add reached the original");
    }

    @Test
    @DisplayName("several callbacks without a filter is rejected with an explanation")
    void rejectsMultipleCallbacksWithoutFilter() {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Service.class);
        enhancer.setCallbacks(NoOp.INSTANCE, NoOp.INSTANCE);

        ClasswrightException failure = assertThrows(ClasswrightException.class, enhancer::create);

        assertTrue(failure.getMessage().contains("CallbackFilter"), failure.getMessage());
    }

    @Test
    @DisplayName("a filter returning an out-of-range index is reported")
    void rejectsOutOfRangeFilterIndex() {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Service.class);
        enhancer.setCallbacks(NoOp.INSTANCE, NoOp.INSTANCE);
        enhancer.setCallbackFilter(new AlwaysReturns(5));

        ClasswrightException failure = assertThrows(ClasswrightException.class, enhancer::create);

        assertTrue(failure.getMessage().contains("only 2 callbacks"), failure.getMessage());
    }

    // ==========================================================================================
    // Factory
    // ==========================================================================================

    @Nested
    @DisplayName("Factory")
    class FactoryBehaviour {

        @Test
        @DisplayName("is implemented by default and exposes the callbacks")
        void exposesCallbacks() {
            Callback callback = NoOp.INSTANCE;
            Service proxy = proxyWith(callback);

            Factory factory = assertInstanceOf(Factory.class, proxy);
            assertSame(callback, factory.getCallback(0));
            assertEquals(1, factory.getCallbacks().length);
        }

        @Test
        @DisplayName("can swap a callback of the same type on a live instance")
        void replacesCallbacks() {
            Service proxy = proxyWith((MethodInterceptor) (obj, method, args, methodProxy) ->
                    methodProxy.invokeSuper(obj, args));
            assertEquals("hello x", proxy.greet("x"));

            ((Factory) proxy).setCallback(0, (MethodInterceptor)
                    (obj, method, args, methodProxy) -> "replaced");

            assertEquals("replaced", proxy.greet("x"));
        }

        @Test
        @DisplayName("rejects a callback of a different type, explaining why")
        void rejectsMismatchedCallbackType() {
            // Callback types are compiled into the generated class: each has a field of its
            // declared interface type and the method bodies call that interface directly. There is
            // nowhere to put a different kind, and no code that would call it.
            Service proxy = proxyWith(NoOp.INSTANCE);

            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> ((Factory) proxy).setCallback(0, (FixedValue) () -> "x"));

            assertTrue(failure.getMessage().contains("NoOp"), failure.getMessage());
            assertTrue(failure.getMessage().contains("compiled into the generated class"),
                    failure.getMessage());
        }

        @Test
        @DisplayName("creates further instances without regenerating the class")
        void createsMoreInstances() {
            Service first = proxyWith((MethodInterceptor) (obj, method, args, methodProxy) ->
                    methodProxy.invokeSuper(obj, args));

            Service second = (Service) ((Factory) first).newInstance(
                    (MethodInterceptor) (obj, method, args, methodProxy) -> "from the factory");

            assertNotSame(first, second);
            assertSame(first.getClass(), second.getClass(), "the class should be reused");
            assertEquals("from the factory", second.greet("x"));
            assertEquals("hello x", first.greet("x"), "the original instance is unaffected");
        }

        @Test
        @DisplayName("creates instances through a specific superclass constructor")
        void usesSpecificConstructor() {
            Service first = proxyWith(NoOp.INSTANCE);

            Service second = (Service) ((Factory) first).newInstance(
                    new Class<?>[]{int.class}, new Object[]{99},
                    new Callback[]{NoOp.INSTANCE});

            assertEquals(99, second.calls, "the int constructor should have run");
        }

        @Test
        @DisplayName("can be switched off")
        void canBeDisabled() {
            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(Service.class);
            enhancer.setCallback(NoOp.INSTANCE);
            enhancer.setUseFactory(false);

            assertFalse(enhancer.create() instanceof Factory);
        }
    }

    // ==========================================================================================
    // Class shape
    // ==========================================================================================

    @Test
    @DisplayName("uses a specific superclass constructor when asked")
    void createsWithConstructorArguments() {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Service.class);
        enhancer.setCallback(NoOp.INSTANCE);

        Service proxy = (Service) enhancer.create(new Class<?>[]{int.class}, new Object[]{42});

        assertEquals(42, proxy.calls);
    }

    @Test
    @DisplayName("implements additional interfaces")
    void implementsInterfaces() {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Service.class);
        enhancer.setInterfaces(Fixtures.Greeter.class);
        enhancer.setCallback((FixedValue) () -> "from the interface");

        Object proxy = enhancer.create();

        assertInstanceOf(Fixtures.Greeter.class, proxy);
        assertEquals("from the interface", ((Fixtures.Greeter) proxy).greet("x"));
    }

    @Test
    @DisplayName("implements abstract methods, which have no original to call")
    void implementsAbstractMethods() {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Fixtures.AbstractService.class);
        enhancer.setCallback((MethodInterceptor) (obj, method, args, methodProxy) ->
                method.getName().equals("compute") ? 21 : methodProxy.invokeSuper(obj, args));

        Fixtures.AbstractService proxy = (Fixtures.AbstractService) enhancer.create();

        assertEquals(21, proxy.compute(1));
        assertEquals(42, proxy.doubled(1), "the concrete method should call the implemented one");
    }

    @Test
    @DisplayName("delegating to an abstract method reports it rather than crashing")
    void abstractInvokeSuperIsReported() {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Fixtures.AbstractService.class);
        enhancer.setCallback((MethodInterceptor) (obj, method, args, methodProxy) ->
                methodProxy.invokeSuper(obj, args));

        Fixtures.AbstractService proxy = (Fixtures.AbstractService) enhancer.create();

        AbstractMethodError failure =
                assertThrows(AbstractMethodError.class, () -> proxy.compute(1));
        assertTrue(failure.getMessage().contains("abstract"), failure.getMessage());
    }

    @Test
    @DisplayName("overrides the real method, not the bridge, for a covariant return")
    void handlesBridgeMethods() {
        // StringBox implements Supplier<String>, so javac emits a bridge Object get() beside the
        // real String get(). Overriding the bridge would break the forwarding it exists for.
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Fixtures.StringBox.class);
        enhancer.setCallback((FixedValue) () -> "intercepted");

        Fixtures.StringBox proxy = (Fixtures.StringBox) enhancer.create();

        assertEquals("intercepted", proxy.get());
        // The same call arriving through the erased signature must reach the override too, via
        // the inherited bridge.
        assertEquals("intercepted", ((java.util.function.Supplier<?>) proxy).get());
        assertEquals(0, proxy.calls);
    }

    @Test
    @DisplayName("can call a default method as the original")
    void callsDefaultMethodsAsSuper() {
        Enhancer enhancer = new Enhancer();
        enhancer.setInterfaces(Fixtures.Greeter.class);
        enhancer.setCallback((MethodInterceptor) (obj, method, args, methodProxy) ->
                method.getName().equals("greet") ? "hi " + args[0]
                        : methodProxy.invokeSuper(obj, args));

        Fixtures.Greeter proxy = (Fixtures.Greeter) enhancer.create();

        assertEquals("hi everyone", proxy.greetEveryone(),
                "greetEveryone's default body should run and call the intercepted greet");
    }

    @Test
    @DisplayName("keeps $$ in the name so framework heuristics recognise it")
    void namesTheClassRecognisably() {
        Service proxy = proxyWith(NoOp.INSTANCE);

        assertTrue(proxy.getClass().getName().startsWith(Service.class.getName() + "$$"),
                proxy.getClass().getName());
        assertEquals(Service.class, proxy.getClass().getSuperclass());
    }

    // ==========================================================================================
    // Caching
    // ==========================================================================================

    @Test
    @DisplayName("reuses the generated class for an equivalent configuration")
    void reusesGeneratedClasses() {
        Service first = proxyWith(NoOp.INSTANCE);
        Service second = proxyWith(NoOp.INSTANCE);

        assertSame(first.getClass(), second.getClass());
        assertNotSame(first, second);
    }

    @Test
    @DisplayName("generates a distinct class when the callback type differs")
    void distinguishesCallbackTypes() {
        Service withNoOp = proxyWith(NoOp.INSTANCE);
        Service withFixed = proxyWith((FixedValue) () -> "x");

        assertNotSame(withNoOp.getClass(), withFixed.getClass());
    }

    @Test
    @DisplayName("caching can be switched off")
    void cachingCanBeDisabled() {
        Enhancer first = new Enhancer();
        first.setSuperclass(Service.class);
        first.setCallback(NoOp.INSTANCE);
        first.setUseCache(false);

        Enhancer second = new Enhancer();
        second.setSuperclass(Service.class);
        second.setCallback(NoOp.INSTANCE);
        second.setUseCache(false);

        assertNotSame(first.create().getClass(), second.create().getClass());
    }

    // ==========================================================================================
    // Diagnostics and rejection
    // ==========================================================================================

    @Nested
    @DisplayName("rejects what cannot be proxied, with a reason")
    class Rejection {

        @Test
        @DisplayName("a final class")
        void rejectsFinalClasses() {
            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> Enhancer.create(String.class, NoOp.INSTANCE));

            assertTrue(failure.getMessage().contains("final"), failure.getMessage());
        }

        @Test
        @DisplayName("a sealed class, naming its permitted subclasses")
        void rejectsSealedClasses() {
            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> Enhancer.create(Fixtures.Sealed.class, NoOp.INSTANCE));

            assertTrue(failure.getMessage().contains("sealed"), failure.getMessage());
            assertTrue(failure.getMessage().contains("Only"),
                    "the message should name what is permitted: " + failure.getMessage());
        }

        @Test
        @DisplayName("a record")
        void rejectsRecords() {
            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> Enhancer.create(Fixtures.Point.class, NoOp.INSTANCE));

            assertTrue(failure.getMessage().contains("final"), failure.getMessage());
        }

        @Test
        @DisplayName("an interface passed as a superclass, pointing at setInterfaces")
        void rejectsInterfaceAsSuperclass() {
            Enhancer enhancer = new Enhancer();

            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> enhancer.setSuperclass(Runnable.class));

            assertTrue(failure.getMessage().contains("setInterfaces"), failure.getMessage());
        }

        @Test
        @DisplayName("a class with no reachable constructor")
        void rejectsUnconstructableClasses() {
            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> Enhancer.create(Fixtures.Unconstructable.class, NoOp.INSTANCE));

            assertTrue(failure.getMessage().contains("constructor"), failure.getMessage());
        }

        @Test
        @DisplayName("no callback at all")
        void rejectsMissingCallback() {
            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(Service.class);

            assertThrows(ClasswrightException.class, enhancer::create);
        }

        @Test
        @DisplayName("a null callback, suggesting NoOp")
        void rejectsNullCallback() {
            Enhancer enhancer = new Enhancer();

            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> enhancer.setCallbacks(NoOp.INSTANCE, null));

            assertTrue(failure.getMessage().contains("NoOp"), failure.getMessage());
        }
    }

    @Test
    @DisplayName("explains which methods were skipped and why")
    void explainsSkippedMethods() {
        // The answer to "why is my interceptor not firing", which CGLib never gave.
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Service.class);
        enhancer.setCallback(NoOp.INSTANCE);
        enhancer.create();

        String explanation = enhancer.describeSkippedMethods();

        assertTrue(explanation.contains("cannotOverride"), explanation);
        assertTrue(explanation.contains("final"), explanation);
        assertTrue(explanation.contains("staticMethod"), explanation);
    }

    @Test
    @DisplayName("named classes are resolvable, at the documented cost")
    void supportsNamedClasses() {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Service.class);
        enhancer.setCallback(NoOp.INSTANCE);
        enhancer.setDefinitionStrategy(DefinitionStrategy.named());

        Service proxy = (Service) enhancer.create();

        assertFalse(proxy.getClass().isHidden());
        assertEquals("hello x", proxy.greet("x"));
    }

    @Nested
    @DisplayName("construction-time semantics, matching CGLib")
    class ConstructionTime {

        @Test
        @DisplayName("a virtual call from the superclass constructor is intercepted by default")
        void interceptsDuringSuperConstructor() {
            SelfCalling proxy = (SelfCalling) Enhancer.create(SelfCalling.class,
                    (MethodInterceptor) (obj, method, args, methodProxy) -> 7);

            // The superclass constructor called value() on this before any field write could
            // happen; the override binds the parked frame on entry, as CGLib's proxies did.
            assertEquals(7, proxy.seenDuringConstruction);
            assertEquals(7, proxy.value());
        }

        @Test
        @DisplayName("interceptDuringConstruction=false routes the same call to the original")
        void constructionBypassStillWorks() {
            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(SelfCalling.class);
            enhancer.setCallback((MethodInterceptor) (obj, method, args, methodProxy) -> 7);
            enhancer.setInterceptDuringConstruction(false);
            SelfCalling proxy = (SelfCalling) enhancer.create();

            assertEquals(1, proxy.seenDuringConstruction);
            assertEquals(7, proxy.value());
        }

        @Test
        @DisplayName("a LazyLoader resolves exactly once under concurrent first use")
        void lazyLoaderResolvesOnce() throws Exception {
            AtomicInteger loads = new AtomicInteger();
            Service proxy = (Service) Enhancer.create(Service.class, (LazyLoader) () -> {
                loads.incrementAndGet();
                Thread.sleep(50);       // widen the race window
                return new Service();
            });

            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            Runnable first = () -> {
                try {
                    go.await();
                    proxy.greet("x");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            Thread a = new Thread(first);
            Thread b = new Thread(first);
            a.start();
            b.start();
            go.countDown();
            a.join();
            b.join();

            // CGLib's synchronised accessor guaranteed at-most-once; loaders with
            // non-idempotent construction (connections, clients) rely on it.
            assertEquals(1, loads.get());
        }

        @Test
        @DisplayName("child-loader strategies with different parents get different cached classes")
        void strategyIdentityKeepsCachesApart() {
            ClassLoader parentA = new java.net.URLClassLoader(
                    new java.net.URL[0], getClass().getClassLoader());
            ClassLoader parentB = new java.net.URLClassLoader(
                    new java.net.URL[0], getClass().getClassLoader());

            Class<?> first = classFor(new DefinitionStrategy.ChildLoader(parentA));
            Class<?> second = classFor(new DefinitionStrategy.ChildLoader(parentB));

            // Keyed by the strategy's name alone, both requests returned one cached class and
            // the second parent was silently ignored.
            assertNotSame(first, second);
            assertNotSame(first.getClassLoader(), second.getClassLoader());
        }

        private Class<?> classFor(DefinitionStrategy strategy) {
            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(Service.class);
            enhancer.setCallback(NoOp.INSTANCE);
            enhancer.setDefinitionStrategy(strategy);
            return enhancer.create().getClass();
        }

        @Test
        @DisplayName("a nested direct construction cannot steal the outer construction's frame")
        void nestedConstructionKeepsFramesApart() throws Exception {
            Class<?> innerClass;
            {
                Enhancer inner = new Enhancer();
                inner.setSuperclass(Service.class);
                inner.setCallbackTypes(MethodInterceptor.class);
                innerClass = inner.createClass();
            }

            Enhancer outer = new Enhancer();
            outer.setSuperclass(NestingHost.class);
            outer.setCallback((MethodInterceptor) (obj, method, args, methodProxy) ->
                    "outer-intercepted");
            NestingHost proxy = (NestingHost) outer.create(
                    new Class<?>[]{Class.class}, new Object[]{innerClass});

            // The inner proxy was constructed directly, mid-outer-construction, with the outer
            // frame parked. Its class does not match the frame, so it stays unbound and falls
            // through to the original — it must not consume the outer callbacks.
            Service nested = (Service) proxy.nestedResult;
            assertEquals("hello x", nested.greet("x"));
            // And the outer proxy still got its own callbacks.
            assertEquals("outer-intercepted", proxy.describe());
        }
    }

    @Nested
    @DisplayName("the JVM method model: covariant descriptors and default resolution")
    class MethodModel {

        @Test
        @DisplayName("independent covariant interface descriptors are both implemented")
        void covariantDescriptorsBothWork() {
            for (Class<?>[] order : new Class<?>[][]{
                    {ObjectValued.class, StringValued.class},
                    {StringValued.class, ObjectValued.class}}) {
                Enhancer enhancer = new Enhancer();
                enhancer.setInterfaces(order);
                enhancer.setCallback(
                        (MethodInterceptor) (obj, method, args, methodProxy) -> "covariant");
                Object proxy = enhancer.create();

                // Both JVM descriptors — ()Ljava/lang/Object; and ()Ljava/lang/String; — must
                // dispatch; before the bridge synthesis one of them threw AbstractMethodError.
                assertEquals("covariant", ((ObjectValued) proxy).value());
                assertEquals("covariant", ((StringValued) proxy).value());
            }
        }

        @Test
        @DisplayName("the most specific default method wins regardless of interface order")
        void defaultResolutionIgnoresCallerOrder() {
            for (Class<?>[] order : new Class<?>[][]{
                    {ParentDefault.class, ChildDefault.class},
                    {ChildDefault.class, ParentDefault.class}}) {
                Enhancer enhancer = new Enhancer();
                enhancer.setInterfaces(order);
                enhancer.setCallback(NoOp.INSTANCE);
                Object proxy = enhancer.create();

                assertEquals("child", ((ParentDefault) proxy).who(),
                        "order " + java.util.Arrays.toString(order));
            }
        }
    }

    public interface ObjectValued {
        Object value();
    }

    public interface StringValued {
        String value();
    }

    public interface ParentDefault {
        default String who() {
            return "parent";
        }
    }

    public interface ChildDefault extends ParentDefault {
        default String who() {
            return "child";
        }
    }

    /** A superclass whose constructor calls an overridable method on {@code this}. */
    public static class SelfCalling {

        public final int seenDuringConstruction;

        public SelfCalling() {
            this.seenDuringConstruction = value();
        }

        public int value() {
            return 1;
        }
    }

    /** A superclass whose constructor directly constructs another (proxy) class. */
    public static class NestingHost {

        public final Object nestedResult;

        public NestingHost(Class<?> toConstruct) throws Exception {
            this.nestedResult = toConstruct == null
                    ? null
                    : toConstruct.getDeclaredConstructor().newInstance();
        }

        public String describe() {
            return "host";
        }
    }

    // ==========================================================================================
    // Filter implementations, which must define equals and hashCode
    // ==========================================================================================

    /** Stateless, so comparing classes is enough. */
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

    private record AlwaysReturns(int index) implements CallbackFilter {

        @Override
        public int accept(Method method) {
            return index;
        }
    }
}
