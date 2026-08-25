package com.classwright.proxy;

/**
 * Leaves a method alone: the original implementation runs, unchanged.
 *
 * <p>Useful with a {@link CallbackFilter} to exempt specific methods from interception, and as the
 * callback for a proxy that exists only to be a subclass.
 *
 * <p>Costs nothing at runtime. A {@code NoOp} method is generated as a direct {@code invokespecial}
 * to the superclass with no field read, no null check, and no argument boxing — the same bytecode
 * {@code javac} would emit for {@code return super.m(args);}. The JIT then inlines it away
 * completely.
 */
public interface NoOp extends Callback {

    /** The only instance there needs to be; it carries no state. */
    NoOp INSTANCE = new NoOp() {
        @Override
        public String toString() {
            return "NoOp.INSTANCE";
        }
    };
}
