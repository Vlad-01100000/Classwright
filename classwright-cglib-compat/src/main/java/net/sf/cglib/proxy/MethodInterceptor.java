package net.sf.cglib.proxy;

import java.lang.reflect.Method;

/**
 * Wraps every call to a proxied method.
 *
 * <p>Reproduces {@code net.sf.cglib.proxy.MethodInterceptor}, signature for signature.
 *
 * @see com.classwright.proxy.MethodInterceptor
 */
public interface MethodInterceptor extends Callback {

    /**
     * Handles a call to a proxied method.
     *
     * @param obj    the proxy instance
     * @param method the method being called
     * @param args   the arguments, boxed
     * @param proxy  the handle for invoking the original implementation
     * @return the value to return to the caller
     * @throws Throwable anything the method or the interceptor raises
     */
    Object intercept(Object obj, Method method, Object[] args, MethodProxy proxy) throws Throwable;
}
