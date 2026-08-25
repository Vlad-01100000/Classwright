package com.classwright.testkit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Generates the matrix of method shapes that every code-generating component is tested against.
 *
 * <p>This is the highest-leverage piece of test infrastructure in the project. A bytecode emitter
 * has an enormous but highly regular input space, and hand-picked examples cover it badly: people
 * write tests for {@code int} and {@code String} and then ship a {@code short} return bug. Walking
 * a generated matrix costs one loop and catches the entire class of problem.
 *
 * <p>The type universe below is chosen so that each entry can break something a different way:
 *
 * <ul>
 *   <li>{@code boolean}, {@code byte}, {@code char}, {@code short} &mdash; all represented as
 *       {@code int} on the operand stack, but each with different truncation and boxing rules. The
 *       classic bug is treating {@code char} as signed.</li>
 *   <li>{@code long}, {@code double} &mdash; two slots each. Every local-variable index after one
 *       of these is shifted, so a single missed slot corrupts every subsequent parameter.</li>
 *   <li>{@code float} vs {@code double} &mdash; separate opcode families that are easy to
 *       transpose.</li>
 *   <li>{@code void} &mdash; legal as a return type, illegal as a parameter; needs {@code return}
 *       rather than any typed return opcode.</li>
 *   <li>arrays, including multi-dimensional &mdash; reference types whose descriptors nest, and a
 *       common source of descriptor-rendering bugs.</li>
 * </ul>
 *
 * <p>All methods return deterministic, stably-ordered results so that a failing case is
 * reproducible and reportable by index.
 */
public final class SignatureMatrix {

    /**
     * Every type worth generating code for, excluding {@code void}.
     *
     * <p>Ordered from "most likely to expose a slot bug" outward, so that a truncated test run
     * still covers the interesting cases first.
     */
    public static final List<Class<?>> VALUE_TYPES = List.of(
            long.class, double.class,           // two-slot types first: the usual suspects
            boolean.class, byte.class, char.class, short.class, int.class, float.class,
            Object.class, String.class,
            int[].class, long[].class, Object[].class, String[][].class);

    /** {@link #VALUE_TYPES} plus {@code void}: everything legal as a return type. */
    public static final List<Class<?>> RETURN_TYPES = concat(List.of(void.class), VALUE_TYPES);

    private SignatureMatrix() {
    }

    /**
     * One no-argument shape per return type.
     *
     * <p>Isolates return-path code generation from argument handling, so a failure points at
     * exactly one thing.
     *
     * @return {@link #RETURN_TYPES}{@code .size()} shapes
     */
    public static List<MethodShape> eachReturnType() {
        return RETURN_TYPES.stream().map(t -> new MethodShape(t, List.of())).toList();
    }

    /**
     * One single-argument, {@code void}-returning shape per parameter type.
     *
     * <p>The mirror of {@link #eachReturnType()}: isolates argument loading from return handling.
     *
     * @return {@link #VALUE_TYPES}{@code .size()} shapes
     */
    public static List<MethodShape> eachSingleParameter() {
        return VALUE_TYPES.stream().map(t -> new MethodShape(void.class, List.of(t))).toList();
    }

    /**
     * Every ordered pair of parameter types, returning the first of the pair.
     *
     * <p>Pairs are where slot arithmetic actually breaks. A method taking {@code (long, int)} loads
     * its second argument from slot 3, not slot 2; getting that wrong produces code that verifies
     * cleanly and silently returns garbage. Only a cross-product finds these reliably.
     *
     * @return {@code VALUE_TYPES.size()^2} shapes
     */
    public static List<MethodShape> parameterPairs() {
        List<MethodShape> shapes = new ArrayList<>(VALUE_TYPES.size() * VALUE_TYPES.size());
        for (Class<?> first : VALUE_TYPES) {
            for (Class<?> second : VALUE_TYPES) {
                shapes.add(new MethodShape(first, List.of(first, second)));
            }
        }
        return List.copyOf(shapes);
    }

    /**
     * Hand-picked interleavings that stress two-slot handling specifically.
     *
     * <p>The cross-product in {@link #parameterPairs()} covers two arguments; these cover the
     * patterns where a slot error compounds across three or more.
     *
     * @return a small, fixed list of awkward shapes
     */
    public static List<MethodShape> slotStress() {
        return List.of(
                MethodShape.shape(void.class, long.class, int.class, long.class),
                MethodShape.shape(void.class, int.class, long.class, int.class),
                MethodShape.shape(void.class, double.class, float.class, double.class),
                MethodShape.shape(void.class, float.class, double.class, float.class),
                MethodShape.shape(long.class, long.class, double.class, long.class, double.class),
                MethodShape.shape(double.class, boolean.class, long.class, char.class,
                        double.class, byte.class),
                MethodShape.shape(Object.class, int[].class, long.class, String[][].class,
                        double.class));
    }

    /**
     * Shapes at the JVM's hard arity limits.
     *
     * <p>A method descriptor may describe at most 255 argument slots, and {@code this} consumes one
     * of them for an instance method (JVMS 4.3.3). These shapes sit exactly on that boundary, where
     * a proxy generator either works or produces a class the JVM refuses outright. They also push
     * local-variable indices past 255, which is where the {@code wide} instruction prefix becomes
     * mandatory &mdash; a rarely-exercised path that is consequently a good place for bugs to hide.
     *
     * @return the maximum-arity shapes, for instance and static methods respectively
     */
    public static List<MethodShape> maxArity() {
        return List.of(
                new MethodShape(void.class, Collections.nCopies(254, int.class)),   // instance limit
                new MethodShape(void.class, Collections.nCopies(255, int.class)),   // static limit
                new MethodShape(void.class, Collections.nCopies(127, long.class))); // 254 slots
    }

    /**
     * The union of every generator above, de-duplicated and stably ordered.
     *
     * <p>The default matrix for any test that just wants broad coverage.
     *
     * @return every shape this class can produce, each appearing once
     */
    public static List<MethodShape> all() {
        Set<MethodShape> unique = new LinkedHashSet<>();
        unique.addAll(eachReturnType());
        unique.addAll(eachSingleParameter());
        unique.addAll(parameterPairs());
        unique.addAll(slotStress());
        unique.addAll(maxArity());
        return List.copyOf(unique);
    }

    /**
     * The subset of {@link #all()} that can legally be an instance method.
     *
     * <p>Proxy generation overrides instance methods, so this is the matrix that matters for
     * Phase 3; the 255-slot static shape is excluded automatically.
     *
     * @return every shape legal for an instance method
     */
    public static List<MethodShape> allForInstanceMethods() {
        return all().stream().filter(MethodShape::isLegalForInstanceMethod).toList();
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> combined = new ArrayList<>(first.size() + second.size());
        combined.addAll(first);
        combined.addAll(second);
        return List.copyOf(combined);
    }
}
