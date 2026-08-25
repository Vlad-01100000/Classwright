package com.classwright.runtime;

import com.classwright.runtime.unsafe.Allocators;
import com.classwright.runtime.unsafe.ConstructorSkippingAllocator;
import com.classwright.runtime.fixtures.DefinitionTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests capability probing and the quarantined allocators behind it. */
class CapabilitiesTest {

    @Test
    @DisplayName("hidden classes are available on a supported runtime")
    void hiddenClassesAreAvailable() {
        // The baseline is Java 17 and hidden classes arrived in 15, so this must hold. It is
        // probed by actually generating and defining a class rather than by checking a version.
        assertTrue(Capabilities.hiddenClasses(),
                "hidden classes underpin the library's memory behaviour");
    }

    @Test
    @DisplayName("the capability report names the JVM and each capability")
    void describesCapabilities() {
        String description = Capabilities.describe();

        assertTrue(description.contains("hidden classes"), description);
        assertTrue(description.contains("constructor-skipping"), description);
        assertTrue(description.contains(System.getProperty("java.version")), description);
    }

    @Test
    @DisplayName("an allocator is always returned, even when none works")
    void alwaysReturnsAnAllocator() {
        // Callers should never have to null-check. An unavailable allocator explains itself
        // instead of being absent.
        ConstructorSkippingAllocator allocator = Capabilities.allocator();

        assertNotNull(allocator);
        assertNotNull(allocator.name());
    }

    @Test
    @DisplayName("constructor-skipping reflects whatever the allocator search found")
    void reportsAllocatorAvailability() {
        // The allocators themselves are tested in the quarantine package, where their internals
        // live. Here the only question is whether Capabilities reports them faithfully.
        assertEquals(Allocators.findBest().isAvailable(), Capabilities.constructorSkipping());
    }

    @Test
    @DisplayName("an unavailable capability still yields a usable object that explains itself")
    void unavailableCapabilityIsStillCallable() {
        ConstructorSkippingAllocator allocator = Capabilities.allocator();

        if (allocator.isAvailable()) {
            assertNotNull(allocator.allocate(DefinitionTarget.class));
        } else {
            assertThrows(UnsupportedOperationException.class,
                    () -> allocator.allocate(DefinitionTarget.class));
        }
    }
}
