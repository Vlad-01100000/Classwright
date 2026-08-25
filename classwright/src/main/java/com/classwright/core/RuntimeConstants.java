package com.classwright.core;

/**
 * Constants that generated code loads with {@code getstatic}.
 *
 * <p><strong>Not part of the public API.</strong> Public only because generated classes live in
 * their target's package rather than Classwright's, and a {@code getstatic} from there must be
 * able to resolve the field.
 */
@com.classwright.Internal
public final class RuntimeConstants {

    /** Internal name of this class, for generators emitting references to it. */
    public static final String INTERNAL_NAME = "com/classwright/core/RuntimeConstants";

    /**
     * The empty argument array shared by every no-argument callback invocation.
     *
     * <p>A zero-length array is immutable, so one instance can serve every call site. Allocating a
     * fresh {@code new Object[0]} per intercepted no-argument call is only free when the JIT can
     * see through the callback and apply escape analysis, which a megamorphic callback defeats.
     */
    public static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];

    private RuntimeConstants() {
    }
}
