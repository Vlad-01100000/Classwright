package com.classwright.runtime;

import com.classwright.ClasswrightException;
import com.classwright.runtime.fixtures.DefinitionTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests lookup acquisition, and above all the failure message.
 *
 * <p>{@code java.util.ArrayList} is used as the closed-module case, and it is a real one rather
 * than a contrivance: {@code java.base} does not open {@code java.util} to anybody, so
 * {@code privateLookupIn} genuinely fails exactly as it would for any application module that
 * declines to open its packages.
 */
class LookupResolverTest {

    @Test
    @DisplayName("resolves a full-privilege lookup for an ordinary class-path class")
    void resolvesForClasspathClasses() {
        var lookup = LookupResolver.resolve(DefinitionTarget.class);

        assertEquals(DefinitionTarget.class, lookup.lookupClass());
        assertTrue(lookup.hasFullPrivilegeAccess(),
                "defineHiddenClass requires full privilege access");
    }

    @Test
    @DisplayName("returns empty rather than throwing for a closed package")
    void returnsEmptyForClosedPackage() {
        assertTrue(LookupResolver.tryResolve(ArrayList.class).isEmpty());
    }

    @Test
    @DisplayName("returns empty for primitives and arrays, which have no package to join")
    void returnsEmptyForNonClasses() {
        assertTrue(LookupResolver.tryResolve(int.class).isEmpty());
        assertTrue(LookupResolver.tryResolve(String[].class).isEmpty());
    }

    @Test
    @DisplayName("names the module, the package, and the exact flag needed")
    void explainsHowToFixAClosedModule() {
        // This is the whole point of the class. CGLib's equivalent failure was an
        // InaccessibleObjectException with no indication of which --add-opens would help, and
        // working that out was a rite of passage. It should not be one.
        ClasswrightException failure = assertThrows(ClasswrightException.class,
                () -> LookupResolver.resolve(ArrayList.class));
        String message = failure.getMessage();

        assertTrue(message.contains("java.util"), message);
        assertTrue(message.contains("java.base"), message);
        assertTrue(message.contains("--add-opens java.base/java.util="), message);
        assertTrue(message.contains("opens java.util to"), message);
        assertTrue(message.contains("own package"),
                "it should also mention the automatic fallback: " + message);
    }

    @Test
    @DisplayName("explains that primitives have no package")
    void explainsPrimitives() {
        ClasswrightException failure = assertThrows(ClasswrightException.class,
                () -> LookupResolver.resolve(int.class));

        assertTrue(failure.getMessage().contains("no package"), failure.getMessage());
    }

    @Test
    @DisplayName("a site reports whether package-private overriding is possible")
    void siteReportsOverrideCapability() {
        assertTrue(DefinitionSite.of(DefinitionTarget.class).canOverridePackagePrivate());
        assertFalse(DefinitionSite.of(ArrayList.class).canOverridePackagePrivate());
    }

    @Test
    @DisplayName("a site exposes the package and loader it would use")
    void siteExposesPlacement() {
        DefinitionSite site = DefinitionSite.of(DefinitionTarget.class);

        assertEquals(DefinitionTarget.class.getPackageName(), site.packageName());
        assertEquals(DefinitionTarget.class.getClassLoader(), site.classLoader());
        assertTrue(site.hasFullPrivilegeLookup());
    }

    @Test
    @DisplayName("asking a successful site to explain itself is a programming error")
    void refusesToExplainASuccess() {
        assertThrows(IllegalStateException.class,
                () -> DefinitionSite.of(DefinitionTarget.class).explainMissingLookup());
    }
}
