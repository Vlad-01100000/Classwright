package net.sf.cglib.proxy;

/**
 * Implemented by generated proxies, exposing their callbacks and letting more be created.
 *
 * <p>Reproduces {@code net.sf.cglib.proxy.Factory}. Widely used — Spring, among others, casts
 * proxies to it — which is why it is reproduced in full rather than left to the Tier 2 list.
 *
 * @see com.classwright.proxy.Factory
 */
public interface Factory {

    /**
     * Creates another proxy of the same class with a single callback.
     *
     * @param callback the callback for every proxied method
     * @return a new proxy
     */
    Object newInstance(Callback callback);

    /**
     * Creates another proxy of the same class.
     *
     * @param callbacks one callback per index
     * @return a new proxy
     */
    Object newInstance(Callback[] callbacks);

    /**
     * Creates another proxy using a specific superclass constructor.
     *
     * @param types     the constructor signature
     * @param args      the constructor arguments
     * @param callbacks one callback per index
     * @return a new proxy
     */
    Object newInstance(Class[] types, Object[] args, Callback[] callbacks);

    /**
     * The callback at the given index.
     *
     * @param index the callback index
     * @return the callback originally supplied, or {@code null}
     */
    Callback getCallback(int index);

    /**
     * Replaces one callback on this instance.
     *
     * @param index    the callback index
     * @param callback the replacement, of the same kind as the original
     */
    void setCallback(int index, Callback callback);

    /**
     * Replaces every callback on this instance.
     *
     * @param callbacks the replacements
     */
    void setCallbacks(Callback[] callbacks);

    /**
     * This instance's callbacks.
     *
     * @return a fresh array
     */
    Callback[] getCallbacks();
}
