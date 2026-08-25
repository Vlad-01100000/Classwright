package com.classwright.runtime;

import com.classwright.ClasswrightException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.util.Objects;
import java.util.Optional;

/**
 * A class that has been defined into the JVM, plus the means to use it.
 *
 * <p>The lookup matters more than it might appear. A hidden class has no resolvable name, so
 * nothing can reach it with {@code Class.forName} or a {@code new} instruction in some other
 * class's bytecode. The {@link Lookup} returned when it was defined is the handle to it, and it is
 * how instances get created.
 *
 * @param type     the loaded class
 * @param lookup   a lookup with access to it, or {@code null} for strategies that produce ordinary
 *                 named classes reachable by reflection
 * @param strategy how it was defined, which determines whether it can ever be unloaded
 */
public record DefinedClass(Class<?> type, Lookup lookup, DefinitionStrategy strategy) {

    /**
     * Validates the components.
     *
     * @param type     the loaded class
     * @param lookup   a lookup with access to it, or {@code null}
     * @param strategy how it was defined
     */
    public DefinedClass {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(strategy, "strategy");
    }

    /**
     * Whether this class can be garbage collected while its defining loader stays alive.
     *
     * @return whether this class can be collected while its defining loader stays alive
     */
    public boolean isUnloadable() {
        return strategy.producesUnloadableClasses();
    }

    /**
     * Whether {@code Class.forName} can find this class by name.
     *
     * @return whether {@code Class.forName} can resolve this class
     */
    public boolean hasResolvableName() {
        return strategy.producesResolvableNames();
    }

    /**
     * The lookup for this class, if the strategy produced one.
     *
     * @return the lookup, or empty
     */
    public Optional<Lookup> maybeLookup() {
        return Optional.ofNullable(lookup);
    }

    /**
     * A handle to one of this class's constructors.
     *
     * <p>Prefers the lookup, falling back to core reflection for named classes. Hidden classes have
     * only the first option, since there is no name for reflection to resolve against.
     *
     * @param parameterTypes the constructor's parameter types
     * @return a handle whose invocation produces a new instance
     * @throws ClasswrightException if no such constructor exists or it cannot be reached
     */
    public MethodHandle constructor(Class<?>... parameterTypes) {
        try {
            if (lookup != null) {
                return lookup.findConstructor(type, MethodType.methodType(void.class,
                        parameterTypes));
            }
            var reflective = type.getDeclaredConstructor(parameterTypes);
            reflective.setAccessible(true);
            return java.lang.invoke.MethodHandles.lookup().unreflectConstructor(reflective);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // RuntimeException too: setAccessible throws InaccessibleObjectException — a
            // RuntimeException — and letting the raw JPMS error escape here is exactly the
            // bare-diagnostic experience this method's message exists to prevent.
            throw new ClasswrightException("no accessible constructor "
                    + type.getSimpleName() + "(" + describe(parameterTypes) + ") on the generated "
                    + "class. Generated classes only have the constructors the generator emitted.",
                    e);
        }
    }

    private static String describe(Class<?>[] parameterTypes) {
        StringBuilder text = new StringBuilder();
        for (Class<?> parameterType : parameterTypes) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(parameterType.getSimpleName());
        }
        return text.toString();
    }
}
