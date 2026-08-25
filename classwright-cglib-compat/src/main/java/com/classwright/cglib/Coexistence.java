package com.classwright.cglib;

import com.classwright.ClasswrightException;

/**
 * Detects the real CGLib on the same class path and refuses to continue.
 *
 * <p>This artifact defines the {@code net.sf.cglib} packages itself. With the real CGLib also
 * present, every class in those packages exists twice and which one the JVM loads is decided by
 * class path order — which build tools do not guarantee and which can differ between a developer's
 * machine and production. The failure mode is not an error but silently running a mixture of two
 * implementations, so it is worth being loud about.
 *
 * <p>Detection looks for {@code net.sf.cglib.transform.ClassTransformer}, which the real CGLib has
 * and this artifact deliberately does not: that package rewrites existing class files and would
 * need the parser Classwright refuses to have. Finding it means another CGLib is on the path.
 *
 * <p>The check is one-directional by necessity. If the real CGLib sorts <em>first</em> on the class
 * path, its {@code Enhancer} loads and this code never runs at all. The Maven Enforcer rule shipped
 * with this module is the belt to this braces; the migration guide explains both.
 */
public final class Coexistence {

    /** Present in the real CGLib, absent here. See the class documentation. */
    private static final String CGLIB_ONLY_CLASS = "net.sf.cglib.transform.ClassTransformer";

    /** Set {@code -Dclasswright.cglib.allowCoexistence=true} to downgrade this to a warning. */
    private static final String OVERRIDE_PROPERTY = "classwright.cglib.allowCoexistence";

    private Coexistence() {
    }

    /**
     * Fails if another CGLib is on the class path.
     *
     * <p>Called from the static initialiser of every entry point in this artifact, so the problem
     * surfaces the first time anything is used rather than as inconsistent behaviour later.
     */
    public static void check() {
        if (!isRealCglibPresent()) {
            return;
        }
        String message = """
                Both classwright-cglib-compat and the real CGLib are on the class path.

                This artifact *defines* the net.sf.cglib packages, so with CGLib also present every \
                class in them exists twice and which one loads depends on class path order. That is \
                not something to run in production.

                Remove the CGLib dependency. In Maven:

                    <dependency>
                      <groupId>com.classwright</groupId>
                      <artifactId>classwright-cglib-compat</artifactId>
                      <exclusions>...</exclusions>
                    </dependency>

                and exclude cglib:cglib wherever it is pulled in transitively:

                    mvn dependency:tree -Dincludes=cglib:cglib

                In Gradle:

                    configurations.all {
                        exclude group: 'cglib', module: 'cglib'
                        exclude group: 'cglib', module: 'cglib-nodep'
                    }

                To proceed anyway, knowing the risk, set -D%s=true.""".formatted(OVERRIDE_PROPERTY);

        if (Boolean.getBoolean(OVERRIDE_PROPERTY)) {
            System.err.println("[classwright] WARNING: " + message);
            return;
        }
        throw new ClasswrightException(message);
    }

    private static boolean isRealCglibPresent() {
        try {
            Class.forName(CGLIB_ONLY_CLASS, false, Coexistence.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }
}
