package com.classwright.architecture;

import com.classwright.architecture.ArchitectureRules.Violation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applies the architecture rules to the real source tree.
 *
 * <p>The integration layer of the pyramid. {@link ArchitectureRulesTest} proves the detectors work
 * by feeding them deliberately-broken input; this class points those same proven detectors at the
 * actual shipped code. Both halves are needed: detectors that are never tested against bad input
 * may not detect anything, and detectors that are never run against real input protect nothing.
 *
 * <p>Runs under Failsafe rather than Surefire because it depends on the project layout on disk
 * rather than only on the classpath.
 */
class ArchitectureRulesIT {

    /** Failsafe runs with the module directory as the working directory. */
    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    /** The module descriptor is compiled separately; see the POM for why. */
    private static final Path MODULE_SOURCES = Path.of("src", "main", "module");

    private static final Path POM = Path.of("pom.xml");

    @Test
    @DisplayName("shipped source obeys every architecture rule")
    void sourceTreeIsClean() {
        List<SourceUnit> units = new java.util.ArrayList<>(SourceUnit.readTree(MAIN_SOURCES));
        units.addAll(SourceUnit.readTree(MODULE_SOURCES));
        assertFalse(units.isEmpty(), "found no sources under " + MAIN_SOURCES.toAbsolutePath());

        List<Violation> violations = ArchitectureRules.checkAll(units);

        assertTrue(violations.isEmpty(), () -> """
                Architecture rules violated. These rules exist because each of them corresponds to \
                a specific reason CGLib stopped working; see docs/RESEARCH.md.
                %s""".formatted(ArchitectureRules.report(violations)));
    }

    @Test
    @DisplayName("the published artifact declares no runtime dependencies")
    void pomShipsNothing() {
        List<String> offenders = PomScopes.runtimeDependencies(read(POM));

        assertTrue(offenders.isEmpty(), () -> """
                classwright must ship with zero runtime dependencies. A shipped dependency is what \
                fragmented CGLib into cglib / cglib-nodep / spring-cglib and made shaded ASM \
                collide with application code.
                  %s""".formatted(String.join("\n  ", offenders)));
    }

    @Test
    @DisplayName("the module declaration exists and requires nothing but java.base")
    void moduleRequiresNothing() {
        Path moduleInfo = MODULE_SOURCES.resolve("module-info.java");
        assertTrue(Files.exists(moduleInfo), "a published library should be a proper JPMS module");

        String code = JavaSourceText.stripCommentsAndLiterals(read(moduleInfo));

        // java.base is implicit and never written; any other `requires` is a real dependency, and
        // `requires static` still couples us to something's continued existence.
        assertFalse(code.contains("requires"), () -> """
                module-info.java declares a `requires`. Classwright depends on java.base only, \
                which is implicit. Found:
                %s""".formatted(code.strip()));
        assertTrue(code.contains("exports com.classwright;"),
                "the root package must be exported");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "cannot read " + path.toAbsolutePath() + " (wrong working directory?)", e);
        }
    }
}
