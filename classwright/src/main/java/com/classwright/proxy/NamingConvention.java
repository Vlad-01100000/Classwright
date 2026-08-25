package com.classwright.proxy;

import com.classwright.ClasswrightException;

/**
 * How a generated proxy's class and members are named.
 *
 * <p>Names are not cosmetic here. A great deal of framework code inspects them:
 *
 * <ul>
 *   <li>Spring's {@code ClassUtils.getUserClass} tests for {@code $$} and then walks to the
 *       superclass, which is how it recovers the real type behind a proxy.</li>
 *   <li>Serialisation, logging, and equality helpers routinely skip members whose names start with
 *       a generator's prefix, so that a proxy's internal plumbing is not mistaken for state.</li>
 *   <li>Some code matches {@code EnhancerByCGLIB} specifically.</li>
 * </ul>
 *
 * <p>{@link #DEFAULT} uses Classwright's own markers. {@link #CGLIB_COMPATIBLE} uses CGLib's, so
 * that code written against CGLib's naming keeps working after the dependency is swapped — which is
 * exactly what the {@code classwright-cglib-compat} artifact needs.
 *
 * @param classNameSuffix appended to the target's name to form the proxy's; must contain
 *                        {@code $$} so framework heuristics recognise it
 * @param memberPrefix    prefixed to every generated field and helper method, so they cannot
 *                        collide with the target's own members and can be filtered out
 */
public record NamingConvention(String classNameSuffix, String memberPrefix) {

    /** Classwright's own naming. */
    public static final NamingConvention DEFAULT = new NamingConvention("$$CW", "CW$");

    /**
     * CGLib's naming, for code that inspects it.
     *
     * <p>CGLib produced classes called {@code Foo$$EnhancerByCGLIB$$1a2b3c4d} with members prefixed
     * {@code CGLIB$}. Reproducing both means a migrated application's name-matching keeps working
     * without anyone having to find and change it.
     */
    public static final NamingConvention CGLIB_COMPATIBLE =
            new NamingConvention("$$EnhancerByCGLIB$$", "CGLIB$");

    /**
     * Validates the components.
     *
     * @param classNameSuffix the suffix appended to a target's name
     * @param memberPrefix    the prefix on generated members
     */
    public NamingConvention {
        if (classNameSuffix == null || !classNameSuffix.contains("$$")) {
            throw new ClasswrightException("the class name suffix must contain '$$', which is what "
                    + "framework heuristics look for to identify a generated class");
        }
        if (memberPrefix == null || memberPrefix.isEmpty()) {
            throw new ClasswrightException("a member prefix is required, so that generated members "
                    + "cannot collide with the target's own");
        }
    }
}
