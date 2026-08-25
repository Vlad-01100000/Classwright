package com.classwright.beans;

/**
 * Transforms a value while {@link BeanCopier} moves it from one bean to another.
 *
 * <p>Lets a copier bridge types that do not match, or apply a rule per property: formatting a date,
 * trimming a string, mapping an enum. Without one, a copier only moves properties whose types are
 * identical.
 *
 * <p>Called for every property, so it must handle every type the copier can encounter, and it must
 * return something assignable to {@code targetType} — the generated code casts rather than checks.
 */
@FunctionalInterface
public interface Converter {

    /**
     * Converts one property value.
     *
     * @param value      the value read from the source bean, boxed; may be {@code null}
     * @param targetType the setter's declared parameter type, exactly as declared — for a
     *                   primitive setter this is {@code int.class}, not {@code Integer.class},
     *                   matching CGLib; the <em>returned</em> value should still be the boxed form
     * @param setterName the name of the setter it is destined for, for rules that key on it
     * @return the value to write; must be assignable to the boxed form of {@code targetType}
     */
    Object convert(Object value, Class<?> targetType, Object setterName);
}
