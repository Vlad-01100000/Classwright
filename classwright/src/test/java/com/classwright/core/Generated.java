package com.classwright.core;

import com.classwright.testkit.ClassVerifier;

/**
 * Boilerplate for tests that build a class, verify it, and call into it.
 *
 * <p>Every generated class goes through {@link ClassVerifier}, so any test that runs a generated
 * method has already proved the bytes pass the JVM's own verifier. A test that only checked the
 * return value could pass on bytes that happen to work while being subtly malformed.
 */
final class Generated {

    /** Generated test classes live in their own package so nothing collides with real code. */
    static final String PACKAGE = "cwtest";

    private Generated() {
    }

    /** A public class extending {@code Object}. */
    static CwClassWriter classWriter(String simpleName) {
        return classWriter(simpleName, "java/lang/Object");
    }

    /** A public class extending the given internal name. */
    static CwClassWriter classWriter(String simpleName, String superInternalName) {
        return CwClassWriter
                .of(AccessFlags.PUBLIC | AccessFlags.SUPER,
                        PACKAGE + "/" + simpleName, superInternalName)
                .sourceFile(simpleName + ".java");
    }

    /**
     * Adds a public no-argument constructor that simply chains to the superclass.
     *
     * @param writer            the class being built
     * @param superInternalName the superclass whose no-argument constructor to call
     */
    static CwClassWriter withDefaultConstructor(CwClassWriter writer, String superInternalName) {
        writer.constructor(AccessFlags.PUBLIC, CwMethodType.of(CwType.VOID))
                .code()
                .loadThis()
                .invokeConstructor(superInternalName, CwMethodType.of(CwType.VOID))
                .returnValue();
        return writer;
    }

    /** Builds, verifies, and loads the class. */
    static Class<?> define(CwClassWriter writer) {
        return ClassVerifier.assertVerifies(writer.toByteArray());
    }
}
