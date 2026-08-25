package com.classwright.proxy;

/**
 * Forwards every call to an object resolved once, on first use.
 *
 * <p>{@link #loadObject()} is called at most once per proxy instance and the result is cached in a
 * field on the proxy. Subsequent calls go straight to the cached delegate. This is the classic
 * lazy-initialisation proxy: hand out something that looks like the real object and only build the
 * real object when somebody actually touches it.
 *
 * <p>Differs from {@link Dispatcher} in exactly that caching. Use a {@code Dispatcher} when the
 * target may change between calls.
 *
 * <p>The cache is not synchronised, matching CGLib. Two threads racing on the first call may both
 * invoke {@link #loadObject()}, and one of the results is discarded. Make {@code loadObject}
 * idempotent, or synchronise inside it, if that matters.
 */
@FunctionalInterface
public interface LazyLoader extends Callback {

    /**
     * Creates the object this proxy stands in for.
     *
     * @return the delegate; must be assignable to the proxied type
     * @throws Exception if the delegate cannot be created
     */
    Object loadObject() throws Exception;
}
