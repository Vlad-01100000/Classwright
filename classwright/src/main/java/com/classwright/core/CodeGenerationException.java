package com.classwright.core;

import com.classwright.ClasswrightException;

import java.io.Serial;

/**
 * Thrown when the engine is asked to emit something it cannot emit.
 *
 * <p>Almost always raised at <em>generation</em> time rather than at class-load time, and that is
 * the whole point. A bytecode engine that produces malformed output reports it as a
 * {@link VerifyError} naming a bytecode offset, which tells you nothing about which line of
 * generator code was wrong. This engine tracks the operand stack and local variable types as it
 * builds, so a mistake is caught at the instruction that made it, with a message and a Java stack
 * trace pointing at the caller.
 *
 * <p>The name matches CGLib's {@code net.sf.cglib.core.CodeGenerationException} so that migrating
 * code catching that type has an obvious counterpart.
 */
public class CodeGenerationException extends ClasswrightException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Reports a fault in the code being generated.
     *
     * @param message what went wrong, and what to change
     */
    public CodeGenerationException(String message) {
        super(message);
    }

    /**
     * Reports a fault in the code being generated.
     *
     * @param message what went wrong, and what to change
     * @param cause   the underlying failure
     */
    public CodeGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
