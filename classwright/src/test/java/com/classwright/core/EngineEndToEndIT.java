package com.classwright.core;

import com.classwright.core.fixtures.EchoTarget;
import com.classwright.core.fixtures.Recorder;
import com.classwright.testkit.ClassVerifier;
import com.classwright.testkit.SignatureMatrix;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Phase 1 milestone: generate a working subclass of an arbitrary class, from reflection alone.
 *
 * <p>This is the shape everything in Phase 3 is built on. If the engine can subclass a class,
 * override its methods, call {@code super} correctly, and marshal arguments through an
 * {@code Object[]}, then a proxy is a matter of deciding what to put between the two.
 *
 * <p>Note what is <em>not</em> here: no class file is read at any point. Every fact used to generate
 * these subclasses comes from {@link Class} and {@link Method}.
 */
class EngineEndToEndIT {

    private static final String TARGET = "com/classwright/core/fixtures/EchoTarget";
    private static final String RECORDER = "com/classwright/core/fixtures/Recorder";

    /**
     * Contains {@code $$}, deliberately.
     *
     * <p>Frameworks identify generated classes by looking for that marker and then walking to the
     * superclass &mdash; Spring's {@code ClassUtils.getUserClass} is the well-known example. Keeping
     * the convention means existing tooling recognises Classwright's output without changes.
     */
    private static final String GENERATED = TARGET + "$$CW";

    static List<Class<?>> valueTypes() {
        return SignatureMatrix.VALUE_TYPES;
    }

    @BeforeEach
    void resetRecorder() {
        Recorder.reset();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("valueTypes")
    @DisplayName("overrides a method and calls super, for every type")
    void overridesAndCallsSuper(Class<?> type) throws Exception {
        CwType cwType = CwType.of(type);
        CwMethodType signature = CwMethodType.of(cwType, cwType);

        CwClassWriter writer = subclassOfTarget();
        writer.method(AccessFlags.PUBLIC, "echo", signature)
                .code()
                .loadThis()
                .loadArgument(0)
                // invokespecial, not invokevirtual: the latter would dispatch back to this very
                // override and recurse until the stack ran out.
                .invokeSpecial(TARGET, "echo", signature)
                .returnValue();

        Class<?> generated = ClassVerifier.assertVerifies(writer.toByteArray());
        EchoTarget instance = (EchoTarget) generated.getDeclaredConstructor().newInstance();

        Object sample = Samples.of(type);
        Object result = generated.getMethod("echo", type).invoke(instance, sample);

        if (type.isPrimitive()) {
            assertEquals(sample, result);
        } else {
            assertSame(sample, result);
        }
        assertEquals(1, instance.superCalls, "the original method must actually have run");
    }

    @Test
    @DisplayName("the generated subclass really is a subclass, and is named recognisably")
    void producesARecognisableSubclass() throws Exception {
        Class<?> generated = ClassVerifier.assertVerifies(subclassOfTarget().toByteArray());

        assertEquals(EchoTarget.class, generated.getSuperclass());
        assertInstanceOf(EchoTarget.class, generated.getDeclaredConstructor().newInstance());
        assertTrue(generated.getName().contains("$$"),
                "framework heuristics look for $$ to spot a generated class");
        assertTrue(generated.getName().startsWith(EchoTarget.class.getName()),
                "the part before $$ should be the target's own name");
    }

    @Test
    @DisplayName("overrides a method with mixed one- and two-slot parameters")
    void overridesMixedWidthSignature() throws Exception {
        CwMethodType signature = CwMethodType.of(CwType.STRING,
                CwType.INT, CwType.LONG, CwType.DOUBLE, CwType.STRING);

        CwClassWriter writer = subclassOfTarget();
        writer.method(AccessFlags.PUBLIC, "mix", signature)
                .code()
                .loadThis()
                .loadAllArguments()
                .invokeSpecial(TARGET, "mix", signature)
                .returnValue();

        Class<?> generated = ClassVerifier.assertVerifies(writer.toByteArray());
        EchoTarget instance = (EchoTarget) generated.getDeclaredConstructor().newInstance();

        Object result = generated
                .getMethod("mix", int.class, long.class, double.class, String.class)
                .invoke(instance, 7, 8L, 9.5, "ten");

        assertEquals("7|8|9.5|ten", result);
        assertEquals(1, instance.superCalls);
    }

    @Test
    @DisplayName("overrides a void method")
    void overridesVoidMethod() throws Exception {
        CwMethodType signature = CwMethodType.of(CwType.VOID);

        CwClassWriter writer = subclassOfTarget();
        writer.method(AccessFlags.PUBLIC, "touch", signature)
                .code()
                .loadThis()
                .invokeSpecial(TARGET, "touch", signature)
                .returnValue();

        Class<?> generated = ClassVerifier.assertVerifies(writer.toByteArray());
        EchoTarget instance = (EchoTarget) generated.getDeclaredConstructor().newInstance();

        generated.getMethod("touch").invoke(instance);

        assertEquals(1, instance.superCalls);
    }

    @Test
    @DisplayName("forwards constructor arguments to the superclass")
    void forwardsConstructorArguments() throws Exception {
        CwMethodType signature = CwMethodType.of(CwType.VOID, CwType.INT);

        CwClassWriter writer = CwClassWriter
                .of(AccessFlags.PUBLIC | AccessFlags.SUPER, GENERATED, TARGET)
                .sourceFile("EchoTarget$$CW.java");
        writer.constructor(AccessFlags.PUBLIC, signature)
                .code()
                .loadThis()
                .loadArgument(0)
                .invokeConstructor(TARGET, signature)
                .returnValue();

        Class<?> generated = ClassVerifier.assertVerifies(writer.toByteArray());
        EchoTarget instance =
                (EchoTarget) generated.getDeclaredConstructor(int.class).newInstance(42);

        assertEquals(42, instance.superCalls, "the argument must reach the super constructor");
    }

    @Test
    @DisplayName("marshals arguments to a callback and then calls super: the proxy shape")
    void generatesTheProxyShape() throws Exception {
        // A preview of Phase 3 in miniature. The override packs its arguments into an Object[],
        // hands them to an external callback, then invokes the original through invokespecial --
        // which is precisely what an interceptor-based proxy has to do.
        CwMethodType signature = CwMethodType.of(CwType.STRING,
                CwType.INT, CwType.LONG, CwType.DOUBLE, CwType.STRING);

        CwClassWriter writer = subclassOfTarget();
        CodeBuilder code = writer.method(AccessFlags.PUBLIC, "mix", signature).code();
        code.packArgumentsIntoArray();
        code.invokeStatic(RECORDER, "record", CwMethodType.of(CwType.VOID, CwType.OBJECT_ARRAY));
        code.loadThis();
        code.loadAllArguments();
        code.invokeSpecial(TARGET, "mix", signature);
        code.returnValue();

        Class<?> generated = ClassVerifier.assertVerifies(writer.toByteArray());
        EchoTarget instance = (EchoTarget) generated.getDeclaredConstructor().newInstance();

        Object result = generated
                .getMethod("mix", int.class, long.class, double.class, String.class)
                .invoke(instance, 7, 8L, 9.5, "ten");

        assertEquals("7|8|9.5|ten", result, "the original result must still be returned");
        assertEquals(1, instance.superCalls);
        assertEquals(1, Recorder.calls().size(), "the callback must have been invoked once");
        // Boxing must preserve every value exactly, including the two-slot ones.
        assertArrayContents(Recorder.calls().get(0), 7, 8L, 9.5, "ten");
    }

    @Test
    @DisplayName("an intercepting override can replace the result entirely")
    void generatedOverrideCanShortCircuit() throws Exception {
        // The FixedValue callback shape: never touch the original at all.
        CwMethodType signature = CwMethodType.of(CwType.INT, CwType.INT);

        CwClassWriter writer = subclassOfTarget();
        writer.method(AccessFlags.PUBLIC, "echo", signature)
                .code()
                .pushInt(-1)
                .returnValue();

        Class<?> generated = ClassVerifier.assertVerifies(writer.toByteArray());
        EchoTarget instance = (EchoTarget) generated.getDeclaredConstructor().newInstance();

        assertEquals(-1, generated.getMethod("echo", int.class).invoke(instance, 5));
        assertEquals(0, instance.superCalls, "the original must not have run");
    }

    @Test
    @DisplayName("a generated class can carry state and reach it from an override")
    void generatedClassCanCarryState() throws Exception {
        CwType counterType = CwType.INT;
        CwClassWriter writer = subclassOfTarget();
        writer.field(AccessFlags.PUBLIC, "invocations", counterType);

        writer.method(AccessFlags.PUBLIC, "echo", CwMethodType.of(counterType, counterType))
                .code()
                .loadThis()
                .loadThis()
                .getField(GENERATED, "invocations", counterType)
                .pushInt(1)
                .add(counterType)
                .putField(GENERATED, "invocations", counterType)
                .loadThis()
                .loadArgument(0)
                .invokeSpecial(TARGET, "echo", CwMethodType.of(counterType, counterType))
                .returnValue();

        Class<?> generated = ClassVerifier.assertVerifies(writer.toByteArray());
        EchoTarget instance = (EchoTarget) generated.getDeclaredConstructor().newInstance();
        Method echo = generated.getMethod("echo", int.class);

        echo.invoke(instance, 1);
        echo.invoke(instance, 2);

        assertEquals(2, generated.getField("invocations").getInt(instance));
        assertEquals(2, instance.superCalls);
    }

    /** A subclass of the fixture with a no-argument constructor chaining to super. */
    private static CwClassWriter subclassOfTarget() {
        CwClassWriter writer = CwClassWriter
                .of(AccessFlags.PUBLIC | AccessFlags.SUPER, GENERATED, TARGET)
                .sourceFile("EchoTarget$$CW.java");
        writer.constructor(AccessFlags.PUBLIC, CwMethodType.of(CwType.VOID))
                .code()
                .loadThis()
                .invokeConstructor(TARGET, CwMethodType.of(CwType.VOID))
                .returnValue();
        return writer;
    }

    private static void assertArrayContents(Object[] actual, Object... expected) {
        assertEquals(expected.length, actual.length, "argument count");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "argument " + i);
        }
    }
}
