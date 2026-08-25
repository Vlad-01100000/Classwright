package com.classwright.core;

import java.lang.reflect.Modifier;

/**
 * Class, field, and method access flags (JVMS 4.1, 4.5, 4.6).
 *
 * <p>These overlap heavily with {@link Modifier} but are not the same set: {@code ACC_SUPER},
 * {@code ACC_BRIDGE}, {@code ACC_SYNTHETIC}, {@code ACC_VARARGS}, and {@code ACC_ENUM} have no
 * {@link Modifier} constant, and {@code Modifier} additionally defines bits that are not access
 * flags at all. Mixing the two is a subtle and popular way to emit a class the verifier rejects,
 * so the class-file flags are declared here explicitly rather than borrowed.
 */
public final class AccessFlags {

    private AccessFlags() {
    }

    /** {@code ACC_PUBLIC}: visible everywhere. Valid on a class, field, or method. */
    public static final int PUBLIC = 0x0001;

    /** {@code ACC_PRIVATE}: visible only within the declaring class. Fields and methods only. */
    public static final int PRIVATE = 0x0002;

    /** {@code ACC_PROTECTED}: visible to subclasses and the package. Fields and methods only. */
    public static final int PROTECTED = 0x0004;

    /** {@code ACC_STATIC}: belongs to the class rather than an instance. */
    public static final int STATIC = 0x0008;

    /**
     * {@code ACC_FINAL}: a class that cannot be extended, a method that cannot be overridden, or a
     * field that cannot be reassigned after construction.
     *
     * <p>Never set on a generated override. A proxy that marked its own methods final would stop
     * anything else from proxying it in turn, which frameworks routinely do.
     */
    public static final int FINAL = 0x0010;

    /**
     * {@code ACC_SUPER} on a class; {@code ACC_SYNCHRONIZED} on a method. Same bit, different
     * meaning depending on where it appears &mdash; a genuine wart in the format.
     *
     * <p>{@code ACC_SUPER} selects the modern (since Java 1.1) semantics for {@code invokespecial},
     * and every class Classwright emits sets it. Without it, a super-call can dispatch to the wrong
     * method.
     */
    public static final int SUPER = 0x0020;

    /** {@code ACC_SYNCHRONIZED} on a method. The same bit as {@link #SUPER} on a class. */
    public static final int SYNCHRONIZED = 0x0020;

    /**
     * {@code ACC_BRIDGE}: a method the compiler generated to bridge a covariant or erased override.
     *
     * <p>The same bit as {@link #VOLATILE} on a field.
     */
    public static final int BRIDGE = 0x0040;

    /** {@code ACC_VOLATILE} on a field. The same bit as {@link #BRIDGE} on a method. */
    public static final int VOLATILE = 0x0040;

    /**
     * {@code ACC_VARARGS}: the method's last parameter is variable-arity.
     *
     * <p>Purely informational to the JVM &mdash; the descriptor is identical either way &mdash; but
     * reflection reports it, so an override that drops it changes what callers see.
     * The same bit as {@link #TRANSIENT} on a field.
     */
    public static final int VARARGS = 0x0080;

    /** {@code ACC_TRANSIENT} on a field. The same bit as {@link #VARARGS} on a method. */
    public static final int TRANSIENT = 0x0080;

    /** {@code ACC_NATIVE}: implemented outside the JVM. Never emitted here; it has no Code. */
    public static final int NATIVE = 0x0100;

    /** {@code ACC_INTERFACE}: the class is an interface rather than an ordinary class. */
    public static final int INTERFACE = 0x0200;

    /** {@code ACC_ABSTRACT}: a class that cannot be instantiated, or a method with no body. */
    public static final int ABSTRACT = 0x0400;

    /**
     * {@code ACC_STRICT}: strict floating point.
     *
     * <p>Meaningless from Java 17 onward, which made all floating-point arithmetic strict
     * (JEP 306). Retained because older class files still carry it.
     */
    public static final int STRICT = 0x0800;

    /**
     * Marks a member the compiler or a generator invented, which did not appear in source.
     *
     * <p>Worth setting on generated members: debuggers hide them, and reflection-based frameworks
     * that scan a class routinely skip synthetic members, which is usually what you want for a
     * proxy's internal plumbing.
     */
    public static final int SYNTHETIC = 0x1000;

    /** {@code ACC_ANNOTATION}: the interface is an annotation type. */
    public static final int ANNOTATION = 0x2000;

    /** {@code ACC_ENUM}: the class is an enum, or the field is one of its constants. */
    public static final int ENUM = 0x4000;

    /**
     * {@code ACC_MANDATED}: required by the language specification, so not literally in the source.
     *
     * <p>The outer-instance parameter of an inner class's constructor is the usual example.
     */
    public static final int MANDATED = 0x8000;

    /**
     * Extracts the flags that belong on a generated method that overrides another, from a
     * {@link Modifier} bit set as returned by {@link java.lang.reflect.Method#getModifiers()}.
     *
     * <p>The overlapping bits happen to line up for the modifiers that exist in both sets, so this
     * is a mask rather than a translation. What survives is deliberate:
     *
     * <ul>
     * <li>{@code ACC_ABSTRACT} and {@code ACC_NATIVE} are dropped &mdash; a generated override
     * always has a body, so carrying either across produces a class that fails verification with a
     * message that does not obviously point at the cause.</li>
     * <li>{@code ACC_SYNCHRONIZED} is dropped &mdash; keeping it would make the override acquire
     * the monitor <em>before</em> the callback runs and hold it across the callback, serialising
     * intercepted calls and inviting deadlock when a callback blocks. The original method's own
     * body still synchronises when the override chains to it, which is the behaviour callers had
     * before proxying. CGLib stripped this bit for the same reason.</li>
     * <li>{@code ACC_STATIC} and {@code ACC_FINAL} are dropped &mdash; neither can be true of a
     * method that is being overridden.</li>
     * <li>{@code ACC_VARARGS} is kept &mdash; it changes nothing in the descriptor, but reflection
     * reports it, and frameworks change argument-coercion behaviour on
     * {@link java.lang.reflect.Method#isVarArgs()}, so an override that dropped it would be
     * visibly different from the method it replaces.</li>
     * </ul>
     *
     * @param modifiers modifiers from core reflection
     * @return access flags suitable for a generated method that overrides it
     */
    public static int forGeneratedOverrideOf(int modifiers) {
        return modifiers & (PUBLIC | PROTECTED | VARARGS | STRICT);
    }

    /**
     * Renders flags for a diagnostic message, e.g. {@code "public final"}.
     *
     * @param flags an access flag bit set
     * @return a human-readable rendering, or {@code "package-private"} if no visibility bit is set
     */
    public static String describe(int flags) {
        StringBuilder text = new StringBuilder();
        appendIf(text, flags, PUBLIC, "public");
        appendIf(text, flags, PRIVATE, "private");
        appendIf(text, flags, PROTECTED, "protected");
        if ((flags & (PUBLIC | PRIVATE | PROTECTED)) == 0) {
            text.append("package-private");
        }
        appendIf(text, flags, STATIC, "static");
        appendIf(text, flags, FINAL, "final");
        appendIf(text, flags, ABSTRACT, "abstract");
        appendIf(text, flags, SYNTHETIC, "synthetic");
        appendIf(text, flags, BRIDGE, "bridge");
        return text.toString();
    }

    private static void appendIf(StringBuilder text, int flags, int bit, String name) {
        if ((flags & bit) != 0) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(name);
        }
    }
}
