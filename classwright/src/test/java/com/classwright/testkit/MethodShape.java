package com.classwright.testkit;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A return type plus a parameter list &mdash; the shape of a method, without a name or a body.
 *
 * <p>Used to drive exhaustive code-generation tests. Most bytecode engine bugs are not logic bugs;
 * they are <em>type</em> bugs. Using the wrong load opcode for a {@code float}, forgetting that a
 * {@code long} occupies two local-variable slots, boxing a {@code char} as an {@code Integer},
 * returning with {@code areturn} where {@code ireturn} was required. None of these are caught by
 * testing a handful of hand-written examples, and all of them are caught by walking a matrix of
 * shapes.
 *
 * <h2>Why this renders its own descriptors</h2>
 *
 * <p>Descriptor rendering here is written from scratch, deliberately duplicating what the
 * production type model will do. Test infrastructure that reuses the implementation it is meant to
 * verify cannot detect a shared misunderstanding: if the production renderer emits {@code Z} for
 * {@code boolean} incorrectly, a test comparing it against itself agrees enthusiastically. Two
 * independent implementations disagreeing is a signal; one implementation agreeing with itself is
 * not. It is a few dozen lines, and it buys a genuine oracle.
 *
 * @param returnType     the return type, possibly {@code void}
 * @param parameterTypes the parameter types, in order; never contains {@code void}
 */
public record MethodShape(Class<?> returnType, List<Class<?>> parameterTypes) {

    /** Convenience factory reading as {@code shape(int.class, long.class, String.class)}. */
    public static MethodShape shape(Class<?> returnType, Class<?>... parameterTypes) {
        return new MethodShape(returnType, List.of(parameterTypes));
    }

    public MethodShape {
        if (parameterTypes.stream().anyMatch(t -> t == void.class)) {
            throw new IllegalArgumentException("void is not a legal parameter type");
        }
        parameterTypes = List.copyOf(parameterTypes);
    }

    /**
     * Renders the JVM method descriptor, e.g. {@code (IJLjava/lang/String;)V}.
     *
     * @return the method descriptor for this shape
     */
    public String descriptor() {
        return parameterTypes.stream()
                .map(MethodShape::descriptorOf)
                .collect(Collectors.joining("", "(", ")")) + descriptorOf(returnType);
    }

    /**
     * Renders the JVM field descriptor for a single type, e.g. {@code [[Ljava/lang/String;}.
     *
     * @param type any type, including primitives, arrays, and {@code void}
     * @return the field descriptor
     */
    public static String descriptorOf(Class<?> type) {
        if (type.isArray()) {
            return "[" + descriptorOf(type.getComponentType());
        }
        if (!type.isPrimitive()) {
            return "L" + type.getName().replace('.', '/') + ";";
        }
        if (type == boolean.class) return "Z";
        if (type == byte.class)    return "B";
        if (type == char.class)    return "C";
        if (type == short.class)   return "S";
        if (type == int.class)     return "I";
        if (type == long.class)    return "J";
        if (type == float.class)   return "F";
        if (type == double.class)  return "D";
        if (type == void.class)    return "V";
        throw new IllegalArgumentException("unreachable: unknown primitive " + type);
    }

    /**
     * Counts the local-variable slots the parameters occupy.
     *
     * <p>{@code long} and {@code double} take two slots each; everything else takes one. This is the
     * single most common source of off-by-one bugs in a bytecode emitter, which is why it is
     * modelled explicitly rather than assumed.
     *
     * @return the total parameter slot count, excluding {@code this}
     */
    public int parameterSlots() {
        return parameterTypes.stream().mapToInt(MethodShape::slotsOf).sum();
    }

    /**
     * Returns the number of slots a value of the given type occupies.
     *
     * @param type any type other than {@code void}
     * @return 2 for {@code long} and {@code double}, otherwise 1
     */
    public static int slotsOf(Class<?> type) {
        return (type == long.class || type == double.class) ? 2 : 1;
    }

    /**
     * Reports whether this shape is legal for an <em>instance</em> method.
     *
     * <p>A method descriptor may describe at most 255 slots of arguments, and for an instance method
     * {@code this} consumes one of them (JVMS 4.3.3). A shape with 255 slots of parameters is
     * therefore valid as a static method and invalid as an instance method &mdash; a distinction
     * worth encoding, because "generate an override with the maximum possible arity" is a real edge
     * case for a proxy library.
     *
     * @return whether an instance method with this shape can exist
     */
    public boolean isLegalForInstanceMethod() {
        return parameterSlots() <= 254;
    }

    @Override
    public String toString() {
        return descriptor();
    }
}
