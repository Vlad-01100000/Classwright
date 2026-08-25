package com.classwright.core;

import java.util.Objects;

/**
 * A JVM type: one of the eight primitives, {@code void}, an array, or a class or interface.
 *
 * <p>This is the engine's vocabulary for talking about types, and every instance is built either
 * from a {@link Class} obtained by reflection or from a descriptor string that reflection produced.
 * Notably it is <em>never</em> built by reading a class file, which is the constraint the whole
 * library is organised around.
 *
 * <h2>What this exists to prevent</h2>
 *
 * <p>Nearly every bug in a hand-written bytecode emitter is a type bug, and they cluster around
 * four facts that are easy to know and easy to forget in the moment:
 *
 * <ul>
 *   <li>{@code long} and {@code double} occupy <strong>two</strong> local-variable slots. Every
 *       slot index after one of them shifts, and getting it wrong produces code that verifies
 *       cleanly and silently reads the wrong variable.</li>
 *   <li>{@code boolean}, {@code byte}, {@code char}, and {@code short} do not exist on the operand
 *       stack. They are all {@code int} there, loaded and stored with the {@code i*} opcodes, but
 *       they use <em>different</em> opcodes for array access and different wrapper classes for
 *       boxing.</li>
 *   <li>The descriptor for {@code long} is {@code J}, not {@code L}. {@code L} introduces a class
 *       type.</li>
 *   <li>An array of anything is a single-slot reference, however many dimensions it has.</li>
 * </ul>
 *
 * <p>Encoding all four here, once, means the rest of the engine asks {@code type.slots()} or
 * {@code type.loadOpcode()} and cannot get them wrong.
 *
 * <p>Instances are immutable and safe to share; equality is by descriptor.
 */
public final class CwType {

    /**
     * The kind of a type. Distinguishes the small integral primitives from {@code int} even though
     * they share stack representation, because they differ for array access and boxing.
     */
    public enum Sort {

        /** {@code void}, legal only as a return type. */
        VOID,

        /** {@code boolean}. */
        BOOLEAN,

        /** {@code byte}. */
        BYTE,

        /** {@code char}. */
        CHAR,

        /** {@code short}. */
        SHORT,

        /** {@code int}. */
        INT,

        /** {@code long}, which occupies two local-variable slots. */
        LONG,

        /** {@code float}. */
        FLOAT,

        /** {@code double}, which occupies two local-variable slots. */
        DOUBLE,

        /** An array of any component type. */
        ARRAY,

        /** A class or interface type. */
        OBJECT
    }

    /**
     * The {@code void} pseudo-type. Legal only as a return type.
     */
    public static final CwType VOID = primitive(Sort.VOID, "V", void.class);
    /**
     * The primitive {@code boolean}, represented as an {@code int} on the operand stack.
     */
    public static final CwType BOOLEAN = primitive(Sort.BOOLEAN, "Z", boolean.class);
    /**
     * The primitive {@code byte}, represented as an {@code int} on the operand stack.
     */
    public static final CwType BYTE = primitive(Sort.BYTE, "B", byte.class);
    /**
     * The primitive {@code char}, represented as an {@code int} on the operand stack.
     */
    public static final CwType CHAR = primitive(Sort.CHAR, "C", char.class);
    /**
     * The primitive {@code short}, represented as an {@code int} on the operand stack.
     */
    public static final CwType SHORT = primitive(Sort.SHORT, "S", short.class);
    /**
     * The primitive {@code int}.
     */
    public static final CwType INT = primitive(Sort.INT, "I", int.class);
    /**
     * The primitive {@code long}, which occupies two local-variable slots.
     */
    public static final CwType LONG = primitive(Sort.LONG, "J", long.class);
    /**
     * The primitive {@code float}.
     */
    public static final CwType FLOAT = primitive(Sort.FLOAT, "F", float.class);
    /**
     * The primitive {@code double}, which occupies two local-variable slots.
     */
    public static final CwType DOUBLE = primitive(Sort.DOUBLE, "D", double.class);

    /**
     * {@code java.lang.Object}.
     */
    public static final CwType OBJECT = objectType("java/lang/Object");
    /**
     * {@code java.lang.String}.
     */
    public static final CwType STRING = objectType("java/lang/String");
    /**
     * {@code java.lang.Class}.
     */
    public static final CwType CLASS = objectType("java/lang/Class");
    /**
     * {@code java.lang.Throwable}.
     */
    public static final CwType THROWABLE = objectType("java/lang/Throwable");
    /**
     * {@code Object[]}, the shape every reflective callback contract uses for arguments.
     */
    public static final CwType OBJECT_ARRAY = arrayOf(OBJECT);

    private final Sort sort;
    private final String descriptor;

    /** Lazily computed by {@link #internalName()}; a benign race, like {@code String.hashCode}. */
    private String internalName;

    /** Lazily cached by {@link VerificationType#of}; the same benign race. */
    VerificationType verificationType;

    private CwType(Sort sort, String descriptor) {
        this.sort = sort;
        this.descriptor = descriptor;
    }

    private static CwType primitive(Sort sort, String descriptor, Class<?> ignored) {
        return new CwType(sort, descriptor);
    }

    // -- factories -----------------------------------------------------------------------------

    /**
     * Builds a type from a reflected {@link Class}.
     *
     * <p>This is the engine's only doorway from the reflection world into the bytecode world, and
     * it is the reason no class-file parser is needed anywhere in the library.
     *
     * @param type any class, including primitives and arrays
     * @return the corresponding JVM type
     */
    public static CwType of(Class<?> type) {
        Objects.requireNonNull(type, "type");
        return INTERNED.get(type);
    }

    /**
     * One {@code CwType} per {@code Class}. This method sits on hot generation paths — every
     * reflected method contributes several calls — and building a descriptor string per call was
     * pure churn; interning also makes the lazy caches on the instance ({@code internalName},
     * {@code verificationType}) effective across call sites. A {@code ClassValue} so the entry
     * lives on the class itself and pins no loader. A hidden class still throws on every
     * attempt: a thrown {@code computeValue} installs nothing.
     */
    private static final ClassValue<CwType> INTERNED = new ClassValue<>() {
        @Override
        protected CwType computeValue(Class<?> type) {
            return build(type);
        }
    };

    private static CwType build(Class<?> type) {
        if (type.isPrimitive()) {
            if (type == void.class) return VOID;
            if (type == boolean.class) return BOOLEAN;
            if (type == byte.class) return BYTE;
            if (type == char.class) return CHAR;
            if (type == short.class) return SHORT;
            if (type == int.class) return INT;
            if (type == long.class) return LONG;
            if (type == float.class) return FLOAT;
            if (type == double.class) return DOUBLE;
            throw new CodeGenerationException("unknown primitive type " + type);
        }
        if (type.isArray()) {
            return arrayOf(of(type.getComponentType()));
        }
        if (type.isHidden()) {
            // A hidden class has no resolvable name, so no other class's bytecode can refer to it:
            // its "name" carries a /0x... suffix that is not a valid binary name. Caught here
            // because the alternative is an internal name with a stray slash, which surfaces much
            // later as a baffling NoClassDefFoundError.
            throw new CodeGenerationException(type.getName() + " is a hidden class and cannot be "
                    + "named in generated bytecode. Generate against a resolvable class, or use "
                    + "reflection to reach it.");
        }
        return objectType(type.getName().replace('.', '/'));
    }

    /**
     * Builds a class or interface type from an internal name such as {@code java/lang/String}.
     *
     * @param internalName the binary name with dots replaced by slashes, no {@code L} or {@code ;}
     * @return the corresponding object type
     */
    public static CwType objectType(String internalName) {
        Objects.requireNonNull(internalName, "internalName");
        if (internalName.isEmpty()) {
            throw new CodeGenerationException("internal name must not be empty");
        }
        if (internalName.indexOf('.') >= 0) {
            throw new CodeGenerationException("'" + internalName + "' looks like a binary name; "
                    + "internal names use '/' as the separator, e.g. java/lang/String");
        }
        if (internalName.charAt(0) == '[') {
            return fromDescriptor(internalName);
        }
        return new CwType(Sort.OBJECT, "L" + internalName + ";");
    }

    /**
     * Builds an array type with the given component.
     *
     * @param component the element type; may itself be an array
     * @return an array type one dimension deeper than {@code component}
     */
    public static CwType arrayOf(CwType component) {
        Objects.requireNonNull(component, "component");
        if (component.sort == Sort.VOID) {
            throw new CodeGenerationException("there is no such thing as an array of void");
        }
        return new CwType(Sort.ARRAY, "[" + component.descriptor);
    }

    /**
     * Parses a field descriptor, e.g. {@code [[Ljava/lang/String;} or {@code J}.
     *
     * <p>Descriptors handled here always originate from reflection or from this engine, never from
     * a class file being read.
     *
     * @param descriptor a JVM field descriptor
     * @return the type it denotes
     */
    public static CwType fromDescriptor(String descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        CwType parsed = parse(descriptor, 0);
        if (parsed.descriptor.length() != descriptor.length()) {
            throw new CodeGenerationException(
                    "trailing characters after descriptor in '" + descriptor + "'");
        }
        return parsed;
    }

    /** Parses one descriptor starting at {@code start}; used by the method-descriptor parser too. */
    static CwType parse(String descriptor, int start) {
        if (start >= descriptor.length()) {
            throw new CodeGenerationException("descriptor ended unexpectedly: '" + descriptor + "'");
        }
        switch (descriptor.charAt(start)) {
            case 'V': return VOID;
            case 'Z': return BOOLEAN;
            case 'B': return BYTE;
            case 'C': return CHAR;
            case 'S': return SHORT;
            case 'I': return INT;
            case 'J': return LONG;
            case 'F': return FLOAT;
            case 'D': return DOUBLE;
            case '[': return arrayOf(parse(descriptor, start + 1));
            case 'L': {
                int end = descriptor.indexOf(';', start);
                if (end < 0) {
                    throw new CodeGenerationException(
                            "unterminated class descriptor in '" + descriptor + "' at " + start);
                }
                return new CwType(Sort.OBJECT, descriptor.substring(start, end + 1));
            }
            default:
                throw new CodeGenerationException("'" + descriptor.charAt(start)
                        + "' is not a valid descriptor at position " + start + " of '"
                        + descriptor + "'");
        }
    }

    // -- shape ---------------------------------------------------------------------------------

    /**
     * Which of the eleven kinds of type this is.
     *
     * @return the sort
     */
    public Sort sort() {
        return sort;
    }

    /**
     * The JVM field descriptor, e.g. {@code J} or {@code Ljava/lang/String;}.
     *
     * @return the JVM descriptor, such as {@code I} or {@code Ljava/lang/String;}
     */
    public String descriptor() {
        return descriptor;
    }

    /**
     * The internal name, for object and array types.
     *
     * <p>Note the asymmetry, which is in the format rather than in this API: for a class the
     * internal name strips the surrounding {@code L} and {@code ;}, but for an array it <em>is</em>
     * the descriptor. That is what {@code CONSTANT_Class} entries hold, and getting it wrong yields
     * a {@code NoClassDefFoundError} for a class named {@code [Ljava/lang/String;;}.
     *
     * @return the internal name
     * @throws CodeGenerationException if this is a primitive
     */
    public String internalName() {
        // Benign-race lazy init, the String.hashCode idiom: the value is a pure function of the
        // immutable descriptor, so two racing threads compute the same string and one write wins.
        // Every classEntry() and checkCast() calls this, and the substring allocation was pure
        // churn on the hottest generation path.
        String cached = internalName;
        if (cached != null) {
            return cached;
        }
        String computed = switch (sort) {
            case OBJECT -> descriptor.substring(1, descriptor.length() - 1);
            case ARRAY -> descriptor;
            default -> throw new CodeGenerationException(
                    "primitive type " + this + " has no internal name");
        };
        internalName = computed;
        return computed;
    }

    /**
     * The binary name, e.g. {@code java.lang.String} or {@code int} or {@code long[]}.
     *
     * @return the binary name, such as {@code java.lang.String} or {@code int[]}
     */
    public String className() {
        return switch (sort) {
            case VOID -> "void";
            case BOOLEAN -> "boolean";
            case BYTE -> "byte";
            case CHAR -> "char";
            case SHORT -> "short";
            case INT -> "int";
            case LONG -> "long";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case ARRAY -> componentType().className() + "[]";
            case OBJECT -> internalName().replace('/', '.');
        };
    }

    /**
     * The number of local-variable slots, or operand stack entries, a value of this type occupies.
     *
     * @return 2 for {@code long} and {@code double}, 0 for {@code void}, otherwise 1
     */
    public int slots() {
        return switch (sort) {
            case LONG, DOUBLE -> 2;
            case VOID -> 0;
            default -> 1;
        };
    }

    /**
     * Whether this is a primitive, {@code void} included.
     *
     * @return whether this is a primitive
     */
    public boolean isPrimitive() {
        return sort != Sort.OBJECT && sort != Sort.ARRAY;
    }

    /**
     * Whether this is an object or array type.
     *
     * @return whether this is a reference
     */
    public boolean isReference() {
        return sort == Sort.OBJECT || sort == Sort.ARRAY;
    }

    /**
     * Whether this is an array type.
     *
     * @return whether this is an array
     */
    public boolean isArray() {
        return sort == Sort.ARRAY;
    }

    /**
     * Whether this is {@code void}.
     *
     * @return whether this is void
     */
    public boolean isVoid() {
        return sort == Sort.VOID;
    }

    /**
     * Whether this type is represented as an {@code int} on the operand stack.
     *
     * <p>True for {@code boolean}, {@code byte}, {@code char}, {@code short}, and {@code int}. The
     * verifier does not distinguish between them, which is why a stack map frame says
     * {@code Integer} for all five.
     *
     * @return whether the JVM represents this as an {@code int} on the stack, which is true of {@code boolean}, {@code byte}, {@code char}, {@code short} and {@code int}
     */
    public boolean isIntLike() {
        return switch (sort) {
            case BOOLEAN, BYTE, CHAR, SHORT, INT -> true;
            default -> false;
        };
    }

    /**
     * The element type of an array.
     *
     * @return the component type
     * @throws CodeGenerationException if this is not an array
     */
    public CwType componentType() {
        if (sort != Sort.ARRAY) {
            throw new CodeGenerationException(this + " is not an array type");
        }
        return parse(descriptor, 1);
    }

    /**
     * How many {@code [} prefixes this array type has; 0 for a non-array.
     *
     * @return the number of array dimensions, or {@code 0} if this is not an array
     */
    public int dimensions() {
        int count = 0;
        while (count < descriptor.length() && descriptor.charAt(count) == '[') {
            count++;
        }
        return count;
    }

    /**
     * The wrapper type this primitive boxes to, e.g. {@code int} to {@code java/lang/Integer}.
     *
     * @return the wrapper object type
     * @throws CodeGenerationException if this is already a reference type
     */
    public CwType boxed() {
        // Shared constants, not fresh objectType() instances: box() runs once per boxed return
        // value in generated dispatch, and a fresh instance also defeated the lazy caches on the
        // wrapper (internalName, verificationType) every single time.
        return switch (sort) {
            case BOOLEAN -> BOOLEAN_BOX;
            case BYTE -> BYTE_BOX;
            case CHAR -> CHARACTER_BOX;
            case SHORT -> SHORT_BOX;
            case INT -> INTEGER_BOX;
            case LONG -> LONG_BOX;
            case FLOAT -> FLOAT_BOX;
            case DOUBLE -> DOUBLE_BOX;
            case VOID -> VOID_BOX;
            case OBJECT, ARRAY ->
                    throw new CodeGenerationException(this + " is already a reference type");
        };
    }

    private static final CwType BOOLEAN_BOX = objectType("java/lang/Boolean");
    private static final CwType BYTE_BOX = objectType("java/lang/Byte");
    private static final CwType CHARACTER_BOX = objectType("java/lang/Character");
    private static final CwType SHORT_BOX = objectType("java/lang/Short");
    private static final CwType INTEGER_BOX = objectType("java/lang/Integer");
    private static final CwType LONG_BOX = objectType("java/lang/Long");
    private static final CwType FLOAT_BOX = objectType("java/lang/Float");
    private static final CwType DOUBLE_BOX = objectType("java/lang/Double");
    private static final CwType VOID_BOX = objectType("java/lang/Void");

    // -- opcode selection ------------------------------------------------------------------------

    /**
     * The {@code *load} opcode for reading a local variable of this type.
     *
     * @return the opcode that loads this type from a local variable
     */
    public int loadOpcode() {
        return baseOpcode(Opcodes.ILOAD, "load");
    }

    /**
     * The {@code *store} opcode for writing a local variable of this type.
     *
     * @return the opcode that stores this type into a local variable
     */
    public int storeOpcode() {
        return baseOpcode(Opcodes.ISTORE, "store");
    }

    /**
     * The {@code *return} opcode for returning a value of this type.
     *
     * @return the opcode that returns this type from a method
     */
    public int returnOpcode() {
        if (sort == Sort.VOID) {
            return Opcodes.RETURN;
        }
        return baseOpcode(Opcodes.IRETURN, "return");
    }

    /**
     * The {@code *aload} opcode for reading an element out of an array of this component type.
     *
     * <p>Unlike local-variable access, the small integral types each have their own opcode here:
     * {@code baload} covers both {@code boolean} and {@code byte}, and {@code char} and
     * {@code short} get {@code caload} and {@code saload}. Using {@code iaload} for a
     * {@code byte[]} is a verification error, not a silent widening.
     *
     * @return the opcode that reads this type from an array
     */
    public int arrayLoadOpcode() {
        return switch (sort) {
            case BOOLEAN, BYTE -> Opcodes.BALOAD;
            case CHAR -> Opcodes.CALOAD;
            case SHORT -> Opcodes.SALOAD;
            case INT -> Opcodes.IALOAD;
            case LONG -> Opcodes.LALOAD;
            case FLOAT -> Opcodes.FALOAD;
            case DOUBLE -> Opcodes.DALOAD;
            case OBJECT, ARRAY -> Opcodes.AALOAD;
            case VOID -> throw new CodeGenerationException("there are no void arrays");
        };
    }

    /**
     * The {@code *astore} opcode for writing an element into an array of this component type.
     *
     * @return the opcode that writes this type into an array
     */
    public int arrayStoreOpcode() {
        return switch (sort) {
            case BOOLEAN, BYTE -> Opcodes.BASTORE;
            case CHAR -> Opcodes.CASTORE;
            case SHORT -> Opcodes.SASTORE;
            case INT -> Opcodes.IASTORE;
            case LONG -> Opcodes.LASTORE;
            case FLOAT -> Opcodes.FASTORE;
            case DOUBLE -> Opcodes.DASTORE;
            case OBJECT, ARRAY -> Opcodes.AASTORE;
            case VOID -> throw new CodeGenerationException("there are no void arrays");
        };
    }

    /**
     * The operand {@code newarray} takes to create a primitive array of this component type.
     *
     * @return the {@code newarray} operand identifying this primitive component type
     */
    public int newArrayTypeCode() {
        return switch (sort) {
            case BOOLEAN -> Opcodes.T_BOOLEAN;
            case CHAR -> Opcodes.T_CHAR;
            case FLOAT -> Opcodes.T_FLOAT;
            case DOUBLE -> Opcodes.T_DOUBLE;
            case BYTE -> Opcodes.T_BYTE;
            case SHORT -> Opcodes.T_SHORT;
            case INT -> Opcodes.T_INT;
            case LONG -> Opcodes.T_LONG;
            default -> throw new CodeGenerationException(
                    this + " is not a primitive; use anewarray for reference arrays");
        };
    }

    /**
     * Maps a sort onto one of the five parallel opcode families.
     *
     * <p>The load, store, and return instruction groups are each laid out in the same order in the
     * opcode table &mdash; int, long, float, double, reference &mdash; so one offset calculation
     * serves all three. The int-like types all collapse onto the {@code i} form, because that is
     * genuinely how the JVM represents them.
     */
    private int baseOpcode(int intOpcode, String operation) {
        return switch (sort) {
            case BOOLEAN, BYTE, CHAR, SHORT, INT -> intOpcode;
            case LONG -> intOpcode + 1;
            case FLOAT -> intOpcode + 2;
            case DOUBLE -> intOpcode + 3;
            case OBJECT, ARRAY -> intOpcode + 4;
            case VOID -> throw new CodeGenerationException("cannot " + operation + " a void value");
        };
    }

    // -- identity ------------------------------------------------------------------------------

    @Override
    public boolean equals(Object other) {
        return other instanceof CwType that && descriptor.equals(that.descriptor);
    }

    @Override
    public int hashCode() {
        return descriptor.hashCode();
    }

    /** Renders the readable class name, which is what a diagnostic message wants. */
    @Override
    public String toString() {
        return className();
    }
}
