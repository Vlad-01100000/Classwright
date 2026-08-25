package com.classwright.proxy;

import java.lang.reflect.Method;

/**
 * Wraps every call to a proxied method, deciding whether and when to run the original.
 *
 * <p>The most general callback, and the one nearly every framework built on CGLib used: it is how
 * transactions, security checks, lazy loading, and caching get woven around a bean.
 *
 * <pre>{@code
 * MethodInterceptor timing = (proxy, method, args, methodProxy) -> {
 *     long start = System.nanoTime();
 *     try {
 *         return methodProxy.invokeSuper(proxy, args);
 *     } finally {
 *         System.out.println(method.getName() + " took " + (System.nanoTime() - start) + "ns");
 *     }
 * };
 * }</pre>
 *
 * <h2>Calling the original</h2>
 *
 * <p>Use {@link MethodProxy#invokeSuper} and not {@link Method#invoke}. The latter re-enters the
 * proxy and calls the interceptor again, which recurses until the stack runs out. This is the
 * single most common mistake with this API, and it was equally true of CGLib.
 *
 * <h2>Cost</h2>
 *
 * <p>Arguments arrive boxed in an {@code Object[]}, which is intrinsic to a contract that has to
 * accept any signature. Classwright keeps the generated method body small enough that the JIT can
 * inline the interceptor and, when it does, eliminate the array allocation entirely. Nothing else
 * about this interface is negotiable without giving up the "works for any method" property that
 * makes it useful.
 */
@FunctionalInterface
public interface MethodInterceptor extends Callback {

    /**
     * Handles a call to a proxied method.
     *
     * @param proxy       the proxy instance the call arrived on
     * @param method      the method being called, as declared on the proxied type
     * @param arguments   the arguments, boxed; writing to this array does not affect the caller
     * @param methodProxy the handle for invoking the original implementation
     * @return the value to return to the caller; boxed for primitive returns, and {@code null} is
     *         only valid for reference and {@code void} returns
     * @throws Throwable anything the method may throw, plus anything the interceptor chooses to
     *                   raise
     */
    Object intercept(Object proxy, Method method, Object[] arguments, MethodProxy methodProxy)
            throws Throwable;
}
