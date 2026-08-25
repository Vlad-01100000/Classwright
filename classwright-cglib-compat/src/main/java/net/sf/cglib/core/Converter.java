package net.sf.cglib.core;

/**
 * Transforms a value while a {@code BeanCopier} moves it between beans.
 *
 * <p>Reproduces {@code net.sf.cglib.core.Converter}.
 *
 * @see com.classwright.beans.Converter
 */
public interface Converter {

    /**
     * Converts one property value.
     *
     * @param value   the value read from the source bean
     * @param target  the type the setter expects
     * @param context the setter's name
     * @return the value to write
     */
    Object convert(Object value, Class target, Object context);
}
