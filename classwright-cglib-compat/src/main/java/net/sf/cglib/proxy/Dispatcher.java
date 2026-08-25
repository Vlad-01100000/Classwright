package net.sf.cglib.proxy;

/**
 * Forwards every call to an object chosen fresh each time.
 *
 * <p>Reproduces {@code net.sf.cglib.proxy.Dispatcher}.
 *
 * @see com.classwright.proxy.Dispatcher
 */
public interface Dispatcher extends Callback {

    /**
     * The object to forward this call to.
     *
     * @return the delegate
     * @throws Exception if it cannot be obtained
     */
    Object loadObject() throws Exception;
}
