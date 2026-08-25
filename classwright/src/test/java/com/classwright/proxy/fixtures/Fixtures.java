package com.classwright.proxy.fixtures;

/** Small types used by the proxy tests, kept together because none needs a file of its own. */
public final class Fixtures {

    private Fixtures() {
    }

    /** An interface with an abstract method and a default one that calls it. */
    public interface Greeter {

        String greet(String name);

        /** A default method: the proxy must be able to reach it with {@code Interface.super}. */
        default String greetEveryone() {
            return greet("everyone");
        }
    }

    /**
     * Implements a generic interface, so {@code javac} emits a bridge {@code Object get()}
     * alongside the real {@code String get()}.
     *
     * <p>A proxy must override the real method and leave the bridge alone. Overriding the bridge
     * instead breaks the forwarding it exists to provide.
     */
    public static class StringBox implements java.util.function.Supplier<String> {

        public int calls;

        @Override
        public String get() {
            calls++;
            return "boxed";
        }
    }

    /** Abstract methods must be implemented by the proxy, and have no original to delegate to. */
    public abstract static class AbstractService {

        public abstract int compute(int value);

        public int doubled(int value) {
            return compute(value) * 2;
        }
    }

    /**
     * Not final, but every constructor is private, so no subclass can be built.
     *
     * <p>Deliberately not final: that would be rejected earlier for a different reason and this
     * fixture would stop testing what it is named for.
     */
    public static class Unconstructable {

        private Unconstructable() {
        }
    }

    /** Sealed, so only its permitted subclasses may extend it. */
    public abstract static sealed class Sealed permits Sealed.Only {

        public static final class Only extends Sealed {
        }
    }

    /** Records are implicitly final. */
    public record Point(int x, int y) {
    }
}
