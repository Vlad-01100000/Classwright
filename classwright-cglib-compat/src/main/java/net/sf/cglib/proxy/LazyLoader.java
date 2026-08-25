package net.sf.cglib.proxy;

/**
 * Forwards every call to an object resolved once, on first use.
 *
 * <p>Reproduces {@code net.sf.cglib.proxy.LazyLoader}.
 *
 * @see com.classwright.proxy.LazyLoader
 */
public interface LazyLoader extends Callback {

    /**
     * Creates the object this proxy stands in for.
     *
     * @return the delegate
     * @throws Exception if it cannot be created
     */
    Object loadObject() throws Exception;
}
