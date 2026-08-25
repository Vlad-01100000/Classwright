package com.classwright.runtime.unsafe;

/**
 * Creates an instance of a class without running any of its constructors.
 *
 * <p>Needed because a proxy sometimes has to exist before its target could legally be built: the
 * superclass may have no accessible constructor, or its constructor may have side effects that must
 * not happen twice, or may require arguments the caller does not have. CGLib solved this the same
 * way, and applications migrating from it will expect the behaviour to be available.
 *
 * <p>The object it produces has every field at its default value. No constructor, no initialiser
 * block, and no field initialiser has run. That is a real hazard &mdash; a class with a
 * {@code final} field the constructor was supposed to set will observe {@code null} &mdash; and it
 * is why this is opt-in rather than the default path.
 *
 * <h2>This will stop working one day</h2>
 *
 * <p>There is no supported way to do this on the JVM, and the JDK has been explicit that providing
 * one is a long-term project. Implementations here rely on APIs that are unsupported today and will
 * be removed eventually. {@link #isAvailable()} exists so callers can find out before they commit,
 * and so the library can degrade rather than fail when the answer becomes {@code false}
 * permanently.
 *
 * @see Allocators
 */
@com.classwright.Internal
public interface ConstructorSkippingAllocator {

    /**
     * A short name for diagnostics, e.g. {@code "ReflectionFactory"}.
     *
     * @return a short name naming the mechanism, for diagnostics
     */
    String name();

    /**
     * Whether this implementation works on the running JVM.
     *
     * <p>Determined by actually attempting an allocation during probing, not by inspecting version
     * strings. A JVM that reports a version where the API exists may still refuse the call because
     * of module access rules or a security configuration, and the only reliable way to find out is
     * to try.
     *
     * @return whether this JVM offers the mechanism this allocator needs
     */
    boolean isAvailable();

    /**
     * Allocates an instance of {@code type} with all fields at their default values.
     *
     * @param type the class to instantiate; must not be abstract, an interface, an array, or a
     *             primitive
     * @return a new, uninitialised instance
     * @throws UnsupportedOperationException if {@link #isAvailable()} is {@code false}
     * @throws IllegalArgumentException      if {@code type} cannot be instantiated at all
     */
    Object allocate(Class<?> type);
}
