package net.sf.cglib.reflect;

import com.classwright.cglib.Coexistence;

/**
 * Binds one method of one object to a single-method interface.
 *
 * <p>Reproduces {@code net.sf.cglib.reflect.MethodDelegate}'s factory surface, delegating to
 * {@link com.classwright.reflect.MethodDelegate}. The generated class is cached per shape exactly
 * as CGLib cached it, so calling a factory per event stays cheap.
 *
 * <p><strong>One deliberate difference:</strong> the factories return {@link Object}, not this
 * type. CGLib's generated class extended its {@code MethodDelegate}; Classwright's extends
 * {@code com.classwright.reflect.MethodDelegate}, and a class cannot extend both. Code that casts
 * the result to the requested interface — the near-universal usage — compiles and runs unchanged;
 * code that declared a variable of this type changes it to {@code Object} or to the interface.
 * The instance surface — {@code getTarget()} and {@code newInstance(Object)} — is reached by
 * casting to {@code com.classwright.reflect.MethodDelegate} instead.
 *
 * @see com.classwright.reflect.MethodDelegate
 */
public final class MethodDelegate {

    static {
        Coexistence.check();
    }

    private MethodDelegate() {
    }

    /**
     * Binds an instance method.
     *
     * @param target        the object to forward to
     * @param methodName    the method to call on it
     * @param interfaceType a single-method interface whose signature the method matches
     * @return an object implementing {@code interfaceType}; cast it to that interface
     */
    public static Object create(Object target, String methodName, Class interfaceType) {
        return com.classwright.reflect.MethodDelegate.create(target, methodName, interfaceType);
    }

    /**
     * Binds a static method.
     *
     * @param targetClass   the class declaring the method
     * @param methodName    the method to call
     * @param interfaceType a single-method interface whose signature the method matches
     * @return an object implementing {@code interfaceType}; cast it to that interface
     */
    public static Object createStatic(Class targetClass, String methodName, Class interfaceType) {
        return com.classwright.reflect.MethodDelegate.createStatic(targetClass, methodName,
                interfaceType);
    }
}
