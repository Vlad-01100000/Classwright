package com.classwright.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the lexer the architecture rules depend on.
 *
 * <p>This is the component most likely to fail silently. If stripping is too aggressive it hides
 * real violations; if too timid it flags documentation and someone eventually "fixes" the build by
 * deleting the explanation. Neither failure announces itself, so both are tested explicitly.
 */
class JavaSourceTextTest {

    @Test
    @DisplayName("blanks line comments")
    void stripsLineComments() {
        String code = strip("int x = 1; // uses sun.misc.Unsafe\n");

        assertFalse(code.contains("sun.misc"));
        assertTrue(code.contains("int x = 1;"));
    }

    @Test
    @DisplayName("blanks block and javadoc comments")
    void stripsBlockComments() {
        String code = strip("""
                /**
                 * Explains why sun.misc.Unsafe is forbidden.
                 */
                int x = 1;
                """);

        assertFalse(code.contains("sun.misc"));
        assertTrue(code.contains("int x = 1;"));
    }

    @Test
    @DisplayName("blanks string literals")
    void stripsStringLiterals() {
        String code = strip("String s = \"org.objectweb.asm.ClassWriter\";");

        assertFalse(code.contains("org.objectweb"));
        assertTrue(code.contains("String s ="));
    }

    @Test
    @DisplayName("blanks text blocks")
    void stripsTextBlocks() {
        String code = strip("var s = \"\"\"\n    jdk.internal.misc.Unsafe\n    \"\"\";\n");

        assertFalse(code.contains("jdk.internal"));
        assertTrue(code.contains("var s ="));
    }

    @Test
    @DisplayName("blanks character literals, including escaped quotes")
    void stripsCharLiterals() {
        String code = strip("char q = '\\''; char s = '/'; int keep = 1;");

        assertTrue(code.contains("char q ="));
        assertTrue(code.contains("int keep = 1;"), "a '/' char literal must not start a comment");
    }

    @Test
    @DisplayName("an escaped quote does not end a string early")
    void handlesEscapedQuotesInStrings() {
        String code = strip("String s = \"a\\\"sun.misc\\\"b\"; int keep = 1;");

        assertFalse(code.contains("sun.misc"));
        assertTrue(code.contains("int keep = 1;"));
    }

    @Test
    @DisplayName("a comment marker inside a string is not a comment")
    void commentMarkersInsideStringsAreInert() {
        String code = strip("String s = \"/* not a comment */\"; int keep = 1;");

        assertTrue(code.contains("int keep = 1;"));
    }

    @Test
    @DisplayName("a string marker inside a comment is not a string")
    void stringMarkersInsideCommentsAreInert() {
        String code = strip("/* \" */ int keep = 1;");

        assertTrue(code.contains("int keep = 1;"));
    }

    @Test
    @DisplayName("preserves length and line numbering exactly")
    void preservesOffsetsAndLines() {
        String source = """
                // sun.misc
                int a = 1;
                /* com.sun */
                int b = 2;
                """;
        String code = strip(source);

        assertEquals(source.length(), code.length(), "offsets must stay valid for line reporting");
        assertEquals(source.lines().count(), code.lines().count());

        SourceUnit unit = SourceUnit.of(java.nio.file.Path.of("X.java"), source);
        assertEquals(2, unit.lineOf(code.indexOf("int a")));
        assertEquals(4, unit.lineOf(code.indexOf("int b")));
    }

    @Test
    @DisplayName("leaves ordinary code untouched")
    void leavesCodeAlone() {
        String source = "int a = b / c; int d = e / f;";

        assertEquals(source, strip(source));
    }

    @Test
    @DisplayName("does not run off the end of an unterminated construct")
    void survivesMalformedInput() {
        // Not legal Java, but the scanner must not hang or throw on it.
        strip("String s = \"unterminated");
        strip("/* unterminated");
        strip("'");
        strip("\"\"\"");
    }

    private static String strip(String source) {
        return JavaSourceText.stripCommentsAndLiterals(source);
    }
}
