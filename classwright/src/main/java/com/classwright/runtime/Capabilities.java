package com.classwright.runtime;

import com.classwright.core.AccessFlags;
import com.classwright.core.CwClassWriter;
import com.classwright.core.CwMethodType;
import com.classwright.core.CwType;
import com.classwright.runtime.unsafe.Allocators;
import com.classwright.runtime.unsafe.ConstructorSkippingAllocator;

import java.lang.invoke.MethodHandles;

/**
 * What this particular JVM can actually do.
 *
 * <p>Probed once, at first use, and cached. Nothing here is inferred from a version string: a JVM
 * that claims to support a feature may still refuse it because of module rules, a security manager,
 * or a vendor decision, and the only trustworthy probe is to attempt the thing itself. So the
 * hidden-class probe really does generate a class and define it.
 *
 * <h2>Why capabilities exist as a concept</h2>
 *
 * <p>CGLib assumed the JVM would keep letting it do what it had always done. When JDK 16 turned on
 * strong encapsulation the assumption became false, and because the assumption was spread through
 * the codebase there was no graceful path &mdash; the library simply stopped working.
 *
 * <p>Classwright treats every such assumption as a question with a runtime answer. A feature that
 * depends on an unstable API asks first, and has something sensible to do when the answer is no.
 * When a future JDK removes one of these, the affected feature reports a clear error and the rest
 * of the library carries on.
 *
 * <h2>Forcing a capability off</h2>
 *
 * <p>Any capability can be disabled with a system property, which is how you test the degraded path
 * without waiting for a JDK to remove something:
 *
 * <pre>{@code -Dclasswright.disable.hiddenClasses=true}
 * {@code -Dclasswright.disable.constructorSkipping=true}</pre>
 */
public final class Capabilities {

    private static final String DISABLE_PREFIX = "classwright.disable.";

    private static final class Holder {
        static final boolean HIDDEN_CLASSES = probeHiddenClasses();
        static final ConstructorSkippingAllocator ALLOCATOR = probeAllocator();
    }

    private Capabilities() {
    }

    /**
     * Whether unloadable hidden classes can be defined.
     *
     * <p>Available since Java 15 and therefore expected on every supported runtime, but probed
     * anyway because it is the capability the library's memory behaviour depends on. If this were
     * ever false, every generated class would be permanent, and that is worth knowing about
     * explicitly rather than discovering from a metaspace graph.
     *
     * @return whether this JVM can define hidden classes
     */
    public static boolean hiddenClasses() {
        return Holder.HIDDEN_CLASSES;
    }

    /**
     * Whether an instance can be created without running a constructor.
     *
     * @return whether an instance can be created without running a constructor
     */
    public static boolean constructorSkipping() {
        return Holder.ALLOCATOR.isAvailable();
    }

    /**
     * Which mechanism backs {@link #constructorSkipping()}, for reporting.
     *
     * <p>A name, deliberately: the mechanisms behind constructor-skipping are unsupported JDK
     * APIs that will be replaced per JDK release, so the supported surface exposes what they
     * <em>do</em> — {@link #constructorSkipping()}, this description, and allocation through
     * {@link Instantiator#allocateWithoutConstructor()} — never the mechanism types themselves.
     * A public method whose signature named the internal allocator type would make that type's
     * descriptor part of the compatibility contract, annotation or no annotation.
     *
     * @return a short mechanism description, e.g. {@code "ReflectionFactory"}; when unavailable,
     *         an explanation of why
     */
    public static String constructorSkippingMechanism() {
        return Holder.ALLOCATOR.name();
    }

    /**
     * The allocator backing {@link #constructorSkipping()}.
     *
     * <p>For reporting. To allocate a specific class, use {@link #allocatorFor(Class)}: which
     * allocator works depends on the class.
     *
     * @return never {@code null}; may be one that always fails with an explanation
     */
    static ConstructorSkippingAllocator allocator() {
        return Holder.ALLOCATOR;
    }

    /**
     * The allocator that can handle {@code type}.
     *
     * <p>Not always {@link #allocator()}. Hidden classes &mdash; which is what Classwright generates
     * by default &mdash; can only be allocated by {@code Unsafe.allocateInstance};
     * {@code ReflectionFactory} resolves its target by name and a hidden class has no resolvable
     * one. See {@link Allocators#forClass(Class)}.
     *
     * @param type the class to be allocated
     * @return never {@code null}; check {@link ConstructorSkippingAllocator#isAvailable()}
     */
    static ConstructorSkippingAllocator allocatorFor(Class<?> type) {
        // The disable property is a blanket switch, so it wins before the per-class choice.
        return Holder.ALLOCATOR instanceof DisabledAllocator
                ? Holder.ALLOCATOR
                : Allocators.forClass(type);
    }

    /**
     * A human-readable summary, for logs and bug reports.
     *
     * @return one capability per line
     */
    public static String describe() {
        return "Classwright capabilities on " + System.getProperty("java.vm.name")
                + " " + System.getProperty("java.version") + ":\n"
                + "  hidden classes (unloadable)   : " + yesNo(hiddenClasses()) + "\n"
                + "  constructor-skipping alloc.   : " + yesNo(constructorSkipping())
                + " (" + Holder.ALLOCATOR.name() + ")";
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private static boolean isDisabled(String capability) {
        return Boolean.getBoolean(DISABLE_PREFIX + capability);
    }

    /**
     * Probes hidden classes by generating one and defining it.
     *
     * <p>Deliberately end-to-end. Checking that {@code Lookup.defineHiddenClass} exists would prove
     * only that the method is present, not that this JVM will let us call it and not that the
     * result behaves. Generating a real class costs microseconds, once.
     */
    private static boolean probeHiddenClasses() {
        if (isDisabled("hiddenClasses")) {
            return false;
        }
        try {
            String internalName = Capabilities.class.getName().replace('.', '/') + "$$Probe";
            CwClassWriter writer = CwClassWriter.of(
                    AccessFlags.PUBLIC | AccessFlags.SUPER, internalName, "java/lang/Object");
            writer.constructor(AccessFlags.PUBLIC, CwMethodType.of(CwType.VOID))
                    .code()
                    .loadThis()
                    .invokeConstructor("java/lang/Object", CwMethodType.of(CwType.VOID))
                    .returnValue();

            Class<?> probe = MethodHandles.lookup()
                    .defineHiddenClass(writer.toByteArray(), false)
                    .lookupClass();
            return probe.isHidden();
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static ConstructorSkippingAllocator probeAllocator() {
        if (isDisabled("constructorSkipping")) {
            return Allocators.findBest().isAvailable()
                    ? new DisabledAllocator(Allocators.findBest().name())
                    : Allocators.findBest();
        }
        return Allocators.findBest();
    }

    /** Reports as unavailable because a system property said so, not because the JVM lacks it. */
    private record DisabledAllocator(String underlying) implements ConstructorSkippingAllocator {

        @Override
        public String name() {
            return underlying + " (disabled by " + DISABLE_PREFIX + "constructorSkipping)";
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Object allocate(Class<?> type) {
            throw new UnsupportedOperationException("constructor-skipping allocation is disabled by "
                    + DISABLE_PREFIX + "constructorSkipping; remove the property to re-enable it");
        }
    }

    /**
     * Prints the capability report.
     *
     * <p>Runnable directly, so a bug report can include exactly what Classwright saw on the
     * reporter's JVM: {@code java -cp classwright.jar com.classwright.runtime.Capabilities}
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        System.out.println(describe());
    }
}
