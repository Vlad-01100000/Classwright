package com.classwright.proxy;

import com.classwright.ClasswrightException;
import com.classwright.proxy.fixtures.Service;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.classwright.testkit.ClassVerifier.assertVerifies;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Build-time proxy generation.
 *
 * <p>The runtime half &mdash; that {@code Enhancer} actually adopts what this produces &mdash; is
 * in {@code AheadOfTimeIT}, which needs a separate JVM to test honestly.
 */
class AheadOfTimeTest {

    private static ProxyBlueprint serviceBlueprint() {
        return ProxyBlueprint.of(Service.class)
                .callbacks(MethodInterceptor.class)
                .build();
    }

    @Test
    @DisplayName("generates a class file the JVM accepts")
    void generatesAVerifiableClass() {
        AheadOfTime.Generated generated = AheadOfTime.generate(serviceBlueprint());

        Class<?> loaded = assertVerifies(generated.classBytes());
        assertEquals(Service.class, loaded.getSuperclass());
        assertTrue(Factory.class.isAssignableFrom(loaded), "should implement Factory by default");
    }

    @Test
    @DisplayName("the class-name digest is sixteen hex characters, not a collision-prone four")
    void digestIsLongEnoughToTrust() {
        String name = serviceBlueprint().generatedClassName();
        String digest = name.substring(name.lastIndexOf('$') + 1);

        assertTrue(digest.matches("[0-9a-f]{16}"),
                "the digest must be 8 bytes (16 hex characters): a 4-byte digest reaches even "
                        + "odds of a collision at ~2^16 blueprints, and a collision is two "
                        + "configurations silently overwriting each other's class file. Got: "
                        + digest + " in " + name);
    }

    @Test
    @DisplayName("interface order is part of the key, because placement and dispatch depend on it")
    void keyPreservesInterfaceOrder() {
        ProxyBlueprint firstThenSecond = ProxyBlueprint.of(Service.class)
                .implementing(First.class, Second.class)
                .callbacks(MethodInterceptor.class)
                .build();
        ProxyBlueprint secondThenFirst = ProxyBlueprint.of(Service.class)
                .implementing(Second.class, First.class)
                .callbacks(MethodInterceptor.class)
                .build();

        assertNotEquals(firstThenSecond.key(), secondThenFirst.key(),
                "the first interface decides where a pure interface proxy is placed and the "
                        + "listed order is the order discovery walks them, so two orderings are "
                        + "two configurations and must not share a key — or a pre-generated "
                        + "class");
    }

    @Test
    @DisplayName("naming conventions that concatenate identically still get distinct keys")
    void keyDoesNotCollideAcrossNamingPairs() {
        ProxyBlueprint sliced = ProxyBlueprint.of(Service.class)
                .callbacks(MethodInterceptor.class)
                .naming(new NamingConvention("$$CWX", "Y$"))
                .build();
        ProxyBlueprint slicedElsewhere = ProxyBlueprint.of(Service.class)
                .callbacks(MethodInterceptor.class)
                .naming(new NamingConvention("$$CW", "XY$"))
                .build();

        assertNotEquals(sliced.key(), slicedElsewhere.key(),
                "suffix and prefix must be separate key components; concatenated, "
                        + "(\"$$CWX\" + \"Y$\") and (\"$$CW\" + \"XY$\") are the same text and "
                        + "two different conventions would share one pre-generated class");
        assertNotEquals(sliced.generatedClassName(), slicedElsewhere.generatedClassName());
    }

    @Test
    @DisplayName("a key can never contain the index format's own structure characters")
    void keyCannotCorruptTheIndex() {
        ProxyBlueprint hostile = ProxyBlueprint.of(Service.class)
                .callbacks(MethodInterceptor.class)
                .naming(new NamingConvention("$$a|b,c", "p\tq\n$"))
                .build();

        String key = hostile.key();
        assertTrue(!key.contains("\t") && !key.contains("\n") && !key.contains("\r"),
                "the key is one field of a tab-separated, line-oriented index; a raw tab or "
                        + "newline from a user-supplied naming convention would corrupt it: "
                        + key);

        // And escaping must stay injective: the same characters placed differently are a
        // different configuration, so they must render as a different key.
        ProxyBlueprint differentlyHostile = ProxyBlueprint.of(Service.class)
                .callbacks(MethodInterceptor.class)
                .naming(new NamingConvention("$$a|b", ",cp\tq\n$"))
                .build();
        assertNotEquals(key, differentlyHostile.key());
    }

    @Test
    @DisplayName("the generated name is in the target's package and carries the $$ marker")
    void generatedNameIsWellFormed() {
        String name = serviceBlueprint().generatedClassName();

        assertTrue(name.startsWith(Service.class.getName() + "$$"),
                "the name must start with the target's own name so framework heuristics that "
                        + "look for $$ and then walk to the superclass keep working: " + name);
        assertEquals(Service.class.getPackageName(), name.substring(0, name.lastIndexOf('.')),
                "an ahead-of-time proxy must land in its target's package to override "
                        + "package-private methods");
    }

    @Test
    @DisplayName("generation is deterministic, so builds stay reproducible")
    void generationIsDeterministic() {
        AheadOfTime.Generated first = AheadOfTime.generate(serviceBlueprint());
        AheadOfTime.Generated second = AheadOfTime.generate(serviceBlueprint());

        assertEquals(first.className(), second.className());
        assertArrayEquals(first.classBytes(), second.classBytes(),
                "identical blueprints must produce byte-identical classes, or the release "
                        + "reproducibility check would fail intermittently");
    }

    @Test
    @DisplayName("configurations that differ get different classes")
    void differentConfigurationsDoNotCollide() {
        ProxyBlueprint plain = serviceBlueprint();
        ProxyBlueprint noFactory = ProxyBlueprint.of(Service.class)
                .callbacks(MethodInterceptor.class)
                .useFactory(false)
                .build();

        assertNotEquals(plain.key(), noFactory.key());
        assertNotEquals(plain.generatedClassName(), noFactory.generatedClassName());
    }

    @Test
    @DisplayName("the key is stable text, not an identity hash")
    void keyIsStableText() {
        String key = serviceBlueprint().key();

        assertTrue(key.startsWith(Service.class.getName() + "|"), key);
        assertTrue(key.contains(MethodInterceptor.class.getName()), key);
        // Recomputed in a fresh JVM at runtime, so nothing about it may depend on this one.
        assertEquals(key, serviceBlueprint().key());
    }

    @Test
    @DisplayName("writes the class, the index and the native-image metadata")
    void writesEverythingNativeImageNeeds(@TempDir Path output) throws Exception {
        List<AheadOfTime.Generated> generated =
                AheadOfTime.writeTo(output, List.of(serviceBlueprint()));

        assertEquals(1, generated.size());
        Path classFile = output.resolve(generated.get(0).relativePath());
        assertTrue(Files.exists(classFile), "the class file was not written: " + classFile);
        assertArrayEquals(generated.get(0).classBytes(), Files.readAllBytes(classFile));

        String index = Files.readString(output.resolve(AheadOfTime.INDEX_RESOURCE));
        assertTrue(index.contains(serviceBlueprint().key() + "\t" + generated.get(0).className()
                        + "\t" + generated.get(0).routingFingerprint()),
                "the index does not map the key to the class and its routing fingerprint:\n"
                        + index);

        Path reflect = output.resolve(
                "META-INF/native-image/com.classwright/classwright/reflect-config.json");
        String config = Files.readString(reflect);
        assertTrue(config.contains("\"name\": \"" + generated.get(0).className() + "\""), config);
        assertTrue(config.contains("\"name\": \"" + Service.class.getName() + "\""),
                "the target needs metadata too: method discovery runs reflectively even on the "
                        + "pre-generated path");
        assertBalancedJson(config);

        assertBalancedJson(Files.readString(output.resolve(
                "META-INF/native-image/com.classwright/classwright/resource-config.json")));
    }

    @Test
    @DisplayName("the reachability metadata is exact, not a blanket allDeclared* registration")
    void reflectConfigIsNarrowedToWhatTheRuntimeTouches(@TempDir Path output) throws Exception {
        ProxyBlueprint filtered = ProxyBlueprint.of(Service.class)
                .callbacks(MethodInterceptor.class, NoOp.class)
                .filteredBy(EverythingToNoOp.class)
                .build();
        List<AheadOfTime.Generated> generated = AheadOfTime.writeTo(output, List.of(filtered));

        String config = Files.readString(output.resolve(
                "META-INF/native-image/com.classwright/classwright/reflect-config.json"));

        // The generated class: CW$init is invoked through a lookup, the fields are enumerated
        // (the method-count gate, callback binding, CW$lookup), and instances come through
        // findConstructor. Everything else it needs is compiled in.
        String proxyEntry = entryFor(config, generated.get(0).className());
        assertTrue(proxyEntry.contains("CW$init"), proxyEntry);
        assertTrue(proxyEntry.contains("\"allDeclaredFields\": true"), proxyEntry);
        assertTrue(proxyEntry.contains("\"allDeclaredConstructors\": true"), proxyEntry);

        // The target is queried, never blanket-registered: discovery enumerates its declared
        // methods and constructors, and only the proxied methods are invocable (interceptors are
        // handed the Method and expect Method.invoke to work; setAccessible must not throw the
        // Error an unregistered member produces in a native image).
        String targetEntry = entryFor(config, Service.class.getName());
        assertTrue(targetEntry.contains("\"queryAllDeclaredMethods\": true"), targetEntry);
        assertTrue(targetEntry.contains("\"queryAllDeclaredConstructors\": true"), targetEntry);
        assertTrue(targetEntry.contains("\"greet\""),
                "proxied methods must be registered for invocation: " + targetEntry);
        assertTrue(!targetEntry.contains("\"allDeclaredMethods\""),
                "the target must not be blanket-registered; that is what defeats the "
                        + "native-image shrinker: " + targetEntry);

        // Discovery walks the whole hierarchy, so its ancestors need querying too.
        assertTrue(config.contains("\"name\": \"java.lang.Object\""),
                "discovery calls getDeclaredMethods() on every hierarchy type, Object included:\n"
                        + config);

        // The filter runs at build time only — the runtime Enhancer already holds an instance —
        // and callback types are compiled into the generated class; nothing reflects on either.
        assertTrue(!config.contains(EverythingToNoOp.class.getName()),
                "the CallbackFilter is never instantiated at runtime and must not be registered:\n"
                        + config);
        assertTrue(!config.contains(MethodInterceptor.class.getName())
                        && !config.contains(NoOp.class.getName()),
                "callback types are field types in compiled code, not reflection targets:\n"
                        + config);
        assertBalancedJson(config);
    }

    /** The one JSON object whose {@code name} is exactly {@code className}. One entry per line. */
    private static String entryFor(String config, String className) {
        int start = config.indexOf("{\"name\": \"" + className + "\"");
        assertTrue(start >= 0, "no reflect-config entry for " + className + " in:\n" + config);
        int end = config.indexOf('\n', start);
        return config.substring(start, end < 0 ? config.length() : end);
    }

    @Test
    @DisplayName("a filter routes methods at build time, as it would at runtime")
    void filtersRouteAtBuildTime() {
        ProxyBlueprint filtered = ProxyBlueprint.of(Service.class)
                .callbacks(MethodInterceptor.class, NoOp.class)
                .filteredBy(EverythingToNoOp.class)
                .build();

        AheadOfTime.Generated generated = AheadOfTime.generate(filtered);

        assertVerifies(generated.classBytes());
        assertTrue(filtered.key().contains(EverythingToNoOp.class.getName()),
                "the filter is part of the identity; two filters must not share a class");
    }

    @Test
    @DisplayName("the routing fingerprint is deterministic, or the runtime could never match it")
    void routingFingerprintIsDeterministic() {
        AheadOfTime.Generated first = AheadOfTime.generate(serviceBlueprint());
        AheadOfTime.Generated second = AheadOfTime.generate(serviceBlueprint());

        assertTrue(first.routingFingerprint().matches("[0-9a-f]{64}"),
                "expected a hex SHA-256: " + first.routingFingerprint());
        assertEquals(first.routingFingerprint(), second.routingFingerprint(),
                "the runtime recomputes this from its own discovery; any nondeterminism here "
                        + "would make every adoption fail");
    }

    @Test
    @DisplayName("two filters that route differently produce different fingerprints")
    void routingDecidesTheFingerprint() {
        AheadOfTime.Generated toNoOp = AheadOfTime.generate(ProxyBlueprint.of(Service.class)
                .callbacks(MethodInterceptor.class, NoOp.class)
                .filteredBy(EverythingToNoOp.class)
                .build());
        AheadOfTime.Generated toInterceptor = AheadOfTime.generate(ProxyBlueprint.of(Service.class)
                .callbacks(MethodInterceptor.class, NoOp.class)
                .filteredBy(EverythingToInterceptor.class)
                .build());

        assertNotEquals(toNoOp.routingFingerprint(), toInterceptor.routingFingerprint(),
                "the fingerprint exists to tell apart routings the key cannot: same callbacks, "
                        + "same shape, different method-to-callback assignment");
    }

    @Test
    @DisplayName("a filter that cannot be built at build time says why")
    void filterNeedsANoArgumentConstructor() {
        ProxyBlueprint blueprint = ProxyBlueprint.of(Service.class)
                .callbacks(MethodInterceptor.class, NoOp.class)
                .filteredBy(NeedsArguments.class)
                .build();

        ClasswrightException failure =
                assertThrows(ClasswrightException.class, () -> AheadOfTime.generate(blueprint));

        assertTrue(failure.getMessage().contains("no-argument constructor"), failure.getMessage());
    }

    @Test
    @DisplayName("listing the same blueprint twice is refused rather than silently overwritten")
    void refusesDuplicates(@TempDir Path output) {
        List<ProxyBlueprint> twice = List.of(serviceBlueprint(), serviceBlueprint());

        ClasswrightException failure = assertThrows(ClasswrightException.class,
                () -> AheadOfTime.writeTo(output, twice));

        assertTrue(failure.getMessage().contains("same class name"), failure.getMessage());
    }

    @Test
    @DisplayName("a blueprint with no callback types is refused")
    void requiresCallbackTypes() {
        ClasswrightException failure = assertThrows(ClasswrightException.class,
                () -> ProxyBlueprint.of(Service.class).build());

        assertTrue(failure.getMessage().contains("callback type"), failure.getMessage());
    }

    /** Braces and brackets balance, which is as much as can be checked without a JSON parser. */
    private static void assertBalancedJson(String json) {
        int braces = 0;
        int brackets = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (inString) {
                continue;
            }
            switch (c) {
                case '{' -> braces++;
                case '}' -> braces--;
                case '[' -> brackets++;
                case ']' -> brackets--;
                default -> { }
            }
        }
        assertEquals(0, braces, "unbalanced braces in:\n" + json);
        assertEquals(0, brackets, "unbalanced brackets in:\n" + json);
        assertTrue(!inString, "unterminated string in:\n" + json);
    }

    /** Sends every method to callback slot 1. */
    public static final class EverythingToNoOp implements CallbackFilter {

        @Override
        public int accept(Method method) {
            return 1;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EverythingToNoOp;
        }

        @Override
        public int hashCode() {
            return EverythingToNoOp.class.hashCode();
        }
    }

    /** Sends every method to callback slot 0: the other routing over the same callbacks. */
    public static final class EverythingToInterceptor implements CallbackFilter {

        @Override
        public int accept(Method method) {
            return 0;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EverythingToInterceptor;
        }

        @Override
        public int hashCode() {
            return EverythingToInterceptor.class.hashCode();
        }
    }

    /** Interfaces whose listed order distinguishes two configurations. */
    public interface First {
    }

    public interface Second {
    }

    /** Cannot be built by the generator, so it must be reported clearly. */
    public static final class NeedsArguments implements CallbackFilter {

        @SuppressWarnings("unused")
        public NeedsArguments(String required) {
        }

        @Override
        public int accept(Method method) {
            return 0;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof NeedsArguments;
        }

        @Override
        public int hashCode() {
            return NeedsArguments.class.hashCode();
        }
    }
}
