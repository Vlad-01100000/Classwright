package net.sf.cglib.core;

/**
 * CGLib's own predicate interface, predating {@code java.util.function}.
 *
 * <p>Present so that {@link NamingPolicy} implementations written against CGLib compile
 * unchanged.
 */
public interface Predicate {

    /**
     * Evaluates the predicate.
     *
     * @param arg the value to test
     * @return whether the value satisfies the predicate
     */
    boolean evaluate(Object arg);
}
