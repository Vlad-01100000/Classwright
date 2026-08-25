package com.classwright.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One Java source file, prepared for rule checking.
 *
 * <p>Holds the original text (for reporting) alongside the comment- and literal-stripped text (for
 * matching), plus the declared package. Rules match against {@link #code()} and report positions
 * that are valid in the original file, because {@link JavaSourceText} preserves offsets.
 *
 * @param path         the file's location on disk, used in violation messages
 * @param originalText the file exactly as written
 * @param code         the same text with comments and literals blanked out
 * @param packageName  the declared package, or the empty string for the default package
 */
public record SourceUnit(Path path, String originalText, String code, String packageName) {

    private static final Pattern PACKAGE_DECLARATION =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    /**
     * Prepares a unit from raw source text.
     *
     * @param path   the file's location, used only for reporting; need not exist
     * @param source the Java source
     * @return a unit ready for rule checking
     */
    public static SourceUnit of(Path path, String source) {
        String code = JavaSourceText.stripCommentsAndLiterals(source);
        Matcher matcher = PACKAGE_DECLARATION.matcher(code);
        String packageName = matcher.find() ? matcher.group(1) : "";
        return new SourceUnit(path, source, code, packageName);
    }

    /**
     * Reads and prepares every {@code .java} file under the given root.
     *
     * <p>{@code module-info.java} and {@code package-info.java} are included deliberately: they
     * carry {@code requires} and {@code import} directives that are just as capable of violating an
     * architecture rule as ordinary code, and are easy to forget about.
     *
     * @param root a source root such as {@code src/main/java}
     * @return every source file beneath it, in stable path order
     * @throws UncheckedIOException if the tree cannot be walked or a file cannot be read
     */
    public static List<SourceUnit> readTree(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .map(SourceUnit::read)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk source tree " + root, e);
        }
    }

    private static SourceUnit read(Path path) {
        try {
            return of(path, Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    /**
     * Converts a character offset in {@link #code()} to a 1-based line number.
     *
     * @param offset an index into the stripped text
     * @return the corresponding line number in the original file
     */
    public int lineOf(int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < code.length(); i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** The fully qualified package plus simple file name, for readable messages. */
    public String displayName() {
        return path.getFileName().toString();
    }
}
