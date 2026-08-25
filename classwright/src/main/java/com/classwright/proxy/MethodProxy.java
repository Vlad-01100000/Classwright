package com.classwright.proxy;

import com.classwright.ClasswrightException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

/**
 * The handle a {@link MethodInterceptor} uses to call the method it intercepted.
 *
 * <p>One instance exists per proxied method, created at generation time and stored in a static
 * field of the generated class, so obtaining it costs a field read.
 *
 * <h2>invokeSuper versus invoke</h2>
 *
 * <p>{@link #invokeSuper} runs the <em>original</em> implementation on the proxy itself, the way
 * {@code super.method()} would. This is what an interceptor almost always wants, and it does not
 * re-enter the interceptor.
 *
 * <p>{@link #invoke} performs an ordinary virtual call on some <em>other</em> object. Calling it
 * with the proxy as the target sends the call straight back through the interceptor and recurses
 * until the stack is exhausted. The same trap existed in CGLib and caught a great many people.
 */
public final class MethodProxy {

    private final int superIndex;
    private final String name;
    private final String descriptor;
    private final Method method;
    private final boolean hasSuperImplementation;

    /** Built on first use of {@link #invoke}; most interceptors never touch it. */
    private volatile MethodHandle virtualInvoker;

    /**
     * Generated index dispatch for {@link #invoke}, built on first use.
     *
     * <p>An immutable pair behind one volatile read, so a racing initialisation can never pair
     * one build's {@code FastClass} with another's index.
     */
    private volatile FastDispatch fastDispatch;

    /** Set when {@link #buildFastDispatch()} concluded the method cannot go through FastClass. */
    private volatile boolean fastRefused;

    private record FastDispatch(com.classwright.reflect.FastClass fast, int index) {
    }

    /** See {@link #attachment()}. */
    private volatile Object attachment;

    MethodProxy(int superIndex, Method method, String descriptor,
                boolean hasSuperImplementation) {
        this.superIndex = superIndex;
        this.method = method;
        this.name = method.getName();
        this.descriptor = descriptor;
        this.hasSuperImplementation = hasSuperImplementation;
    }

    /**
     * Runs the original implementation.
     *
     * <p>Dispatches through {@link SuperDispatcher}, which the proxy implements as a switch over
     * {@link #getSuperIndex()} performing a direct {@code invokespecial}. No reflection and no
     * method handles are involved.
     *
     * @param proxy     the proxy instance the interceptor was given
     * @param arguments the arguments to pass on, boxed
     * @return the original method's result, boxed; {@code null} for {@code void}
     * @throws Throwable whatever the original method throws
     */
    public Object invokeSuper(Object proxy, Object[] arguments) throws Throwable {
        if (!hasSuperImplementation) {
            throw new AbstractMethodError(name + descriptor
                    + " is abstract and has no original implementation to call. Return a value "
                    + "from the interceptor instead of delegating.");
        }
        if (!(proxy instanceof SuperDispatcher dispatcher)) {
            throw new ClasswrightException("invokeSuper was given "
                    + (proxy == null ? "null" : proxy.getClass().getName())
                    + ", which is not a Classwright proxy. Pass the proxy the interceptor "
                    + "received.");
        }
        return dispatcher.cwInvokeSuper(superIndex, arguments);
    }

    /**
     * Performs an ordinary virtual call on another object.
     *
     * <p>For delegating to a separate implementation. Passing the proxy here re-enters the
     * interceptor and recurses; use {@link #invokeSuper} for that case.
     *
     * @param target    the object to call; must be an instance of the declaring type
     * @param arguments the arguments, boxed
     * @return the result, boxed
     * @throws Throwable whatever the target method throws
     */
    public Object invoke(Object target, Object[] arguments) throws Throwable {
        FastDispatch dispatch = fastDispatch;
        if (dispatch == null && !fastRefused) {
            dispatch = buildFastDispatch();
        }
        if (dispatch != null) {
            try {
                return dispatch.fast().invoke(dispatch.index(), target, arguments);
            } catch (java.lang.reflect.InvocationTargetException e) {
                // This method's contract is the bare exception, unlike Method.invoke's.
                throw e.getCause();
            }
        }
        MethodHandle invoker = virtualInvoker;
        if (invoker == null) {
            invoker = buildVirtualInvoker();
        }
        return invoker.invokeExact(target, arguments);
    }

    /**
     * Builds and caches generated dispatch for {@link #invoke}.
     *
     * <p>The preferred path: a {@code FastClass} switch performs a direct call — the mechanism
     * CGLib used here — where the previous generic adapted {@code MethodHandle} paid roughly 4×
     * per call. It also touches no {@code setAccessible} for the public methods that make up
     * almost every target, which matters under strong encapsulation. Methods the generated
     * dispatch cannot reach (a protected method, a closed package) fall back to the handle path
     * below. Deliberately not synchronised; racing builds produce equivalent values.
     */
    private FastDispatch buildFastDispatch() {
        try {
            com.classwright.reflect.FastClass fast =
                    com.classwright.reflect.FastClass.create(method.getDeclaringClass());
            int index = fast.getIndex(method);
            if (index >= 0) {
                FastDispatch built = new FastDispatch(fast, index);
                fastDispatch = built;
                return built;
            }
        } catch (ClasswrightException unusable) {
            // Generation refused (closed package, unusual loader); the handle path still works.
            // Deliberately only this type: a refusal is an expected environment limitation, but
            // an arbitrary RuntimeException here is a generator bug, and swallowing one would
            // silently convert a correctness failure into a permanent 4x dispatch slowdown that
            // no test notices. Bugs propagate; refusals fall back.
            //
            // One category of ClasswrightException is still a bug: definition paths wrap the
            // JVM's rejection of generated bytes (VerifyError, ClassFormatError) in this type
            // too, and a rejected class means the generator emitted something invalid — an
            // environment cannot make bytes malformed. Those must not be absorbed either.
            if (unusable.getCause() instanceof LinkageError) {
                throw unusable;
            }
        }
        fastRefused = true;
        return null;
    }

    /**
     * Builds and caches a handle for {@link #invoke}, for methods generated dispatch cannot
     * reach.
     *
     * <p>Deliberately not synchronised. Two threads racing here produce two equivalent handles and
     * one is discarded, which is cheaper than a lock on a path that is not hot.
     */
    private MethodHandle buildVirtualInvoker() {
        // Best effort, not a requirement: setAccessible widens what unreflect() accepts (a
        // protected method, say), but under strong encapsulation it can be refused for a method
        // that unreflect() would happily handle anyway — a public method of an exported package.
        // Failing outright on the refusal would deny exactly the calls the module system permits.
        try {
            method.setAccessible(true);
        } catch (RuntimeException notPermitted) {
            // InaccessibleObjectException or a SecurityManager veto; unreflect() decides below.
        }
        try {
            MethodHandle handle = MethodHandles.lookup().unreflect(method);
            MethodHandle spread = handle
                    .asSpreader(Object[].class, method.getParameterCount())
                    .asType(MethodType.methodType(Object.class, Object.class, Object[].class));
            virtualInvoker = spread;
            return spread;
        } catch (IllegalAccessException | RuntimeException e) {
            throw new ClasswrightException(
                    "cannot build an invoker for " + method + ": " + e, e);
        }
    }

    /**
     * The method's name.
     *
     * @return the intercepted method's name
     */
    public String getName() {
        return name;
    }

    /**
     * The method's JVM descriptor, e.g. {@code (IJ)Ljava/lang/String;}.
     *
     * <p>CGLib exposed a {@code Signature} object here, but that type wraps ASM's {@code Type} and
     * reproducing it would mean taking the dependency this library exists to avoid. A descriptor
     * string carries the same information.
     *
     * @return the intercepted method's descriptor, in JVM form
     */
    public String getDescriptor() {
        return descriptor;
    }

    /**
     * This method's position in the proxy's dispatch table.
     *
     * @return this method's position in the proxy's super-dispatch table
     */
    public int getSuperIndex() {
        return superIndex;
    }

    /**
     * Whether there is an original implementation at all; false for abstract methods.
     *
     * @return whether the original method has a body, so {@link #invokeSuper} can reach it
     */
    public boolean hasSuperImplementation() {
        return hasSuperImplementation;
    }

    /**
     * An opaque per-proxied-method slot for an adapter or framework to cache one wrapper in.
     *
     * <p><strong>Supported API, deliberately.</strong> Any layer that adapts these method
     * proxies to its own interface — the CGLib compatibility layer is the canonical example, an
     * application framework's own interception facade is the same shape — faces the same three
     * options: allocate the wrapper on every intercepted call (an allocation on the hot path),
     * cache it in a map keyed by this object (a global structure that holds every proxy class
     * alive forever — the leak this library exists to avoid), or cache it here, where it has
     * exactly this object's lifetime and costs a field read. The third is the only good answer,
     * which is why the slot is part of the contract rather than compatibility plumbing.
     *
     * <p>One slot, because one adapter at a time is the only case that arises; an outer adapter
     * wrapping an inner one caches its own composition. Writes race benignly — two threads
     * building equivalent wrappers publish one and discard the other, which is cheaper than any
     * synchronisation on a per-call path.
     *
     * @return whatever was last attached, or {@code null}
     */
    public Object attachment() {
        return attachment;
    }

    /**
     * Attaches an adapter's wrapper. See {@link #attachment()}.
     *
     * <p>Not synchronised. Two threads racing produce two equivalent wrappers and one is discarded,
     * which is cheaper than a lock on a path that runs per intercepted call.
     *
     * @param attachment the value to remember
     */
    public void setAttachment(Object attachment) {
        this.attachment = attachment;
    }

    @Override
    public String toString() {
        return "MethodProxy[" + name + descriptor + " #" + superIndex + "]";
    }
}
