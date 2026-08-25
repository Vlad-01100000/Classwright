package net.sf.cglib.beans;

import com.classwright.cglib.Coexistence;

/**
 * Wraps a bean so that its setters throw.
 *
 * <p>Reproduces {@code net.sf.cglib.beans.ImmutableBean}.
 *
 * @see com.classwright.beans.ImmutableBean
 */
public class ImmutableBean {

    static {
        Coexistence.check();
    }

    private ImmutableBean() {
    }

    /**
     * Creates a read-only view of a bean.
     *
     * @param bean the bean to wrap
     * @return a view of the same type whose setters throw
     */
    public static Object create(Object bean) {
        return com.classwright.beans.ImmutableBean.create(bean);
    }
}
