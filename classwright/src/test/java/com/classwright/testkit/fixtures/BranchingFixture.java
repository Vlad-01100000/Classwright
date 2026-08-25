package com.classwright.testkit.fixtures;

/**
 * A perfectly ordinary compiled class, used as a source of known-good class bytes.
 *
 * <p>It exists so that {@link com.classwright.testkit.ClassVerifier} can be tested before any
 * bytecode engine exists to generate input for it. Rather than embedding an opaque byte array, the
 * tests read this class's own compiled form off the test classpath &mdash; so the "known good"
 * bytes are produced by {@code javac}, which is about as trustworthy an oracle as exists.
 *
 * <p><strong>It must contain a branch.</strong> Methods without branches need no
 * {@code StackMapTable} at all, so a straight-line fixture could not be mutated into a
 * verification failure. The {@code if} in {@link #max} is load-bearing test infrastructure, not an
 * example.
 */
public class BranchingFixture {

    /** Deliberately branching, so the compiled form carries a {@code StackMapTable}. */
    public static int max(int a, int b) {
        if (a >= b) {
            return a;
        }
        return b;
    }

    /** A second method, so tests can confirm the class links and runs as a whole. */
    public int twice(int value) {
        return value + value;
    }
}
