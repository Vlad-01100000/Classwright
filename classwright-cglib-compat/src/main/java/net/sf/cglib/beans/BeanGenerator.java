package net.sf.cglib.beans;

import com.classwright.cglib.Coexistence;

/**
 * Builds a bean class at runtime from a set of property names and types.
 *
 * <p>Reproduces {@code net.sf.cglib.beans.BeanGenerator}, delegating to
 * {@link com.classwright.beans.BeanGenerator}.
 *
 * <p>Note that these classes are named rather than hidden, and therefore never unload — the bean
 * utilities call their accessors by name, and a hidden class has none. Build one per shape at
 * startup, not per request. The same was true of CGLib, so nothing has got worse; it is simply
 * worth saying, because the rest of the library made the opposite promise.
 *
 * @see com.classwright.beans.BeanGenerator
 */
public class BeanGenerator {

    static {
        Coexistence.check();
    }

    /** Creates a generator with no properties, as CGLib's constructor did. */
    public BeanGenerator() {
    }

    private final com.classwright.beans.BeanGenerator delegate =
            new com.classwright.beans.BeanGenerator();

    /**
     * Adds a property to the bean being generated.
     *
     * @param name the property name
     * @param type the property type
     */
    public void addProperty(String name, Class type) {
        delegate.addProperty(name, type);
    }

    /**
     * Sets the class the generated bean extends.
     *
     * @param superclass the class to extend
     */
    public void setSuperclass(Class superclass) {
        delegate.setSuperclass(superclass);
    }

    /**
     * Generates the bean class and creates an instance.
     *
     * @return a new instance of the generated bean
     */
    public Object create() {
        return delegate.create();
    }

    /**
     * Generates the bean class without creating an instance.
     *
     * <p>Declared to return {@link Object}, exactly as CGLib declared it — the return type is
     * part of the JVM method descriptor, and a client compiled against CGLib links against
     * {@code createClass()Ljava/lang/Object;}. Declaring the friendlier {@code Class} here
     * produced a {@link NoSuchMethodError} for precompiled callers on a dependency-only swap.
     * The object returned is still the generated {@link Class}.
     *
     * @return the generated class
     */
    public Object createClass() {
        return delegate.createClass();
    }

    /**
     * Adds a property per map entry — CGLib's helper, reproduced.
     *
     * @param gen   the generator to add to
     * @param props property name to property type
     */
    public static void addProperties(BeanGenerator gen, java.util.Map props) {
        for (Object entry : props.entrySet()) {
            java.util.Map.Entry e = (java.util.Map.Entry) entry;
            gen.addProperty((String) e.getKey(), (Class) e.getValue());
        }
    }

    /**
     * Adds every JavaBean property of a class — CGLib's helper, reproduced.
     *
     * <p>Like CGLib's, this introspects with {@code java.beans}, so it needs the
     * {@code java.desktop} module — the same requirement CGLib's {@code ReflectUtils} had. The
     * rest of the compatibility layer does not.
     *
     * @param gen  the generator to add to
     * @param type the bean class whose properties should be reproduced
     */
    public static void addProperties(BeanGenerator gen, Class type) {
        try {
            java.beans.BeanInfo info = java.beans.Introspector.getBeanInfo(type, Object.class);
            addProperties(gen, info.getPropertyDescriptors());
        } catch (java.beans.IntrospectionException e) {
            throw new com.classwright.ClasswrightException(
                    "could not introspect " + type.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Adds a property per descriptor — CGLib's helper, reproduced.
     *
     * @param gen         the generator to add to
     * @param descriptors the properties to reproduce
     */
    public static void addProperties(BeanGenerator gen,
                                     java.beans.PropertyDescriptor[] descriptors) {
        for (java.beans.PropertyDescriptor descriptor : descriptors) {
            // Indexed properties surface a null propertyType; skip them as CGLib's generator
            // effectively did (it could not emit an accessor pair for them either).
            if (descriptor.getPropertyType() != null) {
                gen.addProperty(descriptor.getName(), descriptor.getPropertyType());
            }
        }
    }
}
