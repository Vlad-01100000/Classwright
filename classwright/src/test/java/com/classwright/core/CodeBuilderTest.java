package com.classwright.core;

import com.classwright.testkit.Javap;
import com.classwright.testkit.SignatureMatrix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the emitter by generating real classes, verifying them, and running them.
 *
 * <p>Two kinds of test here, and both are needed. The matrix tests walk every type in the signature
 * matrix through the same operation, which is what catches the type-specific opcode and slot bugs
 * that hand-picked examples miss. The targeted tests cover control flow and the error paths, where
 * what matters is the shape of the generated code rather than the types flowing through it.
 */
class CodeBuilderTest {

    static List<Class<?>> valueTypes() {
        return SignatureMatrix.VALUE_TYPES;
    }

    // ==========================================================================================
    // Matrix tests: the same operation over every type
    // ==========================================================================================

    @ParameterizedTest(name = "{0}")
    @MethodSource("valueTypes")
    @DisplayName("loads an argument and returns it, for every type")
    void echoesEveryType(Class<?> type) throws Exception {
        CwType cwType = CwType.of(type);
        CwClassWriter writer = Generated.classWriter("Echo");
        writer.method(AccessFlags.PUBLIC | AccessFlags.STATIC, "echo",
                        CwMethodType.of(cwType, cwType))
                .code()
                .loadArgument(0)
                .returnValue();

        Method echo = Generated.define(writer).getMethod("echo", type);
        Object sample = Samples.of(type);

        assertResultMatches(sample, echo.invoke(null, sample), type);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("valueTypes")
    @DisplayName("pushes the default value, for every type")
    void pushesDefaults(Class<?> type) throws Exception {
        CwType cwType = CwType.of(type);
        CwClassWriter writer = Generated.classWriter("Defaults");
        writer.method(AccessFlags.PUBLIC | AccessFlags.STATIC, "value", CwMethodType.of(cwType))
                .code()
                .pushDefault(cwType)
                .returnValue();

        assertEquals(Samples.defaultOf(type),
                Generated.define(writer).getMethod("value").invoke(null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("valueTypes")
    @DisplayName("boxes into an Object[] and unboxes back out, for every type")
    void roundTripsThroughAnObjectArray(Class<?> type) throws Exception {
        // The single highest-value matrix test in the engine. It exercises boxing, array creation,
        // aastore, a local variable store and load, aaload, checkcast, and unboxing, all against
        // one type -- which together is exactly the argument-marshalling path every reflective
        // callback contract needs.
        CwType cwType = CwType.of(type);
        CwClassWriter writer = Generated.classWriter("RoundTrip");
        CodeBuilder code = writer
                .method(AccessFlags.PUBLIC | AccessFlags.STATIC, "roundTrip",
                        CwMethodType.of(cwType, cwType))
                .code();

        code.packArgumentsIntoArray();
        int arraySlot = code.declareLocal(CwType.OBJECT_ARRAY);
        code.store(arraySlot, CwType.OBJECT_ARRAY);
        code.unpackArrayIntoArguments(arraySlot, List.of(cwType));
        code.returnValue();

        Method roundTrip = Generated.define(writer).getMethod("roundTrip", type);
        Object sample = Samples.of(type);

        assertResultMatches(sample, roundTrip.invoke(null, sample), type);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("valueTypes")
    @DisplayName("packs arguments into an Object[] with the right boxed contents")
    void packsArgumentsIntoArray(Class<?> type) throws Exception {
        CwType cwType = CwType.of(type);
        CwClassWriter writer = Generated.classWriter("Pack");
        writer.method(AccessFlags.PUBLIC | AccessFlags.STATIC, "pack",
                        CwMethodType.of(CwType.OBJECT_ARRAY, cwType, CwType.INT))
                .code()
                .packArgumentsIntoArray()
                .returnValue();

        Object sample = Samples.of(type);
        Object[] packed = (Object[]) Generated.define(writer)
                .getMethod("pack", type, int.class).invoke(null, sample, 99);

        assertEquals(2, packed.length);
        assertResultMatches(sample, packed[0], type);
        assertEquals(99, packed[1]);
    }

    @Test
    @DisplayName("handles a method whose parameters interleave one- and two-slot types")
    void handlesMixedSlotWidths() throws Exception {
        // m(long, int, double, Object) puts its parameters in slots 0, 2, 3, 5. Returning the
        // Object proves the emitter found slot 5 and not slot 3.
        CwClassWriter writer = Generated.classWriter("Slots");
        writer.method(AccessFlags.PUBLIC | AccessFlags.STATIC, "lastArgument",
                        CwMethodType.of(CwType.OBJECT, CwType.LONG, CwType.INT,
                                CwType.DOUBLE, CwType.OBJECT))
                .code()
                .loadArgument(3)
                .returnValue();

        Object marker = new Object();
        Object result = Generated.define(writer)
                .getMethod("lastArgument", long.class, int.class, double.class, Object.class)
                .invoke(null, 1L, 2, 3.0, marker);

        assertSame(marker, result);
    }

    // ==========================================================================================
    // Constants
    // ==========================================================================================

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 5, 6, 127, 128, 32_767, 32_768, Integer.MAX_VALUE,
            Integer.MIN_VALUE})
    @DisplayName("pushes int constants across every encoding boundary")
    void pushesIntConstants(int value) throws Exception {
        // iconst / bipush / sipush / ldc each cover a different range, and the boundaries between
        // them are where an off-by-one silently truncates.
        CwClassWriter writer = Generated.classWriter("IntConstant");
        writer.method(AccessFlags.PUBLIC | AccessFlags.STATIC, "value",
                        CwMethodType.of(CwType.INT))
                .code()
                .pushInt(value)
                .returnValue();

        assertEquals(value, Generated.define(writer).getMethod("value").invoke(null));
    }

    @Test
    @DisplayName("pushes long, float, double and String constants")
    void pushesOtherConstants() throws Exception {
        CwClassWriter writer = Generated.classWriter("Constants");
        writer.method(AccessFlags.PUBLIC | AccessFlags.STATIC, "longValue",
                CwMethodType.of(CwType.LONG)).code().pushLong(1L << 40).returnValue();
        writer.method(AccessFlags.PUBLIC | AccessFlags.STATIC, "floatValue",
                CwMethodType.of(CwType.FLOAT)).code().pushFloat(-0.0f).returnValue();
        writer.method(AccessFlags.PUBLIC | AccessFlags.STATIC, "doubleValue",
                CwMethodType.of(CwType.DOUBLE)).code().pushDouble(Math.PI).returnValue();
        writer.method(AccessFlags.PUBLIC | AccessFlags.STATIC, "text",
                CwMethodType.of(CwType.STRING)).code().pushString("hello").returnValue();

        Class<?> generated = Generated.define(writer);

        assertEquals(1L << 40, generated.getMethod("longValue").invoke(null));
        assertEquals(Float.floatToRawIntBits(-0.0f),
                Float.floatToRawIntBits((Float) generated.getMethod("floatValue").invoke(null)),
                "negative zero must not be folded into the fconst_0 shortcut");
        assertEquals(Math.PI, generated.getMethod("doubleValue").invoke(null));
        assertEquals("hello", generated.getMethod("text").invoke(null));
    }

    @Test
    @DisplayName("pushes class constants, including for primitives")
    void pushesClassConstants() throws Exception {
        // ldc cannot name a primitive, so int.class has to come from Integer.TYPE instead.
        CwClassWriter writer = Generated.classWriter("ClassConstants");
        writer.method(AccessFlags.PUBLIC | AccessFlags.STATIC, "reference",
                CwMethodType.of(CwType.CLASS)).code().pushClassConstant(CwType.STRING).returnValue();
        writer.method(AccessFlags.PUBLIC | AccessFlags.STATIC, "primitive",
                CwMethodType.of(CwType.CLASS)).code().pushClassConstant(CwType.INT).returnValue();
        writer.method(AccessFlags.PUBLIC | AccessFlags.STATIC, "array",
                CwMethodType.of(CwType.CLASS)).code()
                .pushClassConstant(CwType.arrayOf(CwType.LONG)).returnValue();

        Class<?> generated = Generated.define(writer);

        assertEquals(String.class, generated.getMethod("reference").invoke(null));
        assertEquals(int.class, generated.getMethod("primitive").invoke(null));
        assertEquals(long[].class, generated.getMethod("array").invoke(null));
    }

    // ==========================================================================================
    // Control flow
    // ==========================================================================================

    @Nested
    @DisplayName("control flow")
    class ControlFlow {

        @Test
        @DisplayName("branches on null, with both arms returning")
        void branchesOnNull() throws Exception {
            CwClassWriter writer = Generated.classWriter("NullCheck");
            CodeBuilder code = writer
                    .method(AccessFlags.PUBLIC | AccessFlags.STATIC, "describe",
                            CwMethodType.of(CwType.STRING, CwType.OBJECT))
                    .code();
            code.loadArgument(0);
            code.ifNullElse(
                    () -> code.pushString("null").returnValue(),
                    () -> code.pushString("present").returnValue());

            Method describe = Generated.define(writer).getMethod("describe", Object.class);

            assertEquals("null", describe.invoke(null, (Object) null));
            assertEquals("present", describe.invoke(null, "anything"));
        }

        @Test
        @DisplayName("branches on null where both arms fall through to a shared join")
        void branchesAndRejoins() throws Exception {
            // Harder than the returning case: both arms leave a value on the stack and control
            // rejoins, so the merged frame at the join has to be right.
            CwClassWriter writer = Generated.classWriter("NullJoin");
            CodeBuilder code = writer
                    .method(AccessFlags.PUBLIC | AccessFlags.STATIC, "describe",
                            CwMethodType.of(CwType.STRING, CwType.OBJECT))
                    .code();
            code.loadArgument(0);
            code.ifNullElse(
                    () -> code.pushString("null"),
                    () -> code.pushString("present"));
            code.returnValue();

            Method describe = Generated.define(writer).getMethod("describe", Object.class);

            assertEquals("null", describe.invoke(null, (Object) null));
            assertEquals("present", describe.invoke(null, "anything"));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(CodeBuilder.IntTest.class)
        @DisplayName("branches correctly on all six int comparisons")
        void branchesOnEveryComparison(CodeBuilder.IntTest comparison) throws Exception {
            // Emitting a conditional means jumping on the *inverse* condition, and the six opcodes
            // pair up in a way that makes the obvious bit trick wrong. Testing one comparison
            // would have missed it, so all six are exercised against three sign cases each.
            CwClassWriter writer = Generated.classWriter("Comparison");
            CodeBuilder code = writer
                    .method(AccessFlags.PUBLIC | AccessFlags.STATIC, "test",
                            CwMethodType.of(CwType.INT, CwType.INT))
                    .code();
            code.loadArgument(0);
            code.ifIntComparison(comparison, () -> code.pushInt(1), () -> code.pushInt(0));
            code.returnValue();

            Method test = Generated.define(writer).getMethod("test", int.class);

            for (int value : new int[]{-3, 0, 3}) {
                boolean expected = switch (comparison) {
                    case EQUAL_TO_ZERO -> value == 0;
                    case NOT_ZERO -> value != 0;
                    case LESS_THAN_ZERO -> value < 0;
                    case AT_LEAST_ZERO -> value >= 0;
                    case GREATER_THAN_ZERO -> value > 0;
                    case AT_MOST_ZERO -> value <= 0;
                };
                assertEquals(expected ? 1 : 0, test.invoke(null, value),
                        comparison + " with input " + value);
            }
        }

        @Test
        @DisplayName("dispatches through a table switch")
        void dispatchesThroughTableSwitch() throws Exception {
            // The shape a proxy uses to reach one of many super methods by index.
            CwClassWriter writer = Generated.classWriter("Switch");
            CodeBuilder code = writer
                    .method(AccessFlags.PUBLIC | AccessFlags.STATIC, "pick",
                            CwMethodType.of(CwType.STRING, CwType.INT))
                    .code();
            code.loadArgument(0);
            code.tableSwitch(0, 3,
                    key -> code.pushString("case" + key).returnValue(),
                    () -> code.pushString("default").returnValue());

            Method pick = Generated.define(writer).getMethod("pick", int.class);

            assertEquals("case0", pick.invoke(null, 0));
            assertEquals("case2", pick.invoke(null, 2));
            assertEquals("case3", pick.invoke(null, 3));
            assertEquals("default", pick.invoke(null, 4));
            assertEquals("default", pick.invoke(null, -1));
        }

        @Test
        @DisplayName("a sparse switch dispatches on exact keys, everything else to default")
        void sparseSwitchDispatches() throws Exception {
            // Sparse and signed on purpose: the keys straddle zero and sit far apart, which a
            // tableswitch could only cover with tens of thousands of holes. String hashes — the
            // reason this primitive exists — look exactly like this.
            CwClassWriter writer = Generated.classWriter("Sparse");
            CodeBuilder code = writer
                    .method(AccessFlags.PUBLIC | AccessFlags.STATIC, "pick",
                            CwMethodType.of(CwType.STRING, CwType.INT))
                    .code();
            code.loadArgument(0);
            code.lookupSwitch(new int[]{-7_000_000, 0, 3104, 2_000_000_000},
                    key -> code.pushString("key" + key).returnValue(),
                    () -> code.pushString("default").returnValue());

            Method pick = Generated.define(writer).getMethod("pick", int.class);

            assertEquals("key-7000000", pick.invoke(null, -7_000_000));
            assertEquals("key0", pick.invoke(null, 0));
            assertEquals("key3104", pick.invoke(null, 3104));
            assertEquals("key2000000000", pick.invoke(null, 2_000_000_000));
            assertEquals("default", pick.invoke(null, 1));
            assertEquals("default", pick.invoke(null, Integer.MIN_VALUE));
        }

        @Test
        @DisplayName("a sparse switch refuses keys that are not strictly ascending")
        void sparseSwitchRequiresOrder() {
            CwClassWriter writer = Generated.classWriter("SparseOrder");
            CodeBuilder code = writer
                    .method(AccessFlags.PUBLIC | AccessFlags.STATIC, "pick",
                            CwMethodType.of(CwType.STRING, CwType.INT))
                    .code();
            code.loadArgument(0);

            assertThrows(CodeGenerationException.class,
                    () -> code.lookupSwitch(new int[]{5, 5},
                            key -> code.pushString("x").returnValue(),
                            () -> code.pushString("default").returnValue()),
                    "duplicate keys would emit bytecode the JVM rejects at class load");
        }

        @Test
        @DisplayName("a switch whose cases rejoin instead of returning")
        void switchCasesRejoin() throws Exception {
            CwClassWriter writer = Generated.classWriter("SwitchJoin");
            CodeBuilder code = writer
                    .method(AccessFlags.PUBLIC | AccessFlags.STATIC, "pick",
                            CwMethodType.of(CwType.INT, CwType.INT))
                    .code();
            code.loadArgument(0);
            code.tableSwitch(1, 3, key -> code.pushInt(key * 10), () -> code.pushInt(-1));
            code.returnValue();

            Method pick = Generated.define(writer).getMethod("pick", int.class);

            assertEquals(10, pick.invoke(null, 1));
            assertEquals(30, pick.invoke(null, 3));
            assertEquals(-1, pick.invoke(null, 7));
        }

        @Test
        @DisplayName("catches an exception thrown inside a try block")
        void catchesExceptions() throws Exception {
            CwClassWriter writer = Generated.classWriter("Guarded");
            CodeBuilder code = writer
                    .method(AccessFlags.PUBLIC | AccessFlags.STATIC, "guarded",
                            CwMethodType.of(CwType.STRING, CwType.OBJECT))
                    .code();
            code.tryCatch(
                    () -> code.loadArgument(0)
                            .invokeVirtual("java/lang/Object", "toString",
                                    CwMethodType.of(CwType.STRING))
                            .returnValue(),
                    CwType.objectType("java/lang/RuntimeException"),
                    () -> code.pop().pushString("caught").returnValue());

            Method guarded = Generated.define(writer).getMethod("guarded", Object.class);

            assertEquals("text", guarded.invoke(null, "text"));
            assertEquals("caught", guarded.invoke(null, (Object) null),
                    "toString() on null throws NPE, which the handler should catch");
        }

        @Test
        @DisplayName("runs a while loop with a back edge")
        void runsWhileLoop() throws Exception {
            // int sumTo(int n) { int i = n, total = 0; while (i != 0) { total += i; i += -1; }
            //                    return total; }
            //
            // The back edge jumps to a label that was bound before the jump existed, so the loop
            // head has to be marked as a branch target ahead of binding or its frame is omitted
            // and the whole method fails verification. Summing rather than merely counting proves
            // the body ran the right number of times, not just that the loop terminated.
            CwClassWriter writer = Generated.classWriter("Loop");
            CodeBuilder code = writer
                    .method(AccessFlags.PUBLIC | AccessFlags.STATIC, "sumTo",
                            CwMethodType.of(CwType.INT, CwType.INT))
                    .code();

            int counter = code.declareLocal(CwType.INT);
            int total = code.declareLocal(CwType.INT);
            code.loadArgument(0).store(counter, CwType.INT);
            code.pushInt(0).store(total, CwType.INT);

            code.whileLoop(
                    // whileLoop continues while the condition is non-zero, so loading the counter
                    // directly expresses "while (i != 0)".
                    () -> code.load(counter, CwType.INT),
                    () -> {
                        code.load(total, CwType.INT).load(counter, CwType.INT)
                                .add(CwType.INT).store(total, CwType.INT);
                        code.load(counter, CwType.INT).pushInt(-1)
                                .add(CwType.INT).store(counter, CwType.INT);
                    });

            code.load(total, CwType.INT).returnValue();

            Method sumTo = Generated.define(writer).getMethod("sumTo", int.class);

            assertEquals(0, sumTo.invoke(null, 0), "loop body must not run at all");
            assertEquals(1, sumTo.invoke(null, 1));
            assertEquals(55, sumTo.invoke(null, 10), "10+9+...+1");
        }
    }

    // ==========================================================================================
    // Stack map frames
    // ==========================================================================================

    @Test
    @DisplayName("a branchless method carries no StackMapTable at all")
    void branchlessMethodsHaveNoFrames() {
        // Confirms the finding in docs/RESEARCH.md section 4: frames are only needed at branch
        // targets, so straight-line generated code costs nothing.
        CwClassWriter writer = Generated.classWriter("NoFrames");
        writer.method(AccessFlags.PUBLIC | AccessFlags.STATIC, "identity",
                        CwMethodType.of(CwType.INT, CwType.INT))
                .code().loadArgument(0).returnValue();

        String listing = Javap.disassembleQuietly(writer.toByteArray());

        assertFalse(listing.contains("StackMapTable"),
                () -> "expected no stack map attribute:\n" + listing);
    }

    @Test
    @DisplayName("a branching method carries exactly the frames it needs")
    void branchingMethodsCarryFrames() {
        CwClassWriter writer = Generated.classWriter("WithFrames");
        CodeBuilder code = writer
                .method(AccessFlags.PUBLIC | AccessFlags.STATIC, "describe",
                        CwMethodType.of(CwType.STRING, CwType.OBJECT))
                .code();
        code.loadArgument(0);
        code.ifNullElse(() -> code.pushString("null"), () -> code.pushString("present"));
        code.returnValue();

        String listing = Javap.disassembleQuietly(writer.toByteArray());

        assertTrue(listing.contains("StackMapTable"),
                () -> "expected a stack map attribute:\n" + listing);
        assertTrue(listing.contains("full_frame"),
                () -> "frames are emitted in full form:\n" + listing);
    }

    // ==========================================================================================
    // Error reporting
    // ==========================================================================================

    @Nested
    @DisplayName("rejects generator mistakes at generation time")
    class Diagnostics {

        private CodeBuilder body(CwMethodType type) {
            return Generated.classWriter("Bad")
                    .method(AccessFlags.PUBLIC | AccessFlags.STATIC, "m", type)
                    .code();
        }

        @Test
        @DisplayName("a type mismatch on the operand stack")
        void rejectsTypeMismatch() {
            CodeBuilder code = body(CwMethodType.of(CwType.OBJECT, CwType.INT));
            code.loadArgument(0);

            CodeGenerationException failure =
                    assertThrows(CodeGenerationException.class, code::returnValue);

            assertTrue(failure.getMessage().contains("int"), failure.getMessage());
        }

        @Test
        @DisplayName("emitting after a return")
        void rejectsUnreachableCode() {
            CodeBuilder code = body(CwMethodType.of(CwType.VOID));
            code.returnValue();

            CodeGenerationException failure =
                    assertThrows(CodeGenerationException.class, () -> code.pushInt(1));

            assertTrue(failure.getMessage().contains("cannot reach"), failure.getMessage());
        }

        @Test
        @DisplayName("a body that can fall off the end")
        void rejectsMissingReturn() {
            CwClassWriter writer = Generated.classWriter("NoReturn");
            writer.method(AccessFlags.PUBLIC | AccessFlags.STATIC, "m",
                    CwMethodType.of(CwType.VOID)).code().pushInt(1).pop();

            CodeGenerationException failure =
                    assertThrows(CodeGenerationException.class, writer::toByteArray);

            assertTrue(failure.getMessage().contains("fall off the end"), failure.getMessage());
        }

        @Test
        @DisplayName("returning with values still on the stack")
        void rejectsDirtyStackAtReturn() {
            CodeBuilder code = body(CwMethodType.of(CwType.VOID));
            code.pushInt(1);

            CodeGenerationException failure =
                    assertThrows(CodeGenerationException.class, code::returnValue);

            assertTrue(failure.getMessage().contains("operand stack"), failure.getMessage());
        }

        @Test
        @DisplayName("popping from an empty stack")
        void rejectsStackUnderflow() {
            CodeBuilder code = body(CwMethodType.of(CwType.VOID));

            assertThrows(CodeGenerationException.class, code::pop);
        }

        @Test
        @DisplayName("dup on a two-slot value, which would corrupt the stack")
        void rejectsDupOnWideValue() {
            CodeBuilder code = body(CwMethodType.of(CwType.VOID));
            code.pushLong(1L);

            CodeGenerationException failure =
                    assertThrows(CodeGenerationException.class, code::dup);

            assertTrue(failure.getMessage().contains("dup2"),
                    "the message should name the right instruction: " + failure.getMessage());
        }

        @Test
        @DisplayName("branches whose arms leave different stack depths")
        void rejectsUnbalancedBranches() {
            CodeBuilder code = body(CwMethodType.of(CwType.VOID, CwType.OBJECT));
            code.loadArgument(0);

            CodeGenerationException failure = assertThrows(CodeGenerationException.class,
                    () -> code.ifNullElse(() -> code.pushInt(1), () -> { }));

            assertTrue(failure.getMessage().contains("stack depth"), failure.getMessage());
        }

        @Test
        @DisplayName("calling a constructor on something already initialised")
        void rejectsConstructorOnInitialisedReference() {
            CodeBuilder code = body(CwMethodType.of(CwType.VOID, CwType.OBJECT));
            code.loadArgument(0);

            CodeGenerationException failure = assertThrows(CodeGenerationException.class,
                    () -> code.invokeConstructor("java/lang/Object", CwMethodType.of(CwType.VOID)));

            assertTrue(failure.getMessage().contains("uninitialised"), failure.getMessage());
        }

        @Test
        @DisplayName("routing <init> through invokeSpecial instead of invokeConstructor")
        void rejectsInitViaInvokeSpecial() {
            CodeBuilder code = body(CwMethodType.of(CwType.VOID, CwType.OBJECT));

            CodeGenerationException failure = assertThrows(CodeGenerationException.class,
                    () -> code.invokeSpecial("java/lang/Object", "<init>",
                            CwMethodType.of(CwType.VOID)));

            assertTrue(failure.getMessage().contains("invokeConstructor"), failure.getMessage());
        }

        @Test
        @DisplayName("loading 'this' in a static method")
        void rejectsThisInStaticMethod() {
            CodeBuilder code = body(CwMethodType.of(CwType.VOID));

            assertThrows(CodeGenerationException.class, code::loadThis);
        }

        @Test
        @DisplayName("declaring two members with the same signature")
        void rejectsDuplicateMembers() {
            CwClassWriter writer = Generated.classWriter("Duplicate");
            writer.field(AccessFlags.PRIVATE, "value", CwType.INT);

            assertThrows(CodeGenerationException.class,
                    () -> writer.field(AccessFlags.PRIVATE, "value", CwType.INT));
        }
    }

    // ==========================================================================================
    // Helpers
    // ==========================================================================================

    /** References must come back identical; primitives are compared by value once autoboxed. */
    private static void assertResultMatches(Object expected, Object actual, Class<?> type) {
        if (type.isPrimitive()) {
            assertEquals(expected, actual);
        } else if (expected == null) {
            assertNull(actual);
        } else {
            assertSame(expected, actual, "a reference should survive the round trip unchanged");
        }
    }
}
