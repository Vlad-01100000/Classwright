package com.classwright.proxy;

/**
 * Implemented by generated proxies, letting callers inspect and replace callbacks after creation,
 * and create further instances without going back through {@link Enhancer}.
 *
 * <p>Creating a second proxy through {@link #newInstance(Callback[])} skips class generation and
 * the cache lookup entirely, because the class already exists — it is the fastest way to produce
 * many proxies of one shape with different callbacks.
 *
 * <p>Mirrors {@code net.sf.cglib.proxy.Factory}. Switch it off with
 * {@link Enhancer#setUseFactory(boolean)} if the extra interface is unwelcome; the cost is losing
 * these operations.
 */
public interface Factory {

    /**
     * Creates another proxy of the same class with a single callback.
     *
     * @param callback the callback for every proxied method
     * @return a new proxy instance
     */
    Object newInstance(Callback callback);

    /**
     * Creates another proxy of the same class.
     *
     * @param callbacks one callback per index used by the callback filter
     * @return a new proxy instance
     */
    Object newInstance(Callback[] callbacks);

    /**
     * Creates another proxy using a specific superclass constructor.
     *
     * @param parameterTypes the constructor signature to invoke
     * @param arguments      the constructor arguments
     * @param callbacks      one callback per index used by the callback filter
     * @return a new proxy instance
     */
    Object newInstance(Class<?>[] parameterTypes, Object[] arguments, Callback[] callbacks);

    /**
     * The callback at the given index.
     *
     * @param index the callback index
     * @return the callback, or {@code null} if none is set
     */
    Callback getCallback(int index);

    /**
     * Replaces one callback on this instance.
     *
     * @param index    the callback index
     * @param callback the replacement
     */
    void setCallback(int index, Callback callback);

    /**
     * Replaces every callback on this instance.
     *
     * @param callbacks the replacements, one per index
     */
    void setCallbacks(Callback[] callbacks);

    /**
     * This instance's callbacks.
     *
     * @return a fresh array; modifying it does not affect the proxy
     */
    Callback[] getCallbacks();
}
