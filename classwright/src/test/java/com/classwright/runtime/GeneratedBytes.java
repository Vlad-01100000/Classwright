package com.classwright.runtime;

import com.classwright.core.AccessFlags;
import com.classwright.core.CwClassWriter;
import com.classwright.core.CwMethodType;
import com.classwright.core.CwType;

/**
 * Produces small, valid subclasses for the definition tests.
 *
 * <p>Uses the real Phase 1 engine rather than a hand-rolled byte array, so these tests exercise the
 * genuine pipeline from generation through definition.
 */
final class GeneratedBytes {

    private GeneratedBytes() {
    }

    /** The internal name of a subclass of {@code target}, in the target's own package. */
    static String subclassName(Class<?> target, String suffix) {
        return target.getName().replace('.', '/') + "$$CW" + suffix;
    }

    /** A trivial subclass with nothing but a constructor chaining to super. */
    static byte[] plainSubclass(Class<?> target, String suffix) {
        return writerFor(target, suffix).toByteArray();
    }

    /** A subclass overriding a no-argument {@code String} method to return {@code value}. */
    static byte[] subclassOverriding(Class<?> target, String suffix, String methodName,
                                     int accessFlags, String value) {
        CwClassWriter writer = writerFor(target, suffix);
        writer.method(accessFlags, methodName, CwMethodType.of(CwType.STRING))
                .code()
                .pushString(value)
                .returnValue();
        return writer.toByteArray();
    }

    private static CwClassWriter writerFor(Class<?> target, String suffix) {
        String superName = target.getName().replace('.', '/');
        CwClassWriter writer = CwClassWriter.of(
                        AccessFlags.PUBLIC | AccessFlags.SUPER,
                        subclassName(target, suffix), superName)
                .sourceFile(target.getSimpleName() + "$$CW.java");
        writer.constructor(AccessFlags.PUBLIC, CwMethodType.of(CwType.VOID))
                .code()
                .loadThis()
                .invokeConstructor(superName, CwMethodType.of(CwType.VOID))
                .returnValue();
        return writer;
    }
}
