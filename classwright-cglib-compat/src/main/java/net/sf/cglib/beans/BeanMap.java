package net.sf.cglib.beans;

import com.classwright.cglib.Coexistence;

import java.util.AbstractMap;
import java.util.Set;

/**
 * A {@link java.util.Map} view over a bean's properties.
 *
 * <p>Reproduces {@code net.sf.cglib.beans.BeanMap}, delegating to
 * {@link com.classwright.beans.BeanMap}.
 *
 * @see com.classwright.beans.BeanMap
 */
public class BeanMap extends AbstractMap<String, Object> {

    static {
        Coexistence.check();
    }

    /**
     * Limit the map to properties with a getter; a {@code Generator.setRequire} flag in CGLib,
     * reproduced for code that references the constant.
     */
    public static final int REQUIRE_GETTER = 1;

    /** As {@link #REQUIRE_GETTER}, for setters. */
    public static final int REQUIRE_SETTER = 2;

    private final com.classwright.beans.BeanMap delegate;

    private BeanMap(com.classwright.beans.BeanMap delegate) {
        this.delegate = delegate;
    }

    /**
     * Creates a map view over a bean.
     *
     * @param bean the bean to wrap
     * @return the view
     * @throws IllegalArgumentException for a {@code null} bean, with CGLib's exact message
     */
    public static BeanMap create(Object bean) {
        if (bean == null) {
            // CGLib failed before ever reaching a generator, with this message; migrated code
            // that catches or asserts on it must keep working.
            throw new IllegalArgumentException("Class of bean unknown");
        }
        return new BeanMap(com.classwright.beans.BeanMap.create(bean));
    }

    /**
     * A {@code null} key is rejected as CGLib rejected it — every keyed operation except
     * {@code containsKey} threw {@link NullPointerException}. The native map is tolerant
     * (answers {@code null}); the facade reproduces the legacy contract, since migrated code
     * distinguishes "no such property" from "you passed null".
     */
    private static Object requireKey(Object key) {
        if (key == null) {
            throw new NullPointerException("null key");
        }
        return key;
    }

    @Override
    public Object get(Object key) {
        return delegate.get(requireKey(key));
    }

    @Override
    public Object put(String key, Object value) {
        requireKey(key);
        return delegate.put(key, value);
    }

    @Override
    public Set<String> keySet() {
        return delegate.keySet();
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        // CGLib's entry view was read-only: entry.setValue threw UnsupportedOperationException
        // and the bean stayed untouched. The native map's entries deliberately write through —
        // better Map behaviour — but migrated code that relies on the write being REFUSED must
        // get the refusal. Writes go through put(), on this map or on the facade.
        return java.util.Collections.unmodifiableMap(delegate).entrySet();
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public boolean containsKey(Object key) {
        // The one null-tolerant keyed operation in CGLib: no property is named null.
        return key != null && delegate.containsKey(key);
    }

    /**
     * The bean this view reads and writes.
     *
     * @return the bean this map reads and writes
     */
    public Object getBean() {
        return delegate.getBean();
    }

    /**
     * Points this view at a different bean of the same type.
     *
     * @param bean the bean to read and write from now on
     */
    public void setBean(Object bean) {
        delegate.setBean(bean);
    }

    /**
     * The declared type of a property, or {@code null} if there is no such property.
     *
     * @param name the property name
     * @return its declared type, or {@code null} if there is no such property
     */
    public Class getPropertyType(String name) {
        requireKey(name);
        return delegate.getPropertyType(name);
    }

    /**
     * Another view of the same shape over a different bean.
     *
     * @param bean the bean the new map should wrap
     * @return a map over {@code bean}, reusing this map's generated class
     */
    public BeanMap newInstance(Object bean) {
        return new BeanMap(delegate.newInstance(bean));
    }

    /**
     * Reads a property directly off {@code bean}, without repointing this view — CGLib's
     * three-argument accessor, reproduced. Allocation-free after warmup: the native map indexes
     * the key and calls the generated accessor with the supplied bean.
     *
     * @param bean a bean of the same type this map was created for
     * @param key  the property name
     * @return the property's value on {@code bean}
     */
    public Object get(Object bean, Object key) {
        return delegate.get(bean, requireKey(key));
    }

    /**
     * Writes a property directly on {@code bean}, without repointing this view.
     *
     * @param bean  a bean of the same type this map was created for
     * @param key   the property name
     * @param value the value to set
     * @return the property's previous value on {@code bean}
     */
    public Object put(Object bean, Object key, Object value) {
        return delegate.put(bean, requireKey(key), value);
    }
}
