package com.classwright.proxy;

/**
 * Hands callbacks to a proxy while it is being constructed.
 *
 * <p><strong>Not part of the public API.</strong> Public only because generated classes live in the
 * target's package and call into it.
 *
 * <h2>Why a thread-local, of all things</h2>
 *
 * <p>A proxy's callbacks must be in place before any of its methods can run, and a superclass
 * constructor is entitled to call an overridable method on {@code this}. That leaves nowhere to
 * pass them: a constructor parameter would change the signature the superclass expects, and setting
 * fields after construction is too late for anything the constructor itself triggered.
 *
 * <p>So the callbacks are parked here, the constructor collects them immediately after chaining to
 * {@code super}, and they are cleared again. CGLib solved it the same way, and the constraint is
 * the JVM's rather than either library's.
 *
 * <p>The window is a single constructor call on one thread, so the value never outlives the call
 * that set it and cannot leak between requests. {@link #clear()} runs in a {@code finally} block.
 */
@com.classwright.Internal
public final class CallbackRegistry {

    /**
     * A parked set of callbacks and the exact generated class they are intended for.
     *
     * <p>The class is what keeps a nested construction honest: a superclass constructor may
     * construct some <em>other</em> proxy — directly, without Classwright — while this frame is
     * parked, and without the class check that unrelated proxy would collect this frame's
     * callbacks. A frame only ever binds to instances of its own class.
     *
     * <p>Only <em>nested</em> constructions materialise as frames; see {@link ConstructionState}.
     */
    private record PendingFrame(Class<?> proxyClass, Callback[] callbacks) {
    }

    /**
     * One thread's in-flight constructions: the innermost held inline, outer ones in an overflow
     * stack.
     *
     * <p>Still LIFO — a superclass constructor is entitled to create another proxy before the
     * outer constructor has collected its callbacks, and constructions nest strictly, so the
     * innermost is the only one whose constructor can be running. What the shape buys is that
     * the overwhelmingly common case, a single non-reentrant construction, reuses this object's
     * two fields and allocates nothing: a frame object per construction was the last remaining
     * allocation on the cached create path. Only a construction that begins while another is in
     * flight pushes the outer one into the overflow stack, and only that path allocates.
     *
     * <p>{@link #clear()} nulls both fields: a thread-local that kept the last proxy class and
     * callbacks reachable after construction would pin them per thread, which is precisely the
     * sort of quiet retention this library exists to end.
     */
    private static final class ConstructionState {

        Class<?> proxyClass;
        Callback[] callbacks;

        /** Outer constructions while a nested one runs; lazily created, usually never. */
        java.util.ArrayDeque<PendingFrame> overflow;
    }

    /**
     * Stays installed once a thread has constructed anything — a thread that constructed one
     * proxy will construct more, and re-creating the state per construction was measurable
     * allocation on the cache-hit path.
     */
    private static final ThreadLocal<ConstructionState> PENDING = new ThreadLocal<>();

    /**
     * Per-class registrations for this thread; see {@code Enhancer.registerCallbacks}.
     *
     * <p>Not {@code withInitial}: most threads never register, and the null check keeps them from
     * each growing an empty map.
     */
    private static final ThreadLocal<java.util.Map<Class<?>, Callback[]>> THREAD_REGISTERED =
            new ThreadLocal<>();

    /**
     * Per-class registrations for every thread; see {@code Enhancer.registerStaticCallbacks}.
     *
     * <p>A {@link ClassValue} rather than a map, for the usual reason: the entry lives on the
     * proxy class and dies with it, so registering callbacks cannot pin the class or its loader.
     */
    private static final ClassValue<java.util.concurrent.atomic.AtomicReference<Callback[]>>
            STATIC_REGISTERED = new ClassValue<>() {
        @Override
        protected java.util.concurrent.atomic.AtomicReference<Callback[]> computeValue(
                Class<?> proxyClass) {
            return new java.util.concurrent.atomic.AtomicReference<>();
        }
    };

    /**
     * Per-class fallbacks, consulted only when neither a pending frame nor a registration
     * provides callbacks.
     *
     * <p>A separate tier below {@link #STATIC_REGISTERED}, not a use of it, and the separation
     * is the point: the static registration is the <em>user's</em> channel, written by
     * {@code Enhancer.registerStaticCallbacks} and never touched by {@code create()} — CGLib
     * behaved the same way. The compatibility layer installs its {@code Factory} bridge here so
     * that Factory methods stay reachable on instances created with nothing registered at all,
     * without ever disturbing a registration the user has made; and when a user <em>clears</em>
     * a registration, lookup falls back here instead of to nothing.
     */
    private static final ClassValue<java.util.concurrent.atomic.AtomicReference<Callback[]>>
            DEFAULTS = new ClassValue<>() {
        @Override
        protected java.util.concurrent.atomic.AtomicReference<Callback[]> computeValue(
                Class<?> proxyClass) {
            return new java.util.concurrent.atomic.AtomicReference<>();
        }
    };

    private CallbackRegistry() {
    }

    /**
     * Parks callbacks for a construction of {@code proxyClass} about to run on this thread.
     *
     * @param proxyClass the generated class being constructed
     * @param callbacks  the callbacks to bind
     */
    public static void bind(Class<?> proxyClass, Callback[] callbacks) {
        ConstructionState state = PENDING.get();
        if (state == null) {
            state = new ConstructionState();
            PENDING.set(state);
        } else if (state.proxyClass != null) {
            // A construction is already in flight; park it until the nested one completes.
            if (state.overflow == null) {
                state.overflow = new java.util.ArrayDeque<>(2);
            }
            state.overflow.push(new PendingFrame(state.proxyClass, state.callbacks));
        }
        state.proxyClass = proxyClass;
        state.callbacks = callbacks;
    }

    /**
     * The parked callbacks intended for this exact class, if a construction of it is in flight
     * on this thread.
     *
     * <p>Only the innermost construction is consulted: constructions nest strictly, so it is the
     * only one whose constructor — or whose superclass constructor's virtual calls — can be
     * running.
     */
    static Callback[] pendingFor(Class<?> proxyClass) {
        ConstructionState state = PENDING.get();
        return state != null && state.proxyClass == proxyClass ? state.callbacks : null;
    }

    /**
     * Registers callbacks for a proxy class, for this thread, until replaced.
     *
     * <p>The registration serves two flows CGLib supported and frameworks use: instances created
     * through the class's own constructor pick registered callbacks up during construction, and
     * instances created <em>without</em> a constructor (Objenesis-style) bind lazily on their
     * first dispatched call.
     *
     * @param proxyClass the generated proxy class
     * @param callbacks  the callbacks, or {@code null} to remove this thread's registration
     */
    public static void register(Class<?> proxyClass, Callback[] callbacks) {
        java.util.Map<Class<?>, Callback[]> registered = THREAD_REGISTERED.get();
        if (callbacks == null) {
            if (registered != null) {
                registered.remove(proxyClass);
                if (registered.isEmpty()) {
                    THREAD_REGISTERED.remove();
                }
            }
            return;
        }
        if (registered == null) {
            registered = new java.util.HashMap<>(4);
            THREAD_REGISTERED.set(registered);
        }
        registered.put(proxyClass, callbacks.clone());
    }

    /**
     * As {@link #register}, but visible to every thread. The per-thread registration wins.
     *
     * @param proxyClass the generated proxy class
     * @param callbacks  the callbacks, or {@code null} to remove the registration
     */
    public static void registerStatic(Class<?> proxyClass, Callback[] callbacks) {
        STATIC_REGISTERED.get(proxyClass).set(callbacks == null ? null : callbacks.clone());
    }

    /**
     * Installs per-class fallback callbacks; see {@link #DEFAULTS} for when they apply.
     *
     * @param proxyClass the generated proxy class
     * @param callbacks  the fallback callbacks, or {@code null} to remove them
     */
    public static void registerDefaults(Class<?> proxyClass, Callback[] callbacks) {
        DEFAULTS.get(proxyClass).set(callbacks == null ? null : callbacks.clone());
    }

    /**
     * The callbacks registered for a class: this thread's registration first, then the global
     * one, then the per-class defaults.
     *
     * @param proxyClass the generated proxy class
     * @return the registered callbacks, or {@code null} when nothing is registered
     */
    static Callback[] registeredFor(Class<?> proxyClass) {
        java.util.Map<Class<?>, Callback[]> registered = THREAD_REGISTERED.get();
        if (registered != null) {
            Callback[] found = registered.get(proxyClass);
            if (found != null) {
                return found;
            }
        }
        Callback[] global = STATIC_REGISTERED.get(proxyClass).get();
        if (global != null) {
            return global;
        }
        return DEFAULTS.get(proxyClass).get();
    }

    /**
     * Collects the callbacks the constructor now running should bind: the ones parked by
     * {@link #bind}, or failing that a registration for the proxy's class. Called from generated
     * constructors.
     *
     * @param proxy the instance under construction
     * @return the callbacks, or {@code null} if the proxy was constructed directly with nothing
     *         registered, in which case its callback fields stay null and every method falls
     *         through to the original implementation
     */
    public static Callback[] collect(Object proxy) {
        Callback[] pending = pendingFor(proxy.getClass());
        if (pending != null) {
            return pending;
        }
        return registeredFor(proxy.getClass());
    }

    /**
     * Unparks the innermost construction's callbacks. Always call this in a {@code finally}.
     *
     * <p>The state object itself stays installed for the thread's next construction; its fields
     * are nulled (or restored to the enclosing construction's) so nothing outlives the
     * constructor call that parked it.
     */
    public static void clear() {
        ConstructionState state = PENDING.get();
        if (state == null) {
            return;
        }
        java.util.ArrayDeque<PendingFrame> overflow = state.overflow;
        PendingFrame outer = overflow == null ? null : overflow.poll();
        if (outer != null) {
            state.proxyClass = outer.proxyClass();
            state.callbacks = outer.callbacks();
        } else {
            state.proxyClass = null;
            state.callbacks = null;
        }
    }
}
