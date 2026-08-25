package com.classwright.runtime.unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Finds a working {@link ConstructorSkippingAllocator}, or reports that none exists.
 *
 * <p>Two implementations are tried, in order of how long each is likely to keep working:
 *
 * <ol>
 *   <li><strong>{@code ReflectionFactory}</strong>, from the {@code jdk.unsupported} module. Its
 *       name says "unsupported" but the module exists precisely to keep such APIs reachable, and
 *       serialization itself is built on this mechanism. The most durable option available.</li>
 *   <li><strong>{@code Unsafe.allocateInstance}</strong>. Being retained for the medium term
 *       because deserialization libraries have nothing else, but on borrowed time.</li>
 * </ol>
 *
 * <p>Both are reached entirely through reflection, with no compile-time reference to any
 * JDK-internal type. That is deliberate: the library must compile, link, and start on a JDK where
 * these classes have been deleted outright. When that happens {@link #findBest()} returns the
 * unavailable allocator and the affected feature reports a clear error, while everything else
 * carries on.
 *
 * <p>{@link com.classwright.Internal @Internal}: the supported way to ask these questions is
 * {@link com.classwright.runtime.Capabilities} and {@link com.classwright.runtime.Instantiator};
 * the mechanism behind them must stay replaceable per JDK.
 */
@com.classwright.Internal
public final class Allocators {

    /** Probed once each. The answers cannot change while the JVM is running. */
    private static final ConstructorSkippingAllocator REFLECTION_FACTORY =
            new ReflectionFactoryAllocator();

    private static final ConstructorSkippingAllocator UNSAFE = new UnsafeAllocator();

    /** What the user sees on the JDK that finally removes both. Named so it can be asserted on. */
    static final String NOTHING_AVAILABLE =
            "Classwright tried ReflectionFactory and Unsafe, and neither is reachable here.\n"
                    + "Give the class an accessible constructor and let Classwright call it, or run "
                    + "on a JVM that still exposes one of those APIs.";

    private static final ConstructorSkippingAllocator BEST =
            firstAvailable(List.of(REFLECTION_FACTORY, UNSAFE), NOTHING_AVAILABLE);

    /**
     * Why {@code ReflectionFactory} is unusable for hidden classes, and what to do instead. Stated
     * once here because it is the kind of thing that otherwise surfaces as a bare
     * {@code NoClassDefFoundError} naming a class the reader has never heard of.
     */
    private static final ConstructorSkippingAllocator HIDDEN_UNSUPPORTED = new UnavailableAllocator(
            "Allocating a hidden class without running a constructor needs "
                    + "Unsafe.allocateInstance, which is not available on this JVM.\n"
                    + "ReflectionFactory cannot substitute for it: the serialization constructor it "
                    + "fabricates resolves its target class by name, and a hidden class has no "
                    + "resolvable name.\n"
                    + "Either let Classwright call a constructor, or generate with "
                    + "DefinitionStrategy.named(), whose classes have ordinary names.");

    private Allocators() {
    }

    /**
     * What the availability probes actually allocate.
     *
     * <p>The private constructor is the point: an allocator that produces an instance of this
     * class has demonstrably skipped construction, on this JVM, under this JVM's restrictions.
     * Resolving the reflective members proves much less — a future JDK can keep the members
     * visible and refuse the call — and this library's whole posture is to find that out at
     * probe time, not in a user's stack trace.
     */
    static final class ProbeTarget {

        private ProbeTarget() {
            throw new AssertionError("the probe must not run this constructor");
        }
    }

    /**
     * The best available allocator, ignoring what it will be asked to allocate.
     *
     * <p>Use for reporting. To actually allocate something, use {@link #forClass(Class)} &mdash;
     * the right choice depends on the class.
     *
     * @return never {@code null}; check {@link ConstructorSkippingAllocator#isAvailable()}
     */
    public static ConstructorSkippingAllocator findBest() {
        return BEST;
    }

    /**
     * The allocator that can actually handle {@code type}.
     *
     * <p>Hidden classes are the reason this is not simply {@link #findBest()}.
     * {@code ReflectionFactory.newConstructorForSerialization} generates a constructor accessor
     * that names its target class, and a hidden class's name &mdash; complete with its
     * {@code /0x...} suffix &mdash; cannot be resolved, so invoking it throws
     * {@code NoClassDefFoundError}. {@code Unsafe.allocateInstance} takes the {@link Class} object
     * directly and is unaffected.
     *
     * <p>Since hidden classes are the default definition strategy, that ordering matters: preferring
     * {@code ReflectionFactory} unconditionally would break constructor-skipping on the common path
     * while working perfectly in any test that used an ordinary class.
     *
     * @param type the class to be allocated
     * @return never {@code null}; check {@link ConstructorSkippingAllocator#isAvailable()}
     */
    public static ConstructorSkippingAllocator forClass(Class<?> type) {
        if (!type.isHidden()) {
            return BEST;
        }
        return UNSAFE.isAvailable() ? UNSAFE : HIDDEN_UNSUPPORTED;
    }

    private static ConstructorSkippingAllocator firstAvailable(
            List<ConstructorSkippingAllocator> candidates, String reasonIfNone) {
        for (ConstructorSkippingAllocator candidate : candidates) {
            if (candidate.isAvailable()) {
                return candidate;
            }
        }
        return new UnavailableAllocator(reasonIfNone);
    }

    /**
     * Allocates via {@code sun.reflect.ReflectionFactory.newConstructorForSerialization}, the same
     * mechanism the JDK's own deserialization uses.
     *
     * <p>It fabricates a constructor for {@code type} whose body is {@code Object}'s constructor, so
     * invoking it produces a fully allocated object on which nothing else has run.
     */
    static final class ReflectionFactoryAllocator implements ConstructorSkippingAllocator {

        private final Object factory;
        private final Method newConstructorForSerialization;
        private final Constructor<?> objectConstructor;

        /**
         * Fabricated serialization constructors, one per class.
         *
         * <p>Fabricating one is the expensive part &mdash; and a <em>freshly</em> fabricated
         * {@link Constructor} always takes the slow native accessor path, since the JIT-friendly
         * accessor is only generated after repeated calls on the same object. Caching turns every
         * allocation after the first from microseconds into the cost of a plain
         * {@code newInstance}. A {@link ClassValue} keeps the entry on the class itself, so it is
         * collected with the class and pins nothing.
         */
        private final ClassValue<Constructor<?>> serialisationConstructors = new ClassValue<>() {
            @Override
            protected Constructor<?> computeValue(Class<?> type) {
                try {
                    Constructor<?> fabricated = (Constructor<?>) newConstructorForSerialization
                            .invoke(factory, type, objectConstructor);
                    fabricated.setAccessible(true);
                    return fabricated;
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    throw new IllegalArgumentException(
                            "cannot allocate " + type.getName() + " without a constructor", e);
                }
            }
        };

        ReflectionFactoryAllocator() {
            Object resolvedFactory = null;
            Method resolvedMethod = null;
            Constructor<?> resolvedObjectConstructor = null;
            try {
                Class<?> reflectionFactory = Class.forName("sun.reflect.ReflectionFactory");
                resolvedFactory = reflectionFactory
                        .getDeclaredMethod("getReflectionFactory").invoke(null);
                resolvedMethod = reflectionFactory.getDeclaredMethod(
                        "newConstructorForSerialization", Class.class, Constructor.class);
                resolvedObjectConstructor = Object.class.getDeclaredConstructor();
            } catch (Throwable ignored) {
                // Absent, encapsulated, or removed. All three mean the same thing to us.
            }
            this.factory = resolvedFactory;
            this.newConstructorForSerialization = resolvedMethod;
            this.objectConstructor = resolvedObjectConstructor;

            boolean succeeded = false;
            if (resolvedFactory != null && resolvedMethod != null) {
                try {
                    Constructor<?> fabricated = (Constructor<?>) resolvedMethod
                            .invoke(resolvedFactory, ProbeTarget.class, resolvedObjectConstructor);
                    fabricated.setAccessible(true);
                    succeeded = fabricated.newInstance() instanceof ProbeTarget;
                } catch (Throwable refused) {
                    // Members resolved but the mechanism is refused here; report unavailable.
                }
            }
            this.probeSucceeded = succeeded;
        }

        /** Set by one real allocation at construction; see {@link ProbeTarget}. */
        private final boolean probeSucceeded;

        @Override
        public String name() {
            return "ReflectionFactory";
        }

        @Override
        public boolean isAvailable() {
            return probeSucceeded;
        }

        @Override
        public Object allocate(Class<?> type) {
            requireAvailable(this);
            requireInstantiable(type);
            // Refused here rather than attempted, because attempting it fails deep inside a
            // generated accessor with a NoClassDefFoundError naming a class nobody wrote.
            if (type.isHidden()) {
                throw new IllegalArgumentException("cannot allocate the hidden class "
                        + type.getName() + " without a constructor: the serialization constructor "
                        + "ReflectionFactory fabricates resolves its target class by name, and a "
                        + "hidden class has no resolvable name. Unsafe.allocateInstance can do it; "
                        + "Allocators.forClass routes hidden classes there.");
            }
            try {
                return serialisationConstructors.get(type).newInstance();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                throw new IllegalArgumentException(
                        "cannot allocate " + type.getName() + " without a constructor", e);
            }
        }
    }

    /** Allocates via {@code sun.misc.Unsafe.allocateInstance}. */
    static final class UnsafeAllocator implements ConstructorSkippingAllocator {

        private final Object unsafe;
        private final Method allocateInstance;

        /**
         * The same method as a bound {@link java.lang.invoke.MethodHandle}, derived once.
         *
         * <p>{@code Method.invoke} boxes its arguments into a fresh {@code Object[]} on every call
         * and dispatches through the reflection machinery; the handle does neither. Hidden classes
         * always allocate through this path, so it is the common one.
         */
        private final java.lang.invoke.MethodHandle allocateHandle;

        UnsafeAllocator() {
            Object resolvedUnsafe = null;
            Method resolvedMethod = null;
            try {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                resolvedUnsafe = theUnsafe.get(null);
                resolvedMethod = unsafeClass.getMethod("allocateInstance", Class.class);
            } catch (Throwable ignored) {
                // Expected on a JDK that has removed it, and on one that has locked it down.
            }
            this.unsafe = resolvedUnsafe;
            this.allocateInstance = resolvedMethod;

            java.lang.invoke.MethodHandle resolvedHandle = null;
            if (resolvedUnsafe != null && resolvedMethod != null) {
                try {
                    resolvedHandle = java.lang.invoke.MethodHandles.lookup()
                            .unreflect(resolvedMethod)
                            .bindTo(resolvedUnsafe)
                            .asType(java.lang.invoke.MethodType.methodType(
                                    Object.class, Class.class));
                } catch (Throwable ignored) {
                    // unreflect refused; Method.invoke below still works, just slower.
                }
            }
            this.allocateHandle = resolvedHandle;

            boolean succeeded = false;
            if (resolvedUnsafe != null && resolvedMethod != null) {
                try {
                    succeeded = resolvedMethod.invoke(resolvedUnsafe, ProbeTarget.class)
                            instanceof ProbeTarget;
                } catch (Throwable refused) {
                    // Members resolved but the call is refused here; report unavailable.
                }
            }
            this.probeSucceeded = succeeded;
        }

        /** Set by one real allocation at construction; see {@link ProbeTarget}. */
        private final boolean probeSucceeded;

        @Override
        public String name() {
            return "Unsafe.allocateInstance";
        }

        @Override
        public boolean isAvailable() {
            return probeSucceeded;
        }

        @Override
        public Object allocate(Class<?> type) {
            requireAvailable(this);
            requireInstantiable(type);
            try {
                if (allocateHandle != null) {
                    return allocateHandle.invokeExact(type);
                }
                return allocateInstance.invoke(unsafe, type);
            } catch (Error error) {
                throw error;
            } catch (Throwable e) {
                throw new IllegalArgumentException(
                        "cannot allocate " + type.getName() + " without a constructor", e);
            }
        }
    }

    /** What you get when nothing applicable works. Fails with an explanation, not a stack trace. */
    static final class UnavailableAllocator implements ConstructorSkippingAllocator {

        private final String reason;

        UnavailableAllocator(String reason) {
            this.reason = reason;
        }

        @Override
        public String name() {
            return "none";
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Object allocate(Class<?> type) {
            throw new UnsupportedOperationException(
                    "Cannot create an instance of " + type.getName()
                            + " without running a constructor.\n" + reason);
        }
    }

    private static void requireAvailable(ConstructorSkippingAllocator allocator) {
        if (!allocator.isAvailable()) {
            throw new UnsupportedOperationException(
                    allocator.name() + " is not available on this JVM");
        }
    }

    private static void requireInstantiable(Class<?> type) {
        if (type.isPrimitive() || type.isArray() || type.isInterface()
                || java.lang.reflect.Modifier.isAbstract(type.getModifiers())) {
            throw new IllegalArgumentException(
                    type.getName() + " cannot be instantiated: it is "
                            + (type.isPrimitive() ? "a primitive"
                            : type.isArray() ? "an array"
                            : type.isInterface() ? "an interface" : "abstract"));
        }
    }
}
