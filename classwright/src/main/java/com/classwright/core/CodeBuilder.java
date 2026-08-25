package com.classwright.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Emits the body of one method, tracking the verifier's view of the operand stack and local
 * variables as it goes.
 *
 * <h2>Why this is not a thin wrapper over opcodes</h2>
 *
 * <p>A bytecode emitter that simply appends instructions produces classes that fail verification
 * with a {@link VerifyError} naming a bytecode offset. The offset tells you where the JVM noticed
 * the problem, not where the generator made it, and the two are often far apart. Debugging that
 * means disassembling the output and simulating the stack by hand.
 *
 * <p>So this builder simulates the stack itself, continuously. Every method knows what it consumes
 * and produces, so pushing a {@code long} where a reference is required is caught at the call that
 * did it, with a message and a Java stack trace pointing at the offending generator code. The
 * checks are always on: generation is not a hot path (defining a class costs the JVM roughly 25 µs,
 * dwarfing anything here) and a bytecode engine that is fast but subtly wrong is worthless.
 *
 * <p>Tracking the frame also means {@code max_stack}, {@code max_locals}, and the entire
 * {@code StackMapTable} fall out as by-products rather than needing a separate analysis pass.
 *
 * <h2>Structured control flow only</h2>
 *
 * <p>There is no {@code goto} in this API, and {@link Label} is not public. Control flow is
 * expressed with {@link #ifNullElse}, {@link #tableSwitch}, {@link #tryCatch}, and friends, each of
 * which is a well-formed region with known state at entry and exit.
 *
 * <p>That restriction is what makes correct stack maps achievable. Arbitrary jumps can produce
 * irreducible control flow, where the frame at a join point depends on paths that have not been
 * emitted yet, and computing frames for that in general is the hard dataflow problem this design
 * exists to avoid. Structured regions never have that property. Since Classwright only ever
 * generates glue code, giving up unstructured jumps costs nothing and removes an entire category
 * of defect.
 *
 * <p>Instances are not thread-safe and are used by a single generator at a time.
 */
public final class CodeBuilder {

    private final ConstantPool pool;
    private final String ownerInternalName;
    private final CwMethodType methodType;
    private final boolean isInstanceMethod;

    private final ByteWriter code = new ByteWriter(64);

    /** Operand stack, entry-indexed: a {@code long} is one element here. */
    private final List<VerificationType> operandStack = new ArrayList<>();

    /** Local variables, slot-indexed: a {@code long} occupies two entries, the second a TOP. */
    private final List<VerificationType> locals = new ArrayList<>();

    private final List<FrameEntry> frames = new ArrayList<>();
    private final List<ExceptionHandler> handlers = new ArrayList<>();

    private int currentStackSlots;
    private int maxStackSlots;
    private int maxLocals;

    /**
     * Whether the next instruction can actually be reached.
     *
     * <p>False immediately after a return, a throw, or an unconditional jump. Emitting into
     * unreachable code is always a generator bug, and is reported rather than silently producing
     * bytes the verifier will never look at.
     */
    private boolean reachable = true;

    private record FrameEntry(int offset, StackFrame frame) {
    }

    private record ExceptionHandler(int startPc, int endPc, int handlerPc, String catchType) {
    }

    /** Whether this body is an {@code <init>} that has not yet chained to super or this. */
    private boolean awaitingSuperCall;

    CodeBuilder(ConstantPool pool, String ownerInternalName, CwMethodType methodType,
                boolean isInstanceMethod, boolean isConstructor) {
        this.pool = pool;
        this.ownerInternalName = ownerInternalName;
        this.methodType = methodType;
        this.isInstanceMethod = isInstanceMethod;
        this.awaitingSuperCall = isConstructor;

        if (isInstanceMethod) {
            // A constructor's `this` is not a usable object until it has chained to a superclass
            // constructor, and the verifier enforces that. Saying so up front means an attempt to
            // use `this` too early is caught here rather than at class-load time.
            locals.add(isConstructor
                    ? VerificationType.UNINITIALIZED_THIS
                    : VerificationType.object(ownerInternalName));
        }
        for (CwType parameter : methodType.parameterTypes()) {
            appendLocal(VerificationType.of(parameter));
        }
        maxLocals = locals.size();
    }

    // ==========================================================================================
    // Local variables
    // ==========================================================================================

    /**
     * Pushes {@code this}.
     *
     * @return this builder, so calls can be chained
     */
    public CodeBuilder loadThis() {
        if (!isInstanceMethod) {
            throw new CodeGenerationException("a static method has no 'this'");
        }
        return load(0, CwType.objectType(ownerInternalName));
    }

    /**
     * Pushes the value in a local variable slot.
     *
     * @param slot the slot index
     * @param type the type stored there
     * @return this builder, so calls can be chained
     */
    public CodeBuilder load(int slot, CwType type) {
        requireReachable("load");
        VerificationType actual = localAt(slot);
        VerificationType expected = VerificationType.of(type);
        // `this` inside a constructor is a legitimate exception: it really is uninitialised, and
        // loading it is exactly how the super constructor gets called.
        if (!actual.equals(expected) && actual.kind() != VerificationType.Kind.UNINITIALIZED_THIS) {
            if (!(actual.isReference() && expected.isReference())) {
                throw new CodeGenerationException("slot " + slot + " holds " + actual
                        + " but was loaded as " + type);
            }
        }
        emitSlotInstruction(type.loadOpcode(), slot);
        push(actual.kind() == VerificationType.Kind.UNINITIALIZED_THIS ? actual : expected);
        return this;
    }

    /**
     * Pops a value into a local variable slot, recording its type there.
     *
     * @param slot the slot index
     * @param type the type being stored
     * @return this builder, so calls can be chained
     */
    public CodeBuilder store(int slot, CwType type) {
        requireReachable("store");
        popExpecting(type, "store");
        emitSlotInstruction(type.storeOpcode(), slot);
        setLocal(slot, VerificationType.of(type));
        return this;
    }

    /**
     * Reserves a fresh local variable slot.
     *
     * @param type the type that will be stored there
     * @return the slot index, for use with {@link #load} and {@link #store}
     */
    public int declareLocal(CwType type) {
        int slot = maxLocals;
        maxLocals += type.slots();
        return slot;
    }

    /**
     * Pushes parameter {@code index} of the method being built.
     *
     * <p>Uses {@link CwMethodType#parameterSlot} so callers never do the slot arithmetic
     * themselves, which is where this goes wrong in hand-written emitters.
     *
     * @param index zero-based parameter position
     * @return this builder, so calls can be chained
     */
    public CodeBuilder loadArgument(int index) {
        return load(methodType.parameterSlot(index, isInstanceMethod),
                methodType.parameterTypes().get(index));
    }

    /**
     * Pushes every parameter in order, ready for a call with the same signature.
     *
     * @return this builder, so calls can be chained
     */
    public CodeBuilder loadAllArguments() {
        for (int i = 0; i < methodType.parameterCount(); i++) {
            loadArgument(i);
        }
        return this;
    }

    // ==========================================================================================
    // Constants
    // ==========================================================================================

    /**
     * Pushes {@code null}.
     *
     * @return this builder, so calls can be chained
     */
    public CodeBuilder pushNull() {
        requireReachable("push");
        code.u1(Opcodes.ACONST_NULL);
        push(VerificationType.NULL);
        return this;
    }

    /**
     * Pushes an {@code int}, choosing the most compact encoding.
     *
     * <p>Small values have single-byte forms, and the range checks below pick between them. This is
     * a size optimisation rather than a speed one, but generated proxies push small constants
     * constantly &mdash; argument array indices, switch keys &mdash; so it adds up across a class.
     *
     * @param value the constant to push
     * @return this builder, so calls can be chained
     */
    public CodeBuilder pushInt(int value) {
        requireReachable("push");
        if (value >= -1 && value <= 5) {
            code.u1(Opcodes.ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            code.u1(Opcodes.BIPUSH).u1(value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            code.u1(Opcodes.SIPUSH).u2(value);
        } else {
            emitLdc(pool.integer(value), false);
        }
        push(VerificationType.INTEGER);
        return this;
    }

    /**
     * Pushes a {@code long}, using the compact form for 0 and 1.
     *
     * @param value the constant to push
     * @return this builder, so calls can be chained
     */
    public CodeBuilder pushLong(long value) {
        requireReachable("push");
        if (value == 0L || value == 1L) {
            code.u1(Opcodes.LCONST_0 + (int) value);
        } else {
            emitLdc(pool.longConstant(value), true);
        }
        push(VerificationType.LONG);
        return this;
    }

    /**
     * Pushes a {@code float}, using the compact form for 0, 1 and 2.
     *
     * @param value the constant to push
     * @return this builder, so calls can be chained
     */
    public CodeBuilder pushFloat(float value) {
        requireReachable("push");
        // Compared by bits: -0.0f would otherwise match the fconst_0 shortcut and lose its sign.
        int bits = Float.floatToRawIntBits(value);
        if (bits == Float.floatToRawIntBits(0f) || bits == Float.floatToRawIntBits(1f)
                || bits == Float.floatToRawIntBits(2f)) {
            code.u1(Opcodes.FCONST_0 + (int) value);
        } else {
            emitLdc(pool.floatConstant(value), false);
        }
        push(VerificationType.FLOAT);
        return this;
    }

    /**
     * Pushes a {@code double}, using the compact form for 0 and 1.
     *
     * @param value the constant to push
     * @return this builder, so calls can be chained
     */
    public CodeBuilder pushDouble(double value) {
        requireReachable("push");
        long bits = Double.doubleToRawLongBits(value);
        if (bits == Double.doubleToRawLongBits(0d) || bits == Double.doubleToRawLongBits(1d)) {
            code.u1(Opcodes.DCONST_0 + (int) value);
        } else {
            emitLdc(pool.doubleConstant(value), true);
        }
        push(VerificationType.DOUBLE);
        return this;
    }

    /**
     * Pushes a {@code String} constant.
     *
     * @param value the constant to push
     * @return this builder, so calls can be chained
     */
    public CodeBuilder pushString(String value) {
        requireReachable("push");
        emitLdc(pool.stringConstant(value), false);
        push(VerificationType.object("java/lang/String"));
        return this;
    }

    /**
     * Pushes a {@code Class} object.
     *
     * <p>Primitives go through the wrapper's {@code TYPE} field, because {@code ldc} of a class
     * constant cannot name a primitive: there is no {@code CONSTANT_Class} entry for {@code int}.
     * {@code Integer.TYPE} is exactly {@code int.class}, so the result is identical.
     *
     * @param type the type whose {@code Class} object to push
     * @return this builder, so calls can be chained
     */
    public CodeBuilder pushClassConstant(CwType type) {
        requireReachable("push");
        if (type.isPrimitive()) {
            return getStatic(type.boxed().internalName(), "TYPE", CwType.CLASS);
        }
        emitLdc(pool.classEntry(type), false);
        push(VerificationType.object("java/lang/Class"));
        return this;
    }

    /**
     * Pushes the default value for a type: zero, {@code false}, or {@code null}.
     *
     * @param type the type whose default to push; must not be {@code void}
     * @return this builder, so calls can be chained
     */
    public CodeBuilder pushDefault(CwType type) {
        return switch (type.sort()) {
            case BOOLEAN, BYTE, CHAR, SHORT, INT -> pushInt(0);
            case LONG -> pushLong(0L);
            case FLOAT -> pushFloat(0f);
            case DOUBLE -> pushDouble(0d);
            case OBJECT, ARRAY -> pushNull();
            case VOID -> throw new CodeGenerationException("void has no default value");
        };
    }

    // ==========================================================================================
    // Fields
    // ==========================================================================================

    /**
     * Reads an instance field, consuming the receiver.
     *
     * @param owner internal name of the declaring class
     * @param name  the field name
     * @param type  the field's type
     * @return this builder, so calls can be chained
     */
    public CodeBuilder getField(String owner, String name, CwType type) {
        requireReachable("getfield");
        popReference("getfield receiver");
        code.u1(Opcodes.GETFIELD).u2(pool.fieldRef(owner, name, type.descriptor()));
        push(VerificationType.of(type));
        return this;
    }

    /**
     * Writes an instance field, consuming the value and the receiver.
     *
     * @param owner internal name of the declaring class
     * @param name  the field name
     * @param type  the field's type
     * @return this builder, so calls can be chained
     */
    public CodeBuilder putField(String owner, String name, CwType type) {
        requireReachable("putfield");
        popExpecting(type, "putfield value");
        popReference("putfield receiver");
        code.u1(Opcodes.PUTFIELD).u2(pool.fieldRef(owner, name, type.descriptor()));
        return this;
    }

    /**
     * Reads a static field.
     *
     * @param owner internal name of the declaring class
     * @param name  the field name
     * @param type  the field's type
     * @return this builder, so calls can be chained
     */
    public CodeBuilder getStatic(String owner, String name, CwType type) {
        requireReachable("getstatic");
        code.u1(Opcodes.GETSTATIC).u2(pool.fieldRef(owner, name, type.descriptor()));
        push(VerificationType.of(type));
        return this;
    }

    /**
     * Writes a static field, consuming the value.
     *
     * @param owner internal name of the declaring class
     * @param name  the field name
     * @param type  the field's type
     * @return this builder, so calls can be chained
     */
    public CodeBuilder putStatic(String owner, String name, CwType type) {
        requireReachable("putstatic");
        popExpecting(type, "putstatic value");
        code.u1(Opcodes.PUTSTATIC).u2(pool.fieldRef(owner, name, type.descriptor()));
        return this;
    }

    // ==========================================================================================
    // Invocation
    // ==========================================================================================

    /**
     * Emits {@code invokevirtual}: an ordinary virtual call.
     *
     * @param owner internal name of the declaring class
     * @param name  the method name
     * @param type  the method's signature
     * @return this builder, so calls can be chained
     */
    public CodeBuilder invokeVirtual(String owner, String name, CwMethodType type) {
        return invoke(Opcodes.INVOKEVIRTUAL, owner, name, type, true, false);
    }

    /**
     * Emits {@code invokespecial}: a super call, a private call, or a constructor.
     *
     * <p>This is the instruction that makes a proxy's {@code super.method()} possible, and doing it
     * directly is why Classwright needs no separate fast-dispatch class the way CGLib did.
     *
     * @param owner internal name of the class declaring the method
     * @param name  the method name
     * @param type  the method's signature
     * @return this builder, so calls can be chained
     */
    public CodeBuilder invokeSpecial(String owner, String name, CwMethodType type) {
        return invokeSpecial(owner, name, type, false);
    }

    /**
     * Emits {@code invokespecial}, choosing the right kind of constant-pool reference.
     *
     * <p>An interface owner needs {@code CONSTANT_InterfaceMethodref} rather than
     * {@code CONSTANT_Methodref}, and the JVM checks: the wrong one produces an
     * {@code IncompatibleClassChangeError} at link time.
     *
     * <p>This form is how a generated class calls a <em>default method</em> as
     * {@code Interface.super.method()}. The JVM requires the interface to be a direct superinterface
     * of the calling class, so a generator using this must also declare that interface on the class
     * it is building.
     *
     * @param owner            internal name of the class or interface declaring the method
     * @param name             the method name
     * @param type             the method's signature
     * @param ownerIsInterface whether {@code owner} names an interface
     * @return this builder, so calls can be chained
     */
    public CodeBuilder invokeSpecial(String owner, String name, CwMethodType type,
                                     boolean ownerIsInterface) {
        return invoke(Opcodes.INVOKESPECIAL, owner, name, type, true, ownerIsInterface);
    }

    /**
     * Emits {@code invokestatic}, which has no receiver.
     *
     * @param owner internal name of the declaring class
     * @param name  the method name
     * @param type  the method's signature
     * @return this builder, so calls can be chained
     */
    public CodeBuilder invokeStatic(String owner, String name, CwMethodType type) {
        return invoke(Opcodes.INVOKESTATIC, owner, name, type, false, false);
    }

    /**
     * Emits {@code invokestatic} against a class or an interface.
     *
     * <p>The distinction matters for the same reason as {@link #invokeSpecial(String, String,
     * CwMethodType, boolean)}: a static method declared on an interface must be referenced through
     * a {@code CONSTANT_InterfaceMethodref}, and the JVM checks &mdash; the wrong constant kind
     * fails at link time with {@code IncompatibleClassChangeError}, an error that names neither
     * the generator nor the reason.
     *
     * @param owner            internal name of the declaring class or interface
     * @param name             the method name
     * @param type             the method's signature
     * @param ownerIsInterface whether {@code owner} names an interface
     * @return this builder, so calls can be chained
     */
    public CodeBuilder invokeStatic(String owner, String name, CwMethodType type,
                                    boolean ownerIsInterface) {
        return invoke(Opcodes.INVOKESTATIC, owner, name, type, false, ownerIsInterface);
    }

    /**
     * Emits {@code invokeinterface}.
     *
     * @param owner internal name of the declaring interface
     * @param name  the method name
     * @param type  the method's signature
     * @return this builder, so calls can be chained
     */
    public CodeBuilder invokeInterface(String owner, String name, CwMethodType type) {
        return invoke(Opcodes.INVOKEINTERFACE, owner, name, type, true, true);
    }

    /**
     * Calls a constructor on an uninitialised reference, turning it into a usable object.
     *
     * <p>Both the stack and the local variables are swept for the uninitialised reference and
     * updated in place. That is not an optimisation: the verifier tracks each pending allocation
     * individually, and a copy of the reference made with {@code dup} before the constructor call
     * &mdash; which is the normal way to construct an object &mdash; has to become initialised at
     * exactly the same moment as the original.
     *
     * @param owner internal name of the class being constructed
     * @param type  the constructor's parameter types, with {@code void} return
     * @return this builder, so calls can be chained
     */
    public CodeBuilder invokeConstructor(String owner, CwMethodType type) {
        requireReachable("constructor call");
        for (int i = type.parameterCount() - 1; i >= 0; i--) {
            popArgument(type.parameterTypes().get(i), i, "<init>");
        }
        VerificationType target = popValue();
        if (target.kind() != VerificationType.Kind.UNINITIALIZED
                && target.kind() != VerificationType.Kind.UNINITIALIZED_THIS) {
            throw new CodeGenerationException("a constructor may only be invoked on an "
                    + "uninitialised reference, but found " + target
                    + ". Emit `new` first, or call this from a constructor.");
        }
        code.u1(Opcodes.INVOKESPECIAL).u2(pool.methodRef(owner, "<init>", type.descriptor()));

        VerificationType initialised = target.kind() == VerificationType.Kind.UNINITIALIZED_THIS
                ? VerificationType.object(ownerInternalName)
                : VerificationType.object(owner);
        if (target.kind() == VerificationType.Kind.UNINITIALIZED_THIS) {
            awaitingSuperCall = false;
        }
        replaceEverywhere(target, initialised);
        return this;
    }

    private CodeBuilder invoke(int opcode, String owner, String name, CwMethodType type,
                               boolean hasReceiver, boolean ownerIsInterface) {
        requireReachable("invoke");
        if (name.equals("<init>")) {
            throw new CodeGenerationException(
                    "use invokeConstructor() for <init>, so that the uninitialised reference is "
                            + "tracked correctly");
        }
        for (int i = type.parameterCount() - 1; i >= 0; i--) {
            popArgument(type.parameterTypes().get(i), i, name);
        }
        if (hasReceiver) {
            popReceiver(name);
        }

        int reference = ownerIsInterface
                ? pool.interfaceMethodRef(owner, name, type.descriptor())
                : pool.methodRef(owner, name, type.descriptor());
        code.u1(opcode).u2(reference);
        if (opcode == Opcodes.INVOKEINTERFACE) {
            // invokeinterface alone carries an argument count and a reserved zero byte, a quirk
            // left over from an early dispatch implementation. The count is in slots, including
            // the receiver.
            code.u1(type.parameterSlots() + 1).u1(0);
        }

        if (!type.returnType().isVoid()) {
            push(VerificationType.of(type.returnType()));
        }
        return this;
    }

    // ==========================================================================================
    // Objects and arrays
    // ==========================================================================================

    /**
     * Allocates an uninitialised instance. Must be followed by {@link #invokeConstructor}.
     *
     * <p>The verification type records this instruction's offset, which is how the verifier
     * distinguishes two allocations that are pending at the same time.
     *
     * @param type the class to allocate
     * @return this builder, so calls can be chained
     */
    public CodeBuilder newInstance(CwType type) {
        requireReachable("new");
        int offset = code.length();
        code.u1(Opcodes.NEW).u2(pool.classEntry(type));
        push(VerificationType.uninitialized(offset));
        return this;
    }

    /**
     * Creates an array, consuming a length from the stack.
     *
     * @param componentType the array's element type
     * @return this builder, so calls can be chained
     */
    public CodeBuilder newArray(CwType componentType) {
        requireReachable("newarray");
        popExpecting(CwType.INT, "array length");
        if (componentType.isPrimitive()) {
            code.u1(Opcodes.NEWARRAY).u1(componentType.newArrayTypeCode());
        } else {
            code.u1(Opcodes.ANEWARRAY).u2(pool.classEntry(componentType));
        }
        push(VerificationType.of(CwType.arrayOf(componentType)));
        return this;
    }

    /**
     * Reads {@code array[index]}, consuming both.
     *
     * @param componentType the array's element type
     * @return this builder, so calls can be chained
     */
    public CodeBuilder arrayLoad(CwType componentType) {
        requireReachable("array load");
        popExpecting(CwType.INT, "array index");
        popReference("array reference");
        code.u1(componentType.arrayLoadOpcode());
        push(VerificationType.of(componentType));
        return this;
    }

    /**
     * Writes {@code array[index] = value}, consuming all three.
     *
     * @param componentType the array's element type
     * @return this builder, so calls can be chained
     */
    public CodeBuilder arrayStore(CwType componentType) {
        requireReachable("array store");
        popExpecting(componentType, "array element");
        popExpecting(CwType.INT, "array index");
        popReference("array reference");
        code.u1(componentType.arrayStoreOpcode());
        return this;
    }

    /**
     * Pushes an array's length, consuming the array.
     *
     * @return this builder, so calls can be chained
     */
    public CodeBuilder arrayLength() {
        requireReachable("arraylength");
        popReference("array reference");
        code.u1(Opcodes.ARRAYLENGTH);
        push(VerificationType.INTEGER);
        return this;
    }

    /**
     * Emits {@code checkcast}, narrowing the reference on top of the stack.
     *
     * @param type the type to cast to
     * @return this builder, so calls can be chained
     */
    public CodeBuilder checkCast(CwType type) {
        requireReachable("checkcast");
        popReference("checkcast operand");
        code.u1(Opcodes.CHECKCAST).u2(pool.classEntry(type));
        push(VerificationType.of(type));
        return this;
    }

    /**
     * Emits {@code instanceof}, replacing the reference with an {@code int} flag.
     *
     * @param type the type to test against
     * @return this builder, so calls can be chained
     */
    public CodeBuilder instanceOf(CwType type) {
        requireReachable("instanceof");
        popReference("instanceof operand");
        code.u1(Opcodes.INSTANCEOF).u2(pool.classEntry(type));
        push(VerificationType.INTEGER);
        return this;
    }

    // ==========================================================================================
    // Stack manipulation
    // ==========================================================================================

    /**
     * Duplicates the top value. Rejects {@code long} and {@code double}, which need {@code dup2}.
     *
     * @return this builder, so calls can be chained
     */
    public CodeBuilder dup() {
        requireReachable("dup");
        VerificationType top = peek();
        if (top.isWide()) {
            throw new CodeGenerationException(
                    "dup cannot duplicate " + top + ", which occupies two slots; use dup2");
        }
        code.u1(Opcodes.DUP);
        push(top);
        return this;
    }

    /**
     * Duplicates a two-slot value.
     *
     * @return this builder, so calls can be chained
     */
    public CodeBuilder dup2() {
        requireReachable("dup2");
        VerificationType top = peek();
        if (!top.isWide()) {
            throw new CodeGenerationException(
                    "dup2 on the one-slot value " + top + " would duplicate two separate stack "
                            + "entries; use dup instead");
        }
        code.u1(Opcodes.DUP2);
        push(top);
        return this;
    }

    /**
     * Duplicates the top value and inserts the copy below the one beneath it.
     *
     * @return this builder, so calls can be chained
     */
    public CodeBuilder dupX1() {
        requireReachable("dup_x1");
        VerificationType top = popValue();
        VerificationType beneath = popValue();
        if (top.isWide() || beneath.isWide()) {
            throw new CodeGenerationException("dup_x1 requires two one-slot values");
        }
        code.u1(Opcodes.DUP_X1);
        push(top);
        push(beneath);
        push(top);
        return this;
    }

    /**
     * Discards the top value.
     *
     * @return this builder, so calls can be chained
     */
    public CodeBuilder pop() {
        requireReachable("pop");
        VerificationType top = popValue();
        code.u1(top.isWide() ? Opcodes.POP2 : Opcodes.POP);
        return this;
    }

    /**
     * Adds the top two values, which must be the same numeric type.
     *
     * <p>The only arithmetic the engine provides. Generated glue code counts array indices and
     * little else; anything richer belongs in the intercepted method, not in the proxy around it.
     *
     * @param type one of {@code int}, {@code long}, {@code float}, {@code double}, or a type
     *             represented as {@code int} on the stack
     * @return this builder, so calls can be chained
     */
    public CodeBuilder add(CwType type) {
        requireReachable("add");
        popExpecting(type, "right operand of add");
        popExpecting(type, "left operand of add");
        int opcode = switch (type.sort()) {
            case BOOLEAN, BYTE, CHAR, SHORT, INT -> Opcodes.IADD;
            case LONG -> Opcodes.LADD;
            case FLOAT -> Opcodes.FADD;
            case DOUBLE -> Opcodes.DADD;
            default -> throw new CodeGenerationException("cannot add values of type " + type);
        };
        code.u1(opcode);
        push(VerificationType.of(type));
        return this;
    }

    /**
     * Unsigned-right-shifts the {@code int} beneath the top of stack by the {@code int} on top.
     *
     * <p>Exists for chunked dispatch: a generated switch that would exceed the JVM's 65,535-byte
     * method limit is split into chunk methods, and {@code index >>> shift} selects the chunk in
     * constant time.
     *
     * @return this builder, so calls can be chained
     */
    public CodeBuilder shiftRightUnsigned() {
        requireReachable("iushr");
        popExpecting(CwType.INT, "shift distance");
        popExpecting(CwType.INT, "shift operand");
        code.u1(Opcodes.IUSHR);
        push(VerificationType.of(CwType.INT));
        return this;
    }

    /**
     * Subtracts the top value from the one beneath it.
     *
     * <p>Exists for the same narrow reason as {@link #add}: counting. A loop over an array needs
     * {@code index - length} to test for the end, since the engine offers comparison against zero
     * rather than between two values.
     *
     * @param type the numeric type of both operands
     * @return this builder, so calls can be chained
     */
    public CodeBuilder subtract(CwType type) {
        requireReachable("subtract");
        popExpecting(type, "right operand of subtract");
        popExpecting(type, "left operand of subtract");
        int opcode = switch (type.sort()) {
            case BOOLEAN, BYTE, CHAR, SHORT, INT -> Opcodes.ISUB;
            case LONG -> Opcodes.LSUB;
            case FLOAT -> Opcodes.FSUB;
            case DOUBLE -> Opcodes.DSUB;
            default -> throw new CodeGenerationException(
                    "cannot subtract values of type " + type);
        };
        code.u1(opcode);
        push(VerificationType.of(type));
        return this;
    }

    // ==========================================================================================
    // Boxing
    // ==========================================================================================

    /**
     * Boxes the primitive on top of the stack, e.g. {@code int} to {@code Integer}.
     *
     * <p>Uses {@code valueOf} rather than a constructor: the wrapper constructors are deprecated
     * for removal, and {@code valueOf} consults the shared cache for small values.
     *
     * @param primitive the primitive type currently on the stack
     * @return this builder, so calls can be chained
     */
    public CodeBuilder box(CwType primitive) {
        if (primitive.isReference()) {
            return this;   // already boxed; harmless no-op keeps callers simple
        }
        if (primitive.isVoid()) {
            throw new CodeGenerationException("void cannot be boxed");
        }
        requireReachable("box");
        popExpecting(primitive, "box operand");
        CwType wrapper = primitive.boxed();
        // The descriptor is one of exactly eight strings; building it through CwMethodType ran a
        // Stream pipeline per boxed argument of every generated method, on the hottest emit path.
        String descriptor = switch (primitive.sort()) {
            case BOOLEAN -> "(Z)Ljava/lang/Boolean;";
            case BYTE -> "(B)Ljava/lang/Byte;";
            case CHAR -> "(C)Ljava/lang/Character;";
            case SHORT -> "(S)Ljava/lang/Short;";
            case INT -> "(I)Ljava/lang/Integer;";
            case LONG -> "(J)Ljava/lang/Long;";
            case FLOAT -> "(F)Ljava/lang/Float;";
            case DOUBLE -> "(D)Ljava/lang/Double;";
            default -> throw new CodeGenerationException(primitive + " cannot be boxed");
        };
        code.u1(Opcodes.INVOKESTATIC).u2(pool.methodRef(wrapper.internalName(), "valueOf",
                descriptor));
        push(VerificationType.of(wrapper));
        return this;
    }

    /**
     * Unboxes the reference on top of the stack into the given primitive.
     *
     * <p>Emits the {@code checkcast} as well, because the value has almost always arrived as
     * {@code Object} &mdash; out of an argument array, or from a generic callback &mdash; and the
     * verifier will not let {@code intValue()} be called on it otherwise.
     *
     * @param primitive the primitive type to unbox to
     * @return this builder, so calls can be chained
     */
    public CodeBuilder unbox(CwType primitive) {
        if (primitive.isReference()) {
            return checkCast(primitive);
        }
        if (primitive.isVoid()) {
            throw new CodeGenerationException("void cannot be unboxed");
        }
        requireReachable("unbox");

        // Numeric primitives go through Number rather than their exact wrapper, which accepts any
        // numeric box and narrows it. That is what CGLib did, and matching it matters: code that
        // returned a Long from an interceptor for an int method worked there, and refusing it here
        // would break migrations for no benefit. The cost is that the narrowing is silent, exactly
        // as Number.intValue() is.
        CwType wrapper = switch (primitive.sort()) {
            case BOOLEAN -> BOOLEAN_WRAPPER;
            case CHAR -> CHARACTER_WRAPPER;
            default -> NUMBER;
        };
        checkCast(wrapper);
        popReference("unbox operand");
        // Name and descriptor are drawn from a fixed set of eight; see box() for why they are
        // literals rather than built through CwMethodType.
        String accessor;
        String descriptor;
        switch (primitive.sort()) {
            case BOOLEAN -> { accessor = "booleanValue"; descriptor = "()Z"; }
            case BYTE -> { accessor = "byteValue"; descriptor = "()B"; }
            case CHAR -> { accessor = "charValue"; descriptor = "()C"; }
            case SHORT -> { accessor = "shortValue"; descriptor = "()S"; }
            case INT -> { accessor = "intValue"; descriptor = "()I"; }
            case LONG -> { accessor = "longValue"; descriptor = "()J"; }
            case FLOAT -> { accessor = "floatValue"; descriptor = "()F"; }
            case DOUBLE -> { accessor = "doubleValue"; descriptor = "()D"; }
            default -> throw new CodeGenerationException(primitive + " cannot be unboxed");
        }
        code.u1(Opcodes.INVOKEVIRTUAL).u2(pool.methodRef(wrapper.internalName(),
                accessor, descriptor));
        push(VerificationType.of(primitive));
        return this;
    }

    private static final CwType NUMBER = CwType.objectType("java/lang/Number");
    private static final CwType BOOLEAN_WRAPPER = CwType.objectType("java/lang/Boolean");
    private static final CwType CHARACTER_WRAPPER = CwType.objectType("java/lang/Character");

    /**
     * Converts an {@code Object} on the stack into {@code type}, treating {@code null} as the
     * type's zero value.
     *
     * <p>For a callback that returns {@code Object} into a method returning a primitive. CGLib
     * substituted zero for {@code null} rather than throwing, and code written against it relies
     * on that: an interceptor with no opinion returns {@code null} and expects {@code 0} or
     * {@code false} to come out.
     *
     * <p>Reference types need no special handling; {@code null} passes a {@code checkcast}.
     *
     * @param type the target type
     * @return this builder, so calls can be chained
     */
    public CodeBuilder unboxOrDefault(CwType type) {
        if (type.isReference()) {
            return checkCast(type);
        }
        if (type.isVoid()) {
            throw new CodeGenerationException("void has no value to convert");
        }
        requireReachable("unbox");

        int slot = declareLocal(CwType.OBJECT);
        store(slot, CwType.OBJECT);
        load(slot, CwType.OBJECT);
        ifNullElse(
                () -> pushDefault(type),
                () -> load(slot, CwType.OBJECT).unbox(type));
        return this;
    }

    // ==========================================================================================
    // Composite operations
    // ==========================================================================================

    /**
     * Packs every parameter of the method being built into a new {@code Object[]}.
     *
     * <p>The shape every reflective callback contract needs: CGLib's {@code MethodInterceptor} and
     * the JDK's {@code InvocationHandler} both receive arguments this way. Primitives are boxed on
     * the way in.
     *
     * <p>Leaves the array on the stack.
     *
     * @return this builder, so calls can be chained
     */
    public CodeBuilder packArgumentsIntoArray() {
        List<CwType> parameters = methodType.parameterTypes();
        if (parameters.isEmpty()) {
            // Zero-length arrays are immutable, so every no-argument call site shares one instead
            // of allocating. The callback contract (writes to the array do not affect the caller)
            // is unaffected: there is nothing to write.
            return getStatic(RuntimeConstants.INTERNAL_NAME, "EMPTY_OBJECT_ARRAY",
                    CwType.OBJECT_ARRAY);
        }
        pushInt(parameters.size());
        newArray(CwType.OBJECT);
        for (int i = 0; i < parameters.size(); i++) {
            dup();
            pushInt(i);
            loadArgument(i);
            box(parameters.get(i));
            arrayStore(CwType.OBJECT);
        }
        return this;
    }

    /**
     * Unpacks an {@code Object[]} held in a local slot, pushing each element as the given type.
     *
     * <p>The inverse of {@link #packArgumentsIntoArray}: takes the generic argument array a
     * callback was handed and turns it back into a typed argument list ready for a real call.
     * Unrolled rather than looped, because the element types differ per position and each needs its
     * own cast or unboxing sequence.
     *
     * @param arraySlot local slot holding the array
     * @param types     the type each element should be converted to, in order
     * @return this builder, so calls can be chained
     */
    public CodeBuilder unpackArrayIntoArguments(int arraySlot, List<CwType> types) {
        for (int i = 0; i < types.size(); i++) {
            load(arraySlot, CwType.OBJECT_ARRAY);
            pushInt(i);
            arrayLoad(CwType.OBJECT);
            unbox(types.get(i));
        }
        return this;
    }

    // ==========================================================================================
    // Control flow
    // ==========================================================================================

    /**
     * Branches on whether the reference on top of the stack is null.
     *
     * <p>Both blocks see the same verifier state on entry, and whatever they leave behind must
     * agree at the join &mdash; a mismatch is reported as a generation error rather than becoming a
     * {@link VerifyError}. Either block may end with a return or a throw, in which case it simply
     * contributes nothing to the join.
     *
     * @param whenNull    emitted when the value is null
     * @param whenNonNull emitted when it is not
     * @return this builder, so calls can be chained
     */
    public CodeBuilder ifNullElse(Runnable whenNull, Runnable whenNonNull) {
        requireReachable("branch");
        popReference("ifnull operand");

        Label nonNull = new Label();
        Label end = new Label();

        emitJump(Opcodes.IFNONNULL, nonNull);
        whenNull.run();
        if (reachable) {
            emitJump(Opcodes.GOTO, end);
        }

        bind(nonNull);
        whenNonNull.run();

        bind(end);
        return this;
    }

    /**
     * Emits {@code body} only when the reference on top of the stack is null.
     *
     * @param body emitted when the value is null
     * @return this builder, so calls can be chained
     */
    public CodeBuilder ifNull(Runnable body) {
        return ifNullElse(body, () -> { });
    }

    /**
     * Emits {@code body} only when the reference on top of the stack is non-null.
     *
     * @param body emitted when the value is non-null
     * @return this builder, so calls can be chained
     */
    public CodeBuilder ifNonNull(Runnable body) {
        return ifNullElse(() -> { }, body);
    }

    /**
     * How to compare an {@code int} against zero.
     *
     * <p>Named rather than a raw opcode so that callers need no opcode table, and so that an
     * invalid comparison is a compile error instead of a generation-time one.
     */
    public enum IntTest {

        /** True when the value is zero. */
        EQUAL_TO_ZERO(Opcodes.IFEQ),

        /** True when the value is anything but zero, which is how a {@code boolean} is tested. */
        NOT_ZERO(Opcodes.IFNE),

        /** True when the value is negative. */
        LESS_THAN_ZERO(Opcodes.IFLT),

        /** True when the value is zero or positive. */
        AT_LEAST_ZERO(Opcodes.IFGE),

        /** True when the value is positive. */
        GREATER_THAN_ZERO(Opcodes.IFGT),

        /** True when the value is zero or negative. */
        AT_MOST_ZERO(Opcodes.IFLE);

        private final int opcode;

        IntTest(int opcode) {
            this.opcode = opcode;
        }
    }

    /**
     * Branches on an {@code int} comparison against zero.
     *
     * @param test      which comparison to apply
     * @param whenTrue  emitted when the comparison holds
     * @param whenFalse emitted when it does not
     * @return this builder, so calls can be chained
     */
    public CodeBuilder ifIntComparison(IntTest test, Runnable whenTrue, Runnable whenFalse) {
        requireReachable("branch");
        popExpecting(CwType.INT, "comparison operand");

        Label whenFalseLabel = new Label();
        Label end = new Label();
        // Jump to the false block when the *inverse* condition holds, then fall through into the
        // true block. The six opcodes form consecutive pairs starting at IFEQ -- (eq,ne),
        // (lt,ge), (gt,le) -- so the inverse is found by flipping the low bit of the offset from
        // IFEQ. Flipping the low bit of the opcode itself is wrong: IFEQ is 153, and 153 ^ 1 is
        // 152, which is dcmpg.
        emitJump(Opcodes.IFEQ + ((test.opcode - Opcodes.IFEQ) ^ 1), whenFalseLabel);
        whenTrue.run();
        if (reachable) {
            emitJump(Opcodes.GOTO, end);
        }
        bind(whenFalseLabel);
        whenFalse.run();
        bind(end);
        return this;
    }

    /**
     * A dense integer switch, consuming the key from the stack.
     *
     * <p>The dispatch mechanism behind index-based invocation: a proxy that must reach one of many
     * super methods gives each an index and switches on it. One indirect jump, no reflection, and
     * &mdash; unlike CGLib's approach &mdash; no separate generated class to hold it.
     *
     * @param low         lowest key
     * @param high        highest key, inclusive
     * @param caseBody    emits the body for each key in {@code [low, high]}
     * @param defaultBody emits the body for anything else; must not fall through
     * @return this builder, so calls can be chained
     */
    public CodeBuilder tableSwitch(int low, int high, IntConsumer caseBody, Runnable defaultBody) {
        requireReachable("tableswitch");
        if (high < low) {
            throw new CodeGenerationException("switch range " + low + ".." + high + " is empty");
        }
        // Long arithmetic, deliberately: the extreme range overflows int (and at
        // Integer.MAX_VALUE the emission loop below would never terminate). The bound itself is
        // the method-code limit talking — at 4 bytes per table entry plus a case body each, a
        // span in the tens of thousands cannot fit in 65,535 bytes, and failing here names the
        // cause instead of hanging or dying later in the size guard.
        long span = (long) high - low + 1;
        if (span > 16_000) {
            throw new CodeGenerationException("tableswitch spanning " + span + " keys cannot fit "
                    + "in the JVM's 65,535-byte method-code limit. Split the dispatch into "
                    + "chunks, as the proxy and FastClass generators do.");
        }
        popExpecting(CwType.INT, "switch key");

        int instructionStart = code.length();
        code.u1(Opcodes.TABLESWITCH);
        // The three-word header must land on a 4-byte boundary measured from the start of the
        // method's code, so between zero and three padding bytes follow the opcode.
        while (code.length() % 4 != 0) {
            code.u1(0);
        }

        Label defaultLabel = new Label();
        Label end = new Label();
        List<Label> caseLabels = new ArrayList<>(high - low + 1);

        reserveWideBranch(defaultLabel, instructionStart);
        code.u4(low);
        code.u4(high);
        for (int key = low; key <= high; key++) {
            Label caseLabel = new Label();
            caseLabels.add(caseLabel);
            reserveWideBranch(caseLabel, instructionStart);
        }

        StackFrame atSwitch = snapshot();
        reachable = false;

        for (int key = low; key <= high; key++) {
            Label caseLabel = caseLabels.get(key - low);
            caseLabel.arriveWith(atSwitch);
            bind(caseLabel);
            caseBody.accept(key);
            if (reachable) {
                emitJump(Opcodes.GOTO, end);
            }
        }

        defaultLabel.arriveWith(atSwitch);
        bind(defaultLabel);
        defaultBody.run();
        if (reachable) {
            emitJump(Opcodes.GOTO, end);
        }

        bind(end);
        return this;
    }

    /**
     * A sparse integer switch, consuming the key from the stack.
     *
     * <p>The dispatch mechanism behind name-based invocation: hashed string keys are anything but
     * dense, so a {@code tableswitch} over them would be almost entirely holes. This is how a
     * generated {@code BeanMap} jumps straight from a property name's hash to its accessor, the
     * shape {@code javac} itself compiles a string switch to.
     *
     * @param keys        the match values, in strictly ascending order
     * @param caseBody    emits the body for each key, receiving the key's value
     * @param defaultBody emits the body for anything else; must not fall through
     * @return this builder, so calls can be chained
     */
    public CodeBuilder lookupSwitch(int[] keys, IntConsumer caseBody, Runnable defaultBody) {
        requireReachable("lookupswitch");
        for (int i = 1; i < keys.length; i++) {
            if (keys[i] <= keys[i - 1]) {
                throw new CodeGenerationException("lookupswitch keys must be strictly ascending; "
                        + keys[i] + " follows " + keys[i - 1]);
            }
        }
        popExpecting(CwType.INT, "switch key");

        int instructionStart = code.length();
        code.u1(Opcodes.LOOKUPSWITCH);
        // The header must land on a 4-byte boundary measured from the start of the method's
        // code, exactly as for tableswitch.
        while (code.length() % 4 != 0) {
            code.u1(0);
        }

        Label defaultLabel = new Label();
        Label end = new Label();
        List<Label> caseLabels = new ArrayList<>(keys.length);

        reserveWideBranch(defaultLabel, instructionStart);
        code.u4(keys.length);
        for (int key : keys) {
            Label caseLabel = new Label();
            caseLabels.add(caseLabel);
            code.u4(key);
            reserveWideBranch(caseLabel, instructionStart);
        }

        StackFrame atSwitch = snapshot();
        reachable = false;

        for (int i = 0; i < keys.length; i++) {
            Label caseLabel = caseLabels.get(i);
            caseLabel.arriveWith(atSwitch);
            bind(caseLabel);
            caseBody.accept(keys[i]);
            if (reachable) {
                emitJump(Opcodes.GOTO, end);
            }
        }

        defaultLabel.arriveWith(atSwitch);
        bind(defaultLabel);
        defaultBody.run();
        if (reachable) {
            emitJump(Opcodes.GOTO, end);
        }

        bind(end);
        return this;
    }

    /**
     * Runs {@code body}, transferring to {@code handler} if it throws the given exception type.
     *
     * <p>The handler starts with the caught exception as the only thing on the stack, and with the
     * local variables as they were on entry to the try block. Anything the body stored into a local
     * may or may not have happened by the time an exception was thrown, so the verifier cannot
     * assume it, and neither does this.
     *
     * @param body          the guarded region
     * @param exceptionType the exception to catch
     * @param handler       emitted with the exception on the stack
     * @return this builder, so calls can be chained
     */
    public CodeBuilder tryCatch(Runnable body, CwType exceptionType, Runnable handler) {
        requireReachable("try");
        StackFrame onEntry = snapshot();
        if (!onEntry.stack().isEmpty()) {
            throw new CodeGenerationException("the operand stack must be empty when entering a "
                    + "try block, because a handler is entered with only the exception on it");
        }

        // The handler's locals start as the entry locals, but the body can retype an entry slot
        // — and the JVM requires every covered instruction's locals to be assignable to the
        // handler frame. setLocal weakens the affected slot to TOP in every active handler
        // frame, which is always sound: the handler then simply cannot read that slot. A frame
        // frozen at entry instead emitted a class the verifier rejects.
        List<VerificationType> handlerLocals = new ArrayList<>(onEntry.locals());
        activeTryHandlerLocals.push(handlerLocals);
        int startPc = code.length();
        try {
            body.run();
        } finally {
            activeTryHandlerLocals.pop();
        }
        int endPc = code.length();
        if (endPc == startPc) {
            // JVMS 4.7.3 requires start_pc < end_pc; an empty guarded region would fail at class
            // load with a ClassFormatError that points nowhere near the generator that caused it.
            throw new CodeGenerationException("the try block emitted no code; the JVM requires a "
                    + "non-empty guarded region. Emit the handler-less code directly instead.");
        }

        Label end = new Label();
        if (reachable) {
            emitJump(Opcodes.GOTO, end);
        }

        int handlerPc = code.length();
        handlers.add(new ExceptionHandler(startPc, endPc, handlerPc, exceptionType.internalName()));

        // Entering a handler is a control-flow join like any other, and needs its own frame.
        StackFrame handlerFrame = StackFrame.of(handlerLocals,
                List.of(VerificationType.of(exceptionType)));
        restore(handlerFrame);
        reachable = true;
        recordFrame(handlerPc, handlerFrame);

        handler.run();
        bind(end);
        return this;
    }

    /**
     * The pending handler frames of every {@code tryCatch} whose covered region is still being
     * emitted — including an inner handler's code, which an enclosing try also covers.
     */
    private final java.util.ArrayDeque<List<VerificationType>> activeTryHandlerLocals =
            new java.util.ArrayDeque<>();

    /**
     * A {@code while} loop.
     *
     * <p>The loop head is a join point before its back edge exists, so it is marked as a branch
     * target ahead of binding. Without that the frame would be omitted and the jump back would fail
     * verification.
     *
     * @param condition emits a test leaving an {@code int} on the stack; loops while non-zero
     * @param body      the loop body
     * @return this builder, so calls can be chained
     */
    public CodeBuilder whileLoop(Runnable condition, Runnable body) {
        requireReachable("loop");
        Label head = new Label();
        Label exit = new Label();

        head.markAsBranchTarget();
        bind(head);

        condition.run();
        popExpecting(CwType.INT, "loop condition");
        emitJump(Opcodes.IFEQ, exit);

        body.run();
        if (reachable) {
            emitJump(Opcodes.GOTO, head);
        }

        bind(exit);
        return this;
    }

    // ==========================================================================================
    // Exits
    // ==========================================================================================

    /**
     * Returns the value on the stack, or nothing if the method is {@code void}.
     *
     * @return this builder, so calls can be chained
     */
    public CodeBuilder returnValue() {
        return returnValue(methodType.returnType());
    }

    /**
     * Returns a value of the given type, consuming it from the stack.
     *
     * @param type the type being returned; {@code void} returns nothing
     * @return this builder, so calls can be chained
     */
    public CodeBuilder returnValue(CwType type) {
        requireReachable("return");
        if (awaitingSuperCall) {
            // The verifier rejects a constructor that returns without chaining ("Constructor must
            // call super() or this()"), at class load, pointing at nothing useful.
            throw new CodeGenerationException("this constructor returns without having chained "
                    + "to a superclass constructor; call invokeConstructor on `this` first");
        }
        if (!type.isVoid()) {
            popExpecting(type, "return value");
        }
        if (!operandStack.isEmpty()) {
            throw new CodeGenerationException("returning with " + operandStack.size()
                    + " value(s) still on the operand stack: " + operandStack);
        }
        code.u1(type.returnOpcode());
        reachable = false;
        return this;
    }

    /**
     * Throws the exception on top of the stack.
     *
     * @return this builder, so calls can be chained
     */
    public CodeBuilder throwException() {
        requireReachable("athrow");
        popReference("athrow operand");
        code.u1(Opcodes.ATHROW);
        reachable = false;
        return this;
    }

    // ==========================================================================================
    // Emission internals
    // ==========================================================================================

    private void emitLdc(int poolIndex, boolean wide) {
        if (wide) {
            code.u1(Opcodes.LDC2_W).u2(poolIndex);
        } else if (poolIndex <= 0xFF) {
            code.u1(Opcodes.LDC).u1(poolIndex);
        } else {
            code.u1(Opcodes.LDC_W).u2(poolIndex);
        }
    }

    /**
     * Emits a local-variable instruction, using the compact and wide forms as needed.
     *
     * <p>Slots 0 to 3 have single-byte opcodes, slots up to 255 take a one-byte operand, and beyond
     * that the {@code wide} prefix is mandatory. A method only reaches the third case if it has a
     * great many parameters or locals, which is exactly when nobody is watching &mdash; so it is
     * handled here rather than left as a latent limit.
     */
    private void emitSlotInstruction(int opcode, int slot) {
        if (slot < 0) {
            throw new CodeGenerationException("negative local variable slot " + slot);
        }
        if (slot <= 3) {
            // The _0.._3 shortcut blocks sit four apart, in the same order as the base opcodes.
            int shortcutBase = opcode < Opcodes.ISTORE
                    ? Opcodes.ILOAD_0 + (opcode - Opcodes.ILOAD) * 4
                    : Opcodes.ISTORE_0 + (opcode - Opcodes.ISTORE) * 4;
            code.u1(shortcutBase + slot);
        } else if (slot <= 0xFF) {
            code.u1(opcode).u1(slot);
        } else if (slot <= 0xFFFF) {
            code.u1(Opcodes.WIDE).u1(opcode).u2(slot);
        } else {
            // u2 would silently truncate, aliasing a live low slot — code that *verifies* and
            // reads the wrong variable, the one failure worse than a rejected class.
            throw new CodeGenerationException("local variable slot " + slot + " is beyond the "
                    + "65,535 the wide instruction form can address; declare fewer locals");
        }
        int required = slot + (opcode == Opcodes.LLOAD || opcode == Opcodes.DLOAD
                || opcode == Opcodes.LSTORE || opcode == Opcodes.DSTORE ? 2 : 1);
        maxLocals = Math.max(maxLocals, required);
    }

    private void emitJump(int opcode, Label label) {
        int instructionStart = code.length();
        label.markAsBranchTarget();

        StackFrame here = snapshot();
        if (label.isBound()) {
            StackFrame before = label.expectedFrame();
            label.arriveWith(here);
            if (before != null && !before.equals(label.expectedFrame())) {
                throw new CodeGenerationException("a backward jump reaches an already-emitted "
                        + "stack map frame with a different state (" + before + " versus " + here
                        + "). Loop bodies must leave the verifier state as they found it.");
            }
        } else {
            label.arriveWith(here);
        }

        code.u1(opcode);
        if (label.isBound()) {
            int relative = label.offset() - instructionStart;
            if (relative < Short.MIN_VALUE || relative > Short.MAX_VALUE) {
                throw new CodeGenerationException("backward branch of " + relative
                        + " bytes exceeds the 16-bit jump range");
            }
            code.u2(relative & 0xFFFF);
        } else {
            label.addPatch(code.length(), instructionStart);
            code.u2(0);
        }

        if (opcode == Opcodes.GOTO) {
            reachable = false;
        }
    }

    private void reserveWideBranch(Label label, int instructionStart) {
        label.markAsBranchTarget();
        label.addWidePatch(code.length(), instructionStart);
        code.u4(0);
    }

    /**
     * Fixes a label here, merging in the fall-through state and emitting a frame if needed.
     *
     * <p>Three cases. If control can fall into the label, its state joins whatever jumped here. If
     * nothing reaches the label at all, binding it is a no-op and the code stays unreachable. If
     * only jumps reach it, their merged state becomes the current state and generation resumes.
     */
    private void bind(Label label) {
        int position = code.length();
        if (reachable) {
            label.arriveWith(snapshot());
        }
        label.bindAt(position, code);

        StackFrame frame = label.expectedFrame();
        if (frame == null) {
            return;         // dead label: nothing jumps here and nothing falls through
        }
        restore(frame);
        reachable = true;
        if (label.needsFrame()) {
            recordFrame(position, frame);
        }
    }

    /**
     * Records a stack map entry, merging with any entry already at this offset.
     *
     * <p>Two labels can legitimately be bound at the same position &mdash; the end of a switch case
     * that is also the end of the switch, for instance. The format allows one frame per offset, so
     * they combine.
     */
    private void recordFrame(int offset, StackFrame frame) {
        if (!frames.isEmpty()) {
            FrameEntry last = frames.get(frames.size() - 1);
            if (last.offset() == offset) {
                frames.set(frames.size() - 1, new FrameEntry(offset, last.frame().merge(frame)));
                return;
            }
        }
        frames.add(new FrameEntry(offset, frame));
    }

    // ==========================================================================================
    // Verifier state bookkeeping
    // ==========================================================================================

    private void push(VerificationType type) {
        operandStack.add(type);
        currentStackSlots += type.slots();
        maxStackSlots = Math.max(maxStackSlots, currentStackSlots);
    }

    /** Removes and returns the top of the abstract stack. Named to avoid clashing with {@link #pop()}. */
    private VerificationType popValue() {
        if (operandStack.isEmpty()) {
            throw new CodeGenerationException(
                    "the operand stack is empty, but a value was required here");
        }
        VerificationType top = operandStack.remove(operandStack.size() - 1);
        currentStackSlots -= top.slots();
        return top;
    }

    private VerificationType peek() {
        if (operandStack.isEmpty()) {
            throw new CodeGenerationException(
                    "the operand stack is empty, but a value was required here");
        }
        return operandStack.get(operandStack.size() - 1);
    }

    /**
     * Pops a value, checking it is what the instruction expects.
     *
     * <p>Reference types are checked only for being references. Verifying assignability properly
     * would mean loading both classes and walking their hierarchies, which is expensive and, for a
     * class currently being generated, impossible. The JVM's verifier does that check at load time
     * anyway; the value of this one is catching the confusions that are common and cheap to
     * detect &mdash; an {@code int} where a reference belongs, a {@code long} where an {@code int}
     * does.
     */
    private void popExpecting(CwType expected, String what) {
        VerificationType actual = popValue();
        if (!satisfies(actual, expected)) {
            throw new CodeGenerationException(
                    "expected " + expected + " for " + what + " but the stack holds " + actual);
        }
    }

    /**
     * As {@link #popExpecting}, for method arguments.
     *
     * <p>A separate entry point so the diagnostic string is only assembled when the check fails.
     * Every emitted call instruction runs this once per argument, and building "argument 2 of
     * intercept" on each success added measurable garbage to generation for messages that were
     * always thrown away.
     */
    private void popArgument(CwType expected, int index, String methodName) {
        VerificationType actual = popValue();
        if (!satisfies(actual, expected)) {
            throw new CodeGenerationException("expected " + expected + " for argument " + index
                    + " of " + methodName + " but the stack holds " + actual);
        }
    }

    /** As {@link #popReference}, with the message assembled only on failure. */
    private void popReceiver(String methodName) {
        VerificationType actual = popValue();
        if (!actual.isInitialisedReference()) {
            throw new CodeGenerationException("expected an initialised reference for the "
                    + "receiver of " + methodName + " but the stack holds " + actual
                    + (actual.isReference()
                    ? ". An object is unusable until its constructor has been called — in a "
                    + "constructor, chain to super first." : ""));
        }
    }

    private static boolean satisfies(VerificationType actual, CwType expected) {
        VerificationType wanted = VerificationType.of(expected);
        // Initialised references only: an uninitialised reference is a reference too, but the
        // JVM verifier refuses it as an argument, store, or return value, and accepting it here
        // would launder it into a load-time VerifyError. The one instruction allowed to consume
        // one — the constructor call — pops it directly, not through this check.
        return actual.equals(wanted)
                || (wanted.isReference() && actual.isInitialisedReference());
    }

    private void popReference(String what) {
        VerificationType actual = popValue();
        if (!actual.isInitialisedReference()) {
            throw new CodeGenerationException(
                    "expected an initialised reference for " + what + " but the stack holds "
                    + actual + (actual.isReference()
                    ? ". An object is unusable until its constructor has been called — in a "
                    + "constructor, chain to super first." : ""));
        }
    }

    private VerificationType localAt(int slot) {
        if (slot < 0 || slot >= locals.size()) {
            throw new CodeGenerationException("local variable slot " + slot
                    + " has not been written on every path reaching here"
                    + (slot >= maxLocals ? " (and is beyond the method's " + maxLocals + " slots)"
                    : ""));
        }
        return locals.get(slot);
    }

    private void appendLocal(VerificationType type) {
        locals.add(type);
        if (type.isWide()) {
            locals.add(VerificationType.TOP);
        }
    }

    private void setLocal(int slot, VerificationType type) {
        while (locals.size() <= slot) {
            locals.add(VerificationType.TOP);
        }
        // Writing into the second slot of a long or double kills the pair: the JVM verifier
        // treats the remaining first half as unusable, so the simulation must agree or a later
        // load of the half would pass generation and fail verification at class load.
        if (slot > 0 && locals.get(slot - 1).isWide()) {
            locals.set(slot - 1, VerificationType.TOP);
        }
        locals.set(slot, type);
        if (type.isWide()) {
            if (locals.size() <= slot + 1) {
                locals.add(VerificationType.TOP);
            } else {
                locals.set(slot + 1, VerificationType.TOP);
            }
        }
        maxLocals = Math.max(maxLocals, slot + type.slots());

        // Keep every pending handler frame honest; see tryCatch. Slots beyond an entry frame
        // need nothing — a shorter handler frame is implicitly padded with TOP.
        for (List<VerificationType> handlerLocals : activeTryHandlerLocals) {
            if (slot < handlerLocals.size() && !handlerLocals.get(slot).equals(type)) {
                handlerLocals.set(slot, VerificationType.TOP);
            }
            if (slot > 0 && slot - 1 < handlerLocals.size()
                    && handlerLocals.get(slot - 1).isWide()) {
                handlerLocals.set(slot - 1, VerificationType.TOP);
            }
            if (type.isWide() && slot + 1 < handlerLocals.size()) {
                handlerLocals.set(slot + 1, VerificationType.TOP);
            }
        }
    }

    /** Swaps one verification type for another everywhere it appears. See invokeConstructor. */
    private void replaceEverywhere(VerificationType from, VerificationType to) {
        for (int i = 0; i < operandStack.size(); i++) {
            if (operandStack.get(i).equals(from)) {
                operandStack.set(i, to);
            }
        }
        for (int i = 0; i < locals.size(); i++) {
            if (locals.get(i).equals(from)) {
                locals.set(i, to);
            }
        }
    }

    private StackFrame snapshot() {
        return StackFrame.of(locals, operandStack);
    }

    private void restore(StackFrame frame) {
        locals.clear();
        locals.addAll(frame.locals());
        operandStack.clear();
        operandStack.addAll(frame.stack());
        currentStackSlots = 0;
        for (VerificationType entry : operandStack) {
            currentStackSlots += entry.slots();
        }
        maxStackSlots = Math.max(maxStackSlots, currentStackSlots);
        maxLocals = Math.max(maxLocals, locals.size());
    }

    private void requireReachable(String operation) {
        if (!reachable) {
            throw new CodeGenerationException("cannot emit '" + operation
                    + "': the preceding instruction was a return, throw, or unconditional jump, so "
                    + "control cannot reach here. This is almost always a missing branch in the "
                    + "generator.");
        }
    }

    // ==========================================================================================
    // Output
    // ==========================================================================================

    /** Whether control can still fall off the end; used to check a method actually returns. */
    boolean isReachable() {
        return reachable;
    }

    int codeLength() {
        return code.length();
    }

    /**
     * Serialises the {@code Code} attribute, including {@code StackMapTable} when frames exist.
     *
     * @param out             destination
     * @param codeAttributeName pool index of the UTF-8 "Code"
     */
    void writeCodeAttribute(ByteWriter out, int codeAttributeName) {
        if (reachable) {
            throw new CodeGenerationException(
                    "method body can fall off the end without returning; emit a return or a throw");
        }
        if (code.length() == 0) {
            throw new CodeGenerationException("method body is empty");
        }
        // code_length is written as a u4, but JVMS 4.7.3 caps it at 65535 — and the exception
        // table's pcs and the StackMapTable's offsets are u2, so exceeding the cap would truncate
        // them silently. Straight-line code can get here without ever tripping the branch-distance
        // check, so this is the only guard that catches it at generation time.
        if (code.length() > 0xFFFF) {
            throw new CodeGenerationException("method body is " + code.length()
                    + " bytes; the class-file format caps a method's code at 65535 bytes. Split "
                    + "the generated method, or generate less into it.");
        }
        // Both fields below are u2; past 65,535 they would wrap silently into a class that
        // verifies against the wrong sizes.
        if (maxStackSlots > 0xFFFF || maxLocals > 0xFFFF) {
            throw new CodeGenerationException("the method needs " + maxStackSlots
                    + " stack slots and " + maxLocals + " locals; the class-file format caps "
                    + "both at 65535");
        }

        ByteWriter attributes = new ByteWriter(64);
        int attributeCount = 0;
        if (!frames.isEmpty()) {
            writeStackMapTable(attributes);
            attributeCount++;
        }

        out.u2(codeAttributeName);
        out.u4(12 + code.length() + handlers.size() * 8 + attributes.length());
        out.u2(maxStackSlots);
        out.u2(maxLocals);
        out.u4(code.length());
        out.bytes(code);
        out.u2(handlers.size());
        for (ExceptionHandler handler : handlers) {
            out.u2(handler.startPc());
            out.u2(handler.endPc());
            out.u2(handler.handlerPc());
            out.u2(pool.classEntry(handler.catchType()));
        }
        out.u2(attributeCount);
        out.bytes(attributes);
    }

    /**
     * Writes the {@code StackMapTable}.
     *
     * <p>The offset encoding is the format's one genuine subtlety here: the first entry carries the
     * absolute bytecode offset, and every subsequent entry carries the distance from the previous
     * one <em>minus one</em>. The minus one exists so that consecutive frames can be one byte
     * apart with a delta of zero; getting it wrong shifts every frame after the first.
     */
    private void writeStackMapTable(ByteWriter attributes) {
        ByteWriter table = new ByteWriter(64);
        table.u2(frames.size());
        int previous = -1;
        for (FrameEntry entry : frames) {
            int delta = previous < 0 ? entry.offset() : entry.offset() - previous - 1;
            entry.frame().writeTo(table, pool, delta);
            previous = entry.offset();
        }
        attributes.u2(pool.utf8("StackMapTable"));
        attributes.u4(table.length());
        attributes.bytes(table);
    }
}
