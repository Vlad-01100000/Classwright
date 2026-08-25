package com.classwright.proxy;

/**
 * Marker for everything that can be attached to a proxied method.
 *
 * <p>Each sub-interface describes a different way to handle a call, and each one gets its own
 * generated method body rather than a shared dispatch path. A {@link NoOp} method is compiled to a
 * plain super-call, a {@link FixedValue} method to a field read and a cast; neither pays anything
 * for machinery it does not use. That specialisation is the reason the callback types exist as
 * separate interfaces rather than as flags on one.
 *
 * <p>The names, semantics, and inheritance here mirror {@code net.sf.cglib.proxy} so that code
 * migrating from CGLib needs a package rename and nothing more.
 */
public interface Callback {
}
