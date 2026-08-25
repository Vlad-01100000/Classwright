package com.classwright.proxy;

import com.classwright.ClasswrightException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runtime index: which loader's index applies, and how duplicate entries merge.
 *
 * <p>Every test builds its own class loader and its own copy of the neighbour class, because the
 * index is cached per loader and memoised per neighbour — shared fixtures would test whichever
 * test ran first, which is not a test at all. The end-to-end adoption path is {@code
 * AheadOfTimeIT}; what is tested here is the lookup machinery itself.
 */
class AotProxiesTest {

    /** A stand-in fingerprint; {@link AotProxies} stores it, {@code Enhancer} verifies it. */
    private static final String FINGERPRINT = "0f".repeat(32);

    @Test
    @DisplayName("each loader sees its own index, not whichever one was read first")
    void indexesAreKeyedByTheNeighboursLoader(@TempDir Path dirA, @TempDir Path dirB)
            throws Exception {
        writeIndex(dirA, "keyA\tcom.example.ProxyA\t" + FINGERPRINT);
        writeIndex(dirB, "keyB\tcom.example.ProxyB\t" + FINGERPRINT);

        try (IndexLoader loaderA = new IndexLoader(dirA);
             IndexLoader loaderB = new IndexLoader(dirB)) {
            Class<?> neighbourA = loaderA.neighbour();
            Class<?> neighbourB = loaderB.neighbour();

            assertFalse(AotProxies.isEmpty(neighbourA));
            assertEquals(1, AotProxies.size(neighbourA));
            assertEquals(1, AotProxies.size(neighbourB));
            assertTrue(AotProxies.preGenerated(neighbourA, "keyB").isEmpty(),
                    "loader A must not see loader B's entries; a single process-wide index bound "
                            + "to the first context loader is exactly the bug this guards "
                            + "against");
            assertTrue(AotProxies.preGenerated(neighbourB, "keyA").isEmpty());
            assertTrue(AotProxies.describe(neighbourA).contains("com.example.ProxyA"));
            assertFalse(AotProxies.describe(neighbourA).contains("com.example.ProxyB"));
        }
    }

    @Test
    @DisplayName("a bootstrap-loaded neighbour is answered empty, not with a NullPointerException")
    void bootstrapNeighbourIsNullSafe() {
        // Object's loader is null. The bootstrap loader has no application resources to carry an
        // index, and a proxy could not be defined into a java.* package anyway, so the honest
        // answer is "nothing registered" — reached without dereferencing the missing loader.
        assertTrue(AotProxies.isEmpty(Object.class));
        assertEquals(0, AotProxies.size(Object.class));
        assertTrue(AotProxies.find(Object.class, "anything").isEmpty());
        assertTrue(AotProxies.describe(Object.class).contains("No ahead-of-time proxies"));
    }

    @Test
    @DisplayName("the found class is resolved by the neighbour's loader, not the context loader")
    void resolvesThroughTheNeighboursLoader(@TempDir Path dir) throws Exception {
        writeIndex(dir, "the-key\t" + IndexLoader.NEIGHBOUR_NAME + "\t" + FINGERPRINT);

        try (IndexLoader loader = new IndexLoader(dir)) {
            Class<?> neighbour = loader.neighbour();

            // A context loader that can see neither the index nor the class. The old
            // implementation resolved through it and would fail here; the neighbour's own loader
            // is the one the generated class ships beside.
            Thread thread = Thread.currentThread();
            ClassLoader original = thread.getContextClassLoader();
            thread.setContextClassLoader(new URLClassLoader(new URL[0], null));
            try {
                Optional<AotProxies.PreGenerated> found =
                        AotProxies.preGenerated(neighbour, "the-key");
                assertTrue(found.isPresent());
                assertSame(neighbour, found.get().type(),
                        "the entry names the neighbour itself, which only its own loader "
                                + "resolves to this copy");
                assertEquals(FINGERPRINT, found.get().routingFingerprint(),
                        "the fingerprint column must survive the round trip; Enhancer verifies "
                                + "routing against it");
            } finally {
                thread.setContextClassLoader(original);
            }
        }
    }

    @Test
    @DisplayName("identical entries from two resources merge quietly")
    void identicalDuplicatesAreAccepted(@TempDir Path dirA, @TempDir Path dirB) throws Exception {
        writeIndex(dirA, "shared\tcom.example.SameProxy\t" + FINGERPRINT);
        writeIndex(dirB, "shared\tcom.example.SameProxy\t" + FINGERPRINT);

        try (IndexLoader loader = new IndexLoader(dirA, dirB)) {
            assertEquals(1, AotProxies.size(loader.neighbour()),
                    "the same jar seen twice — a shaded dependency, an overlaid test class path "
                            + "— agrees with itself and must not be an error");
        }
    }

    @Test
    @DisplayName("conflicting entries for one key are refused, naming both sides")
    void conflictingDuplicatesAreRefused(@TempDir Path dirA, @TempDir Path dirB) throws Exception {
        writeIndex(dirA, "contested\tcom.example.FromA\t" + FINGERPRINT);
        writeIndex(dirB, "contested\tcom.example.FromB\t" + "e1".repeat(32));

        try (IndexLoader loader = new IndexLoader(dirA, dirB)) {
            Class<?> neighbour = loader.neighbour();

            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> AotProxies.isEmpty(neighbour),
                    "letting resource order pick a winner would make the adopted proxy depend on "
                            + "class-path ordering");

            assertTrue(failure.getMessage().contains("contested"), failure.getMessage());
            assertTrue(failure.getMessage().contains("com.example.FromA"), failure.getMessage());
            assertTrue(failure.getMessage().contains("com.example.FromB"), failure.getMessage());
            assertTrue(failure.getMessage().contains(dirA.getFileName().toString())
                            && failure.getMessage().contains(dirB.getFileName().toString()),
                    "both resource URLs must be named, or the user cannot find the offending "
                            + "jars: " + failure.getMessage());
        }
    }

    @Test
    @DisplayName("a line without a fingerprint column is skipped, not half-adopted")
    void oldFormatLinesAreSkipped(@TempDir Path dir) throws Exception {
        writeIndex(dir, "old-key\tcom.example.OldProxy");

        try (IndexLoader loader = new IndexLoader(dir)) {
            assertTrue(AotProxies.isEmpty(loader.neighbour()),
                    "an entry with no fingerprint cannot be verified against routing drift; "
                            + "adopting it anyway would disable the very check the fingerprint "
                            + "exists for. Falling back to runtime generation is the safe "
                            + "answer.");
        }
    }

    @Test
    @DisplayName("an index declaring the current runtime ABI is accepted")
    void matchingAbiIsAccepted(@TempDir Path dir) throws Exception {
        writeIndex(dir, "%abi\t" + AheadOfTime.RUNTIME_ABI,
                "abi-key\t" + IndexLoader.NEIGHBOUR_NAME + "\t" + FINGERPRINT);

        try (IndexLoader loader = new IndexLoader(dir)) {
            assertEquals(1, AotProxies.size(loader.neighbour()),
                    "the directive AheadOfTime.index() writes must be understood, or every "
                            + "freshly generated index would be ignored");
        }
    }

    @Test
    @DisplayName("an index for a different runtime ABI is refused whole, not half-adopted")
    void mismatchedAbiIsRefused(@TempDir Path dir) throws Exception {
        // The entry itself is perfectly well-formed; only the declared ABI disagrees. Adopting it
        // anyway would defer the failure to a NoSuchMethodError at first dispatch, far from the
        // cause. On an ordinary JVM the classes can simply be regenerated, so the index is
        // dropped with a warning rather than failing the application.
        writeIndex(dir, "%abi\t" + (AheadOfTime.RUNTIME_ABI + 1),
                "future-key\t" + IndexLoader.NEIGHBOUR_NAME + "\t" + FINGERPRINT);

        try (IndexLoader loader = new IndexLoader(dir)) {
            assertTrue(AotProxies.isEmpty(loader.neighbour()),
                    "proxies compiled against different runtime helpers must not be adopted");
        }
    }

    @Test
    @DisplayName("an entry whose class is missing points at the stale index")
    void staleEntriesAreReported(@TempDir Path dir) throws Exception {
        writeIndex(dir, "stale-key\tcom.example.LongGone\t" + FINGERPRINT);

        try (IndexLoader loader = new IndexLoader(dir)) {
            Class<?> neighbour = loader.neighbour();

            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> AotProxies.find(neighbour, "stale-key"));

            assertTrue(failure.getMessage().contains("com.example.LongGone"),
                    failure.getMessage());
            assertTrue(failure.getMessage().contains("stale"), failure.getMessage());
        }
    }

    // ==============================================================================================

    private static void writeIndex(Path directory, String... lines) throws IOException {
        Path index = directory.resolve(AheadOfTime.INDEX_RESOURCE);
        Files.createDirectories(index.getParent());
        Files.writeString(index,
                "# test index\n" + String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    /**
     * A loader over index directories, carrying its own copy of a neighbour class.
     *
     * <p>The neighbour is this test class's {@link NeighbourSeed}, redefined from its class-file
     * bytes so that {@code getClassLoader()} answers with <em>this</em> loader — which is the
     * whole point: {@link AotProxies} keys everything off the neighbour's loader. The override of
     * {@link #loadClass} serves the planted copy first, so resolving the neighbour's name through
     * this loader finds this loader's copy rather than delegating to the parent's.
     */
    private static final class IndexLoader extends URLClassLoader {

        static final String NEIGHBOUR_NAME = NeighbourSeed.class.getName();

        private final Map<String, byte[]> planted = new HashMap<>();

        IndexLoader(Path... indexDirectories) throws IOException {
            super("classwright-aot-index-test", urlsOf(indexDirectories),
                    AotProxiesTest.class.getClassLoader());
            planted.put(NEIGHBOUR_NAME, seedBytes());
        }

        Class<?> neighbour() throws ClassNotFoundException {
            return Class.forName(NEIGHBOUR_NAME, false, this);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    byte[] bytes = planted.get(name);
                    loaded = bytes != null
                            ? defineClass(name, bytes, 0, bytes.length)
                            : super.loadClass(name, false);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private static URL[] urlsOf(Path... directories) throws IOException {
            URL[] urls = new URL[directories.length];
            for (int i = 0; i < directories.length; i++) {
                urls[i] = directories[i].toUri().toURL();
            }
            return urls;
        }

        private static byte[] seedBytes() throws IOException {
            String resource = NEIGHBOUR_NAME.replace('.', '/') + ".class";
            try (InputStream stream =
                         AotProxiesTest.class.getClassLoader().getResourceAsStream(resource)) {
                if (stream == null) {
                    throw new IOException("cannot read " + resource + " from the test class path");
                }
                return stream.readAllBytes();
            }
        }
    }

    /** Exists to be copied into test loaders; see {@link IndexLoader}. */
    public static class NeighbourSeed {
    }
}
