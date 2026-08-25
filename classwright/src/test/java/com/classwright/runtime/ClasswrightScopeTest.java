package com.classwright.runtime;

import com.classwright.runtime.fixtures.DefinitionTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the scope's API contract. That closing it actually releases classes for collection is
 * covered by {@link ClassUnloadingIT}, since it needs a garbage collector to observe.
 */
class ClasswrightScopeTest {

    private static byte[] bytes(String suffix) {
        return GeneratedBytes.plainSubclass(DefinitionTarget.class, suffix);
    }

    @Test
    @DisplayName("tracks what it has defined")
    void tracksDefinedClasses() {
        try (ClasswrightScope scope = ClasswrightScope.open("tracking")) {
            assertEquals(0, scope.size());

            scope.define(DefinitionTarget.class, bytes("S1"));
            scope.define(DefinitionTarget.class, bytes("S2"));

            assertEquals(2, scope.size());
            assertFalse(scope.isClosed());
        }
    }

    @Test
    @DisplayName("refuses to define anything after closing")
    void refusesDefinitionAfterClose() {
        ClasswrightScope scope = ClasswrightScope.open("closed");
        scope.close();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> scope.define(DefinitionTarget.class, bytes("S3")));

        assertTrue(failure.getMessage().contains("closed"), failure.getMessage());
    }

    @Test
    @DisplayName("closing twice is harmless")
    void closeIsIdempotent() {
        ClasswrightScope scope = ClasswrightScope.open("twice");
        scope.define(DefinitionTarget.class, bytes("S4"));

        scope.close();
        scope.close();

        assertTrue(scope.isClosed());
        assertEquals(0, scope.size());
    }

    @Test
    @DisplayName("reports whether everything in it could be reclaimed")
    void reportsReclaimability() {
        try (ClasswrightScope scope = ClasswrightScope.open("reclaim")) {
            assertTrue(scope.isFullyReclaimable(), "an empty scope is trivially reclaimable");

            scope.define(DefinitionTarget.class, bytes("S5"));
            assertTrue(scope.isFullyReclaimable());

            // One named class is enough to defeat a container's undeploy cleanup, and the failure
            // mode is a slow leak rather than an error, so it is worth being able to ask.
            scope.define(ClassDefiner.using(DefinitionTarget.class, DefinitionStrategy.named()),
                    bytes("S6Named"));
            assertFalse(scope.isFullyReclaimable());
        }
    }

    @Test
    @DisplayName("describes itself usefully")
    void describesItself() {
        try (ClasswrightScope scope = ClasswrightScope.open("plugin-42")) {
            scope.define(DefinitionTarget.class, bytes("S7"));

            assertTrue(scope.toString().contains("plugin-42"), scope.toString());
            assertTrue(scope.toString().contains("1 classes"), scope.toString());
        }
    }
}
