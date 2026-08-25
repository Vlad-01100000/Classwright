package net.sf.cglib.reflect;

import com.classwright.cglib.Coexistence;

/**
 * Binds a constructor to a single-method interface, turning it into a factory.
 *
 * <p>Reproduces {@code net.sf.cglib.reflect.ConstructorDelegate}'s factory surface, delegating to
 * {@link com.classwright.reflect.ConstructorDelegate}. The interface method's parameters select
 * the constructor, and the generated factory class is cached per (target, interface) pair, as
 * CGLib cached it.
 *
 * <p><strong>One deliberate difference:</strong> the factory returns {@link Object}, not this
 * type. CGLib's generated class extended its {@code ConstructorDelegate}; Classwright's extends
 * {@code com.classwright.reflect.ConstructorDelegate}, and a class cannot extend both. Code that
 * casts the result to the factory interface — the near-universal usage — compiles and runs
 * unchanged; code that declared a variable of this type changes it to {@code Object} or to the
 * interface.
 *
 * @see com.classwright.reflect.ConstructorDelegate
 */
public final class ConstructorDelegate {

    static {
        Coexistence.check();
    }

    private ConstructorDelegate() {
    }

    /**
     * Binds the constructor whose parameters match the interface method.
     *
     * @param targetClass   the class to construct
     * @param interfaceType a single-method interface; its parameters select the constructor and
     *                      its return type must accept the constructed object
     * @return an object implementing {@code interfaceType}; cast it to that interface
     */
    public static Object create(Class targetClass, Class interfaceType) {
        return com.classwright.reflect.ConstructorDelegate.create(targetClass, interfaceType);
    }
}
