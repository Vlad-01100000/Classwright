package com.classwright.reflect;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * One method, callable without reflection.
 *
 * <pre>{@code
 * FastMethod greet = FastClass.create(Service.class)
 *         .getMethod("greet", new Class<?>[]{String.class});
 * Object result = greet.invoke(service, new Object[]{"world"});
 * }</pre>
 *
 * <p>The lookup happens once, here; each {@link #invoke} then goes straight to the generated
 * switch. Using {@link FastClass#invoke(String, Class[], Object, Object[])} instead would repeat
 * the lookup on every call.
 */
public final class FastMethod extends FastMember {

    FastMethod(FastClass fastClass, Method method, int index) {
        super(fastClass, method, index);
    }

    /**
     * Invokes the method.
     *
     * @param target    the receiver, or {@code null} for a static method
     * @param arguments the arguments, boxed
     * @return the result, boxed; {@code null} for a {@code void} method
     * @throws InvocationTargetException wrapping anything the method threw
     */
    public Object invoke(Object target, Object[] arguments) throws InvocationTargetException {
        return fastClass.invoke(index, target, arguments);
    }

    /**
     * The method's return type.
     *
     * @return the return type
     */
    public Class<?> getReturnType() {
        return getJavaMethod().getReturnType();
    }

    @Override
    public Class<?>[] getParameterTypes() {
        return getJavaMethod().getParameterTypes();
    }

    @Override
    public Class<?>[] getExceptionTypes() {
        return getJavaMethod().getExceptionTypes();
    }

    /**
     * The underlying reflective method.
     *
     * @return the method this wraps
     */
    public Method getJavaMethod() {
        return (Method) member;
    }
}
