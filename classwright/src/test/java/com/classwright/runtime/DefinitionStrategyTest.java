package com.classwright.runtime;

import com.classwright.core.AccessFlags;
import com.classwright.runtime.fixtures.DefinitionTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests each definition strategy, and in particular the properties that differ between them.
 *
 * <p>The interesting assertions are not "it produced a class" but the trade-offs: can it be found
 * by name, can it override package-private methods, and can it ever be unloaded. Those three
 * decide which strategy is right for a given situation, and each is easy to assume wrongly.
 */
class DefinitionStrategyTest {

    private static DefinitionSite site() {
        return DefinitionSite.of(DefinitionTarget.class);
    }

    @Nested
    @DisplayName("hidden classes (the default)")
    class Hidden {

        @Test
        @DisplayName("defines a working subclass in the target's package and loader")
        void definesWorkingSubclass() throws Throwable {
            DefinedClass defined = DefinitionStrategy.hidden()
                    .define(site(), GeneratedBytes.plainSubclass(DefinitionTarget.class, "H"));

            assertTrue(defined.type().isHidden());
            assertEquals(DefinitionTarget.class, defined.type().getSuperclass());
            assertEquals(DefinitionTarget.class.getPackageName(),
                    defined.type().getPackageName());
            assertSame(DefinitionTarget.class.getClassLoader(),
                    defined.type().getClassLoader(),
                    "no extra class loader should be created");

            Object instance = defined.constructor().invoke();
            assertInstanceOf(DefinitionTarget.class, instance);
            assertEquals("hello", ((DefinitionTarget) instance).greet());
        }

        @Test
        @DisplayName("can override a package-private method, because it is a true package-mate")
        void overridesPackagePrivateMethods() throws Throwable {
            // The payoff of defining into the target's own package and loader. CGLib could only
            // manage this by accident of where its class loader happened to put things.
            DefinedClass defined = DefinitionStrategy.hidden().define(site(),
                    GeneratedBytes.subclassOverriding(DefinitionTarget.class, "HP",
                            "secret", 0 /* package-private */, "overridden"));

            DefinitionTarget instance = (DefinitionTarget) defined.constructor().invoke();

            assertEquals("overridden", instance.callSecret(),
                    "a package-private override should win virtual dispatch");
        }

        @Test
        @DisplayName("is a nestmate of the target, so private members are reachable")
        void joinsTheTargetsNest() {
            DefinedClass defined = DefinitionStrategy.hidden()
                    .define(site(), GeneratedBytes.plainSubclass(DefinitionTarget.class, "HN"));

            assertEquals(DefinitionTarget.class, defined.type().getNestHost());
        }

        @Test
        @DisplayName("cannot be found by name, by design")
        void hasNoResolvableName() {
            DefinedClass defined = DefinitionStrategy.hidden()
                    .define(site(), GeneratedBytes.plainSubclass(DefinitionTarget.class, "HF"));

            assertFalse(defined.hasResolvableName());
            // The name carries a /0x... suffix and is not a valid binary name. Anything doing
            // Class.forName on a proxy's name will fail, which is the documented trade-off.
            assertThrows(ClassNotFoundException.class, () -> Class.forName(defined.type().getName()));
        }

        @Test
        @DisplayName("keeps $$ in the name so framework heuristics still recognise it")
        void keepsTheGeneratedClassMarker() {
            DefinedClass defined = DefinitionStrategy.hidden()
                    .define(site(), GeneratedBytes.plainSubclass(DefinitionTarget.class, "HM"));

            assertTrue(defined.type().getName().contains("$$"));
            assertTrue(defined.type().getName().startsWith(DefinitionTarget.class.getName()));
        }

        @Test
        @DisplayName("reports itself as unloadable")
        void isUnloadable() {
            assertTrue(DefinitionStrategy.hidden().producesUnloadableClasses());
        }
    }

    @Nested
    @DisplayName("named classes")
    class Named {

        @Test
        @DisplayName("defines a class that Class.forName can find")
        void definesResolvableClass() throws Exception {
            DefinedClass defined = DefinitionStrategy.named()
                    .define(site(), GeneratedBytes.plainSubclass(DefinitionTarget.class, "N1"));

            assertFalse(defined.type().isHidden());
            assertTrue(defined.hasResolvableName());
            assertSame(defined.type(), Class.forName(defined.type().getName(), false,
                    defined.type().getClassLoader()));
        }

        @Test
        @DisplayName("also gets package-private override, being a real package-mate")
        void overridesPackagePrivateMethods() throws Throwable {
            DefinedClass defined = DefinitionStrategy.named().define(site(),
                    GeneratedBytes.subclassOverriding(DefinitionTarget.class, "N2",
                            "secret", 0, "overridden"));

            assertEquals("overridden",
                    ((DefinitionTarget) defined.constructor().invoke()).callSecret());
        }

        @Test
        @DisplayName("declares that it never unloads")
        void isNotUnloadable() {
            // Not a limitation to work around: an ordinary class is recorded in its loader's class
            // table permanently. This is exactly CGLib's leak, offered only for tooling that
            // genuinely needs a resolvable name.
            assertFalse(DefinitionStrategy.named().producesUnloadableClasses());
        }

        @Test
        @DisplayName("rejects a duplicate name rather than silently returning the first")
        void rejectsDuplicateDefinition() {
            byte[] bytes = GeneratedBytes.plainSubclass(DefinitionTarget.class, "N3");
            DefinitionStrategy.named().define(site(), bytes);

            assertThrows(RuntimeException.class,
                    () -> DefinitionStrategy.named().define(site(), bytes));
        }
    }

    @Nested
    @DisplayName("child class loader")
    class ChildLoader {

        @Test
        @DisplayName("defines a working subclass without needing any access to the target")
        void definesWorkingSubclass() throws Throwable {
            DefinedClass defined = DefinitionStrategy.childLoader()
                    .define(site(), GeneratedBytes.plainSubclass(DefinitionTarget.class, "C1"));

            assertEquals(DefinitionTarget.class, defined.type().getSuperclass());
            assertEquals("hello",
                    ((DefinitionTarget) defined.constructor().invoke()).greet());
        }

        @Test
        @DisplayName("creates its own loader, so it is not a package-mate")
        void isNotAPackageMate() throws Throwable {
            DefinedClass defined = DefinitionStrategy.childLoader().define(site(),
                    GeneratedBytes.subclassOverriding(DefinitionTarget.class, "C2",
                            "secret", 0, "overridden"));

            assertFalse(DefinitionTarget.class.getClassLoader()
                    == defined.type().getClassLoader());
            // Same package *name*, different loader, therefore a different runtime package. The
            // override is not an override at all; it just sits there.
            assertEquals("original",
                    ((DefinitionTarget) defined.constructor().invoke()).callSecret(),
                    "a child loader cannot override package-private methods");
        }

        @Test
        @DisplayName("works even when no lookup is available")
        void needsNoLookup() {
            DefinitionSite closed = new DefinitionSite(DefinitionTarget.class, null);

            assertTrue(DefinitionStrategy.childLoader().isUsableAt(closed));
            assertNotNull(DefinitionStrategy.childLoader()
                    .define(closed, GeneratedBytes.plainSubclass(DefinitionTarget.class, "C3")));
        }

        @Test
        @DisplayName("shares one loader across everything it defines")
        void reusesItsLoader() {
            DefinitionStrategy strategy = new DefinitionStrategy.ChildLoader(
                    DefinitionTarget.class.getClassLoader());

            Class<?> first = strategy
                    .define(site(), GeneratedBytes.plainSubclass(DefinitionTarget.class, "C4"))
                    .type();
            Class<?> second = strategy
                    .define(site(), GeneratedBytes.plainSubclass(DefinitionTarget.class, "C5"))
                    .type();

            assertSame(first.getClassLoader(), second.getClassLoader(),
                    "one strategy instance means one loader, collected as a unit");
        }
    }

    @Nested
    @DisplayName("strategies that need a lookup")
    class RequiresLookup {

        @Test
        @DisplayName("report themselves unusable when the package is closed")
        void areUnusableWithoutALookup() {
            DefinitionSite closed = new DefinitionSite(DefinitionTarget.class, null);

            assertFalse(DefinitionStrategy.hidden().isUsableAt(closed));
            assertFalse(DefinitionStrategy.named().isUsableAt(closed));
        }

        @Test
        @DisplayName("fail with remediation instructions rather than a bare access error")
        void failWithActionableMessage() {
            DefinitionSite closed = new DefinitionSite(DefinitionTarget.class, null);

            Exception failure = assertThrows(Exception.class, () -> DefinitionStrategy.hidden()
                    .define(closed, GeneratedBytes.plainSubclass(DefinitionTarget.class, "X")));

            assertTrue(failure.getMessage().contains(DefinitionTarget.class.getPackageName()),
                    failure.getMessage());
            assertTrue(failure.getMessage().contains("own package"),
                    "the message should mention the fallback: " + failure.getMessage());
        }
    }

    @Test
    @DisplayName("a public override works under every strategy")
    void publicOverridesWorkEverywhere() throws Throwable {
        for (DefinitionStrategy strategy : new DefinitionStrategy[]{
                DefinitionStrategy.hidden(),
                DefinitionStrategy.named(),
                DefinitionStrategy.childLoader()}) {

            DefinedClass defined = strategy.define(site(),
                    GeneratedBytes.subclassOverriding(DefinitionTarget.class,
                            "P" + Math.abs(strategy.name().hashCode()),
                            "greet", AccessFlags.PUBLIC, "overridden"));

            assertEquals("overridden",
                    ((DefinitionTarget) defined.constructor().invoke()).greet(),
                    "public override under " + strategy.name());
        }
    }
}
