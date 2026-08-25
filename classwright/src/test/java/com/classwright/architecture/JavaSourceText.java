package com.classwright.architecture;

/**
 * Blanks out comments and literals in Java source, leaving only code.
 *
 * <p>The architecture rules work by looking for forbidden text in source files. Without this step
 * they would be unusable, because the places most likely to <em>mention</em> a forbidden API are
 * precisely the places explaining why it is forbidden. This project's own module declaration
 * discusses {@code sun.misc.Unsafe} at length; a naive scanner would flag it and the only way to
 * keep the build green would be to stop documenting the reasoning. That is a bad trade, so the
 * scanner learns to read Java instead.
 *
 * <p>Blanking preserves the exact length of the input and every newline, replacing only the
 * <em>content</em> of comments and literals with spaces. Byte offsets and line numbers in the
 * stripped text therefore still correspond to the original file, so a violation can be reported at
 * the line a human would expect.
 *
 * <p>This is a lexer, not a parser: it understands the four ways Java hides text from the compiler
 * (line comments, block comments, string and character literals, and text blocks) and nothing more.
 * That is all the rules need.
 */
public final class JavaSourceText {

    private JavaSourceText() {
    }

    /**
     * Replaces comment and literal content with spaces, preserving length and line structure.
     *
     * @param source Java source text
     * @return the same text with everything the compiler ignores blanked out
     */
    public static String stripCommentsAndLiterals(String source) {
        char[] out = source.toCharArray();
        int length = source.length();
        int i = 0;

        while (i < length) {
            char c = source.charAt(i);

            if (c == '/' && peekIs(source, i + 1, '/')) {
                i = blankUntilNewline(source, out, i);
            } else if (c == '/' && peekIs(source, i + 1, '*')) {
                i = blankBlockComment(source, out, i);
            } else if (c == '"' && peekIs(source, i + 1, '"') && peekIs(source, i + 2, '"')) {
                i = blankTextBlock(source, out, i);
            } else if (c == '"') {
                i = blankQuoted(source, out, i, '"');
            } else if (c == '\'') {
                i = blankQuoted(source, out, i, '\'');
            } else {
                i++;
            }
        }
        return new String(out);
    }

    /** Line comment: everything up to, but not including, the newline. */
    private static int blankUntilNewline(String source, char[] out, int start) {
        int i = start;
        while (i < source.length() && source.charAt(i) != '\n') {
            blank(out, i++);
        }
        return i;
    }

    /** Block comment: from the opening slash-star through the closing star-slash. */
    private static int blankBlockComment(String source, char[] out, int start) {
        int length = source.length();
        int i = start;
        blank(out, i++);
        blank(out, i++);
        while (i < length && !(source.charAt(i) == '*' && peekIs(source, i + 1, '/'))) {
            blank(out, i++);
        }
        if (i < length) {
            blank(out, i++);   // *
            blank(out, i++);   // /
        }
        return i;
    }

    /**
     * Text block: from the opening triple quote through the closing one.
     *
     * <p>Escapes are honoured so that a {@code \"""} sequence inside the block does not look like a
     * terminator.
     */
    private static int blankTextBlock(String source, char[] out, int start) {
        int length = source.length();
        int i = start;
        for (int q = 0; q < 3 && i < length; q++) {
            blank(out, i++);
        }
        while (i < length) {
            char c = source.charAt(i);
            if (c == '\\') {
                blank(out, i++);
                if (i < length) {
                    blank(out, i++);
                }
                continue;
            }
            if (c == '"' && peekIs(source, i + 1, '"') && peekIs(source, i + 2, '"')) {
                for (int q = 0; q < 3 && i < length; q++) {
                    blank(out, i++);
                }
                return i;
            }
            blank(out, i++);
        }
        return i;
    }

    /** String or character literal, terminated by an unescaped {@code quote}. */
    private static int blankQuoted(String source, char[] out, int start, char quote) {
        int length = source.length();
        int i = start;
        blank(out, i++);
        while (i < length && source.charAt(i) != quote) {
            if (source.charAt(i) == '\\') {
                blank(out, i++);
                if (i < length) {
                    blank(out, i++);
                }
                continue;
            }
            // An unterminated literal cannot span lines in legal Java; bail out rather than
            // swallowing the rest of the file if we are handed something that does not compile.
            if (source.charAt(i) == '\n') {
                return i;
            }
            blank(out, i++);
        }
        if (i < length) {
            blank(out, i++);
        }
        return i;
    }

    /** Blanks one character, but never a newline: line numbers must survive. */
    private static void blank(char[] out, int index) {
        if (out[index] != '\n') {
            out[index] = ' ';
        }
    }

    private static boolean peekIs(String source, int index, char expected) {
        return index < source.length() && source.charAt(index) == expected;
    }
}
