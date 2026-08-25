package net.sf.cglib.reflect;

/**
 * A member paired with its {@link FastClass} index.
 *
 * <p>Reproduces {@code net.sf.cglib.reflect.FastMember}.
 *
 * @see com.classwright.reflect.FastMember
 */
public abstract class FastMember {

    private final com.classwright.reflect.FastMember delegate;

    FastMember(com.classwright.reflect.FastMember delegate) {
        this.delegate = delegate;
    }

    /**
     * This member's index in its {@link FastClass}.
     *
     * @return the index
     */
    public int getIndex() {
        return delegate.getIndex();
    }

    /**
     * The member's name.
     *
     * @return the name
     */
    public String getName() {
        return delegate.getName();
    }

    /**
     * The class that declares this member.
     *
     * @return the declaring class
     */
    public Class getDeclaringClass() {
        return delegate.getDeclaringClass();
    }

    /**
     * The member's modifiers.
     *
     * @return the modifier bits
     */
    public int getModifiers() {
        return delegate.getModifiers();
    }

    /**
     * The parameter types.
     *
     * @return the parameter types, in order
     */
    public Class[] getParameterTypes() {
        return delegate.getParameterTypes();
    }

    /**
     * The declared exception types.
     *
     * @return the exception types
     */
    public Class[] getExceptionTypes() {
        return delegate.getExceptionTypes();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FastMember that && delegate.equals(that.delegate);
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
