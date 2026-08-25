package net.sf.cglib.proxy;

import com.classwright.cglib.Coexistence;

/**
 * Combines several objects behind the union of their interfaces.
 *
 * <p>Reproduces {@code net.sf.cglib.proxy.Mixin}'s factory surface, delegating to
 * {@link com.classwright.proxy.Mixin}. Each interface routes to the first delegate implementing
 * it, and the generated class is cached per shape exactly as CGLib cached it.
 *
 * <p><strong>One deliberate difference:</strong> the factories return {@link Object}, not this
 * type. CGLib's generated class extended its {@code Mixin}; Classwright's extends
 * {@code com.classwright.proxy.Mixin}, and a class cannot extend both. Code that casts the result
 * to its business interfaces — the near-universal usage — compiles and runs unchanged; code that
 * declared a variable of type {@code Mixin} changes it to {@code Object} or to an interface.
 * {@code createBean} (the {@code STYLE_BEANS} generator) is not reproduced; see the
 * compatibility allowlist.
 */
public abstract class Mixin {

    static {
        Coexistence.check();
    }

    /** Route every interface to the first delegate implementing it; the {@code create} default. */
    public static final int STYLE_INTERFACES = 0;

    /** Merge JavaBean properties instead of interfaces; the {@code createBean} style. */
    public static final int STYLE_BEANS = 1;

    /** Route interfaces and bean properties both. */
    public static final int STYLE_EVERYTHING = 2;

    /** Present so subclasses compile, as in CGLib. */
    public Mixin() {
    }

    /**
     * Another combination of the same shape over different delegates, as CGLib declared it.
     *
     * <p>Never called on objects returned by the factories here, which are not instances of this
     * type; it exists so code compiled against CGLib's abstract shape still compiles.
     *
     * @param delegates the new delegates, in the same order as the original combination
     * @return an object combining the new delegates
     */
    public abstract Mixin newInstance(Object[] delegates);

    /**
     * Combines the delegates, implementing every interface each of them implements.
     *
     * @param delegates the objects to combine, in precedence order
     * @return an object implementing the union of their interfaces; cast to the wanted one
     */
    public static Object create(Object[] delegates) {
        return com.classwright.proxy.Mixin.create(delegates);
    }

    /**
     * Combines the delegates, implementing exactly the interfaces named.
     *
     * @param interfaces the interfaces the result should implement
     * @param delegates  the objects to combine
     * @return an object implementing those interfaces; cast to the wanted one
     */
    public static Object create(Class[] interfaces, Object[] delegates) {
        return com.classwright.proxy.Mixin.create(interfaces, delegates);
    }

    /**
     * The classes of the delegates, in order — CGLib's helper, reproduced.
     *
     * @param delegates any objects
     * @return each delegate's class, in the same order
     */
    public static Class[] getClasses(Object[] delegates) {
        Class[] classes = new Class[delegates.length];
        for (int i = 0; i < delegates.length; i++) {
            classes[i] = delegates[i].getClass();
        }
        return classes;
    }
}
