package com.classwright.testkit;

import com.classwright.testkit.fixtures.BranchingFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Confirms the disassembler produces useful output and never becomes a source of test failures. */
class JavapTest {

    @Test
    @DisplayName("disassembles a real class, including the stack map frames")
    void disassemblesRealClass() {
        String listing = Javap.disassembleQuietly(fixtureBytes());

        assertTrue(listing.contains("BranchingFixture"), listing);
        assertTrue(listing.contains("Code:"), listing);
        // -v output is what makes frame bugs legible in Phase 1; assert we asked for it.
        assertTrue(listing.contains("StackMapTable") || listing.contains("stack="),
                "expected verbose output with frame information:\n" + listing);
    }

    @Test
    @DisplayName("returns an explanation rather than throwing when handed nonsense")
    void degradesGracefully() {
        // Diagnostics must never be the reason a test fails: the caller is already reporting a
        // real failure and just wants context.
        String listing = Javap.disassembleQuietly(new byte[]{1, 2, 3, 4});

        assertNotNull(listing);
        assertTrue(listing.length() > 0);
    }

    @Test
    @DisplayName("tolerates empty input")
    void handlesEmptyInput() {
        assertNotNull(Javap.disassembleQuietly(new byte[0]));
    }

    private static byte[] fixtureBytes() {
        String resource = "/" + BranchingFixture.class.getName().replace('.', '/') + ".class";
        try (InputStream in = JavapTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "fixture not on the test classpath");
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
