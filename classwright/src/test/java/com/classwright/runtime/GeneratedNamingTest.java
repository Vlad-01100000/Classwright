package com.classwright.runtime;

import com.classwright.core.AccessFlags;
import com.classwright.core.CwClassWriter;
import com.classwright.core.CwMethodType;
import com.classwright.core.CwType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the naming rules {@link ClassDefiner} applies: hidden names stay clean, resolvable names
 * are unique per definition, and {@code java.*} targets are relocated whenever a child loader
 * will do the defining.
 *
 * <p>The uniqueness rule earns its test the hard way: child loaders are shared per parent, and
 * before names were uniquified in the definer itself, the second of two different configurations
 * against one closed-package target collided with the first's class name and was rejected by the
 * JVM — for every generator that had not duplicated Enhancer's private sequence.
 */
class GeneratedNamingTest {

    @Test
    @DisplayName("hidden definitions keep the plain target-derived name")
    void hiddenNamesAreClean() {
        ClassDefiner definer = ClassDefiner.alongside(GeneratedNamingTest.class);
        assertEquals("com/classwright/runtime/GeneratedNamingTest$$T",
                definer.generatedNameFor(GeneratedNamingTest.class, "$$T"));
    }

    @Test
    @DisplayName("resolvable-name strategies get a unique suffix per definition")
    void resolvableNamesAreUnique() {
        ClassDefiner definer = ClassDefiner.using(GeneratedNamingTest.class,
                DefinitionStrategy.named());
        String first = definer.generatedNameFor(GeneratedNamingTest.class, "$$T");
        String second = definer.generatedNameFor(GeneratedNamingTest.class, "$$T");

        assertTrue(first.startsWith("com/classwright/runtime/GeneratedNamingTest$$T$"), first);
        assertNotEquals(first, second);
    }

    @Test
    @DisplayName("a java.* target is relocated when a child loader will define")
    void javaTargetsAreRelocatedForChildLoaders() {
        // java.util is closed without --add-opens, so the automatic choice is the child
        // loader — which may not define java.* names, whatever lookup happens to exist.
        ClassDefiner definer = ClassDefiner.alongside(java.util.ArrayList.class);

        assertInstanceOf(DefinitionStrategy.ChildLoader.class, definer.strategy());
        String name = definer.generatedNameFor(java.util.ArrayList.class, "$$T");
        assertTrue(name.startsWith("com/classwright/generated/java$util$ArrayList$$T$"), name);
    }

    @Test
    @DisplayName("two definitions against one closed-package target coexist in the shared loader")
    void sharedLoaderAcceptsRepeatedDefinitions() {
        ClassDefiner definer = ClassDefiner.alongside(java.util.ArrayList.class);

        DefinedClass first = definer.define(trivialClass(
                definer.generatedNameFor(java.util.ArrayList.class, "$$T")));
        DefinedClass second = definer.define(trivialClass(
                definer.generatedNameFor(java.util.ArrayList.class, "$$T")));

        // The same shared loader — which is exactly what made identical names collide — now
        // holds two distinct classes.
        assertSame(first.type().getClassLoader(), second.type().getClassLoader());
        assertNotEquals(first.type().getName(), second.type().getName());
    }

    /** A minimal public class extending Object, with the given internal name. */
    private static byte[] trivialClass(String internalName) {
        CwClassWriter writer = CwClassWriter.of(
                AccessFlags.PUBLIC | AccessFlags.SUPER, internalName, "java/lang/Object");
        writer.constructor(AccessFlags.PUBLIC, CwMethodType.of(CwType.VOID))
                .code()
                .loadThis()
                .invokeConstructor("java/lang/Object", CwMethodType.of(CwType.VOID))
                .returnValue();
        return writer.toByteArray();
    }
}
