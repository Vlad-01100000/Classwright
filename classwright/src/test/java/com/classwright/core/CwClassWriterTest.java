package com.classwright.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests class-level structure: versions, interfaces, static state, and attributes. */
class CwClassWriterTest {

    /** Reads the class-file major version straight out of the header (bytes 6 and 7). */
    private static int majorVersionOf(byte[] classFile) {
        return ((classFile[6] & 0xFF) << 8) | (classFile[7] & 0xFF);
    }

    @Test
    @DisplayName("emits Java 8 bytecode by default, well below the Java 17 baseline")
    void defaultsToJava8Bytecode() {
        // Deliberate: JVMs accept old class files essentially forever, so emitting low maximises
        // the range of runtimes that will load the output, including ones that do not exist yet.
        // See ClassFileVersion and docs/RESEARCH.md section 5.
        byte[] classFile = Generated.withDefaultConstructor(
                Generated.classWriter("Versioned"), "java/lang/Object").toByteArray();

        assertEquals(ClassFileVersion.JAVA_8, majorVersionOf(classFile));
        assertEquals(52, majorVersionOf(classFile));
    }

    @ParameterizedTest
    @ValueSource(ints = {ClassFileVersion.JAVA_6, ClassFileVersion.JAVA_7,
            ClassFileVersion.JAVA_8, ClassFileVersion.JAVA_11, ClassFileVersion.JAVA_17})
    @DisplayName("classes verify at every supported class-file version")
    void verifiesAtEveryVersion(int major) {
        CwClassWriter writer = Generated.classWriter("Versioned");
        Generated.withDefaultConstructor(writer, "java/lang/Object").version(major);
        // A branch, so the StackMapTable path is exercised at each version. Below Java 7 frames
        // are optional but still legal; from Java 7 they are mandatory.
        CodeBuilder code = writer
                .method(AccessFlags.PUBLIC | AccessFlags.STATIC, "describe",
                        CwMethodType.of(CwType.STRING, CwType.OBJECT))
                .code();
        code.loadArgument(0);
        code.ifNullElse(() -> code.pushString("null"), () -> code.pushString("present"));
        code.returnValue();

        byte[] classFile = writer.toByteArray();

        assertEquals(major, majorVersionOf(classFile));
        Generated.define(writer);   // asserts it verifies
    }

    @Test
    @DisplayName("rejects an unreasonable class-file version")
    void rejectsUnknownVersion() {
        assertThrows(CodeGenerationException.class, () -> Generated.classWriter("X").version(3));
    }

    @Test
    @DisplayName("implements an interface, and the JVM agrees")
    void implementsInterfaces() throws Exception {
        CwClassWriter writer = CwClassWriter.of(
                AccessFlags.PUBLIC | AccessFlags.SUPER,
                Generated.PACKAGE + "/Runner", "java/lang/Object", "java/lang/Runnable");
        Generated.withDefaultConstructor(writer, "java/lang/Object");
        writer.method(AccessFlags.PUBLIC, "run", CwMethodType.of(CwType.VOID))
                .code().returnValue();

        Class<?> generated = Generated.define(writer);

        assertTrue(Runnable.class.isAssignableFrom(generated));
        ((Runnable) generated.getDeclaredConstructor().newInstance()).run();
    }

    @Test
    @DisplayName("generates an interface")
    void generatesInterfaces() {
        CwClassWriter writer = CwClassWriter.ofInterface(
                AccessFlags.PUBLIC, Generated.PACKAGE + "/Contract");
        writer.method(AccessFlags.PUBLIC | AccessFlags.ABSTRACT, "compute",
                CwMethodType.of(CwType.INT, CwType.INT));

        Class<?> generated = Generated.define(writer);

        assertTrue(generated.isInterface());
        assertEquals(1, generated.getDeclaredMethods().length);
    }

    @Test
    @DisplayName("an abstract method has no body to write to")
    void abstractMethodsHaveNoBody() {
        CwClassWriter writer = CwClassWriter.ofInterface(
                AccessFlags.PUBLIC, Generated.PACKAGE + "/Contract");
        MethodBuilder method = writer.method(AccessFlags.PUBLIC | AccessFlags.ABSTRACT, "compute",
                CwMethodType.of(CwType.INT));

        assertThrows(CodeGenerationException.class, method::code);
    }

    @Test
    @DisplayName("runs a static initialiser to set up static state")
    void runsStaticInitializer() throws Exception {
        // How a generated proxy will cache its Method objects and dispatch tables.
        CwClassWriter writer = Generated.classWriter("WithStatics");
        writer.field(AccessFlags.PUBLIC | AccessFlags.STATIC, "greeting", CwType.STRING);
        writer.staticInitializer()
                .code()
                .pushString("initialised")
                .putStatic(Generated.PACKAGE + "/WithStatics", "greeting", CwType.STRING)
                .returnValue();

        Class<?> generated = Generated.define(writer);

        assertEquals("initialised", generated.getField("greeting").get(null));
    }

    @Test
    @DisplayName("declares checked exceptions so reflection reports them")
    void declaresCheckedExceptions() throws Exception {
        CwClassWriter writer = Generated.classWriter("Thrower");
        writer.method(AccessFlags.PUBLIC | AccessFlags.STATIC, "risky",
                        CwMethodType.of(CwType.VOID))
                .throwsException("java/io/IOException")
                .code().returnValue();

        Class<?> generated = Generated.define(writer);

        assertEquals(java.util.List.of(java.io.IOException.class),
                Arrays.asList(generated.getMethod("risky").getExceptionTypes()));
    }

    @Test
    @DisplayName("the SourceFile attribute reaches stack traces")
    void sourceFileAppearsInStackTraces() throws Exception {
        // Worth setting: without it a frame reads "Unknown Source", which tells a reader nothing.
        // With it, the frame announces that they are looking at generated code.
        CwClassWriter writer = Generated.classWriter("Failing").sourceFile("Failing$$CW.java");
        Generated.withDefaultConstructor(writer, "java/lang/Object");
        writer.method(AccessFlags.PUBLIC | AccessFlags.STATIC, "boom", CwMethodType.of(CwType.VOID))
                .code()
                .newInstance(CwType.objectType("java/lang/IllegalStateException"))
                .dup()
                .pushString("deliberate")
                .invokeConstructor("java/lang/IllegalStateException",
                        CwMethodType.of(CwType.VOID, CwType.STRING))
                .throwException();

        Class<?> generated = Generated.define(writer);

        Throwable thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> generated.getMethod("boom").invoke(null)).getCause();

        assertEquals("deliberate", thrown.getMessage());
        assertEquals("Failing$$CW.java", thrown.getStackTrace()[0].getFileName());
    }

    @Test
    @DisplayName("constructs an object with new, dup and a constructor call")
    void constructsObjects() throws Exception {
        // The new/dup/<init> dance: `new` leaves an uninitialised reference, `dup` keeps a copy
        // because the constructor consumes one, and the tracker has to mark *both* initialised
        // when <init> runs.
        CwClassWriter writer = Generated.classWriter("Factory");
        writer.method(AccessFlags.PUBLIC | AccessFlags.STATIC, "create",
                        CwMethodType.of(CwType.STRING, CwType.STRING))
                .code()
                .newInstance(CwType.STRING)
                .dup()
                .loadArgument(0)
                .invokeConstructor("java/lang/String", CwMethodType.of(CwType.VOID, CwType.STRING))
                .returnValue();

        Class<?> generated = Generated.define(writer);

        assertEquals("copied", generated.getMethod("create", String.class).invoke(null, "copied"));
    }

    @Test
    @DisplayName("access flags reach reflection intact")
    void accessFlagsSurvive() throws Exception {
        CwClassWriter writer = Generated.classWriter("Flags");
        Generated.withDefaultConstructor(writer, "java/lang/Object");
        writer.field(AccessFlags.PRIVATE | AccessFlags.FINAL | AccessFlags.SYNTHETIC,
                "hidden", CwType.INT);
        writer.method(AccessFlags.PUBLIC | AccessFlags.STATIC | AccessFlags.FINAL, "utility",
                CwMethodType.of(CwType.VOID)).code().returnValue();

        Class<?> generated = Generated.define(writer);

        int fieldModifiers = generated.getDeclaredField("hidden").getModifiers();
        assertTrue(Modifier.isPrivate(fieldModifiers));
        assertTrue(Modifier.isFinal(fieldModifiers));
        assertTrue(generated.getDeclaredField("hidden").isSynthetic());

        int methodModifiers = generated.getMethod("utility").getModifiers();
        assertTrue(Modifier.isPublic(methodModifiers));
        assertTrue(Modifier.isStatic(methodModifiers));
    }

    @Test
    @DisplayName("insists on ACC_SUPER, which super calls depend on")
    void requiresAccSuper() {
        CodeGenerationException failure = assertThrows(CodeGenerationException.class,
                () -> CwClassWriter.of(AccessFlags.PUBLIC, "a/B", "java/lang/Object"));

        assertTrue(failure.getMessage().contains("ACC_SUPER"), failure.getMessage());
    }

    @Test
    @DisplayName("rejects binary names where internal names belong")
    void rejectsBinaryNames() {
        assertThrows(CodeGenerationException.class,
                () -> CwClassWriter.of(AccessFlags.PUBLIC | AccessFlags.SUPER,
                        "com.example.Foo", "java/lang/Object"));
        assertThrows(CodeGenerationException.class,
                () -> CwClassWriter.of(AccessFlags.PUBLIC | AccessFlags.SUPER,
                        "com/example/Foo", "java.lang.Object"));
    }

    @Test
    @DisplayName("routes <init> and <clinit> through their own factory methods")
    void reservesSpecialMethodNames() {
        CwClassWriter writer = Generated.classWriter("Reserved");

        assertThrows(CodeGenerationException.class,
                () -> writer.method(AccessFlags.PUBLIC, "<init>", CwMethodType.of(CwType.VOID)));
        assertThrows(CodeGenerationException.class,
                () -> writer.method(AccessFlags.STATIC, "<clinit>", CwMethodType.of(CwType.VOID)));
        assertThrows(CodeGenerationException.class,
                () -> writer.constructor(AccessFlags.PUBLIC, CwMethodType.of(CwType.INT)));
    }
}
