package net.sf.cglib.proxy;

/**
 * Gives the adapter package access to {@link MethodProxy}'s package-private factory.
 *
 * <p>{@code MethodProxy} has no public constructor in CGLib and must not gain one here — code
 * receives instances, it never creates them. This one-method bridge lets
 * {@code com.classwright.cglib} construct wrappers without widening the reproduced API.
 *
 * <p>Not part of the reproduced API.
 */
public final class MethodProxies {

    private MethodProxies() {
    }

    /**
     * The CGLib-shaped wrapper for a Classwright method proxy.
     *
     * @param delegate the Classwright method proxy
     * @return its wrapper, cached on the delegate
     */
    public static MethodProxy wrap(com.classwright.proxy.MethodProxy delegate) {
        return MethodProxy.wrapping(delegate);
    }
}
