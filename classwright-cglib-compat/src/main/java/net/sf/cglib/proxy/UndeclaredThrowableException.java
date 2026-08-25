package net.sf.cglib.proxy;

import java.io.Serial;

/**
 * Wraps a checked exception a proxy was not declared to throw.
 *
 * <p>Reproduces {@code net.sf.cglib.proxy.UndeclaredThrowableException}, including its position
 * in the hierarchy: CGLib's extended {@code CodeGenerationException}, and {@code catch} blocks
 * written for one expect to receive the other. It is raised on the {@link InvocationHandler}
 * path exactly as CGLib raised it — a checked exception the intercepted method did not declare
 * arrives wrapped. The {@link MethodInterceptor} path propagates exceptions unwrapped, which
 * also matches CGLib.
 */
public class UndeclaredThrowableException extends net.sf.cglib.core.CodeGenerationException {

    /** CGLib 3.3.0's computed value, verified against the reference artifact. */
    @Serial
    private static final long serialVersionUID = 5826832563155386085L;

    /**
     * Wraps a checked exception the intercepted method did not declare.
     *
     * @param cause the exception that was thrown
     */
    public UndeclaredThrowableException(Throwable cause) {
        super(cause);
    }

    /**
     * CGLib exposed the wrapped exception under this name.
     *
     * @return the exception that was thrown
     */
    public Throwable getUndeclaredThrowable() {
        return getCause();
    }
}
