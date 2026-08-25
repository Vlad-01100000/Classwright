package net.sf.cglib.proxy;

/**
 * Marker for everything that can be attached to a proxied method.
 *
 * <p>Reproduces {@code net.sf.cglib.proxy.Callback}. It is a distinct type from
 * {@link com.classwright.proxy.Callback} on purpose: existing code implements <em>this</em>
 * interface, so migrating must not require touching it. The compatibility layer adapts between the
 * two.
 */
public interface Callback {
}
