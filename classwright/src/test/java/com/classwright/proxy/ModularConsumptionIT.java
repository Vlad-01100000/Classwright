package com.classwright.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Consumes the built jar as a named JPMS module, the way a module-path application would.
 *
 * <p>Every other test runs on the classpath, where Classwright itself sits in the unnamed module
 * and {@code Module.canRead} is vacuously true — which is exactly why a broken read edge toward
 * unnamed <em>target</em> modules cannot be seen there. This child JVM puts the jar on
 * {@code --module-path} and the application (see {@code cwtest.modular.ModularChild}) on the
 * classpath, and requires the hidden-class path to work with no flags at all.
 */
class ModularConsumptionIT {

    @Test
    @DisplayName("proxies work, hidden, with Classwright as a named module")
    void proxiesWorkWithClasswrightOnTheModulePath() throws Exception {
        Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--module-path", builtJar().toAbsolutePath().toString(),
                "--add-modules", "com.classwright",
                "-cp", Path.of("target", "test-classes").toAbsolutePath().toString(),
                "cwtest.modular.ModularChild")
                .redirectErrorStream(true)
                .start();

        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(child.waitFor(2, TimeUnit.MINUTES), "the child JVM did not finish:\n" + output);
        assertEquals(0, child.exitValue(),
                "proxying failed with Classwright as a named module:\n" + output);
        assertTrue(output.contains("subclass proxy works on the module path"), output);
        assertTrue(output.contains("interface proxy works on the module path"), output);
        // Hidden definition beside an unnamed-module target is impossible by JPMS design (a
        // cross-module privateLookupIn drops MODULE, and defineHiddenClass demands full
        // privilege), so today's correct answer is the child-loader fallback. Pinned so that a
        // strategy that someday does better — a hidden class beside Classwright itself, say —
        // has to update this expectation consciously.
        assertTrue(output.contains("definition path: child loader"), output);
    }

    /** The main artifact under {@code target/}; failsafe runs after {@code package} built it. */
    private static Path builtJar() throws IOException {
        try (var entries = Files.list(Path.of("target"))) {
            return entries
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("classwright-") && name.endsWith(".jar")
                                && !name.contains("-sources") && !name.contains("-javadoc");
                    })
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no classwright jar under target/; "
                            + "integration tests must run after the package phase"));
        }
    }
}
