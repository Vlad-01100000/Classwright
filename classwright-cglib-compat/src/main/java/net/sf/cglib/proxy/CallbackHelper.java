package net.sf.cglib.proxy;

import com.classwright.ClasswrightException;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link CallbackFilter} and its callback array together, by asking for a callback per
 * method.
 *
 * <p>Reproduces {@code net.sf.cglib.proxy.CallbackHelper}. Subclass it, implement
 * {@link #getCallback(Method)}, and the filter and array fall out — which is easier to get right
 * than maintaining an index mapping by hand.
 *
 * <pre>{@code
 * CallbackHelper helper = new CallbackHelper(Service.class, new Class[0]) {
 *     protected Object getCallback(Method method) {
 *         return method.getName().startsWith("get") ? NoOp.INSTANCE : logging;
 *     }
 * };
 * enhancer.setCallbacks(helper.getCallbacks());
 * enhancer.setCallbackFilter(helper);
 * }</pre>
 */
public abstract class CallbackHelper implements CallbackFilter {

    private final Map<Object, Integer> indexes = new LinkedHashMap<>();
    private final Map<Method, Integer> byMethod = new LinkedHashMap<>();
    private final List<Object> callbacks = new ArrayList<>();

    /**
     * Enumerates the proxyable methods and asks {@link #getCallback} for each — <em>during
     * construction</em>, before any subclass constructor body or field initialiser has run.
     * That is CGLib's contract, inherited deliberately: implementations of {@code getCallback}
     * must not rely on subclass state, because there is none yet. Moving the enumeration later
     * would silently change when migrated subclasses' overrides run, which is the kind of
     * behavioural drift a compatibility layer exists to avoid; hence the suppressed
     * {@code this-escape} warning rather than a fix.
     *
     * @param superclass the class the proxy will extend
     * @param interfaces additional interfaces it will implement
     */
    @SuppressWarnings("this-escape")
    public CallbackHelper(Class superclass, Class[] interfaces) {
        // The same enumeration generation uses — final/static/private absent, protected present,
        // bridges resolved, signatures de-duplicated. Anything else (getMethods(), say) assigns
        // indexes for methods that will never dispatch and misses protected ones that will,
        // silently routing them to slot 0.
        for (Method method : com.classwright.proxy.Enhancer.proxiedMethods(superclass,
                interfaces == null ? new Class<?>[0] : interfaces)) {
            Object callback = getCallback(method);
            if (!(callback instanceof Callback) && !(callback instanceof Class)) {
                // CGLib's contract: a Callback instance, or a Class for the types-only flow.
                throw new ClasswrightException("getCallback returned "
                        + (callback == null ? "null" : callback.getClass().getName())
                        + " for " + method + ", which is neither a Callback nor a Class");
            }
            byMethod.put(method, indexes.computeIfAbsent(callback, unused -> {
                callbacks.add(callback);
                return callbacks.size() - 1;
            }));
        }
    }

    /**
     * The callback for one method.
     *
     * <p>Returning the same object for several methods puts them on the same callback index, which
     * is what keeps the generated class small.
     *
     * @param method a method that will be proxied
     * @return the callback to use
     */
    protected abstract Object getCallback(Method method);

    /**
     * The callbacks, in index order.
     *
     * @return the callbacks, in the slot order this helper assigned
     * @throws IllegalStateException if {@link #getCallback} returned classes rather than
     *                               instances; use {@link #getCallbackTypes} with that flow
     */
    public Callback[] getCallbacks() {
        Callback[] instances = new Callback[callbacks.size()];
        for (int i = 0; i < instances.length; i++) {
            if (!(callbacks.get(i) instanceof Callback instance)) {
                throw new IllegalStateException("getCallback returned callback types, not "
                        + "instances; use getCallbackTypes() and createClass()");
            }
            instances[i] = instance;
        }
        return instances;
    }

    /**
     * The callback types, in index order — for the {@code createClass()} flow.
     *
     * @return each slot's callback type
     */
    public Class[] getCallbackTypes() {
        Class[] types = new Class[callbacks.size()];
        for (int i = 0; i < types.length; i++) {
            Object callback = callbacks.get(i);
            types[i] = callback instanceof Class type ? type : callback.getClass();
        }
        return types;
    }

    @Override
    public int accept(Method method) {
        Integer index = byMethod.get(method);
        return index == null ? 0 : index;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CallbackHelper that && byMethod.equals(that.byMethod);
    }

    @Override
    public int hashCode() {
        return byMethod.hashCode();
    }

}
