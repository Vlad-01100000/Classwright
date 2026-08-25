package com.classwright.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves the dependency-scope check catches a dependency that would reach consumers. */
class PomScopesTest {

    private static String pom(String dependenciesXml) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>example</artifactId>
                  %s
                </project>
                """.formatted(dependenciesXml);
    }

    @Test
    @DisplayName("accepts a POM with no dependencies at all")
    void acceptsNoDependencies() {
        assertTrue(PomScopes.runtimeDependencies(pom("")).isEmpty());
    }

    @Test
    @DisplayName("accepts test and provided scopes")
    void acceptsHarmlessScopes() {
        String xml = pom("""
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>jakarta.servlet</groupId><artifactId>jakarta.servlet-api</artifactId>
                      <scope>provided</scope>
                    </dependency>
                  </dependencies>
                """);

        assertTrue(PomScopes.runtimeDependencies(xml).isEmpty());
    }

    @Test
    @DisplayName("rejects an explicit compile-scoped dependency")
    void rejectsCompileScope() {
        String xml = pom("""
                  <dependencies>
                    <dependency>
                      <groupId>org.ow2.asm</groupId><artifactId>asm</artifactId>
                      <scope>compile</scope>
                    </dependency>
                  </dependencies>
                """);

        List<String> offenders = PomScopes.runtimeDependencies(xml);

        assertEquals(1, offenders.size());
        assertTrue(offenders.get(0).contains("org.ow2.asm:asm"), offenders.get(0));
    }

    @Test
    @DisplayName("rejects a dependency with no scope, since that silently defaults to compile")
    void rejectsDefaultedScope() {
        String xml = pom("""
                  <dependencies>
                    <dependency>
                      <groupId>net.bytebuddy</groupId><artifactId>byte-buddy</artifactId>
                    </dependency>
                  </dependencies>
                """);

        List<String> offenders = PomScopes.runtimeDependencies(xml);

        assertEquals(1, offenders.size());
        assertTrue(offenders.get(0).contains("defaulted"),
                "the message should explain that an omitted scope means compile");
    }

    @Test
    @DisplayName("rejects runtime scope")
    void rejectsRuntimeScope() {
        String xml = pom("""
                  <dependencies>
                    <dependency>
                      <groupId>x</groupId><artifactId>y</artifactId><scope>runtime</scope>
                    </dependency>
                  </dependencies>
                """);

        assertEquals(1, PomScopes.runtimeDependencies(xml).size());
    }

    @Test
    @DisplayName("ignores dependencyManagement, which declares versions without adding dependencies")
    void ignoresDependencyManagement() {
        String xml = pom("""
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.junit</groupId><artifactId>junit-bom</artifactId>
                        <version>5.11.4</version><type>pom</type><scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                """);

        assertTrue(PomScopes.runtimeDependencies(xml).isEmpty());
    }

    @Test
    @DisplayName("fails loudly on input that is not XML")
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class,
                () -> PomScopes.runtimeDependencies("not xml at all"));
    }
}
