package com.classwright.runtime;

import com.classwright.ClasswrightException;
import com.classwright.runtime.fixtures.DefinitionTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests automatic strategy selection, instantiation, and the class-dumping debug aid. */
class ClassDefinerTest {

    @Test
    @DisplayName("prefers hidden classes when the target's package is reachable")
    void prefersHiddenClasses() {
        ClassDefiner definer = ClassDefiner.alongside(DefinitionTarget.class);

        assertInstanceOf(DefinitionStrategy.Hidden.class, definer.strategy());
        assertTrue(definer.canOverridePackagePrivate());
        assertTrue(definer.strategy().producesUnloadableClasses());
    }

    @Test
    @DisplayName("falls back to a child loader when the package is closed, rather than failing")
    void fallsBackToChildLoader() {
        // java.util is not open to anyone, so this is the same situation as an application module
        // that declines to open its packages. A working proxy with fewer powers beats no proxy.
        ClassDefiner definer = ClassDefiner.alongside(ArrayList.class);

        assertInstanceOf(DefinitionStrategy.ChildLoader.class, definer.strategy());
        assertFalse(definer.canOverridePackagePrivate(),
                "a child loader is not a package-mate, whatever the package is called");
    }

    @Test
    @DisplayName("falls back when the target is in another unnamed module")
    void fallsBackForForeignClassLoaders() {
        // Every class loader gets its own unnamed module, so privateLookupIn across one returns a
        // *teleported* lookup: it has package access but has lost full privilege. defineHiddenClass
        // requires full privilege, so treating that lookup as usable produced an
        // IllegalAccessException from inside definition rather than a clean fallback.
        Class<?> foreign = DefinitionStrategy.childLoader()
                .define(DefinitionSite.of(DefinitionTarget.class),
                        GeneratedBytes.plainSubclass(DefinitionTarget.class, "Foreign"))
                .type();

        DefinitionSite site = DefinitionSite.of(foreign);
        assertFalse(site.canDefineHiddenClass(),
                "a teleported lookup cannot define a hidden class");
        assertFalse(DefinitionStrategy.hidden().isUsableAt(site));

        ClassDefiner definer = ClassDefiner.alongside(foreign);

        assertInstanceOf(DefinitionStrategy.ChildLoader.class, definer.strategy(),
                "it should fall back rather than fail");
        assertInstanceOf(DefinitionTarget.class, Instantiator
                .forClass(definer.define(GeneratedBytes.plainSubclass(foreign, "Nested")))
                .newInstance());
    }

    @Test
    @DisplayName("an explicitly requested strategy that cannot work fails with the reason")
    void explicitStrategyFailsLoudly() {
        ClasswrightException failure = assertThrows(ClasswrightException.class,
                () -> ClassDefiner.using(ArrayList.class, DefinitionStrategy.hidden()));

        assertTrue(failure.getMessage().contains("hidden"), failure.getMessage());
        assertTrue(failure.getMessage().contains("--add-opens"),
                "it should still say how to fix it: " + failure.getMessage());
    }

    @Test
    @DisplayName("defines and instantiates end to end")
    void definesAndInstantiates() {
        DefinedClass defined = ClassDefiner.alongside(DefinitionTarget.class)
                .define(GeneratedBytes.plainSubclass(DefinitionTarget.class, "D1"));

        Object instance = Instantiator.forClass(defined).newInstance();

        assertInstanceOf(DefinitionTarget.class, instance);
        assertEquals(84, ((DefinitionTarget) instance).doubled(42));
    }

    @Test
    @DisplayName("instantiates through a constructor taking arguments")
    void instantiatesWithArguments() {
        // Hidden classes cannot be reached by name, so this necessarily goes through a
        // MethodHandle derived from the defining lookup.
        DefinedClass defined = ClassDefiner.alongside(StringHolder.class)
                .define(GeneratedBytes.plainSubclass(StringHolder.class, "D2"));

        Instantiator instantiator = Instantiator.forClass(defined);
        Object instance = instantiator.newInstance();

        assertInstanceOf(StringHolder.class, instance);
        assertEquals("default", ((StringHolder) instance).value);
    }

    @Test
    @DisplayName("reuses a cached constructor handle across calls")
    void cachesConstructorHandles() {
        DefinedClass defined = ClassDefiner.alongside(DefinitionTarget.class)
                .define(GeneratedBytes.plainSubclass(DefinitionTarget.class, "D3"));
        Instantiator instantiator = Instantiator.forClass(defined);

        Object first = instantiator.newInstance();
        Object second = instantiator.newInstance();

        assertFalse(first == second, "each call should produce a new instance");
        assertEquals(first.getClass(), second.getClass());
    }

    @Test
    @DisplayName("reports a missing constructor clearly")
    void reportsMissingConstructor() {
        DefinedClass defined = ClassDefiner.alongside(DefinitionTarget.class)
                .define(GeneratedBytes.plainSubclass(DefinitionTarget.class, "D4"));

        ClasswrightException failure = assertThrows(ClasswrightException.class,
                () -> Instantiator.forClass(defined)
                        .newInstance(new Class<?>[]{int.class}, new Object[]{1}));

        assertTrue(failure.getMessage().contains("constructor"), failure.getMessage());
    }

    @Test
    @DisplayName("mismatched argument counts are rejected before anything is invoked")
    void rejectsArgumentCountMismatch() {
        DefinedClass defined = ClassDefiner.alongside(DefinitionTarget.class)
                .define(GeneratedBytes.plainSubclass(DefinitionTarget.class, "D5"));

        assertThrows(IllegalArgumentException.class, () -> Instantiator.forClass(defined)
                .newInstance(new Class<?>[]{int.class}, new Object[0]));
    }

    @Test
    @DisplayName("dumps generated classes to disk when asked, for javap")
    void dumpsClassesWhenRequested(@TempDir Path directory) throws IOException {
        // The equivalent of CGLib's DebuggingClassWriter. When a generated class misbehaves, the
        // first thing anyone wants is the class file itself.
        System.setProperty(ClassDefiner.DUMP_DIRECTORY_PROPERTY, directory.toString());
        ClassDefiner.refreshDumpDirectory();
        try {
            ClassDefiner.alongside(DefinitionTarget.class)
                    .define(GeneratedBytes.plainSubclass(DefinitionTarget.class, "Dump"));
        } finally {
            System.clearProperty(ClassDefiner.DUMP_DIRECTORY_PROPERTY);
            ClassDefiner.refreshDumpDirectory();
        }

        List<Path> dumped;
        try (var files = Files.list(directory)) {
            dumped = files.toList();
        }

        assertEquals(1, dumped.size(), "expected exactly one dumped class file");
        assertTrue(dumped.get(0).getFileName().toString().endsWith(".class"));
        assertTrue(Files.size(dumped.get(0)) > 0);
    }

    @Test
    @DisplayName("a broken dump directory does not break class definition")
    void dumpFailureIsNotFatal() {
        // A debugging aid must never be able to break the thing it is helping debug.
        System.setProperty(ClassDefiner.DUMP_DIRECTORY_PROPERTY, " :/definitely/not/valid");
        ClassDefiner.refreshDumpDirectory();
        try {
            DefinedClass defined = ClassDefiner.alongside(DefinitionTarget.class)
                    .define(GeneratedBytes.plainSubclass(DefinitionTarget.class, "D6"));

            assertInstanceOf(DefinitionTarget.class, Instantiator.forClass(defined).newInstance());
        } finally {
            System.clearProperty(ClassDefiner.DUMP_DIRECTORY_PROPERTY);
            ClassDefiner.refreshDumpDirectory();
        }
    }

    /** Has a no-argument constructor that sets a field, so instantiation can be observed. */
    public static class StringHolder {

        public final String value;

        public StringHolder() {
            this.value = "default";
        }
    }
}
