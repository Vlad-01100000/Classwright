package com.classwright.runtime;

import com.classwright.ClasswrightException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.util.Optional;

/**
 * Obtains a full-privilege {@link Lookup} in another class's package.
 *
 * <p>A lookup is the key to everything this package does. With one, Classwright can define a class
 * into the target's own package and class loader, which is what allows a generated subclass to
 * override package-private methods and to be a nestmate of the class it extends. Without one, the
 * proxy has to live in Classwright's own package and can only override what is {@code public} or
 * {@code protected}.
 *
 * <p>{@link MethodHandles#privateLookupIn} grants that access, and it works on any class whose
 * package is open to us. Classpath code is always open. Code in a named module is open only if it
 * says so.
 *
 * <h2>Diagnostics are the feature here</h2>
 *
 * <p>When this fails it produces a message naming the module, the package, and the exact directive
 * needed to fix it. That is a deliberate response to how CGLib behaved: under the JDK 9+ module
 * system it produced {@code InaccessibleObjectException} and {@code IllegalAccessError} with no
 * indication of which flag would help, and working that out was a rite of passage for a generation
 * of Java developers. Getting this right costs a few dozen lines and saves users hours.
 */
public final class LookupResolver {

    /** Our own lookup. Derived lookups inherit full privilege access from it. */
    private static final Lookup OURS = MethodHandles.lookup();

    private LookupResolver() {
    }

    /**
     * Attempts to obtain a full-privilege lookup in {@code target}'s package.
     *
     * @param target the class whose package the generated class should join
     * @return the lookup, or empty if the package is not open to Classwright
     */
    public static Optional<Lookup> tryResolve(Class<?> target) {
        if (target.isPrimitive() || target.isArray()) {
            return Optional.empty();
        }
        // privateLookupIn demands two things, not one: the target package must be open to us,
        // AND our module must read the target's. `opens` alone satisfies only the first, so a
        // user who followed the diagnostic exactly would still be refused. Reading is not a
        // capability grab — any module may read any other — so establish the edge ourselves
        // rather than asking the user to add a --add-reads flag no one remembers exists.
        //
        // The unnamed module needs this edge too. When Classwright is on the module path it is
        // an explicit named module, and an explicit module does not read the unnamed module by
        // default — yet every classpath class and every custom-loader class lives in one. An
        // isNamed() guard here once skipped exactly that case, and with it broke every
        // creation under module-path deployment; addReads toward the unnamed module is legal
        // and idempotent, so the only correct condition is canRead.
        Module ourModule = LookupResolver.class.getModule();
        Module targetModule = target.getModule();
        if (!ourModule.canRead(targetModule)) {
            ourModule.addReads(targetModule);
        }
        try {
            return Optional.of(MethodHandles.privateLookupIn(target, OURS));
        } catch (IllegalAccessException | RuntimeException e) {
            // Includes SecurityException and the IllegalArgumentException a hidden or primitive
            // class produces. Every one of them means the same thing: no lookup here.
            return Optional.empty();
        }
    }

    /**
     * Obtains a full-privilege lookup in {@code target}'s package, or explains why it cannot.
     *
     * @param target the class whose package the generated class should join
     * @return the lookup
     * @throws ClasswrightException with remediation instructions, if the package is not open
     */
    public static Lookup resolve(Class<?> target) {
        return tryResolve(target).orElseThrow(
                () -> new ClasswrightException(explainInaccessible(target)));
    }

    /**
     * Builds the "here is how to fix it" message.
     *
     * <p>Kept separate and public-ish so callers that recover by falling back to a different
     * placement can still include the reason in their own diagnostics.
     *
     * @param target the class that could not be reached
     * @return a multi-line explanation naming the concrete fixes
     */
    static String explainInaccessible(Class<?> target) {
        if (target.isPrimitive() || target.isArray()) {
            return "Cannot generate a class alongside " + target.getTypeName()
                    + ": primitives and arrays have no package to join.";
        }

        Module targetModule = target.getModule();
        String packageName = target.getPackageName();
        Module ourModule = LookupResolver.class.getModule();
        String ourName = ourModule.isNamed() ? ourModule.getName() : "ALL-UNNAMED";

        StringBuilder message = new StringBuilder()
                .append("Cannot generate a class in the package of ").append(target.getName())
                .append(": package ").append(packageName);

        if (targetModule.isNamed()) {
            message.append(" in module ").append(targetModule.getName())
                    .append(" is not open to ").append(ourName).append(".\n\n")
                    .append("Fix it in one of these ways:\n")
                    .append("  1. In ").append(targetModule.getName())
                    .append("'s module-info.java, add:\n")
                    .append("         opens ").append(packageName)
                    .append(" to ").append(ourName).append(";\n")
                    .append("  2. Or launch the JVM with:\n")
                    .append("         --add-opens ").append(targetModule.getName())
                    .append('/').append(packageName).append('=').append(ourName).append('\n');
        } else {
            // The unnamed module is open to everything, so reaching here means something unusual:
            // a custom class loader that rejects the lookup, or a security policy.
            message.append(" is on the class path, which is normally always accessible.\n")
                    .append("The lookup was still refused, which suggests a custom class loader ")
                    .append("or security configuration is intervening.\n");
        }

        message.append("  3. Or let Classwright place the generated class in its own package ")
                .append("instead. It does this automatically where it can; the cost is that ")
                .append("package-private methods of ").append(target.getSimpleName())
                .append(" can no longer be overridden.");
        return message.toString();
    }
}
