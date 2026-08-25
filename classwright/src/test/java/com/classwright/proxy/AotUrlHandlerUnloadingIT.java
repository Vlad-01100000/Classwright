package com.classwright.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An ahead-of-time index served through a <em>custom</em> {@code URLStreamHandler} must not pin
 * its loader.
 *
 * <p>The subtlety this pins down: a {@link URL} retains its handler, and a custom loader can
 * hand out index URLs whose handler class it loaded itself. Had the per-loader index retained
 * the {@code URL}, the graph {@code weak map value → URL → handler → handler class → loader}
 * made the map's weak key strongly reachable from its own value — the entry never cleared and
 * the disposable loader never collected. The registration stores the URL's <em>text</em>, so
 * after this test drops its references — performing <strong>no further AOT operation of any
 * kind</strong> — the loader must go.
 */
class AotUrlHandlerUnloadingIT {

    /**
     * Planted into the disposable loader; the URL's handler, loaded by the loader under test.
     *
     * <p>Self-contained on purpose: planted copies run in a different runtime package from this
     * (package-private) test class, so they must reference nothing from it.
     */
    public static class MemoryIndexHandler extends URLStreamHandler {

        public MemoryIndexHandler() {
        }

        @Override
        protected URLConnection openConnection(URL url) {
            return new MemoryIndexConnection(url);
        }
    }

    /** Also planted; serves the index bytes. Self-contained, as above. */
    public static class MemoryIndexConnection extends URLConnection {

        /** {@code %abi 1} plus one well-formed entry; kept literal so no outer class is named. */
        private static final String INDEX_CONTENT = "%abi\t1\n"
                + "handler-key\tcom.example.NeverResolved\t"
                + "0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f\n";

        public MemoryIndexConnection(URL url) {
            super(url);
        }

        @Override
        public void connect() {
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(INDEX_CONTENT.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Exists to be copied into the disposable loader as the index's neighbour class. */
    public static class NeighbourSeed {
    }

    @Test
    @DisplayName("a custom-handler index URL cannot keep its loader alive; no drain required")
    void customHandlerIndexDoesNotPinItsLoader() throws Exception {
        HandlerLoader loader = new HandlerLoader();
        Class<?> neighbour = loader.planted(NeighbourSeed.class.getName());

        assertFalse(AotProxies.isEmpty(neighbour),
                "the index must actually have been read through the custom handler");

        WeakReference<ClassLoader> loaderReference = new WeakReference<>(loader);
        neighbour = null;
        loader = null;

        assertTrue(awaitCleared(loaderReference),
                "the discarded loader must be collectible without any further AOT activity; "
                        + "if it is not, a retained index URL is reaching the loader through "
                        + "its custom stream handler");
    }

    /**
     * Plants its own copies of the seed classes and serves the index resource through a URL
     * whose handler is one of those planted copies — the loader-reaches-itself shape.
     */
    private static final class HandlerLoader extends ClassLoader {

        private final Map<String, byte[]> planted = new HashMap<>();

        HandlerLoader() throws IOException {
            super("classwright-aot-handler-test", AotUrlHandlerUnloadingIT.class.getClassLoader());
            plant(NeighbourSeed.class);
            plant(MemoryIndexHandler.class);
            plant(MemoryIndexConnection.class);
        }

        private void plant(Class<?> seed) throws IOException {
            String resource = seed.getName().replace('.', '/') + ".class";
            try (InputStream stream = getParent().getResourceAsStream(resource)) {
                if (stream == null) {
                    throw new IOException("cannot read " + resource);
                }
                planted.put(seed.getName(), stream.readAllBytes());
            }
        }

        Class<?> planted(String name) throws ClassNotFoundException {
            return Class.forName(name, false, this);
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

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (!AheadOfTime.INDEX_RESOURCE.equals(name)) {
                return super.getResources(name);
            }
            try {
                URLStreamHandler handler = (URLStreamHandler) planted(
                        MemoryIndexHandler.class.getName()).getConstructor().newInstance();
                return Collections.enumeration(List.of(
                        new URL("classwright-mem", null, -1, "/proxies.index", handler)));
            } catch (ReflectiveOperationException e) {
                throw new IOException("could not build the custom-handler index URL", e);
            }
        }
    }

    private static boolean awaitCleared(WeakReference<?> reference) throws InterruptedException {
        for (int attempt = 0; attempt < 6; attempt++) {
            if (reference.get() == null) {
                return true;
            }
            System.gc();
            Thread.sleep(100);
        }
        return reference.get() == null;
    }
}
