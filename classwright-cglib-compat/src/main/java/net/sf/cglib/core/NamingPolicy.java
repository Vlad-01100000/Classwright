package net.sf.cglib.core;

/**
 * CGLib's pluggable class-naming hook.
 *
 * <p>Present so code that supplies one compiles unchanged. It is <strong>accepted and
 * ignored</strong>: Classwright's generated classes are hidden, and a hidden class's name is
 * assigned by the JVM at definition, not chosen by the generator. The generated name still
 * contains the {@code $$EnhancerByCGLIB$$} marker that naming-sensitive frameworks look for.
 */
public interface NamingPolicy {

    /**
     * Chooses a class name.
     *
     * @param prefix    the name prefix, usually the target class's name
     * @param source    the generator class's name
     * @param key       the generation key
     * @param names     evaluates to {@code true} for names already taken
     * @return the class name to use
     */
    String getClassName(String prefix, String source, Object key, Predicate names);

    /**
     * Redeclared, as CGLib declared it: policies are compared by value when they participate in
     * generator configuration, and the interface says so.
     *
     * @param o another object
     * @return whether the two policies name classes identically
     */
    boolean equals(Object o);
}
