package com.classwright.proxy;

/**
 * A {@link Dispatcher} that is told which proxy the call arrived on.
 *
 * <p>Same per-call resolution, with the proxy instance passed in so one callback can serve many
 * proxies and pick a delegate per instance — reading an identifier off the proxy, or looking it up
 * in a map keyed by the proxy itself.
 *
 * <p>Do not call a proxied method on the {@code proxy} argument from inside
 * {@link #loadObject(Object)}: it comes straight back here and recurses.
 */
@FunctionalInterface
public interface ProxyRefDispatcher extends Callback {

    /**
     * The object to forward this call to.
     *
     * @param proxy the proxy instance the call arrived on
     * @return the delegate; must be assignable to the proxied type
     * @throws Exception if the delegate cannot be obtained
     */
    Object loadObject(Object proxy) throws Exception;
}
