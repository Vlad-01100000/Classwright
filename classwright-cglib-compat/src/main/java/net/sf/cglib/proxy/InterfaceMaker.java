package net.sf.cglib.proxy;

import com.classwright.cglib.Coexistence;

import java.lang.reflect.Method;

/**
 * Builds an interface at runtime from a chosen set of method shapes.
 *
 * <p>Reproduces the ASM-free surface of {@code net.sf.cglib.core.InterfaceMaker} (which CGLib
 * housed in {@code core}; it is provided here and re-exported from neither package to avoid the
 * ASM-typed {@code add(Signature, Type[])} overload, which cannot exist without the dependency
 * this library removes — add methods by {@link Method} or by shape instead).
 *
 * <p>Generated interfaces are ordinary named classes and are never unloaded; build one per shape
 * and reuse it.
 *
 * @see com.classwright.proxy.InterfaceMaker
 */
public class InterfaceMaker {

    static {
        Coexistence.check();
    }

    /** Creates a maker with no methods, as CGLib's constructor did. */
    public InterfaceMaker() {
    }

    private final com.classwright.proxy.InterfaceMaker delegate =
            new com.classwright.proxy.InterfaceMaker();

    /**
     * Adds a method with the same name and signature as an existing one.
     *
     * @param method the method whose shape to copy
     */
    public void add(Method method) {
        delegate.add(method);
    }

    /**
     * Adds every public instance method of a type.
     *
     * @param type the class or interface whose methods to copy
     */
    public void add(Class type) {
        delegate.addAll(type);
    }

    /**
     * Creates the interface.
     *
     * @return the generated interface
     */
    public Class create() {
        return delegate.create();
    }
}
