package com.classwright.proxy;

import java.lang.reflect.Method;

/**
 * Handles a call with the same shape as {@link java.lang.reflect.InvocationHandler}.
 *
 * <p>Exists so that a handler written for the JDK's dynamic proxies can be reused against a
 * class-based proxy with no changes. CGLib offered the same bridge and it was widely used to move
 * interface-based code onto concrete classes.
 *
 * <p>Note what is missing compared to {@link MethodInterceptor}: there is no {@link MethodProxy},
 * so there is no way to call the original implementation. A handler here fully replaces the method.
 * Calling {@link Method#invoke} on the proxy instance re-enters the handler and recurses; invoking
 * it on a separate delegate object is the intended pattern.
 */
@FunctionalInterface
public interface InvocationHandler extends Callback {

    /**
     * Handles a call to a proxied method.
     *
     * @param proxy     the proxy instance the call arrived on
     * @param method    the method being called
     * @param arguments the arguments, boxed
     * @return the value to return to the caller
     * @throws Throwable anything the handler chooses to raise
     */
    Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable;
}
