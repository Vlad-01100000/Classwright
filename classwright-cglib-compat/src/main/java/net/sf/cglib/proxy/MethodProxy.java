package net.sf.cglib.proxy;

import net.sf.cglib.core.Signature;

/**
 * The handle a {@link MethodInterceptor} uses to call the method it intercepted.
 *
 * <p>Reproduces {@code net.sf.cglib.proxy.MethodProxy}, wrapping the Classwright one.
 *
 * <p>Instances are cached on the Classwright {@code MethodProxy} they wrap rather than allocated
 * per call. A wrapper per invocation would put an allocation on the hot path; a static map keyed by
 * the wrapped object would hold every proxy class alive forever, which is the leak the library
 * exists to avoid. Attaching it to the object gives it exactly the right lifetime.
 *
 * @see com.classwright.proxy.MethodProxy
 */
public class MethodProxy {

    private final com.classwright.proxy.MethodProxy delegate;

    private MethodProxy(com.classwright.proxy.MethodProxy delegate) {
        this.delegate = delegate;
    }

    /**
     * The wrapper for a Classwright method proxy, creating it on first use.
     *
     * <p>Racing threads may each create one; the loser is discarded, which is cheaper than a lock
     * on a path that runs per intercepted call.
     */
    static MethodProxy wrapping(com.classwright.proxy.MethodProxy delegate) {
        Object cached = delegate.attachment();
        if (cached instanceof MethodProxy wrapper) {
            return wrapper;
        }
        MethodProxy wrapper = new MethodProxy(delegate);
        delegate.setAttachment(wrapper);
        return wrapper;
    }

    /**
     * Runs the original implementation.
     *
     * @param obj  the proxy the interceptor was given
     * @param args the arguments to pass on
     * @return the original method's result
     * @throws Throwable whatever the original throws
     */
    public Object invokeSuper(Object obj, Object[] args) throws Throwable {
        return delegate.invokeSuper(obj, args);
    }

    /**
     * Performs an ordinary virtual call on another object.
     *
     * <p>Passing the proxy here re-enters the interceptor and recurses, exactly as in CGLib.
     *
     * @param obj  the object to call
     * @param args the arguments
     * @return the result
     * @throws Throwable whatever the target throws
     */
    public Object invoke(Object obj, Object[] args) throws Throwable {
        return delegate.invoke(obj, args);
    }

    /**
     * The method's name.
     *
     * @return the intercepted method's name
     */
    public String getSignatureName() {
        return delegate.getName();
    }

    /**
     * The method's name and descriptor.
     *
     * <p>CGLib's {@code Signature} exposed ASM {@code Type} objects as well. Those accessors are
     * absent here, because reproducing them would mean depending on ASM — the dependency this
     * library exists to remove. Name and descriptor carry the same information; see
     * {@link Signature}.
     *
     * @return the signature
     */
    public Signature getSignature() {
        // Interceptors call this per invocation; CGLib returned a cached instance. The benign
        // race is the String.hashCode idiom: both winners build equal values.
        Signature cached = signature;
        if (cached == null) {
            cached = new Signature(delegate.getName(), delegate.getDescriptor());
            signature = cached;
        }
        return cached;
    }

    private Signature signature;

    /**
     * The index this method occupies in the proxy's dispatch table.
     *
     * @return this method's position in the proxy's super-dispatch table
     */
    public int getSuperIndex() {
        return delegate.getSuperIndex();
    }

    /**
     * The generated super-call method's name, as CGLib reported it.
     *
     * @return the name of the generated method that reaches the original implementation
     */
    public String getSuperName() {
        return "CGLIB$" + delegate.getName() + "$" + delegate.getSuperIndex();
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
