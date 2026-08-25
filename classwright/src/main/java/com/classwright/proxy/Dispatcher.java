package com.classwright.proxy;

/**
 * Forwards every call to an object chosen fresh each time.
 *
 * <p>{@link #loadObject()} runs on <em>every</em> invocation, so the proxy can point at a different
 * instance from one call to the next. That is what makes it the right tool for scoped beans: a
 * singleton-scoped proxy injected once can resolve to the current request's or session's instance
 * at each call.
 *
 * <p>Use {@link LazyLoader} instead when the target is fixed and merely expensive to create; it
 * resolves once and remembers, which is both cheaper and easier to reason about.
 */
@FunctionalInterface
public interface Dispatcher extends Callback {

    /**
     * The object to forward this call to.
     *
     * @return the delegate; must be assignable to the proxied type
     * @throws Exception if the delegate cannot be obtained
     */
    Object loadObject() throws Exception;
}
