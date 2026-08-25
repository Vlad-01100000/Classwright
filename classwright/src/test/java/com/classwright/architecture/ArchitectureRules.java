package com.classwright.architecture;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The rules that keep Classwright from repeating CGLib's history.
 *
 * <p>Every rule here encodes a specific, documented failure mode from {@code docs/RESEARCH.md}. They
 * are expressed as automated checks rather than as guidance in a contributing guide because the
 * decisions they protect are easy to erode one pull request at a time, and because the consequences
 * of eroding them do not show up until a JDK release three years later. A build failure today is a
 * much cheaper teacher.
 *
 * <p>Each rule is a pure function from a {@link SourceUnit} to a list of {@link Violation}s, which
 * makes them directly unit-testable against deliberately-bad inputs. That matters: a rule engine
 * that has never been shown to reject anything is indistinguishable from one that does nothing.
 */
public final class ArchitectureRules {

    /**
     * Packages that must never appear in shipped code.
     *
     * <p>{@code sun}, {@code com.sun}, and {@code jdk.internal} are JDK internals. Reaching into
     * them is what made CGLib stop working when JDK 16 turned on strong encapsulation by default:
     * code that had quietly relied on {@code setAccessible} against {@code ClassLoader} and
     * {@code Unsafe} went from "warns" to "fails" with no path forward.
     *
     * <p>{@code org.objectweb} (ASM), {@code net.bytebuddy}, and {@code javassist} are the bytecode
     * libraries we exist in order not to need. Classwright's whole value proposition is that its
     * release cadence is not coupled to anyone else's.
     */
    public static final Pattern FORBIDDEN_PACKAGES = Pattern.compile(
            "\\b(?:sun|com\\.sun|jdk\\.internal|org\\.objectweb|net\\.bytebuddy|javassist)"
                    + "\\.[A-Za-z_$]");

    /**
     * The one package permitted to touch unstable APIs.
     *
     * <p>Some optional features genuinely cannot be built on public API &mdash; constructing an
     * object without running a constructor is the standing example, and the JDK has said outright
     * that a supported replacement is a long-term project. The answer is not to pretend otherwise,
     * but to confine such code to a single package reached only through an interface, so that when
     * the API disappears one feature degrades instead of the library dying.
     *
     * @see <a href="https://openjdk.org/jeps/498">JEP 498</a>
     */
    public static final String UNSTABLE_API_QUARANTINE = "com.classwright.runtime.unsafe";

    /**
     * Signs that code is trying to read a class file.
     *
     * <p>Classwright writes bytecode and never reads it. That single constraint is what decouples
     * the library from the class-file format version, and therefore from the JDK release cadence: a
     * parser must be taught every new format, and a parser that falls behind is how CGLib died.
     *
     * <p>This check is a tripwire, not a proof. It looks for the mechanisms one would actually use
     * to obtain class bytes; someone determined to parse a class file could evade it. Its job is to
     * make an accidental drift loud, and to force anyone doing this on purpose to state so
     * explicitly by editing this rule.
     */
    public static final Pattern CLASS_FILE_READING = Pattern.compile(
            "\\b(?:getResourceAsStream|getSystemResourceAsStream|ClassReader|ClassFile\\s*\\.\\s*of)\\b");

    /**
     * Which package layer may depend on which.
     *
     * <p>Keys and values are the segment following {@code com.classwright}; the empty string is the
     * root package. A layer may always use itself. Any package not listed here is a violation by
     * default, so adding a new top-level package is a deliberate act rather than an accident.
     *
     * <p>Layering runs strictly one way: the bytecode engine knows nothing about proxies, and the
     * proxy layer knows nothing about beans. Enforcing that keeps the engine independently
     * testable and independently reusable, and prevents the tangle that makes a mature codebase
     * impossible to change.
     */
    public static final Map<String, Set<String>> ALLOWED_LAYER_DEPENDENCIES = Map.of(
            "", Set.of(),                                            // com.classwright: base types
            "core", Set.of(""),                                      // bytecode engine
            "runtime", Set.of("", "core"),                           // class definition + lifecycle
            // MethodProxy.invoke dispatches through FastClass — the same shape CGLib used, and
            // the reason proxy may reach reflect. reflect must never reach back into proxy.
            "proxy", Set.of("", "core", "runtime", "reflect"),       // Enhancer and callbacks
            "reflect", Set.of("", "core", "runtime"),                // FastClass and delegates
            // ImmutableBean is built from Enhancer rather than its own generator, which is why
            // beans is allowed to reach proxy. Nothing else here does.
            "beans", Set.of("", "core", "runtime", "reflect", "proxy"),
            "generated", Set.of());                                  // relocated generated classes

    private static final String OWN_PACKAGE_ROOT = "com.classwright";

    private static final Pattern OWN_PACKAGE_REFERENCE =
            Pattern.compile("\\bcom\\.classwright(?:\\.([A-Za-z_$][\\w$]*))?");

    private ArchitectureRules() {
    }

    /**
     * A single rule breach, located precisely enough to fix.
     *
     * @param file   the offending source file
     * @param line   1-based line number
     * @param rule   short rule identifier, for grouping in reports
     * @param detail what was found and why it is not allowed
     */
    public record Violation(Path file, int line, String rule, String detail) {

        @Override
        public String toString() {
            return file.getFileName() + ":" + line + "  [" + rule + "] " + detail;
        }
    }

    /**
     * Applies every rule to every unit.
     *
     * @param units the source files to check
     * @return all violations found, in file order
     */
    public static List<Violation> checkAll(List<SourceUnit> units) {
        List<Violation> violations = new ArrayList<>();
        for (SourceUnit unit : units) {
            violations.addAll(noForbiddenPackages(unit));
            violations.addAll(noClassFileReading(unit));
            violations.addAll(layeringRespected(unit));
        }
        return List.copyOf(violations);
    }

    /**
     * Rejects references to JDK internals and to competing bytecode libraries.
     *
     * <p>Code inside {@link #UNSTABLE_API_QUARANTINE} is exempt, by design.
     *
     * @param unit the file to check
     * @return violations, if any
     */
    public static List<Violation> noForbiddenPackages(SourceUnit unit) {
        if (unit.packageName().equals(UNSTABLE_API_QUARANTINE)) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        Matcher matcher = FORBIDDEN_PACKAGES.matcher(unit.code());
        while (matcher.find()) {
            violations.add(new Violation(unit.path(), unit.lineOf(matcher.start()),
                    "forbidden-package",
                    "references '" + matcher.group().trim() + "...'. Shipped code must use only "
                            + "public JDK API; unstable APIs belong in " + UNSTABLE_API_QUARANTINE
                            + " behind a capability interface."));
        }
        return List.copyOf(violations);
    }

    /**
     * Rejects the mechanisms used to obtain and parse class bytes.
     *
     * @param unit the file to check
     * @return violations, if any
     */
    public static List<Violation> noClassFileReading(SourceUnit unit) {
        List<Violation> violations = new ArrayList<>();
        Matcher matcher = CLASS_FILE_READING.matcher(unit.code());
        while (matcher.find()) {
            violations.add(new Violation(unit.path(), unit.lineOf(matcher.start()),
                    "no-class-file-reading",
                    "uses '" + matcher.group() + "'. Classwright introspects via core reflection "
                            + "and never reads class files; that is what decouples it from the "
                            + "class-file format version."));
        }
        return List.copyOf(violations);
    }

    /**
     * Rejects dependencies that run against the declared layering.
     *
     * @param unit the file to check
     * @return violations, if any
     */
    public static List<Violation> layeringRespected(SourceUnit unit) {
        if (!unit.packageName().equals(OWN_PACKAGE_ROOT)
                && !unit.packageName().startsWith(OWN_PACKAGE_ROOT + ".")) {
            return List.of();   // not our code; layering says nothing about it
        }
        String fromLayer = layerOf(unit.packageName());
        Set<String> allowed = ALLOWED_LAYER_DEPENDENCIES.get(fromLayer);
        if (allowed == null) {
            return List.of(new Violation(unit.path(), 1, "unknown-layer",
                    "package '" + unit.packageName() + "' is not listed in "
                            + "ALLOWED_LAYER_DEPENDENCIES. Add it deliberately, declaring what it "
                            + "may depend on."));
        }

        List<Violation> violations = new ArrayList<>();
        Matcher matcher = OWN_PACKAGE_REFERENCE.matcher(unit.code());
        while (matcher.find()) {
            String toLayer = matcher.group(1) == null ? "" : matcher.group(1);
            if (!ALLOWED_LAYER_DEPENDENCIES.containsKey(toLayer)) {
                continue;   // a class in the root package, not a sub-package: e.g. com.classwright.Foo
            }
            if (toLayer.equals(fromLayer) || allowed.contains(toLayer)) {
                continue;
            }
            violations.add(new Violation(unit.path(), unit.lineOf(matcher.start()),
                    "layering",
                    "layer '" + describeLayer(fromLayer) + "' may not depend on '"
                            + describeLayer(toLayer) + "'. Allowed: "
                            + allowed.stream().map(ArchitectureRules::describeLayer).sorted()
                            .collect(Collectors.joining(", ", "[", "]"))));
        }
        return List.copyOf(violations);
    }

    /**
     * Extracts the layer name from a package, e.g. {@code com.classwright.core.pool} to
     * {@code core}.
     *
     * @param packageName a package inside {@code com.classwright}
     * @return the layer segment, or the empty string for the root package
     */
    static String layerOf(String packageName) {
        if (packageName.equals(OWN_PACKAGE_ROOT)) {
            return "";
        }
        String remainder = packageName.substring(OWN_PACKAGE_ROOT.length() + 1);
        int dot = remainder.indexOf('.');
        return dot < 0 ? remainder : remainder.substring(0, dot);
    }

    private static String describeLayer(String layer) {
        return layer.isEmpty() ? OWN_PACKAGE_ROOT : OWN_PACKAGE_ROOT + "." + layer;
    }

    /**
     * Formats violations for an assertion message.
     *
     * @param violations the violations to render
     * @return a human-readable, one-per-line report
     */
    public static String report(List<Violation> violations) {
        return violations.stream().map(Violation::toString)
                .collect(Collectors.joining("\n  ", "\n  ", "\n"));
    }
}
