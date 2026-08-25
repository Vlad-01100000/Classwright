package net.sf.cglib.beans;

import com.classwright.cglib.Coexistence;

/**
 * Reads or writes a chosen set of a bean's properties in one call.
 *
 * <p>Reproduces {@code net.sf.cglib.beans.BulkBean}, delegating to
 * {@link com.classwright.beans.BulkBean}.
 *
 * @see com.classwright.beans.BulkBean
 */
public class BulkBean {

    static {
        Coexistence.check();
    }

    private final com.classwright.beans.BulkBean delegate;

    private BulkBean(com.classwright.beans.BulkBean delegate) {
        this.delegate = delegate;
    }

    /**
     * Creates a bulk accessor.
     *
     * <p>An unresolvable accessor name is reported as CGLib reported it: a
     * {@link BulkBeanException} whose {@code getIndex()} names the failing position.
     *
     * @param target  the bean type
     * @param getters the read accessor names, in order
     * @param setters the write accessor names, in order
     * @param types   the property types, in order
     * @return the accessor
     */
    public static BulkBean create(Class target, String[] getters, String[] setters, Class[] types) {
        try {
            return new BulkBean(
                    com.classwright.beans.BulkBean.create(target, getters, setters, types));
        } catch (com.classwright.beans.BulkAccessException unresolvable) {
            throw new BulkBeanException("Cannot find specified property",
                    unresolvable.propertyIndex());
        }
    }

    /**
     * Reads every configured property into {@code values}.
     *
     * <p>Failures propagate raw — a getter's own exception, an
     * {@code ArrayIndexOutOfBoundsException} for an undersized array — exactly as CGLib's did.
     * Only the <em>write</em> side wraps with an index; CGLib's contract was asymmetric and the
     * asymmetry is reproduced.
     *
     * @param bean   the bean to read from
     * @param values the array to fill, in property order
     */
    public void getPropertyValues(Object bean, Object[] values) {
        delegate.getPropertyValues(bean, values);
    }

    /**
     * Reads every configured property into a new array.
     *
     * @param bean the bean to read from
     * @return the values, in property order
     */
    public Object[] getPropertyValues(Object bean) {
        return delegate.getPropertyValues(bean);
    }

    /**
     * Writes every configured property from {@code values}.
     *
     * <p>Whatever fails — the setter's own exception, a value of the wrong type, an undersized
     * array — arrives as CGLib's {@link BulkBeanException} with the failing index; earlier
     * positions have already been written, as in CGLib.
     *
     * @param bean   the bean to write to
     * @param values the values, in property order
     */
    public void setPropertyValues(Object bean, Object[] values) {
        try {
            delegate.setPropertyValues(bean, values);
        } catch (com.classwright.beans.BulkAccessException failure) {
            throw new BulkBeanException(failure.getCause(), failure.propertyIndex());
        }
    }

    /**
     * The configured property types.
     *
     * @return the types, in property order
     */
    public Class[] getPropertyTypes() {
        return delegate.getPropertyTypes();
    }

    /**
     * The configured getter names.
     *
     * @return the getter names, in property order
     */
    public String[] getGetters() {
        return delegate.getGetters();
    }

    /**
     * The configured setter names.
     *
     * @return the setter names, in property order
     */
    public String[] getSetters() {
        return delegate.getSetters();
    }
}
