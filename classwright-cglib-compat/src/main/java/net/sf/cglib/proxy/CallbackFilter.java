package net.sf.cglib.proxy;

import java.lang.reflect.Method;

/**
 * Decides which callback handles which method.
 *
 * <p>Reproduces {@code net.sf.cglib.proxy.CallbackFilter}. As in CGLib, implementations must define
 * {@code equals} and {@code hashCode}, because the filter is part of the key that decides whether
 * an already-generated class can be reused.
 *
 * @see com.classwright.proxy.CallbackFilter
 */
public interface CallbackFilter {

    /**
     * The index of the callback to use for this method.
     *
     * @param method a method that will be proxied
     * @return the zero-based callback index
     */
    int accept(Method method);

    @Override
    boolean equals(Object other);

    @Override
    int hashCode();
}
