package com.classwright.beans;

import com.classwright.ClasswrightException;

/**
 * A {@link BulkBean} failure that knows which property position failed.
 *
 * <p>A bulk operation is positional, and the position is the one piece of context a caller
 * cannot recover after the fact — "the third setter blew up" is diagnosable, a bare
 * {@code ClassCastException} half-way through an array is not. Generated bulk methods track the
 * position as they go and wrap whatever escapes — a setter's own exception, a failed unboxing
 * cast, an undersized values array — in this type; accessor resolution at creation time reports
 * the failing position the same way. The original failure is always the {@link #getCause()}.
 *
 * <p>{@link com.classwright.Internal @Internal} as a <em>shape</em>: it extends
 * {@link ClasswrightException}, so callers catch and read it like any other Classwright failure
 * without naming this type, and the CGLib compatibility layer translates it into CGLib's
 * {@code BulkBeanException} contract. Only the structured-index surface is reserved.
 */
@com.classwright.Internal
public class BulkAccessException extends ClasswrightException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** The failing property's position; part of the serialized form. */
    private final int propertyIndex;

    /**
     * A creation-time failure at one property position.
     *
     * @param message       what went wrong
     * @param propertyIndex the failing position
     */
    public BulkAccessException(String message, int propertyIndex) {
        super(message);
        this.propertyIndex = propertyIndex;
    }

    /**
     * A bulk-operation failure at one property position. Called by generated code.
     *
     * @param cause         what the property access threw
     * @param propertyIndex the failing position
     */
    public BulkAccessException(Throwable cause, int propertyIndex) {
        super("bulk property access failed at position " + propertyIndex + ": " + cause, cause);
        this.propertyIndex = propertyIndex;
    }

    /**
     * The failing property's position in the bulk accessor's positional arrays.
     *
     * @return the zero-based position
     */
    public int propertyIndex() {
        return propertyIndex;
    }
}
