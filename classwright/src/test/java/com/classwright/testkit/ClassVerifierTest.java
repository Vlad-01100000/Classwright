package com.classwright.testkit;

import com.classwright.testkit.fixtures.BranchingFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the verification harness both accepts good bytecode and rejects bad bytecode.
 *
 * <p>The second half is the point. A harness that only ever says "yes" would let every Phase 1 bug
 * through while looking like a passing test suite, so each rejection path is exercised against a
 * specifically-broken input.
 */
class ClassVerifierTest {

    @Test
    @DisplayName("accepts a class compiled by javac, and links it well enough to run")
    void acceptsGoodBytes() throws Exception {
        Class<?> loaded = ClassVerifier.assertVerifies(fixtureBytes());

        assertEquals(BranchingFixture.class.getName(), loaded.getName());
        // Loaded in a throwaway loader, so it is a *different* Class than the one on our classpath.
        assertFalse(loaded == BranchingFixture.class,
                "fixture should be defined in the throwaway loader, not resolved from the classpath");
        assertEquals(9, loaded.getMethod("max", int.class, int.class).invoke(null, 9, 4));
    }

    @Test
    @DisplayName("rejects bytes that are not a class file at all")
    void rejectsBadMagic() {
        byte[] corrupted = fixtureBytes();
        corrupted[0] = 0x00;   // 0xCAFEBABE no longer

        ClassFormatError error = ClassVerifier.assertRejects(ClassFormatError.class, corrupted);
        assertNotNull(error.getMessage());
    }

    @Test
    @DisplayName("rejects a class file from a future JDK")
    void rejectsUnsupportedVersion() {
        byte[] futuristic = fixtureBytes();
        futuristic[6] = 0x00;
        futuristic[7] = (byte) 99;   // major version 99: some JDK we have never heard of

        ClassVerifier.assertRejects(UnsupportedClassVersionError.class, futuristic);
    }

    @Test
    @DisplayName("rejects a truncated class file")
    void rejectsTruncated() {
        byte[] full = fixtureBytes();
        byte[] truncated = Arrays.copyOf(full, full.length / 2);

        ClassVerifier.assertRejects(ClassFormatError.class, truncated);
    }

    @Test
    @DisplayName("rejects a branching method whose stack map frames have gone missing")
    void rejectsMissingStackMapFrames() {
        VerifyError error = ClassVerifier.assertRejects(VerifyError.class, withoutStackMaps());

        assertTrue(error.getMessage().contains("stackmap"),
                () -> "expected a stack map complaint, got: " + error.getMessage());
    }

    @Test
    @DisplayName("failure messages carry a disassembly, so a VerifyError is actionable")
    void failureMessagesIncludeDisassembly() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> ClassVerifier.assertVerifies(withoutStackMaps()));

        // Without this, a VerifyError gives a bytecode offset and nothing else. With it, the
        // listing is right there in the test output. This is the single highest-value line in the
        // harness for Phase 1 debugging.
        assertTrue(failure.getMessage().contains("Code:")
                        || failure.getMessage().contains("[javap unavailable"),
                () -> "expected javap output in the failure message, got:\n" + failure.getMessage());
    }

    @Test
    @DisplayName("reports when bytes were expected to fail but did not")
    void complainsWhenBadBytesAreAccepted() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> ClassVerifier.assertRejects(VerifyError.class, fixtureBytes()));

        assertTrue(failure.getMessage().contains("accepted"), failure.getMessage());
    }

    /**
     * Breaks stack map verification without needing a class-file parser.
     *
     * <p>Attributes are located by name, and JVMS requires that attributes a JVM does not recognise
     * be silently ignored. So renaming the {@code StackMapTable} constant-pool entry to something
     * unrecognised makes the attribute invisible: the branching method in the fixture then has no
     * frames, and the verifier rejects it exactly as it would reject a Phase 1 engine that forgot
     * to emit them.
     *
     * <p>The replacement is the same length as the original, so every offset and length in the file
     * stays valid. That is what makes this a one-line mutation rather than a rewrite.
     */
    private static byte[] withoutStackMaps() {
        return replaceAscii(fixtureBytes(), "StackMapTable", "StackMapTabIe");
    }

    private static byte[] fixtureBytes() {
        String resource = "/" + BranchingFixture.class.getName().replace('.', '/') + ".class";
        try (InputStream in = ClassVerifierTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("fixture not on the test classpath: " + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Replaces the first occurrence of an ASCII needle; both must be the same length. */
    private static byte[] replaceAscii(byte[] haystack, String needle, String replacement) {
        if (needle.length() != replacement.length()) {
            throw new IllegalArgumentException("replacement must preserve length");
        }
        byte[] target = needle.getBytes(StandardCharsets.US_ASCII);
        byte[] with = replacement.getBytes(StandardCharsets.US_ASCII);
        byte[] result = haystack.clone();

        outer:
        for (int i = 0; i <= result.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (result[i + j] != target[j]) {
                    continue outer;
                }
            }
            System.arraycopy(with, 0, result, i, with.length);
            return result;
        }
        throw new IllegalStateException("'" + needle + "' not found; did the fixture lose its branch?");
    }
}
