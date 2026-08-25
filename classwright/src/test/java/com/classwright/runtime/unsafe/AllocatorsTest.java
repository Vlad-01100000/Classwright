package com.classwright.runtime.unsafe;

import com.classwright.core.AccessFlags;
import com.classwright.core.CwClassWriter;
import com.classwright.core.CwMethodType;
import com.classwright.core.CwType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the quarantined allocators, including the day they all stop working.
 *
 * <p>That last case is the important one. Every implementation here depends on an API the JDK
 * intends to withdraw, so "none of them work" is a future this library must already handle. It is
 * tested directly rather than waited for.
 */
class AllocatorsTest {

    @Test
    @DisplayName("finds a working allocator on this JVM")
    void findsAWorkingAllocator() {
        ConstructorSkippingAllocator allocator = Allocators.findBest();

        assertNotNull(allocator);
        assertTrue(allocator.isAvailable(),
                "Java 17 still exposes ReflectionFactory; if this fails the degraded path applies");
        assertEquals("ReflectionFactory", allocator.name(),
                "the more durable option should be preferred over Unsafe");
    }

    @Test
    @DisplayName("allocates without running any constructor")
    void skipsConstructors() {
        ConstructorCounter.constructions = 0;

        Object allocated = Allocators.findBest().allocate(ConstructorCounter.class);

        assertNotNull(allocated);
        assertEquals(0, ConstructorCounter.constructions, "no constructor should have run");
        assertEquals(0, ((ConstructorCounter) allocated).value,
                "fields stay at their defaults; the constructor would have set 42");
    }

    @Test
    @DisplayName("both implementations behave identically where both are available")
    void implementationsAgree() {
        for (ConstructorSkippingAllocator allocator : new ConstructorSkippingAllocator[]{
                new Allocators.ReflectionFactoryAllocator(), new Allocators.UnsafeAllocator()}) {
            if (!allocator.isAvailable()) {
                continue;
            }
            ConstructorCounter.constructions = 0;

            Object allocated = allocator.allocate(ConstructorCounter.class);

            assertNotNull(allocated, allocator.name());
            assertEquals(0, ConstructorCounter.constructions, allocator.name());
        }
    }

    @Test
    @DisplayName("refuses types that cannot be instantiated at all")
    void refusesImpossibleTypes() {
        ConstructorSkippingAllocator allocator = Allocators.findBest();

        assertThrows(IllegalArgumentException.class, () -> allocator.allocate(int.class));
        assertThrows(IllegalArgumentException.class, () -> allocator.allocate(Runnable.class));
        assertThrows(IllegalArgumentException.class, () -> allocator.allocate(String[].class));
        assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate(AbstractThing.class));
    }

    @Test
    @DisplayName("when nothing works, it says so usefully")
    void degradesWithAnExplanation() {
        // What users will see on the JDK that finally removes these. The message has to say what
        // was tried and what to do instead, not surface a NoClassDefFoundError from inside.
        ConstructorSkippingAllocator unavailable =
                new Allocators.UnavailableAllocator(Allocators.NOTHING_AVAILABLE);

        assertFalse(unavailable.isAvailable());
        assertEquals("none", unavailable.name());

        UnsupportedOperationException failure = assertThrows(UnsupportedOperationException.class,
                () -> unavailable.allocate(ConstructorCounter.class));

        assertTrue(failure.getMessage().contains("ReflectionFactory"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Unsafe"), failure.getMessage());
        assertTrue(failure.getMessage().contains("accessible constructor"),
                "it should point at the supported alternative: " + failure.getMessage());
        assertTrue(failure.getMessage().contains(ConstructorCounter.class.getName()),
                "and name the class that could not be created: " + failure.getMessage());
    }

    @Test
    @DisplayName("a hidden class is allocated by Unsafe, not by ReflectionFactory")
    void hiddenClassesNeedUnsafe() throws Throwable {
        // Found by the TCK, not by these tests, because every test here used an ordinary class.
        // ReflectionFactory fabricates a serialization constructor that resolves its target class
        // by name; a hidden class's name carries a /0x... suffix and resolves to nothing, so the
        // accessor throws NoClassDefFoundError the first time it is invoked. Since hidden is the
        // DEFAULT definition strategy, preferring ReflectionFactory unconditionally broke
        // constructor-skipping on the common path while passing every test that used a plain class.
        Class<?> hidden = MethodHandles.lookup()
                .defineHiddenClass(hiddenCounterBytes(), false)
                .lookupClass();
        assertTrue(hidden.isHidden(), "the fixture is not actually hidden");

        assertThrows(IllegalArgumentException.class,
                () -> new Allocators.ReflectionFactoryAllocator().allocate(hidden),
                "ReflectionFactory cannot allocate a hidden class and must say so, not "
                        + "leak a NoClassDefFoundError");

        ConstructorSkippingAllocator chosen = Allocators.forClass(hidden);
        assertTrue(chosen.isAvailable(), "no allocator was offered for a hidden class");
        assertEquals("Unsafe.allocateInstance", chosen.name(),
                "a hidden class must be routed to Unsafe");
        assertNotNull(chosen.allocate(hidden));

        assertEquals(Allocators.findBest().name(), Allocators.forClass(ConstructorCounter.class).name(),
                "an ordinary class should still get the preferred allocator");
    }

    /** A trivial, self-contained class file, defined hidden so it has no resolvable name. */
    private static byte[] hiddenCounterBytes() {
        CwClassWriter writer = CwClassWriter.of(AccessFlags.PUBLIC | AccessFlags.SUPER,
                "com/classwright/runtime/unsafe/HiddenCounter", "java/lang/Object");
        writer.constructor(AccessFlags.PUBLIC, CwMethodType.of(CwType.VOID))
                .code()
                .loadThis()
                .invokeConstructor("java/lang/Object", CwMethodType.of(CwType.VOID))
                .returnValue();
        return writer.toByteArray();
    }

    /** Records whether a constructor ran, so skipping is observed rather than assumed. */
    public static class ConstructorCounter {

        static int constructions;

        int value;

        public ConstructorCounter() {
            constructions++;
            value = 42;
        }
    }

    /** Abstract, so allocation must be refused. */
    public abstract static class AbstractThing {
    }
}
