package net.sf.cglib.beans;

import com.classwright.cglib.Coexistence;
import net.sf.cglib.core.Converter;

/**
 * Copies matching properties from one bean to another.
 *
 * <p>Reproduces {@code net.sf.cglib.beans.BeanCopier}, delegating to
 * {@link com.classwright.beans.BeanCopier}.
 *
 * @see com.classwright.beans.BeanCopier
 */
public abstract class BeanCopier {

    static {
        Coexistence.check();
    }

    /** Present so subclasses compile, as in CGLib; obtain instances through {@link #create}. */
    public BeanCopier() {
    }

    /**
     * Creates a copier between two bean types.
     *
     * @param source       the class to read from
     * @param target       the class to write to
     * @param useConverter whether {@link #copy} will be given a {@link Converter}
     * @return the copier
     */
    public static BeanCopier create(Class source, Class target, boolean useConverter) {
        com.classwright.beans.BeanCopier delegate =
                com.classwright.beans.BeanCopier.create(source, target, useConverter);
        return new BeanCopier() {

            /** The last converter's adapter; copy() is a bulk-loop hot path, and adapting the
             * same converter object on every call allocated per copied bean. An immutable pair
             * behind one volatile read keeps the race harmless. */
            private volatile CachedAdapter cached;

            @Override
            public void copy(Object from, Object to, Converter converter) {
                if (converter == null) {
                    delegate.copy(from, to, null);
                    return;
                }
                if (converter instanceof com.classwright.beans.Converter direct) {
                    // A converter implementing both interfaces skips adaptation entirely.
                    delegate.copy(from, to, direct);
                    return;
                }
                CachedAdapter adapter = cached;
                if (adapter == null || adapter.source() != converter) {
                    adapter = new CachedAdapter(converter, converter::convert);
                    cached = adapter;
                }
                delegate.copy(from, to, adapter.adapted());
            }
        };
    }

    private record CachedAdapter(Converter source, com.classwright.beans.Converter adapted) {
    }

    /**
     * Copies every matched property.
     *
     * @param from      the bean to read
     * @param to        the bean to write
     * @param converter applied to every value, or {@code null}
     */
    public abstract void copy(Object from, Object to, Converter converter);
}
