package net.sf.cglib.core;

import java.io.Serial;

/**
 * Thrown when a class cannot be generated.
 *
 * <p>Reproduces {@code net.sf.cglib.core.CodeGenerationException} literally, down to the
 * serialized form: it extends {@link RuntimeException} directly, renders its message in CGLib's
 * pre-1.4 legacy form — {@code causeClassName-->causeMessage} — and stores the cause in its own
 * {@code private Throwable cause} field with an overriding {@link #getCause()}, exactly as
 * CGLib's pre-{@code initCause} implementation did. The field layout matters: matching only the
 * {@code serialVersionUID} let a real CGLib-serialized instance deserialize here with its cause
 * silently dropped, because the stream's {@code cause} field had no counterpart. A {@code null}
 * cause fails with the same {@link NullPointerException} CGLib's constructor produced; this
 * class is a compatibility facade, not a place for better modern manners.
 */
public class CodeGenerationException extends RuntimeException {

    /** CGLib 3.3.0's computed value, verified against the reference artifact. */
    @Serial
    private static final long serialVersionUID = -5101850775205203357L;

    /** CGLib's own field, pre-dating {@code Throwable.initCause}; part of the serialized form. */
    private final Throwable cause;

    /**
     * Wraps an underlying failure, CGLib-style.
     *
     * @param cause the underlying failure; {@code null} fails, as it did in CGLib
     */
    public CodeGenerationException(Throwable cause) {
        super(cause.getClass().getName() + "-->" + cause.getMessage());
        this.cause = cause;
    }

    /** CGLib exposed the wrapped cause through its own field, and so does this. */
    @Override
    public Throwable getCause() {
        return cause;
    }
}
