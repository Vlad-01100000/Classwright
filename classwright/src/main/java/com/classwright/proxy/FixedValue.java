package com.classwright.proxy;

/**
 * Replaces a method's implementation with a constant.
 *
 * <p>The original never runs and the arguments are never even boxed: the generated body reads the
 * callback field, calls {@link #loadObject()}, casts or unboxes the result, and returns. Handy for
 * stubbing a method out in tests, or for a proxy whose whole purpose is to answer one question the
 * same way every time.
 *
 * <p>{@link #loadObject()} is called on every invocation, so the value need not actually be
 * constant — but if it is, cache it in the callback rather than recomputing.
 *
 * <p>The returned object must be assignable to the method's return type, boxed for primitives.
 * A mismatch surfaces as a {@link ClassCastException} at the call site, because the generated code
 * casts rather than checking.
 */
@FunctionalInterface
public interface FixedValue extends Callback {

    /**
     * The value to return.
     *
     * @return a value assignable to the intercepted method's return type; for a primitive return,
     *         its wrapper
     * @throws Exception if the value cannot be produced
     */
    Object loadObject() throws Exception;
}
