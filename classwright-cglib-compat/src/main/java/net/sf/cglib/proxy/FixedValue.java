package net.sf.cglib.proxy;

/**
 * Replaces a method's implementation with a constant.
 *
 * <p>Reproduces {@code net.sf.cglib.proxy.FixedValue}.
 *
 * @see com.classwright.proxy.FixedValue
 */
public interface FixedValue extends Callback {

    /**
     * The value to return.
     *
     * @return a value assignable to the intercepted method's return type
     * @throws Exception if it cannot be produced
     */
    Object loadObject() throws Exception;
}
