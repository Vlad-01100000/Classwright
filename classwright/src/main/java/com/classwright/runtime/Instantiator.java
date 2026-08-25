package com.classwright.runtime;

import com.classwright.ClasswrightException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates instances of a generated class.
 *
 * <p>Needed as its own concept because a hidden class cannot be instantiated the ordinary way. It
 * has no resolvable name, so no other class's bytecode can name it in a {@code new} instruction and
 * {@code Class.forName} will not find it. The only route in is the {@link java.lang.invoke.MethodHandles.Lookup}
 * handed back when it was defined.
 *
 * <p>Handles are cached per signature. Deriving one costs a lookup and some {@code MethodType}
 * work, which is trivial once and wasteful on every proxy an application creates.
 */
public final class Instantiator {

    private static final Class<?>[] NO_TYPES = {};

    private final DefinedClass definedClass;
    private final Map<MethodType, MethodHandle> constructors = new ConcurrentHashMap<>(2);

    /** {@code ()Object}, derived on first use; racy publication of equivalents is fine. */
    private volatile MethodHandle noArgConstructor;

    private Instantiator(DefinedClass definedClass) {
        this.definedClass = definedClass;
    }

    /**
     * An instantiator for a class that has just been defined.
     *
     * @param definedClass the class to create instances of
     * @return an instantiator for it
     */
    public static Instantiator forClass(DefinedClass definedClass) {
        return new Instantiator(definedClass);
    }

    /**
     * Instantiators for generated classes served from {@link GenerationCache}.
     *
     * <p>A cache stores only the {@code Class}, but creating an instance of a hidden class needs
     * the {@link java.lang.invoke.MethodHandles.Lookup} it was defined with — so the definer
     * registers here, and a later cache hit recovers the instantiator (and its already-adapted
     * constructor handles) from the class alone. A {@link ClassValue} keeps the entry on the
     * generated class itself: it lives exactly as long as the class and pins nothing, the same
     * no-leak shape as the cache it complements.
     */
    private static final ClassValue<java.util.concurrent.atomic.AtomicReference<Instantiator>>
            REGISTRY = new ClassValue<>() {
        @Override
        protected java.util.concurrent.atomic.AtomicReference<Instantiator> computeValue(
                Class<?> type) {
            return new java.util.concurrent.atomic.AtomicReference<>();
        }
    };

    /**
     * Creates an instantiator and remembers it, so {@link #forGenerated(Class)} can recover it.
     *
     * <p>Call this where the class is defined, before its {@code Class} is handed to a cache.
     *
     * @param definedClass the class just defined
     * @return the registered instantiator (an earlier registration wins a race, so callers always
     *         share one)
     */
    public static Instantiator register(DefinedClass definedClass) {
        java.util.concurrent.atomic.AtomicReference<Instantiator> slot =
                REGISTRY.get(definedClass.type());
        slot.compareAndSet(null, new Instantiator(definedClass));
        return slot.get();
    }

    /**
     * The instantiator registered for a generated class.
     *
     * @param generated a class previously passed through {@link #register}
     * @return its instantiator
     * @throws ClasswrightException if the class was never registered — which means it did not
     *                              come from a Classwright definer in this JVM
     */
    public static Instantiator forGenerated(Class<?> generated) {
        Instantiator registered = REGISTRY.get(generated).get();
        if (registered == null) {
            throw new ClasswrightException(generated.getName() + " has no registered "
                    + "instantiator; it was not defined by Classwright in this JVM");
        }
        return registered;
    }

    /**
     * The class instances are created of.
     *
     * @return the generated class
     */
    public Class<?> type() {
        return definedClass.type();
    }

    /**
     * Creates an instance using the no-argument constructor.
     *
     * <p>The common case, so it carries none of the general path's machinery: no empty arrays,
     * no {@link MethodType}, no map probe — a field read and the call.
     *
     * @return a new instance
     * @throws ClasswrightException if there is no such constructor, or it threw
     */
    public Object newInstance() {
        MethodHandle constructor = noArgConstructor;
        if (constructor == null) {
            constructor = deriveNoArgConstructor();
        }
        try {
            return constructor.invokeExact();
        } catch (Throwable t) {
            throw asInstantiationFailure(t);
        }
    }

    private MethodHandle deriveNoArgConstructor() {
        MethodHandle derived = definedClass.constructor(NO_TYPES)
                .asType(MethodType.methodType(Object.class));
        noArgConstructor = derived;
        return derived;
    }

    /**
     * Creates an instance using the constructor matching {@code parameterTypes}.
     *
     * @param parameterTypes the constructor signature to invoke
     * @param arguments      the arguments, in order
     * @return a new instance
     * @throws ClasswrightException if there is no such constructor, or it threw
     */
    public Object newInstance(Class<?>[] parameterTypes, Object[] arguments) {
        if (parameterTypes.length != arguments.length) {
            throw new IllegalArgumentException("got " + parameterTypes.length
                    + " parameter types but " + arguments.length + " arguments");
        }
        MethodType signature = MethodType.methodType(void.class, parameterTypes);
        // The cached handle is the fully adapted one. asSpreader adapts the exact signature to one
        // taking an Object[], so a single call site handles any arity — and both adaptations are
        // built once per signature, not per call. Rebuilding them per call costs far more than the
        // constructor call itself and defeats inlining, which is the mistake this cache exists to
        // avoid (see ProxySupport.constructorFor for the same lesson learned on the proxy path).
        MethodHandle constructor = constructors.computeIfAbsent(signature,
                wanted -> definedClass.constructor(parameterTypes)
                        .asSpreader(Object[].class, wanted.parameterCount())
                        .asType(MethodType.methodType(Object.class, Object[].class)));
        try {
            return constructor.invokeExact(arguments);
        } catch (Throwable t) {
            throw asInstantiationFailure(t);
        }
    }

    /**
     * Creates an instance <strong>without running any constructor</strong>.
     *
     * <p>Every field is left at its default value. Use only when a constructor genuinely cannot be
     * called &mdash; the superclass has none accessible, or running it twice would be wrong.
     * A class whose invariants are established in its constructor will not have them here.
     *
     * <p>Depends on an unstable JVM capability that will eventually be withdrawn — the
     * mechanism is internal and replaceable per JDK, which is why no allocator type appears in
     * any signature here. Check {@link Capabilities#constructorSkipping()} first if there is a
     * fallback worth taking; {@link Capabilities#constructorSkippingMechanism()} names what is
     * in use.
     *
     * @return a new, uninitialised instance
     * @throws UnsupportedOperationException if this JVM offers no way to do it
     */
    public Object allocateWithoutConstructor() {
        // allocatorFor, not allocator: hidden classes need Unsafe specifically, and hidden is what
        // the default strategy produces.
        return Capabilities.allocatorFor(definedClass.type()).allocate(definedClass.type());
    }

    private RuntimeException asInstantiationFailure(Throwable t) {
        if (t instanceof Error error) {
            // An OutOfMemoryError or LinkageError is not ours to reinterpret.
            throw error;
        }
        if (t instanceof RuntimeException runtime) {
            // A constructor that threw should surface its own exception, not be wrapped in ours.
            return runtime;
        }
        return new ClasswrightException("could not instantiate the generated class "
                + definedClass.type().getName() + ": " + t, t);
    }
}
