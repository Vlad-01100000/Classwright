package net.sf.cglib.proxy;

/**
 * Leaves a method alone: the original implementation runs, unchanged.
 *
 * <p>Reproduces {@code net.sf.cglib.proxy.NoOp}.
 *
 * @see com.classwright.proxy.NoOp
 */
public interface NoOp extends Callback {

    /**
     * The shared instance. {@code NoOp} has no state.
     */
    NoOp INSTANCE = new NoOp() {
        @Override
        public String toString() {
            return "NoOp.INSTANCE";
        }
    };
}
