package com.classwright.cglib;

import com.classwright.ClasswrightException;

import java.lang.reflect.Method;

/**
 * Converts between CGLib's callback interfaces and Classwright's.
 *
 * <p>They are structurally identical and nominally distinct, which is the whole problem: existing
 * code implements {@code net.sf.cglib.proxy.MethodInterceptor}, and the generated proxy calls
 * {@code com.classwright.proxy.MethodInterceptor}. One thin object per callback bridges them.
 *
 * <p>Every adapter remembers the callback it wraps, so {@code Factory.getCallback} can hand back
 * the very object the caller supplied rather than an adapter they have never seen.
 *
 * <p>Not part of the reproduced API.
 */
public final class CallbackAdapters {

    /** Implemented by every adapter, so the original can be recovered. */
    public interface Adapted {
        /**
         * The CGLib callback being adapted.
         *
         * @return the wrapped callback
         */
        net.sf.cglib.proxy.Callback source();
    }

    private CallbackAdapters() {
    }

    /**
     * Wraps a CGLib callback so a Classwright proxy can call it.
     *
     * @param source the caller's callback
     * @return the equivalent Classwright callback
     */
    public static com.classwright.proxy.Callback toClasswright(
            net.sf.cglib.proxy.Callback source) {
        if (source == null) {
            throw new ClasswrightException("callback must not be null; use NoOp.INSTANCE to leave "
                    + "a method alone");
        }
        if (source instanceof net.sf.cglib.proxy.MethodInterceptor interceptor) {
            return new InterceptorAdapter(interceptor);
        }
        if (source instanceof net.sf.cglib.proxy.NoOp noOp) {
            return new NoOpAdapter(noOp);
        }
        if (source instanceof net.sf.cglib.proxy.FixedValue fixedValue) {
            return new FixedValueAdapter(fixedValue);
        }
        if (source instanceof net.sf.cglib.proxy.LazyLoader lazyLoader) {
            return new LazyLoaderAdapter(lazyLoader);
        }
        if (source instanceof net.sf.cglib.proxy.ProxyRefDispatcher dispatcher) {
            // Checked before Dispatcher: a class may implement both, and the more specific
            // contract is the one the caller meant.
            return new ProxyRefDispatcherAdapter(dispatcher);
        }
        if (source instanceof net.sf.cglib.proxy.Dispatcher dispatcher) {
            return new DispatcherAdapter(dispatcher);
        }
        if (source instanceof net.sf.cglib.proxy.InvocationHandler handler) {
            return new InvocationHandlerAdapter(handler);
        }
        throw new ClasswrightException(source.getClass().getName()
                + " implements Callback but none of the callback kinds Classwright understands");
    }

    /**
     * Recovers the CGLib callback an adapter wraps.
     *
     * @param adapted an adapter produced by {@link #toClasswright}
     * @return the original callback, or {@code null}
     */
    public static net.sf.cglib.proxy.Callback toCglib(com.classwright.proxy.Callback adapted) {
        if (adapted == null) {
            return null;
        }
        if (adapted instanceof Adapted wrapper) {
            return wrapper.source();
        }
        throw new ClasswrightException("this proxy's callback was not created through the CGLib "
                + "compatibility layer, so there is no original to return");
    }

    /**
     * The Classwright callback interface a CGLib callback maps onto.
     *
     * <p>Needed by {@code createClass()}, where there are callback <em>types</em> but no instances.
     *
     * @param cglibCallbackType a CGLib callback interface
     * @return the corresponding Classwright interface
     */
    public static Class<?> classwrightTypeOf(Class<?> cglibCallbackType) {
        if (net.sf.cglib.proxy.MethodInterceptor.class.isAssignableFrom(cglibCallbackType)) {
            return com.classwright.proxy.MethodInterceptor.class;
        }
        if (net.sf.cglib.proxy.NoOp.class.isAssignableFrom(cglibCallbackType)) {
            return com.classwright.proxy.NoOp.class;
        }
        if (net.sf.cglib.proxy.FixedValue.class.isAssignableFrom(cglibCallbackType)) {
            return com.classwright.proxy.FixedValue.class;
        }
        if (net.sf.cglib.proxy.LazyLoader.class.isAssignableFrom(cglibCallbackType)) {
            return com.classwright.proxy.LazyLoader.class;
        }
        if (net.sf.cglib.proxy.ProxyRefDispatcher.class.isAssignableFrom(cglibCallbackType)) {
            return com.classwright.proxy.ProxyRefDispatcher.class;
        }
        if (net.sf.cglib.proxy.Dispatcher.class.isAssignableFrom(cglibCallbackType)) {
            return com.classwright.proxy.Dispatcher.class;
        }
        if (net.sf.cglib.proxy.InvocationHandler.class.isAssignableFrom(cglibCallbackType)) {
            return com.classwright.proxy.InvocationHandler.class;
        }
        throw new ClasswrightException(cglibCallbackType.getName()
                + " is not a recognised CGLib callback type");
    }

    // ==========================================================================================

    private record InterceptorAdapter(net.sf.cglib.proxy.MethodInterceptor source)
            implements com.classwright.proxy.MethodInterceptor, Adapted {

        @Override
        public Object intercept(Object proxy, Method method, Object[] arguments,
                                com.classwright.proxy.MethodProxy methodProxy) throws Throwable {
            // wrapping() caches on the MethodProxy itself, so this is a field read after the
            // first call rather than an allocation on every intercepted invocation.
            return source.intercept(proxy, method, arguments,
                    net.sf.cglib.proxy.MethodProxies.wrap(methodProxy));
        }
    }

    private record NoOpAdapter(net.sf.cglib.proxy.NoOp source)
            implements com.classwright.proxy.NoOp, Adapted {
    }

    private record FixedValueAdapter(net.sf.cglib.proxy.FixedValue source)
            implements com.classwright.proxy.FixedValue, Adapted {

        @Override
        public Object loadObject() throws Exception {
            return source.loadObject();
        }
    }

    private record LazyLoaderAdapter(net.sf.cglib.proxy.LazyLoader source)
            implements com.classwright.proxy.LazyLoader, Adapted {

        @Override
        public Object loadObject() throws Exception {
            return source.loadObject();
        }
    }

    private record DispatcherAdapter(net.sf.cglib.proxy.Dispatcher source)
            implements com.classwright.proxy.Dispatcher, Adapted {

        @Override
        public Object loadObject() throws Exception {
            return source.loadObject();
        }
    }

    private record ProxyRefDispatcherAdapter(net.sf.cglib.proxy.ProxyRefDispatcher source)
            implements com.classwright.proxy.ProxyRefDispatcher, Adapted {

        @Override
        public Object loadObject(Object proxy) throws Exception {
            return source.loadObject(proxy);
        }
    }

    private record InvocationHandlerAdapter(net.sf.cglib.proxy.InvocationHandler source)
            implements com.classwright.proxy.InvocationHandler, Adapted {

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            try {
                return source.invoke(proxy, method, arguments);
            } catch (RuntimeException | Error unchecked) {
                throw unchecked;
            } catch (Throwable checked) {
                // CGLib's InvocationHandler contract: a checked exception the intercepted method
                // did not declare arrives wrapped, and migrated catch blocks depend on it.
                for (Class<?> declared : method.getExceptionTypes()) {
                    if (declared.isInstance(checked)) {
                        throw checked;
                    }
                }
                throw new net.sf.cglib.proxy.UndeclaredThrowableException(checked);
            }
        }
    }
}
