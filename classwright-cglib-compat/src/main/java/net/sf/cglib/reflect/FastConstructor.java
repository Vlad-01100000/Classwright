package net.sf.cglib.reflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * One constructor, callable without reflection.
 *
 * <p>Reproduces {@code net.sf.cglib.reflect.FastConstructor}.
 *
 * @see com.classwright.reflect.FastConstructor
 */
public class FastConstructor extends FastMember {

    private final com.classwright.reflect.FastConstructor delegate;

    FastConstructor(com.classwright.reflect.FastConstructor delegate) {
        super(delegate);
        this.delegate = delegate;
    }

    /**
     * Creates an instance with no arguments — CGLib's convenience overload.
     *
     * @return the new instance
     * @throws InvocationTargetException if the constructor threw
     */
    public Object newInstance() throws InvocationTargetException {
        return delegate.newInstance(NO_ARGUMENTS);
    }

    private static final Object[] NO_ARGUMENTS = {};

    /**
     * Creates an instance.
     *
     * @param args the arguments, boxed
     * @return the new instance
     * @throws InvocationTargetException if the constructor threw
     */
    public Object newInstance(Object[] args) throws InvocationTargetException {
        return delegate.newInstance(args);
    }

    /**
     * The constructor this wraps.
     *
     * @return the constructor
     */
    public Constructor getJavaConstructor() {
        return delegate.getJavaConstructor();
    }
}
