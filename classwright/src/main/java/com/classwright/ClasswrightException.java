package com.classwright;

import java.io.Serial;

/**
 * Base type for every failure raised by Classwright.
 *
 * <p>Unchecked by design. Code generation failures are almost always programming or configuration
 * errors &mdash; a final class asked to be subclassed, a module that refuses to open, a callback
 * type that does not match its filter &mdash; and none of those are conditions a caller can
 * meaningfully recover from at the call site. Forcing {@code catch} blocks around every
 * {@code create()} would add noise without adding safety.
 *
 * <p>Catching this type is the supported way to handle "anything Classwright could not do".
 *
 * <p><strong>On diagnostics.</strong> Subclasses are expected to produce messages that say what was
 * attempted, why it failed, and what to change. CGLib's opaque failures under the JDK 9+ module
 * system were a real and lasting source of user pain, and avoiding a repeat is treated here as a
 * feature rather than as polish.
 */
public class ClasswrightException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with the given message.
     *
     * @param message a description of what failed and, where possible, how to fix it
     */
    public ClasswrightException(String message) {
        super(message);
    }

    /**
     * Creates an exception with the given message and underlying cause.
     *
     * @param message a description of what failed and, where possible, how to fix it
     * @param cause   the underlying failure
     */
    public ClasswrightException(String message, Throwable cause) {
        super(message, cause);
    }
}
