package com.classwright.runtime.fixtures;

/**
 * A class for generated subclasses to be defined alongside.
 *
 * <p>{@link #secret()} is package-private and {@link #callSecret()} reaches it through ordinary
 * virtual dispatch. Together they detect whether a generated subclass really landed in the same
 * <em>runtime</em> package: if it did, its override of {@code secret()} wins and
 * {@code callSecret()} reports it; if it merely shares the package <em>name</em> — which is what a
 * child class loader gives you — the override is invisible and the original answer comes back.
 *
 * <p>That distinction is easy to state and easy to get wrong, and it silently produces overrides
 * that load fine and never run.
 */
public class DefinitionTarget {

    /** Package-private on purpose. Only a genuine package-mate can override it. */
    String secret() {
        return "original";
    }

    /** Public window onto {@link #secret()}, so a test in another package can observe dispatch. */
    public String callSecret() {
        return secret();
    }

    public String greet() {
        return "hello";
    }

    public int doubled(int value) {
        return value * 2;
    }
}
