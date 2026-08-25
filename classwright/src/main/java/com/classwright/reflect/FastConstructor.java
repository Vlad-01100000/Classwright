package com.classwright.reflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * One constructor, callable without reflection.
 *
 * <p>The generated case performs a real {@code new} followed by {@code invokespecial}, so the
 * object is constructed exactly as {@code new Target(...)} would construct it. Nothing here skips
 * a constructor; that is a separate and deliberately awkward capability, reached through
 * {@link com.classwright.runtime.Instantiator#allocateWithoutConstructor()} and described there.
 */
public final class FastConstructor extends FastMember {

    FastConstructor(FastClass fastClass, Constructor<?> constructor, int index) {
        super(fastClass, constructor, index);
    }

    /**
     * Creates an instance.
     *
     * @param arguments the constructor arguments, boxed
     * @return the new instance
     * @throws InvocationTargetException wrapping anything the constructor threw
     */
    public Object newInstance(Object[] arguments) throws InvocationTargetException {
        return fastClass.newInstance(index, arguments);
    }

    @Override
    public Class<?>[] getParameterTypes() {
        return getJavaConstructor().getParameterTypes();
    }

    @Override
    public Class<?>[] getExceptionTypes() {
        return getJavaConstructor().getExceptionTypes();
    }

    /**
     * The underlying reflective constructor.
     *
     * @return the constructor this wraps
     */
    public Constructor<?> getJavaConstructor() {
        return (Constructor<?>) member;
    }
}
