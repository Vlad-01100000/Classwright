package com.classwright.core;

import java.util.Objects;

/**
 * A type as the JVM's bytecode verifier sees it.
 *
 * <p>Deliberately coarser than {@link CwType}, because the verifier is. All five int-like
 * primitives collapse to {@link #INTEGER}: the verifier genuinely cannot tell a {@code boolean}
 * from a {@code short} on the operand stack, and a stack map frame that tried to say otherwise
 * would be rejected.
 *
 * <p>Two of the nine forms exist only to describe objects that are not yet objects. Between a
 * {@code new} instruction and the {@code <init>} call that follows it, the reference on the stack
 * is <em>uninitialized</em>: it cannot be used for anything except that constructor call. The
 * verifier tracks this precisely, which is what stops uninitialised objects from escaping.
 * {@link #UNINITIALIZED_THIS} is the same idea for a constructor's own {@code this} before it has
 * chained to its superclass.
 *
 * <p>Instances are immutable and compared by value.
 *
 * @see <a href="https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-4.html#jvms-4.7.4">JVMS
 *      4.7.4, the StackMapTable attribute</a>
 */
final class VerificationType {

    /**
     * Item tags from JVMS 4.7.4. Note that {@code Double} is 3 and {@code Long} is 4 &mdash; the
     * spec does not order these the way the rest of the format does, and transposing them produces
     * a frame that describes the wrong types.
     */
    public enum Kind {
        TOP(0), INTEGER(1), FLOAT(2), DOUBLE(3), LONG(4), NULL(5),
        UNINITIALIZED_THIS(6), OBJECT(7), UNINITIALIZED(8);

        final int tag;

        Kind(int tag) {
            this.tag = tag;
        }
    }

    /**
     * An unusable slot: either the second half of a {@code long} or {@code double}, or a local
     * variable that holds nothing meaningful at this point.
     */
    public static final VerificationType TOP = new VerificationType(Kind.TOP, null, -1);

    public static final VerificationType INTEGER = new VerificationType(Kind.INTEGER, null, -1);
    public static final VerificationType FLOAT = new VerificationType(Kind.FLOAT, null, -1);
    public static final VerificationType LONG = new VerificationType(Kind.LONG, null, -1);
    public static final VerificationType DOUBLE = new VerificationType(Kind.DOUBLE, null, -1);

    /** The type of {@code aconst_null}: assignable to every reference type. */
    public static final VerificationType NULL = new VerificationType(Kind.NULL, null, -1);

    /** A constructor's {@code this}, before it has called a superclass or sibling constructor. */
    public static final VerificationType UNINITIALIZED_THIS =
            new VerificationType(Kind.UNINITIALIZED_THIS, null, -1);

    private final Kind kind;
    private final String internalName;

    /** For {@link Kind#UNINITIALIZED}: the bytecode offset of the {@code new} that produced it. */
    private final int newInstructionOffset;

    private VerificationType(Kind kind, String internalName, int newInstructionOffset) {
        this.kind = kind;
        this.internalName = internalName;
        this.newInstructionOffset = newInstructionOffset;
    }

    /** A reference to a fully initialised instance of the named class, interface, or array. */
    public static VerificationType object(String internalName) {
        return new VerificationType(Kind.OBJECT, Objects.requireNonNull(internalName), -1);
    }

    /**
     * A reference produced by {@code new} that has not been passed to a constructor yet.
     *
     * @param newInstructionOffset bytecode offset of the {@code new}, which is how the verifier
     *                             tells two pending allocations apart
     */
    public static VerificationType uninitialized(int newInstructionOffset) {
        return new VerificationType(Kind.UNINITIALIZED, null, newInstructionOffset);
    }

    /**
     * The verifier's view of a declared type.
     *
     * @param type any non-void type
     * @return the corresponding verification type
     */
    public static VerificationType of(CwType type) {
        // Cached on the CwType: this runs for every reference-typed load, store, push, field
        // access and invoke return, and allocating a fresh instance each time was measurable
        // churn on the generation path. The benign race is the String.hashCode idiom.
        VerificationType cached = type.verificationType;
        if (cached != null) {
            return cached;
        }
        VerificationType computed = switch (type.sort()) {
            case BOOLEAN, BYTE, CHAR, SHORT, INT -> INTEGER;
            case LONG -> LONG;
            case FLOAT -> FLOAT;
            case DOUBLE -> DOUBLE;
            case OBJECT, ARRAY -> object(type.internalName());
            case VOID -> throw new CodeGenerationException(
                    "void has no verification type; it is the absence of a value");
        };
        type.verificationType = computed;
        return computed;
    }

    public Kind kind() {
        return kind;
    }

    public String internalName() {
        return internalName;
    }

    public int newInstructionOffset() {
        return newInstructionOffset;
    }

    /** Whether this occupies two slots, and therefore is followed by a {@link #TOP}. */
    public boolean isWide() {
        return kind == Kind.LONG || kind == Kind.DOUBLE;
    }

    public boolean isReference() {
        return kind == Kind.OBJECT || kind == Kind.NULL
                || kind == Kind.UNINITIALIZED || kind == Kind.UNINITIALIZED_THIS;
    }

    /**
     * Whether this is a reference to a fully initialised object, or {@code null}.
     *
     * <p>This — not {@link #isReference()} — is what almost every instruction that consumes "a
     * reference" requires. The uninitialised forms are references too, but the verifier permits
     * them almost nowhere: passing one as an argument, receiver, store, or return value would
     * pass a looser simulation here and then fail JVM verification at class load, which is
     * exactly the failure mode this simulation exists to prevent. The two legal sinks — the
     * constructor call itself, and {@code dup} while setting up one — do not go through the
     * reference checks at all.
     */
    public boolean isInitialisedReference() {
        return kind == Kind.OBJECT || kind == Kind.NULL;
    }

    /** Slots occupied on the operand stack or in the local variable array. */
    public int slots() {
        return isWide() ? 2 : 1;
    }

    /**
     * Combines the types arriving at a join point from two different paths.
     *
     * <p>The verifier requires a single type at each merge point that is assignable from both
     * incoming ones. This implements the cases a code generator actually produces:
     *
     * <ul>
     *   <li>Identical types merge to themselves.</li>
     *   <li>{@link #NULL} merges with any reference to that reference, since null is assignable
     *       to everything.</li>
     *   <li>Two <em>different</em> object types merge to {@code java/lang/Object}. Computing the
     *       real least upper bound would mean loading and walking both hierarchies, which is both
     *       expensive and, for classes that are still being generated, sometimes impossible.
     *       {@code Object} is always a correct answer; where the code afterwards needs something
     *       more specific, the generator that created the merge is the thing that must emit a
     *       {@code checkcast}.</li>
     *   <li>Anything else &mdash; an {@code int} meeting a {@code long}, a reference meeting a
     *       primitive &mdash; is a generator bug, and is reported as one.</li>
     * </ul>
     *
     * @param other the type arriving from the other path
     * @return the merged type
     * @throws CodeGenerationException if the two cannot be reconciled
     */
    public VerificationType merge(VerificationType other) {
        if (equals(other)) {
            return this;
        }
        // Null joins only with initialised references. JVMS does not make null assignable to the
        // uninitialised forms, so a join of a null-pushing arm with a pending `new` must be a
        // generation error here — emitted, it would be a frame the real verifier rejects.
        if (kind == Kind.NULL && other.kind == Kind.OBJECT) {
            return other;
        }
        if (other.kind == Kind.NULL && kind == Kind.OBJECT) {
            return this;
        }
        if (kind == Kind.OBJECT && other.kind == Kind.OBJECT) {
            return object("java/lang/Object");
        }
        if (kind == Kind.TOP || other.kind == Kind.TOP) {
            // An unused slot on one path and a live value on the other: the value is not
            // guaranteed present after the join, so nothing may be assumed about the slot.
            return TOP;
        }
        throw new CodeGenerationException("cannot merge " + this + " with " + other
                + " at a control-flow join; the two paths leave incompatible values here");
    }

    /** Writes this as a {@code verification_type_info} structure. */
    void writeTo(ByteWriter out, ConstantPool pool) {
        out.u1(kind.tag);
        switch (kind) {
            case OBJECT -> out.u2(pool.classEntry(internalName));
            case UNINITIALIZED -> out.u2(newInstructionOffset);
            default -> { /* the tag alone is the whole structure */ }
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VerificationType that
                && kind == that.kind
                && Objects.equals(internalName, that.internalName)
                && newInstructionOffset == that.newInstructionOffset;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, internalName, newInstructionOffset);
    }

    @Override
    public String toString() {
        return switch (kind) {
            case OBJECT -> internalName.replace('/', '.');
            case UNINITIALIZED -> "uninitialized(new@" + newInstructionOffset + ")";
            default -> kind.name().toLowerCase(java.util.Locale.ROOT);
        };
    }
}
