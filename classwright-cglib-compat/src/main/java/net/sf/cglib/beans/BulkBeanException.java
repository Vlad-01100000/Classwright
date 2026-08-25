package net.sf.cglib.beans;

import java.io.Serial;

/**
 * Thrown when a bulk property access fails.
 *
 * <p>Reproduces {@code net.sf.cglib.beans.BulkBeanException} literally, down to the serialized
 * form: the cause constructor takes the <em>cause's message</em> as its own (not the JDK's
 * {@code cls: msg} rendering), and both {@code index} and {@code cause} live in this class's own
 * fields — the serialized layout CGLib wrote — with an overriding {@link #getCause()}. Matching
 * only the {@code serialVersionUID} let a real CGLib-serialized instance deserialize here with
 * its cause silently dropped. A {@code null} cause fails with the same
 * {@link NullPointerException} CGLib's constructor produced.
 */
public class BulkBeanException extends RuntimeException {

    /** CGLib 3.3.0's computed value, verified against the reference artifact. */
    @Serial
    private static final long serialVersionUID = 4969008443043011086L;

    /** Which property failed; part of the serialized form. */
    private final int index;

    /** CGLib's own field, pre-dating {@code Throwable.initCause}; part of the serialized form. */
    private final Throwable cause;

    /**
     * Reports a failure against one property.
     *
     * @param message what went wrong
     * @param index   the property's position
     */
    public BulkBeanException(String message, int index) {
        super(message);
        this.index = index;
        this.cause = null;
    }

    /**
     * Reports a failure against one property.
     *
     * @param cause the underlying failure; {@code null} fails, as it did in CGLib
     * @param index the property's position
     */
    public BulkBeanException(Throwable cause, int index) {
        super(cause.getMessage());
        this.index = index;
        this.cause = cause;
    }

    /** CGLib exposed the wrapped cause through its own field, and so does this. */
    @Override
    public Throwable getCause() {
        return cause;
    }

    /**
     * Which property, by position, the failure relates to.
     *
     * @return the position of the property that failed
     */
    public int getIndex() {
        return index;
    }
}
