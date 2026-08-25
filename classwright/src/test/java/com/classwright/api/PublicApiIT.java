package com.classwright.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The public API is exactly what was last approved.
 *
 * <p>An integration test rather than a unit test because it needs {@code module-info.class}, which
 * this module compiles in the {@code prepare-package} phase &mdash; after unit tests have run.
 *
 * <h2>What to do when this fails</h2>
 *
 * <p>Read the diff. If the change is intended, regenerate the snapshot and commit it alongside the
 * code, so that the API change is reviewed as part of the pull request that makes it:
 *
 * <pre>{@code
 * mvn -pl classwright verify -Dclasswright.api.update=true
 * }</pre>
 *
 * <p>If it is not intended, something became public that should not have. That is the case this
 * exists to catch: accidental API is permanent API, because removing it later is a breaking change.
 */
class PublicApiIT {

    /** Deliberately not under {@code src/}: it is a reviewed artefact, not a test resource. */
    private static final Path SNAPSHOT = Path.of("api", "classwright.api");

    private static final Path CLASSES = Path.of("target", "classes");

    private static final String UPDATE_PROPERTY = "classwright.api.update";

    @Test
    @DisplayName("the public API matches the approved snapshot")
    void publicApiMatchesTheApprovedSnapshot() throws IOException {
        String current = ApiSnapshot.render(CLASSES);

        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, current, StandardCharsets.UTF_8);
            System.out.println("[api] snapshot rewritten: " + SNAPSHOT.toAbsolutePath());
            return;
        }

        assertTrue(Files.exists(SNAPSHOT),
                () -> SNAPSHOT.toAbsolutePath() + " is missing. Create it with "
                        + "`mvn -pl classwright verify -D" + UPDATE_PROPERTY + "=true`.");

        String approved = Files.readString(SNAPSHOT, StandardCharsets.UTF_8);

        if (normalise(approved).equals(normalise(current))) {
            return;
        }
        fail("The public API has changed.\n"
                + difference(normalise(approved), normalise(current))
                + "\nIf that is intended, regenerate the snapshot and commit it with the change:\n"
                + "  mvn -pl classwright verify -D" + UPDATE_PROPERTY + "=true\n"
                + "If it is not, something became public by accident. Accidental API is permanent "
                + "API: once released, removing it is a breaking change.");
    }

    /**
     * The added and removed lines, and nothing else.
     *
     * <p>{@code assertEquals} on two files this size prints both in full, which buries the one line
     * that matters. Order within the snapshot is canonical, so a set difference says everything a
     * reviewer needs.
     */
    private static String difference(String approved, String current) {
        Set<String> before = new LinkedHashSet<>(List.of(approved.split("\n")));
        Set<String> after = new LinkedHashSet<>(List.of(current.split("\n")));

        List<String> removed = before.stream().filter(line -> !after.contains(line)).toList();
        List<String> added = after.stream().filter(line -> !before.contains(line)).toList();

        StringBuilder report = new StringBuilder();
        report.append("\n  removed (").append(removed.size()).append("), ")
                .append("added (").append(added.size()).append("):\n");
        removed.forEach(line -> report.append("    - ").append(line.strip()).append('\n'));
        added.forEach(line -> report.append("    + ").append(line.strip()).append('\n'));
        return report.toString();
    }

    /** Line endings differ between the CI runners; the API does not. */
    private static String normalise(String text) {
        return text.replace("\r\n", "\n").strip();
    }
}
