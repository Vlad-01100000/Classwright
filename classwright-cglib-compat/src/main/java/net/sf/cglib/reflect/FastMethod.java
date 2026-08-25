package net.sf.cglib.reflect;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * One method, callable without reflection.
 *
 * <p>Reproduces {@code net.sf.cglib.reflect.FastMethod}.
 *
 * @see com.classwright.reflect.FastMethod
 */
public class FastMethod extends FastMember {

    private final com.classwright.reflect.FastMethod delegate;

    FastMethod(com.classwright.reflect.FastMethod delegate) {
        super(delegate);
        this.delegate = delegate;
    }

    /**
     * Invokes the method.
     *
     * @param obj  the receiver, or {@code null} for a static method
     * @param args the arguments, boxed
     * @return the result, boxed
     * @throws InvocationTargetException if the method threw
     */
    public Object invoke(Object obj, Object[] args) throws InvocationTargetException {
        return delegate.invoke(obj, args);
    }

    /**
     * The method's return type.
     *
     * @return the return type
     */
    public Class getReturnType() {
        return delegate.getReturnType();
    }

    /**
     * The method this wraps.
     *
     * @return the method
     */
    public Method getJavaMethod() {
        return delegate.getJavaMethod();
    }
}
