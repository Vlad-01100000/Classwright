package net.sf.cglib.proxy;

import java.lang.reflect.Method;

/**
 * Handles a call with the same shape as {@link java.lang.reflect.InvocationHandler}.
 *
 * <p>Reproduces {@code net.sf.cglib.proxy.InvocationHandler}.
 *
 * @see com.classwright.proxy.InvocationHandler
 */
public interface InvocationHandler extends Callback {

    /**
     * Handles a call to a proxied method.
     *
     * @param proxy  the proxy instance
     * @param method the method being called
     * @param args   the arguments, boxed
     * @return the value to return
     * @throws Throwable anything the handler raises
     */
    Object invoke(Object proxy, Method method, Object[] args) throws Throwable;
}
