package com.classwright.architecture;

import com.classwright.architecture.ArchitectureRules.Violation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves each architecture rule catches what it claims to catch.
 *
 * <p>Every rule is exercised against source that deliberately breaks it. Those bad examples live
 * here as strings rather than as files in the source tree, for the obvious reason that a real file
 * violating a real rule would make the build permanently red. Keeping them inline means the
 * detectors are demonstrably working without anything ever being genuinely broken.
 *
 * <p>This is the unit layer of the pyramid: pure functions, fast, no filesystem.
 * {@link ArchitectureRulesIT} then applies these proven detectors to the real source tree.
 */
class ArchitectureRulesTest {

    private static SourceUnit unit(String packageName, String body) {
        return SourceUnit.of(Path.of("Example.java"), "package " + packageName + ";\n" + body);
    }

    @Nested
    @DisplayName("forbidden packages")
    class ForbiddenPackages {

        @ParameterizedTest(name = "rejects {0}")
        @ValueSource(strings = {
                "sun.misc.Unsafe",
                "com.sun.tools.attach.VirtualMachine",
                "jdk.internal.misc.Unsafe",
                "org.objectweb.asm.ClassWriter",
                "net.bytebuddy.ByteBuddy",
                "javassist.ClassPool",
        })
        @DisplayName("flags a reference to an internal or competing API")
        void rejectsForbiddenReference(String forbidden) {
            List<Violation> violations = ArchitectureRules.noForbiddenPackages(
                    unit("com.classwright.core", "import " + forbidden + ";"));

            assertEquals(1, violations.size(), () -> "expected to flag " + forbidden);
            assertEquals("forbidden-package", violations.get(0).rule());
        }

        @Test
        @DisplayName("flags fully-qualified use even without an import")
        void rejectsInlineFullyQualifiedUse() {
            List<Violation> violations = ArchitectureRules.noForbiddenPackages(
                    unit("com.classwright.core", "class X { sun.misc.Unsafe u; }"));

            assertEquals(1, violations.size());
        }

        @Test
        @DisplayName("permits the quarantine package, which exists for exactly this")
        void permitsQuarantinePackage() {
            List<Violation> violations = ArchitectureRules.noForbiddenPackages(
                    unit(ArchitectureRules.UNSTABLE_API_QUARANTINE, "import sun.misc.Unsafe;"));

            assertTrue(violations.isEmpty(),
                    "the quarantine package is the one place unstable APIs are allowed");
        }

        @Test
        @DisplayName("ignores forbidden names mentioned in comments and strings")
        void ignoresDocumentationAndLiterals() {
            SourceUnit documented = unit("com.classwright.core", """
                    /** CGLib failed because it shaded org.objectweb.asm and used sun.misc.Unsafe. */
                    class X {
                        // Never reach for jdk.internal.misc here.
                        String note = "com.sun.tools is off limits";
                    }
                    """);

            assertTrue(ArchitectureRules.noForbiddenPackages(documented).isEmpty(),
                    "documenting why an API is banned must not itself be a violation");
        }

        @Test
        @DisplayName("does not flag legitimate packages with similar names")
        void avoidsFalsePositives() {
            SourceUnit innocent = unit("com.classwright.core", """
                    import java.lang.invoke.MethodHandles;
                    import javax.xml.parsers.DocumentBuilder;
                    class X { int sunshine = 1; String s = "sunny"; }
                    """);

            assertTrue(ArchitectureRules.noForbiddenPackages(innocent).isEmpty());
        }

        @Test
        @DisplayName("reports the line the violation is on")
        void reportsLineNumber() {
            List<Violation> violations = ArchitectureRules.noForbiddenPackages(
                    unit("com.classwright.core", "\n\nimport sun.misc.Unsafe;"));

            assertEquals(4, violations.get(0).line());   // 1 package + 2 blank + the import
        }
    }

    @Nested
    @DisplayName("no class-file reading")
    class NoClassFileReading {

        @ParameterizedTest(name = "rejects {0}")
        @ValueSource(strings = {
                "in = X.class.getResourceAsStream(\"/a.class\");",
                "in = ClassLoader.getSystemResourceAsStream(name);",
                "var r = new ClassReader(bytes);",
        })
        @DisplayName("flags the mechanisms used to obtain class bytes")
        void rejectsClassByteReading(String statement) {
            List<Violation> violations = ArchitectureRules.noClassFileReading(
                    unit("com.classwright.core", "class X { void m() { " + statement + " } }"));

            assertEquals(1, violations.size());
            assertEquals("no-class-file-reading", violations.get(0).rule());
        }

        @Test
        @DisplayName("permits ordinary reflection, which is how we are supposed to introspect")
        void permitsReflection() {
            SourceUnit reflective = unit("com.classwright.core", """
                    class X {
                        void m(Class<?> c) {
                            for (var method : c.getDeclaredMethods()) {
                                var params = method.getParameterTypes();
                            }
                        }
                    }
                    """);

            assertTrue(ArchitectureRules.noClassFileReading(reflective).isEmpty());
        }
    }

    @Nested
    @DisplayName("package layering")
    class Layering {

        @Test
        @DisplayName("permits a downward dependency")
        void permitsDownwardDependency() {
            List<Violation> violations = ArchitectureRules.layeringRespected(
                    unit("com.classwright.proxy", "import com.classwright.core.Emitter;"));

            assertTrue(violations.isEmpty(), "proxy is allowed to use core");
        }

        @Test
        @DisplayName("rejects an upward dependency")
        void rejectsUpwardDependency() {
            List<Violation> violations = ArchitectureRules.layeringRespected(
                    unit("com.classwright.core", "import com.classwright.proxy.Enhancer;"));

            assertEquals(1, violations.size(), "the bytecode engine must not know about proxies");
            assertEquals("layering", violations.get(0).rule());
        }

        @Test
        @DisplayName("rejects a sideways dependency between peer layers")
        void rejectsSidewaysDependency() {
            List<Violation> violations = ArchitectureRules.layeringRespected(
                    unit("com.classwright.reflect", "import com.classwright.proxy.Enhancer;"));

            assertEquals(1, violations.size());
        }

        @Test
        @DisplayName("permits use of the root package from anywhere")
        void permitsRootPackage() {
            List<Violation> violations = ArchitectureRules.layeringRespected(
                    unit("com.classwright.core", "import com.classwright.ClasswrightException;"));

            assertTrue(violations.isEmpty());
        }

        @Test
        @DisplayName("permits a layer to use its own sub-packages")
        void permitsSelfDependency() {
            List<Violation> violations = ArchitectureRules.layeringRespected(
                    unit("com.classwright.core.pool", "import com.classwright.core.Emitter;"));

            assertTrue(violations.isEmpty());
        }

        @Test
        @DisplayName("flags an unrecognised package rather than silently allowing it")
        void rejectsUnknownLayer() {
            List<Violation> violations = ArchitectureRules.layeringRespected(
                    unit("com.classwright.experimental", "class X {}"));

            assertEquals(1, violations.size(), "a new top-level package must be declared on purpose");
            assertEquals("unknown-layer", violations.get(0).rule());
        }

        @Test
        @DisplayName("says nothing about code outside com.classwright")
        void ignoresForeignCode() {
            assertTrue(ArchitectureRules.layeringRespected(
                    unit("org.example.app", "import com.classwright.proxy.Enhancer;")).isEmpty());
        }

        @Test
        @DisplayName("extracts the layer from a package name")
        void extractsLayer() {
            assertEquals("", ArchitectureRules.layerOf("com.classwright"));
            assertEquals("core", ArchitectureRules.layerOf("com.classwright.core"));
            assertEquals("core", ArchitectureRules.layerOf("com.classwright.core.pool.internal"));
        }
    }

    @Test
    @DisplayName("checkAll aggregates every rule")
    void checkAllAggregates() {
        SourceUnit bad = unit("com.classwright.core", """
                import sun.misc.Unsafe;
                import com.classwright.proxy.Enhancer;
                class X { void m() { var s = Y.class.getResourceAsStream("/a.class"); } }
                """);

        List<Violation> violations = ArchitectureRules.checkAll(List.of(bad));

        assertEquals(3, violations.size(), () -> ArchitectureRules.report(violations));
        assertTrue(ArchitectureRules.report(violations).contains("forbidden-package"));
        assertTrue(ArchitectureRules.report(violations).contains("layering"));
        assertTrue(ArchitectureRules.report(violations).contains("no-class-file-reading"));
    }
}
