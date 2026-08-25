package com.classwright.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Disassembles class bytes by shelling out to the {@code javap} that ships with the running JDK.
 *
 * <p>Two reasons this exists rather than a library:
 *
 * <ol>
 *   <li><strong>It reads class files, and we refuse to.</strong> Classwright's central design rule
 *       is that nothing in the library parses bytecode. Pulling in a bytecode library for test
 *       diagnostics would put the very dependency we are avoiding into the build, and would quietly
 *       make it the thing that has to be upgraded for each new JDK.</li>
 *   <li><strong>{@code javap} is always correct and always present.</strong> It is part of the JDK,
 *       so it understands the exact class-file version the running JVM does &mdash; including
 *       versions that do not exist yet.</li>
 * </ol>
 *
 * <p>This is test-only diagnostic scaffolding. It is never on a hot path, so process startup cost
 * is irrelevant, and it degrades to a readable note rather than an exception when {@code javap} is
 * unavailable (a JRE-only environment, or a locked-down CI sandbox).
 */
public final class Javap {

    /** Generated methods can be large; cap output so a runaway listing cannot swamp a test report. */
    private static final int MAX_OUTPUT_CHARS = 200_000;

    private static final long TIMEOUT_SECONDS = 30;

    private Javap() {
    }

    /**
     * Disassembles the given class bytes, returning a note instead of throwing on any failure.
     *
     * <p>Intended for use inside assertion messages, where the disassembly is a nice-to-have and
     * must never become the reason a test fails. If {@code javap} cannot run, or the bytes are so
     * malformed that even {@code javap} gives up, the caller still gets its original error.
     *
     * @param classBytes the class file to disassemble
     * @return a {@code javap -c -p -v} listing, or an explanatory note
     */
    public static String disassembleQuietly(byte[] classBytes) {
        try {
            return disassemble(classBytes);
        } catch (Exception | Error e) {
            return "[javap unavailable: " + e + "; " + classBytes.length + " bytes of class data]";
        }
    }

    /**
     * Disassembles the given class bytes.
     *
     * @param classBytes the class file to disassemble
     * @return a {@code javap -c -p -v} listing
     * @throws IOException          if {@code javap} cannot be located or run
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public static String disassemble(byte[] classBytes) throws IOException, InterruptedException {
        Path javap = locateJavap();
        Path temp = Files.createTempFile("classwright-disassembly-", ".class");
        try {
            Files.write(temp, classBytes);
            // -c bytecode, -p include private members, -v constant pool + stack maps + attributes.
            // The stack map frames in -v output are what make Phase 1 frame bugs legible.
            Process process = new ProcessBuilder(
                    List.of(javap.toString(), "-c", "-p", "-v", temp.toString()))
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes());
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "[javap timed out after " + TIMEOUT_SECONDS + "s]";
            }
            return truncate(output);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Finds {@code javap} inside the running JDK.
     *
     * <p>Resolved from {@code java.home} rather than {@code PATH} so that the disassembler always
     * matches the JVM running the tests. On a machine with several JDKs installed &mdash; which is
     * the normal case, and is true of this project's own development environment &mdash; a
     * {@code PATH} lookup would silently disassemble with the wrong version's tool.
     */
    private static Path locateJavap() throws IOException {
        String executable = isWindows() ? "javap.exe" : "javap";
        Path candidate = Path.of(System.getProperty("java.home"), "bin", executable);
        if (!Files.isExecutable(candidate)) {
            throw new IOException("no javap at " + candidate + " (JRE-only runtime?)");
        }
        return candidate;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static String truncate(String output) {
        if (output.length() <= MAX_OUTPUT_CHARS) {
            return output;
        }
        return output.substring(0, MAX_OUTPUT_CHARS)
                + "\n[... truncated, " + output.length() + " chars total]";
    }
}
