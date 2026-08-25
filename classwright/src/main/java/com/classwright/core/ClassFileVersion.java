package com.classwright.core;

/**
 * Class-file format versions, and the policy for choosing one.
 *
 * <h2>Why Classwright emits old bytecode on purpose</h2>
 *
 * <p>The JVM's compatibility guarantee runs one way: a JVM accepts class files at or below its own
 * version, essentially forever. A JDK 25 runtime still loads Java 8 bytecode without complaint. So
 * the lower the version we emit, the wider the range of JVMs that will accept our output, including
 * JVMs that do not exist yet.
 *
 * <p>That is not a theoretical benefit. CGLib's fatal problem was the mirror image of this: it had
 * to <em>read</em> class files, so every new format version required an upgrade it eventually
 * stopped shipping. Classwright never reads, and writes as low as it can. The two decisions
 * together mean a new Java release cannot strand us.
 *
 * <p>The default is {@link #JAVA_8} (major 52). It supports everything the engine currently emits,
 * and it was measured working with {@code defineHiddenClass} down to major 49 &mdash; hidden
 * classes, a Java 15 feature, happily accept Java 5 bytecode, because nest membership comes from
 * the {@code NESTMATE} class option rather than from a {@code NestHost} attribute. Details in
 * {@code docs/RESEARCH.md} §5.
 *
 * <p>Raise the version only when a feature genuinely requires it, and record why here.
 */
public final class ClassFileVersion {

    private ClassFileVersion() {
    }

    /**
     * Class-file major version 49, emitted by Java 5.
     */
    public static final int JAVA_5 = 49;

    /** Major 50. Stack map frames appear, but the verifier still falls back to inference. */
    public static final int JAVA_6 = 50;

    /**
     * Major 51. The verifier stops falling back: from here on, a branch without a stack map frame
     * is a hard {@link VerifyError}. This is the boundary that makes
     * {@code StackMapTable} generation mandatory rather than optional.
     */
    public static final int JAVA_7 = 51;

    /**
     * Major 52, and the engine's default.
     *
     * <p>Chosen as the lowest version supporting everything Classwright emits: notably
     * {@code invokespecial} on an interface method, which default methods require, and
     * {@code invokedynamic}, which later phases may use.
     */
    public static final int JAVA_8 = 52;

    /** Major 55. Nest-based access control ({@code NestHost}/{@code NestMembers} attributes). */
    public static final int JAVA_11 = 55;

    /** Major 61. Sealed classes ({@code PermittedSubclasses}). */
    public static final int JAVA_17 = 61;

    /**
     * Class-file major version 65, emitted by Java 21.
     */
    public static final int JAVA_21 = 65;

    /** The version Classwright emits unless told otherwise. */
    public static final int DEFAULT = JAVA_8;

    /**
     * The lowest version whose verifier requires stack map frames at branch targets.
     *
     * <p>Below this the engine could legally omit the {@code StackMapTable} attribute. It does not:
     * emitting frames unconditionally means one code path rather than two, and a frame that is
     * merely unnecessary costs a few bytes, whereas a missing one costs a {@link VerifyError}.
     */
    public static final int FIRST_VERSION_REQUIRING_FRAMES = JAVA_7;

    /**
     * Maps a major version to the Java feature release that introduced it, for messages.
     *
     * @param major a class-file major version
     * @return a description such as {@code "52 (Java 8)"}
     */
    public static String describe(int major) {
        // Major 49 is Java 5; every release since has incremented by exactly one.
        int javaRelease = major - 44;
        return major + " (Java " + javaRelease + ")";
    }

    /**
     * Rejects a version the engine cannot honestly produce.
     *
     * @param major a proposed class-file major version
     * @throws CodeGenerationException if it is outside the supported range
     */
    public static void validate(int major) {
        if (major < JAVA_5 || major > JAVA_21 + 20) {
            throw new CodeGenerationException("unsupported class-file major version " + major
                    + "; expected between " + JAVA_5 + " and a plausible future release");
        }
    }
}
