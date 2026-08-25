package com.classwright.proxy;

import com.classwright.ClasswrightException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Finds proxy classes that were generated before the program started.
 *
 * <p>The runtime half of {@link AheadOfTime}. It reads every
 * {@value AheadOfTime#INDEX_RESOURCE} a class loader can see, once per loader, and answers whether
 * a given configuration already has a class compiled for it. {@link Enhancer} asks before
 * generating.
 *
 * <p>In a GraalVM native image this is not an optimisation but the only path that works, since a
 * native image cannot define a class at runtime. On an ordinary JVM it is an optimisation: a
 * pre-generated class costs a name lookup instead of a generate-and-define.
 *
 * <h2>Which index applies is decided per class loader</h2>
 *
 * <p>Every query is made for a <em>placement neighbour</em> — the class the proxy must live
 * beside — and the index consulted is the one <em>that neighbour's own loader</em> can see. A
 * single process-wide index, read through whichever thread's context loader got there first, gets
 * both directions wrong in any application with more than one loader: a web application's proxies
 * would be invisible when the first query happened to come from a different deployment, and —
 * worse — a key from one deployment could adopt a class from another. Keying by the neighbour's
 * loader also means the found class is resolved by a loader that can actually see it, and that a
 * redeployed loader's index entries die with the loader instead of pinning it.
 *
 * <h2>Cost when unused</h2>
 *
 * <p>Nothing measurable, and that is a documented property: this gate sits ahead of the generation
 * cache on every {@code create()}. The per-loader index is memoised on the neighbour class itself
 * through a {@link ClassValue}, so after the first query for a class the check is a
 * {@code ClassValue} read and an {@code isEmpty()} — no lock, no map lookup keyed by loader.
 */
public final class AotProxies {

    private AotProxies() {
    }

    /**
     * One index line: the generated class, its routing fingerprint, and where it was read from.
     *
     * <p>The source is the URL's <em>text</em>, deliberately not the {@link URL} itself. A URL
     * retains its {@link java.net.URLStreamHandler}, and a custom loader can hand out index URLs
     * whose handler class it loaded itself — at which point a retained URL reaches back to the
     * loader, and this map's weak key is strongly reachable from its own value. The text serves
     * the only use the source has, diagnostics, and can pin nothing.
     */
    record Registration(String className, String routingFingerprint, String source) {
    }

    /** What {@code Enhancer} adopts: the resolved class plus the fingerprint to verify it by. */
    record PreGenerated(Class<?> type, String routingFingerprint) {
    }

    /**
     * The index each loader can see, loaded on first use, never again. Class-path contents do not
     * change under a running loader.
     *
     * <p>A {@link WeakHashMap} so the loader key is not pinned: values hold only strings —
     * class names, fingerprints, source-location text — never the loader, its classes, or a
     * {@code URL} (whose stream handler can reach a loader; see {@link Registration}). An entry
     * therefore dies with its loader. Synchronized
     * because loads for distinct loaders may race; the lock is only ever taken on the first query
     * for a given <em>class</em> — every later one is answered by {@link #INDEX_BY_NEIGHBOUR}.
     */
    private static final Map<ClassLoader, Map<String, Registration>> INDEX_BY_LOADER =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * The per-loader index, memoised on the neighbour class.
     *
     * <p>This is the fast path's whole trick: {@link ClassValue} reads are unsynchronized and
     * near-free, and anchoring on the class means the cached reference is collected with the class
     * — nothing global points at the loader or its index. The value is shared with
     * {@link #INDEX_BY_LOADER}, which does the once-per-loader work.
     */
    private static final ClassValue<Map<String, Registration>> INDEX_BY_NEIGHBOUR =
            new ClassValue<>() {
                @Override
                protected Map<String, Registration> computeValue(Class<?> neighbour) {
                    return forLoader(neighbour.getClassLoader());
                }
            };

    /**
     * Conflicting-index failures, memoised per loader.
     *
     * <p>A conflict throws out of {@link #load}, so {@code computeIfAbsent} installs nothing —
     * and without this, every subsequent creation re-enumerated and re-parsed every index
     * resource on the loader, under the map's lock, before throwing again: a classpath I/O
     * storm on top of a misconfiguration. The failure stays loud, but it is diagnosed once.
     * Weak keys for the usual reason; the exception holds only strings.
     */
    private static final Map<ClassLoader, ClasswrightException> FAILED_LOADS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static Map<String, Registration> forLoader(ClassLoader loader) {
        if (loader == null) {
            // The bootstrap loader. It has no application resources to read an index from, and a
            // class it would place in one of its packages could not be defined on the class path
            // anyway (java.* is a prohibited package). Empty, rather than a NullPointerException.
            return Map.of();
        }
        ClasswrightException failed = FAILED_LOADS.get(loader);
        if (failed != null) {
            // The same instance each time; its stack names the first occurrence, its message the
            // conflicting resources, which is what anyone debugging this needs.
            throw failed;
        }
        try {
            return INDEX_BY_LOADER.computeIfAbsent(loader, AotProxies::load);
        } catch (ClasswrightException conflict) {
            FAILED_LOADS.put(loader, conflict);
            throw conflict;
        }
    }

    private static Map<String, Registration> load(ClassLoader loader) {
        Map<String, Registration> entries = new HashMap<>();
        try {
            Enumeration<URL> resources = loader.getResources(AheadOfTime.INDEX_RESOURCE);
            while (resources.hasMoreElements()) {
                read(resources.nextElement(), entries);
            }
        } catch (IOException e) {
            // A malformed or unreadable index must not stop the application: generating at
            // runtime is still available on any ordinary JVM. In a native image it will fail
            // later and more specifically, at the point a proxy is actually wanted.
            System.getLogger(AotProxies.class.getName()).log(System.Logger.Level.WARNING,
                    "could not read " + AheadOfTime.INDEX_RESOURCE
                            + "; ahead-of-time proxies will be ignored", e);
        }
        return Map.copyOf(entries);
    }

    /** Set in a GraalVM native image, where runtime generation cannot replace a refused index. */
    private static final boolean NATIVE_IMAGE =
            System.getProperty("org.graalvm.nativeimage.imagecode") != null;

    private static void read(URL resource, Map<String, Registration> entries) throws IOException {
        // Buffered per resource so the ABI verdict can reject the resource as a whole — the
        // directive is written first, but an index is accepted or refused as a unit either way.
        Map<String, Registration> fromResource = new HashMap<>();
        // The text form once, up front; registrations must not retain the URL (see Registration).
        String source = resource.toExternalForm();
        // Indexes predating the directive were written by ABI-1 generators.
        int declaredAbi = 1;
        try (InputStream stream = resource.openStream();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("%abi\t")) {
                    try {
                        declaredAbi = Integer.parseInt(line.substring("%abi\t".length()).trim());
                    } catch (NumberFormatException unreadable) {
                        declaredAbi = Integer.MIN_VALUE;    // unknown is not compatible
                    }
                    continue;
                }
                int firstTab = line.indexOf('\t');
                int secondTab = firstTab < 0 ? -1 : line.indexOf('\t', firstTab + 1);
                if (firstTab <= 0 || secondTab < 0) {
                    // Keys escape tabs, so a well-formed line always has three columns. Anything
                    // else is an index from a different (older or newer) Classwright; adopting a
                    // class without its fingerprint would disable the drift check, so skip it and
                    // say so — the runtime falls back to ordinary generation.
                    System.getLogger(AotProxies.class.getName()).log(
                            System.Logger.Level.WARNING,
                            "ignoring a malformed line in " + resource + ": '" + line
                                    + "'. The index was probably written by a different "
                                    + "Classwright version; regenerate it.");
                    continue;
                }
                merge(fromResource, line.substring(0, firstTab),
                        new Registration(line.substring(firstTab + 1, secondTab),
                                line.substring(secondTab + 1).trim(), source));
            }
        }

        if (declaredAbi != AheadOfTime.RUNTIME_ABI) {
            // The proxies behind this index were compiled against different runtime helpers, and
            // adopting one would surface as a NoSuchMethodError at first dispatch, far from the
            // cause. On an ordinary JVM the classes are simply regenerated, so refusing the index
            // costs one generation per shape; in a native image nothing can be generated, so
            // failing here — early, with the reason — is the only honest option.
            String reason = "the ahead-of-time index at " + resource + " was generated for "
                    + "Classwright runtime ABI " + declaredAbi + ", but this runtime requires ABI "
                    + AheadOfTime.RUNTIME_ABI + ". Regenerate the ahead-of-time proxies with the "
                    + "Classwright version the application runs against.";
            if (NATIVE_IMAGE) {
                throw new ClasswrightException(reason);
            }
            System.getLogger(AotProxies.class.getName()).log(System.Logger.Level.WARNING,
                    reason + " Ignoring the index; proxies will be generated at runtime.");
            return;
        }
        for (Map.Entry<String, Registration> entry : fromResource.entrySet()) {
            merge(entries, entry.getKey(), entry.getValue());
        }
    }

    /**
     * Adds one entry, refusing a conflicting duplicate.
     *
     * <p>The same key can legitimately appear in two resources — a jar that shades another, a test
     * class path overlaying the main one — and when both name the same class with the same
     * fingerprint, either is fine. When they disagree, choosing one by resource order would mean
     * the adopted proxy depends on class-path ordering, which is exactly the kind of
     * works-on-my-machine failure this library exists to end. Refuse, naming both sides.
     */
    private static void merge(Map<String, Registration> entries, String key,
                              Registration incoming) {
        Registration existing = entries.putIfAbsent(key, incoming);
        if (existing == null
                || (existing.className().equals(incoming.className())
                && existing.routingFingerprint().equals(incoming.routingFingerprint()))) {
            return;
        }
        throw new ClasswrightException("two ahead-of-time indexes disagree about the key '" + key
                + "': " + existing.source() + " maps it to " + existing.className()
                + ", but " + incoming.source() + " maps it to " + incoming.className()
                + ". These were generated from different configurations or different builds; "
                + "keep whichever is current and remove or regenerate the other.");
    }

    /**
     * Whether any ahead-of-time proxies are visible where {@code neighbour} lives.
     *
     * <p>The cheap gate {@link Enhancer} uses before paying to build a key.
     *
     * @param neighbour the class the proxy would be placed beside
     * @return {@code false} for an application that has not opted in
     */
    public static boolean isEmpty(Class<?> neighbour) {
        return INDEX_BY_NEIGHBOUR.get(neighbour).isEmpty();
    }

    /**
     * How many are registered for {@code neighbour}'s loader. For diagnostics and for tests.
     *
     * @param neighbour the class the proxy would be placed beside
     * @return the number of index entries its loader can see
     */
    public static int size(Class<?> neighbour) {
        return INDEX_BY_NEIGHBOUR.get(neighbour).size();
    }

    /**
     * Finds the class generated for {@code key}, if there is one.
     *
     * @param neighbour the class the proxy must be placed beside; decides which loader's index is
     *                  consulted and which loader resolves the class
     * @param key       a {@link ProxyBlueprint#key()}
     * @return the pre-generated class, or empty
     */
    public static Optional<Class<?>> find(Class<?> neighbour, String key) {
        return preGenerated(neighbour, key).map(PreGenerated::type);
    }

    /** As {@link #find}, but carrying the routing fingerprint {@code Enhancer} verifies. */
    static Optional<PreGenerated> preGenerated(Class<?> neighbour, String key) {
        Registration registration = INDEX_BY_NEIGHBOUR.get(neighbour).get(key);
        if (registration == null) {
            return Optional.empty();
        }
        try {
            // The neighbour's own loader, not the context loader: the generated class lives in
            // the neighbour's package and ships beside it, so this is the loader that can see it
            // — and in an application with several loaders, the only one guaranteed to see the
            // right one.
            return Optional.of(new PreGenerated(
                    Class.forName(registration.className(), false, neighbour.getClassLoader()),
                    registration.routingFingerprint()));
        } catch (ClassNotFoundException e) {
            // The index and the class path disagree. Almost always a stale index left behind by an
            // earlier build; say which entry, because the alternative is a mystery at startup.
            throw new ClasswrightException("the ahead-of-time index at " + registration.source()
                    + " names " + registration.className() + " for key '" + key
                    + "', but that class is not visible to " + neighbour.getName()
                    + "'s class loader. The index is probably stale — rebuild, or delete "
                    + AheadOfTime.INDEX_RESOURCE + ".", e);
        }
    }

    /**
     * A human-readable listing of what {@code neighbour}'s loader can see, for diagnostics.
     *
     * @param neighbour the class whose loader's index to describe
     * @return one entry per line
     */
    public static String describe(Class<?> neighbour) {
        Map<String, Registration> entries = INDEX_BY_NEIGHBOUR.get(neighbour);
        if (entries.isEmpty()) {
            return "No ahead-of-time proxies are registered; classes are generated on demand.";
        }
        StringBuilder text = new StringBuilder(entries.size()
                + " ahead-of-time proxies registered:\n");
        entries.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(
                        java.util.Comparator.comparing(Registration::className)))
                .forEach(entry -> text.append("  ").append(entry.getValue().className())
                        .append("\n    for ").append(entry.getKey()).append('\n'));
        return text.toString();
    }
}
