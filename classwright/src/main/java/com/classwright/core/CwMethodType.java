package com.classwright.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A method's parameter types and return type.
 *
 * <p>Built from a reflected {@link Method} or {@link Constructor}, or from a descriptor string.
 * Like {@link CwType}, it never comes from reading a class file.
 *
 * <p>Beyond descriptor rendering, this exists to answer one question the emitter asks constantly
 * and can easily get wrong: <em>which local-variable slot does parameter {@code n} live in?</em>
 * The answer is not {@code n}, because {@code long} and {@code double} consume two slots each and
 * an instance method reserves slot 0 for {@code this}. {@link #parameterSlot} works it out.
 */
public final class CwMethodType {

    /**
     * The JVM's hard limit on argument slots in a method descriptor (JVMS 4.3.3).
     *
     * <p>For an instance method {@code this} counts against it, leaving 254 for real parameters.
     */
    public static final int MAX_ARGUMENT_SLOTS = 255;

    private final CwType returnType;
    private final List<CwType> parameterTypes;
    private final String descriptor;

    private CwMethodType(CwType returnType, List<CwType> parameterTypes) {
        this.returnType = returnType;
        this.parameterTypes = List.copyOf(parameterTypes);
        // A plain loop, not a stream collector: this runs for every method a generator touches,
        // and the collector's chain of intermediaries allocated several times what one builder
        // does.
        StringBuilder descriptor = new StringBuilder(16 + 8 * this.parameterTypes.size());
        descriptor.append('(');
        for (CwType parameter : this.parameterTypes) {
            descriptor.append(parameter.descriptor());
        }
        descriptor.append(')').append(returnType.descriptor());
        this.descriptor = descriptor.toString();
    }

    /**
     * Builds a method type.
     *
     * @param returnType     the return type, possibly {@code void}
     * @param parameterTypes the parameter types in declaration order
     * @return the method type
     */
    public static CwMethodType of(CwType returnType, List<CwType> parameterTypes) {
        Objects.requireNonNull(returnType, "returnType");
        Objects.requireNonNull(parameterTypes, "parameterTypes");
        for (CwType parameter : parameterTypes) {
            if (parameter.isVoid()) {
                throw new CodeGenerationException("void is not a legal parameter type");
            }
        }
        return new CwMethodType(returnType, parameterTypes);
    }

    /**
     * A signature from engine types.
     *
     * @param returnType     the return type
     * @param parameterTypes the parameter types, in order
     * @return the signature
     */
    public static CwMethodType of(CwType returnType, CwType... parameterTypes) {
        return of(returnType, List.of(parameterTypes));
    }

    /**
     * Builds a method type from reflected classes.
     *
     * @param returnType     the return type
     * @param parameterTypes the parameter types, in order
     * @return the signature
     */
    public static CwMethodType of(Class<?> returnType, Class<?>... parameterTypes) {
        List<CwType> parameters = new ArrayList<>(parameterTypes.length);
        for (Class<?> parameter : parameterTypes) {
            parameters.add(CwType.of(parameter));
        }
        return of(CwType.of(returnType), parameters);
    }

    /**
     * The type of an existing method, as reflection reports it.
     *
     * @param method the method to describe
     * @return its signature
     */
    public static CwMethodType of(Method method) {
        return of(method.getReturnType(), method.getParameterTypes());
    }

    /**
     * The type of a constructor, which is always {@code void}-returning.
     *
     * <p>Constructors are named {@code <init>} in bytecode and their descriptor claims a
     * {@code void} return, even though {@code new} plainly produces an object. The object comes
     * from the {@code new} instruction; {@code <init>} only initialises it.
     *
     * @param constructor the constructor to describe
     * @return its signature, with a {@code void} return
     */
    public static CwMethodType of(Constructor<?> constructor) {
        return of(void.class, constructor.getParameterTypes());
    }

    /**
     * Parses a method descriptor such as {@code (IJ)Ljava/lang/String;}.
     *
     * @param descriptor a method descriptor, such as {@code (ILjava/lang/String;)V}
     * @return the signature it describes
     */
    public static CwMethodType fromDescriptor(String descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (descriptor.isEmpty() || descriptor.charAt(0) != '(') {
            throw new CodeGenerationException(
                    "method descriptor must start with '(': '" + descriptor + "'");
        }
        int close = descriptor.indexOf(')');
        if (close < 0) {
            throw new CodeGenerationException(
                    "method descriptor has no ')': '" + descriptor + "'");
        }

        List<CwType> parameters = new ArrayList<>();
        int position = 1;
        while (position < close) {
            CwType parameter = CwType.parse(descriptor, position);
            parameters.add(parameter);
            position += parameter.descriptor().length();
        }
        if (position != close) {
            throw new CodeGenerationException(
                    "malformed parameter list in descriptor '" + descriptor + "'");
        }
        return of(CwType.fromDescriptor(descriptor.substring(close + 1)), parameters);
    }

    /**
     * The return type.
     *
     * @return the return type
     */
    public CwType returnType() {
        return returnType;
    }

    /**
     * The parameter types, in declaration order.
     *
     * @return the parameter types
     */
    public List<CwType> parameterTypes() {
        return parameterTypes;
    }

    /**
     * How many parameters there are.
     *
     * @return the parameter count
     */
    public int parameterCount() {
        return parameterTypes.size();
    }

    /**
     * The JVM method descriptor.
     *
     * @return the JVM method descriptor
     */
    public String descriptor() {
        return descriptor;
    }

    /**
     * Total local-variable slots the parameters occupy, not counting {@code this}.
     *
     * @return how many local-variable slots the parameters occupy, counting {@code long} and {@code double} twice
     */
    public int parameterSlots() {
        int slots = 0;
        for (CwType parameter : parameterTypes) {
            slots += parameter.slots();
        }
        return slots;
    }

    /**
     * The local-variable slot holding parameter {@code index} on method entry.
     *
     * <p>Not the same as {@code index}. Two adjustments apply: an instance method receives
     * {@code this} in slot 0, and every preceding {@code long} or {@code double} parameter has
     * already consumed two slots rather than one. So for an instance method
     * {@code m(long a, int b)}, {@code b} lives in slot 3.
     *
     * @param index      zero-based parameter position
     * @param isInstance whether the method has a {@code this} receiver
     * @return the local-variable slot index
     */
    public int parameterSlot(int index, boolean isInstance) {
        if (index < 0 || index >= parameterTypes.size()) {
            throw new CodeGenerationException("no parameter " + index + " in " + descriptor);
        }
        int slot = isInstance ? 1 : 0;
        for (int i = 0; i < index; i++) {
            slot += parameterTypes.get(i).slots();
        }
        return slot;
    }

    /**
     * The first free local-variable slot after the parameters.
     *
     * <p>Where an emitter should start allocating scratch variables.
     *
     * @param isInstance whether the method has a {@code this} receiver
     * @return the first slot not occupied by a parameter
     */
    public int firstFreeSlot(boolean isInstance) {
        return (isInstance ? 1 : 0) + parameterSlots();
    }

    /**
     * Checks this method type against the JVM's argument-slot limit.
     *
     * @param isInstance whether {@code this} occupies one of the slots
     * @throws CodeGenerationException if the descriptor would be rejected by the JVM
     */
    public void validateArity(boolean isInstance) {
        int slots = parameterSlots() + (isInstance ? 1 : 0);
        if (slots > MAX_ARGUMENT_SLOTS) {
            throw new CodeGenerationException("method takes " + slots + " argument slots"
                    + (isInstance ? " (including 'this')" : "")
                    + ", but the class-file format allows at most " + MAX_ARGUMENT_SLOTS
                    + "; descriptor was " + descriptor);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CwMethodType that && descriptor.equals(that.descriptor);
    }

    @Override
    public int hashCode() {
        return descriptor.hashCode();
    }

    @Override
    public String toString() {
        return parameterTypes.stream().map(CwType::toString)
                .collect(Collectors.joining(", ", "(", ")")) + " -> " + returnType;
    }
}
