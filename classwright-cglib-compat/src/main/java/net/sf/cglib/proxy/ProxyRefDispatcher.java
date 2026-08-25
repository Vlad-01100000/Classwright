package net.sf.cglib.proxy;

/**
 * A {@link Dispatcher} that is told which proxy the call arrived on.
 *
 * <p>Reproduces {@code net.sf.cglib.proxy.ProxyRefDispatcher}.
 *
 * @see com.classwright.proxy.ProxyRefDispatcher
 */
public interface ProxyRefDispatcher extends Callback {

    /**
     * The object to forward this call to.
     *
     * @param proxy the proxy the call arrived on
     * @return the delegate
     * @throws Exception if it cannot be obtained
     */
    Object loadObject(Object proxy) throws Exception;
}
