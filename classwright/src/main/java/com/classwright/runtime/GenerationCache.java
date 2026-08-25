package com.classwright.runtime;

import com.classwright.ClasswrightException;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Remembers generated classes so the same request does not generate twice.
 *
 * <p>Generating a proxy costs hundreds of microseconds, and applications ask for the same one over
 * and over, so a cache is not optional. What is interesting is making one that does not leak, since
 * CGLib's cache is a large part of why CGLib leaked.
 *
 * <h2>Two decisions, both load-bearing</h2>
 *
 * <p><strong>Keyed by {@link ClassValue}, not by a static map.</strong> CGLib used a static
 * {@code Map<ClassLoader, Map<Key, Class>>}, which is a strong reference from a permanently-live
 * static field to every class loader it had ever seen. In a container that redeploys applications,
 * that alone pins each dead application's loader and everything it ever loaded &mdash; the classic
 * redeploy leak. A {@code ClassValue} stores its entries <em>on the key class itself</em>, so when
 * the target class becomes unreachable its cache entry goes with it, automatically. There is no
 * global structure to leak from.
 *
 * <p><strong>Values are weak.</strong> A strong reference here would keep every generated class
 * loaded forever and quietly undo the entire point of using hidden classes. Weak references are
 * safe because of a happy property: <em>a generated class is strongly reachable from every live
 * instance of it</em>. So while an application is using its proxies, the cache hits every time;
 * once the last proxy instance is gone, the class becomes collectible and the cache entry clears
 * itself. The class lives exactly as long as it is needed, with nobody having to manage it.
 *
 * <p>The corollary is worth knowing: a workload whose proxies are all short-lived can have
 * <em>no</em> live instance at collection time, and then pays a full regeneration after every GC.
 * Two knobs exist for that shape. {@code -Dclasswright.cacheRetention=soft} switches the values to
 * {@link SoftReference}s, which ordinary collections leave alone and memory pressure still
 * reclaims &mdash; at the cost of making retention depend on the collector's opaque policy.
 * {@code -Dclasswright.cacheRetention=mru} instead keeps the most recently <em>generated</em>
 * classes per anchor strongly, {@code -Dclasswright.cacheMruSize} of them (default 16): hot shapes
 * survive any GC, retention is bounded and predictable, and because the ring lives on the anchor's
 * {@link ClassValue} entry, unloading the anchor still releases everything. Both are knobs rather
 * than the default because the default's behaviour &mdash; drop the references, collect, watch the
 * class go &mdash; is the reachability promise the rest of the library is built on.
 *
 * <p>Cache statistics are opt-in via {@code -Dclasswright.cacheStats=true}; see
 * {@link #statistics()}.
 *
 * <h2>What a key must satisfy</h2>
 *
 * <p>Keys need meaningful {@code equals} and {@code hashCode} &mdash; identity comparison would miss
 * every hit. More subtly, <strong>a key must not strongly reference anything from a foreign class
 * loader</strong>. The cache entry lives as long as the anchor class does, so a key holding, say, a
 * user-supplied callback instance would pin that instance and its loader for the same duration.
 * Keys should hold classes and simple values &mdash; or weak references with a captured hash, in
 * which case the key should implement {@link StaleKey} so a key whose referent is gone can be
 * swept. CGLib made the same demand and documented it barely; it is repeated here because getting
 * it wrong reintroduces the leak.
 */
public final class GenerationCache {

    /**
     * A key that can report its referents are gone.
     *
     * <p>For keys that hold parts of their identity weakly (a callback filter, an interface list).
     * Once a referent is collected the key can never match a probe again, so the entry is dead
     * weight; {@link #computeIfAbsent} sweeps such entries periodically as it generates.
     */
    public interface StaleKey {

        /**
         * Whether any weakly-held part of this key has been collected.
         *
         * @return {@code true} once the key can no longer match any probe
         */
        boolean isStale();
    }

    /**
     * A cheap lookup key that knows how to produce the weak form the cache may retain.
     *
     * <p>The rule that keys must not strongly reference foreign classes applies to what the cache
     * <em>stores</em>. A key that exists only for the duration of one lookup has no such
     * constraint — it can hold everything strongly and allocate nothing — and almost every lookup
     * is a hit, where nothing is ever stored. Implementing this interface splits the two roles:
     * probes stay cheap, and only a miss pays for the weak representation via {@link #retained()}.
     *
     * <p>The probe and its retained form must be mutually {@code equals}-compatible with identical
     * hashes, since the map compares a probe against stored keys on every hit.
     */
    public interface ProbeKey {

        /**
         * The representation of this key that is safe for the cache to retain.
         *
         * @return a key equal to this one whose foreign references are weak
         */
        Object retained();
    }

    /**
     * A weakly-held part of a cache key.
     *
     * <p>The sanctioned way to name a possibly-foreign class in a key without pinning its
     * loader: equality is referent identity, the hash is captured up front so equal keys hash
     * equally even after collection, and a collected referent matches nothing — which can only
     * cause a miss, never a wrong hit. A key made of these should implement {@link StaleKey} by
     * asking its parts, so the cache can sweep it once any referent is gone.
     */
    public static final class WeakPart {

        private final WeakReference<Object> referent;
        private final int hash;

        private WeakPart(Object referent, int hash) {
            this.referent = new WeakReference<>(referent);
            this.hash = hash;
        }

        /**
         * A part holding a class, hashed by its name so the hash survives collection.
         *
         * @param type the class to hold weakly
         * @return the part
         */
        public static WeakPart of(Class<?> type) {
            return new WeakPart(type, type.getName().hashCode());
        }

        /**
         * Parts for several classes, in order.
         *
         * @param types the classes to hold weakly
         * @return one part per class, in the same order
         */
        public static java.util.List<WeakPart> ofClasses(java.util.List<Class<?>> types) {
            java.util.List<WeakPart> parts = new java.util.ArrayList<>(types.size());
            for (Class<?> each : types) {
                parts.add(of(each));
            }
            return java.util.List.copyOf(parts);
        }

        /**
         * Whether any of the given parts has lost its referent.
         *
         * @param parts parts of a key
         * @return {@code true} once any referent is collected
         */
        public static boolean anyStale(java.util.List<WeakPart> parts) {
            for (WeakPart each : parts) {
                if (each.isStale()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether the referent has been collected.
         *
         * @return {@code true} once the referent is gone
         */
        public boolean isStale() {
            return referent.get() == null;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof WeakPart that)) {
                return false;
            }
            Object mine = referent.get();
            return mine != null && mine == that.referent.get();
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /**
     * Everything one anchor's cache owns: the entries, <em>their reference queue</em>, and the
     * sweep cursor.
     *
     * <p>The queue being per-anchor is a lifetime requirement, not a tidiness choice. A cleared
     * value reference is enqueued by the collector, and the reference strongly holds its map and
     * key so it can remove its own entry — and a stored key strongly holds the anchor. Behind a
     * <em>static</em> queue, that chain becomes {@code GC root → reference → key → anchor →
     * class loader}, pinning the application loader until some future cache operation happens to
     * drain the queue; unloading must never depend on future Classwright activity. Owned by the
     * anchor's {@link ClassValue} entry, every link of that chain — queue, reference, key, map —
     * sits inside the anchor's own object graph and is collected with it, drained or not.
     *
     * <p>Entry values are either a {@link Reference} to the generated class, or a
     * {@link Pending} while one thread is generating it. The value references the generated
     * class, which references the target as its superclass, which references this state. That is
     * a cycle, and a collector handles cycles: the whole group is collected together once
     * nothing outside it points in.
     */
    private static final class CacheState {

        final ConcurrentHashMap<Object, Object> entries = new ConcurrentHashMap<>(4);
        final java.lang.ref.ReferenceQueue<Class<?>> collected =
                new java.lang.ref.ReferenceQueue<>();

        /** Whether a miss is currently sweeping; losers skip rather than queue up. */
        final java.util.concurrent.atomic.AtomicBoolean sweeping =
                new java.util.concurrent.atomic.AtomicBoolean();

        /**
         * The strong retention ring for {@code cacheRetention=mru}; {@code null} when the mode
         * is off. Inside this state rather than in a {@link ClassValue} of its own, and the
         * placement is the correctness: with a separate {@code ClassValue}, a generation already
         * in flight when {@link #invalidate} ran would call {@code MRU.get(anchor)} on
         * completion, mint a <em>fresh</em> ring, and strongly retain the very class the
         * invalidation was supposed to drop — invisible through the cache, immortal through the
         * ring. Owned here, that late retain lands in the detached state the generation started
         * with, which becomes unreachable when its call returns; one anchor, one state, one
         * ownership story.
         */
        final MruRing mru = MRU_SIZE > 0 ? new MruRing() : null;

        /** Where the stale-key sweep resumes; written only while {@link #sweeping} is held. */
        private java.util.Iterator<Map.Entry<Object, Object>> sweepCursor;
    }

    private static final ClassValue<CacheState> ENTRIES =
            new ClassValue<>() {
                @Override
                protected CacheState computeValue(Class<?> target) {
                    return new CacheState();
                }
            };

    /** See the class documentation; {@code -Dclasswright.cacheRetention=soft|mru} to change. */
    private static final String RETENTION =
            System.getProperty("classwright.cacheRetention", "weak");

    private static final boolean SOFT_VALUES = "soft".equalsIgnoreCase(RETENTION);

    /**
     * With {@code -Dclasswright.cacheRetention=mru}, how many recently generated classes each
     * anchor keeps strongly ({@code -Dclasswright.cacheMruSize}, default 16). Zero otherwise.
     *
     * <p>The compromise between the weak default and {@code soft}: a workload whose proxies are
     * all short-lived can find its hot shapes collected on every GC and regenerate them each
     * time. A small strong ring stops exactly that, with a bound on what it can retain — and
     * because the ring hangs off the anchor's {@link ClassValue} entry, unloading the anchor
     * still releases everything, so the library's no-leak promise is undisturbed. It is a knob
     * rather than the default because the default's observable behaviour — drop the references,
     * collect, watch the class go — is documented, tested, and part of the point.
     */
    private static final int MRU_SIZE = "mru".equalsIgnoreCase(RETENTION)
            ? Math.max(1, Integer.getInteger("classwright.cacheMruSize", 16))
            : 0;

    /**
     * Statistics are opt-in: {@code -Dclasswright.cacheStats=true}.
     *
     * <p>The counters themselves are cheap, but the cache-hit path is measured in tens of
     * nanoseconds and the cheapest operation is the one not executed. A {@code static final}
     * false folds every increment away entirely.
     */
    private static final boolean STATS_ENABLED = Boolean.getBoolean("classwright.cacheStats");

    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();
    private static final LongAdder RECLAIMED = new LongAdder();

    /**
     * A value reference that can remove its own map entry once cleared.
     *
     * <p>What makes cleanup proportional to garbage rather than to cache size: the collector
     * delivers cleared references to their {@link CacheState}'s own queue, each knows its map
     * and key, and a miss pays {@code O(collected)} instead of sweeping every entry. The map and
     * key are held strongly, which is safe precisely because the queue is anchor-owned — see
     * {@link CacheState} for why it must never be static.
     */
    private interface SelfRemoving {

        /** Removes this reference's own entry; whether the removal actually happened. */
        boolean removeSelf();
    }

    private static final class KeyedWeakValue extends WeakReference<Class<?>>
            implements SelfRemoving {

        private final Map<Object, Object> entries;
        private final Object key;

        KeyedWeakValue(Class<?> generated, CacheState owner, Object key) {
            super(generated, owner.collected);
            this.entries = owner.entries;
            this.key = key;
        }

        @Override
        public boolean removeSelf() {
            return entries.remove(key, this);
        }
    }

    private static final class KeyedSoftValue extends SoftReference<Class<?>>
            implements SelfRemoving {

        private final Map<Object, Object> entries;
        private final Object key;

        KeyedSoftValue(Class<?> generated, CacheState owner, Object key) {
            super(generated, owner.collected);
            this.entries = owner.entries;
            this.key = key;
        }

        @Override
        public boolean removeSelf() {
            return entries.remove(key, this);
        }
    }

    /**
     * The strong retention ring for {@code cacheRetention=mru}; see {@link #MRU_SIZE}.
     *
     * <p>Written on generation only, never on a hit: once every hot shape is cached there are no
     * more misses, so nothing rotates out and the hit path stays untouched. Synchronised because
     * it is only ever reached alongside a generation, whose cost dwarfs any lock.
     */
    private static final class MruRing {

        private final Class<?>[] recent = new Class<?>[MRU_SIZE];
        private int cursor;

        synchronized void retain(Class<?> generated) {
            recent[cursor] = generated;
            cursor = (cursor + 1) % recent.length;
        }
    }

    /**
     * How many entries each miss's stale-key sweep visits.
     *
     * <p>The reference queue removes dead <em>values</em> exactly, but a key whose weak part was
     * collected while its class lives (a collected {@code CallbackFilter}, typically) is only
     * found by asking it. Each such entry is a few dozen bytes and disappears anyway when its
     * value is collected, so the sweep exists only to bound the lingering. A full walk per miss
     * was {@code O(entries)}; a full walk every Nth miss was still {@code O(entries)} per
     * interval, which over sustained monotonic growth remains quadratic with a smaller constant.
     * A fixed stride per miss, resuming where the last sweep stopped, makes housekeeping cost
     * independent of cache size — genuinely linear over any workload — while still visiting
     * every entry once per {@code size/stride} misses.
     */
    private static final int STALE_SWEEP_STRIDE = 64;

    /** A generation in flight: the owner runs the generator, everyone else waits on the future. */
    private static final class Pending {

        final Thread owner = Thread.currentThread();
        final CompletableFuture<Class<?>> future = new CompletableFuture<>();
    }

    private GenerationCache() {
    }

    /**
     * Returns the cached class for {@code (target, key)}, generating it if absent or collected.
     *
     * <p>Generation is single-flight but runs <em>outside</em> the map's locks: the winning thread
     * installs a placeholder with a cheap {@code compute}, releases the lock, and only then runs
     * the generator; racing threads wait on the placeholder rather than generating a duplicate.
     * That matters because the generator is not under Classwright's control &mdash; it calls user
     * code (a {@code CallbackFilter}, a superclass initialiser), and user code that touched this
     * cache from inside a {@code ConcurrentHashMap.compute} would deadlock or corrupt the map. A
     * generator that re-enters this method <em>for the same key</em> is still impossible to
     * satisfy, and is reported as an error rather than deadlocking.
     *
     * @param target    the class the cache entry should live and die with; also what the generated
     *                  class is placed beside
     * @param key       identifies this particular configuration; see the class documentation for
     *                  what it must satisfy
     * @param generator produces the class when there is no usable cached one
     * @return the cached or freshly generated class
     */
    public static Class<?> computeIfAbsent(Class<?> target, Object key,
                                           Supplier<Class<?>> generator) {
        // Deliberately tiny, and the size is load-bearing. This is the hottest framework
        // control path in the library — every cached create() runs it — and it must stay under
        // HotSpot's hot-method inline budget so it inlines into its caller, where escape
        // analysis can scalar-replace the caller's transient probe key. Folding the miss logic
        // in here once pushed the method past the budget ("hot method too big"), the call
        // boundary stopped inlining, the probe stopped being eliminated, and cached-create
        // allocation regressed by ~50 B/op with not one line of the fast path changed. Anything
        // rare belongs in generateMissing, behind the call.
        CacheState state = ENTRIES.get(target);
        Object current = state.entries.get(key);
        if (current instanceof Reference<?> reference) {
            Object cached = reference.get();
            if (cached != null) {
                if (STATS_ENABLED) {
                    HITS.increment();
                }
                return (Class<?>) cached;
            }
        } else if (current instanceof Pending pending) {
            return await(pending);
        }
        return generateMissing(state, target, key, generator);
    }

    /** The cold path: everything a miss needs, kept out of the inlineable method above. */
    private static Class<?> generateMissing(CacheState state, Class<?> target, Object key,
                                            Supplier<Class<?>> generator) {
        Map<Object, Object> entries = state.entries;

        while (true) {
            // Re-probed each round: the first iteration duplicates the caller's fast probe (one
            // map read on a path about to spend hundreds of microseconds generating), and a
            // retry after losing a race must see what the winner installed.
            Object current = entries.get(key);
            if (current instanceof Reference<?> reference) {
                Object cached = reference.get();
                if (cached != null) {
                    if (STATS_ENABLED) {
                        HITS.increment();
                    }
                    return (Class<?>) cached;
                }
            } else if (current instanceof Pending pending) {
                return await(pending);
            }

            // A miss (or a collected entry). Only now may anything be retained, so only now is
            // the probe converted to the weak form the map is allowed to keep.
            Object stored = key instanceof ProbeKey probe ? probe.retained() : key;
            Pending mine = new Pending();
            Object raced = entries.compute(stored, (unusedKey, existing) -> {
                if (existing instanceof Reference<?> reference && reference.get() != null) {
                    return existing;             // another thread finished first
                }
                if (existing instanceof Pending) {
                    return existing;             // another thread is generating right now
                }
                if (existing != null && STATS_ENABLED) {
                    RECLAIMED.increment();       // a collected class; regenerate
                }
                return mine;
            });

            if (raced instanceof Reference<?> reference) {
                Object cached = reference.get();
                if (cached != null) {
                    if (STATS_ENABLED) {
                        HITS.increment();
                    }
                    return (Class<?>) cached;
                }
                continue;                        // collected in the race window; try again
            }
            if (raced != mine) {
                return await((Pending) raced);
            }

            if (STATS_ENABLED) {
                MISSES.increment();
            }
            Class<?> generated;
            try {
                generated = generator.get();
                if (generated == null) {
                    // A public utility path; a generator bug should say what happened, not
                    // surface later as an inexplicable NullPointerException from a cache hit.
                    throw new ClasswrightException(
                            "the generator returned null instead of a generated class");
                }
            } catch (RuntimeException | Error failure) {
                entries.remove(stored, mine);
                mine.future.completeExceptionally(failure);
                throw failure;
            }
            entries.replace(stored, mine, newValueReference(generated, state, stored));
            mine.future.complete(generated);
            if (state.mru != null) {
                state.mru.retain(generated);
            }
            housekeep(state);
            return generated;
        }
    }

    /**
     * How long a thread waits for another thread's generation before giving up.
     *
     * <p>Long enough that no honest generation — hundreds of microseconds, plus whatever user
     * code a {@code CallbackFilter} runs — ever meets it. What does meet it is a cross-key
     * cycle through user code: thread A generating key X whose filter asks for key Y, while
     * thread B generating Y asks for X. Same-thread cycles are detected exactly (below), but a
     * cross-thread cycle has no cheap detection, and an unbounded {@code join()} turned it into
     * a silent, uninterruptible hang. A bounded wait converts it into an exception naming the
     * other thread and its stack — a diagnosis instead of a thread dump nobody takes.
     */
    private static final long AWAIT_SECONDS = 60;

    private static Class<?> await(Pending pending) {
        if (pending.owner == Thread.currentThread()) {
            throw new ClasswrightException("recursive generation: while generating this class, "
                    + "user code (a CallbackFilter, or code run by the superclass) asked for the "
                    + "same proxy configuration again. Break the cycle, for example by creating "
                    + "the inner proxy lazily.");
        }
        try {
            Class<?> generated = pending.future.get(AWAIT_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS);
            if (STATS_ENABLED) {
                HITS.increment();
            }
            return generated;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ClasswrightException(
                    "interrupted while another thread was generating this class");
        } catch (java.util.concurrent.TimeoutException stuck) {
            throw stuckWaitingOn(pending);
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new ClasswrightException("generation failed in another thread: " + cause, cause);
        }
    }

    private static ClasswrightException stuckWaitingOn(Pending pending) {
        StringBuilder message = new StringBuilder("waited ").append(AWAIT_SECONDS)
                .append(" seconds for thread '").append(pending.owner.getName())
                .append("' to finish generating this class. Either its generation is "
                        + "extraordinarily slow, or two threads' user code (a CallbackFilter "
                        + "creating another proxy, say) are each waiting on a class the other "
                        + "owns — a deadlock. Break the cycle, for example by creating the inner "
                        + "proxy lazily. The owning thread was at:");
        for (StackTraceElement frame : pending.owner.getStackTrace()) {
            message.append("\n\tat ").append(frame);
        }
        return new ClasswrightException(message.toString());
    }

    private static Reference<Class<?>> newValueReference(Class<?> generated,
                                                         CacheState state, Object key) {
        return SOFT_VALUES
                ? new KeyedSoftValue(generated, state, key)
                : new KeyedWeakValue(generated, state, key);
    }

    /**
     * Post-generation housekeeping: exact for collected values, bounded for stale keys.
     *
     * <p>Runs on the miss path only, for this anchor's own state. Draining the queue is
     * {@code O(collected)}; the stale-key sweep visits at most {@link #STALE_SWEEP_STRIDE}
     * entries, resuming where it last stopped, so the cost per miss is a constant regardless of
     * how large the cache has grown.
     */
    private static void housekeep(CacheState state) {
        drainCollected(state);
        incrementalSweep(state);
    }

    private static int drainCollected(CacheState state) {
        int removed = 0;
        for (Reference<? extends Class<?>> cleared; (cleared = state.collected.poll()) != null; ) {
            if (((SelfRemoving) cleared).removeSelf()) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Advances the resumable stale-key sweep by at most one stride, at most one pass.
     *
     * <p>Two boundaries, both deliberate. The stride caps how much of a <em>large</em> map one
     * miss inspects. Stopping at end-of-map caps how often a <em>small</em> map is inspected:
     * restarting the iterator inside one call made a five-entry cache scan its five entries
     * thirteen times over — twelve fresh iterators for nothing — so small caches paid
     * <em>more</em> housekeeping per miss than large ones. One call now visits each entry at
     * most once; the next miss starts the next pass.
     *
     * <p>Guarded by a compare-and-set rather than a monitor: concurrent misses for different
     * keys generate concurrently and would otherwise serialise here at the very end. A loser
     * skips this stride entirely — housekeeping is best-effort by nature, and the entries it
     * would have visited are still there for the next miss. The winning thread's cursor writes
     * are ordered by the flag's release/acquire.
     *
     * <p>The iterator is weakly consistent, which is exactly right for housekeeping — an entry
     * added mid-pass is simply seen on a later one.
     */
    private static void incrementalSweep(CacheState state) {
        if (!state.sweeping.compareAndSet(false, true)) {
            return;
        }
        try {
            java.util.Iterator<Map.Entry<Object, Object>> cursor = state.sweepCursor;
            for (int visited = 0; visited < STALE_SWEEP_STRIDE; visited++) {
                if (cursor == null) {
                    cursor = state.entries.entrySet().iterator();
                }
                if (!cursor.hasNext()) {
                    cursor = null;          // pass complete; the next miss starts a new one
                    break;
                }
                Map.Entry<Object, Object> entry = cursor.next();
                boolean dead = entry.getValue() instanceof Reference<?> reference
                        && reference.get() == null
                        || entry.getKey() instanceof StaleKey stale && stale.isStale();
                if (dead) {
                    state.entries.remove(entry.getKey(), entry.getValue());
                }
            }
            state.sweepCursor = cursor;
        } finally {
            state.sweeping.set(false);
        }
    }

    private static int purgeStale(Map<Object, Object> entries) {
        int removed = 0;
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            boolean dead = entry.getValue() instanceof Reference<?> reference
                    && reference.get() == null
                    || entry.getKey() instanceof StaleKey stale && stale.isStale();
            if (dead && entries.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Whether a class is currently cached for this configuration.
     *
     * <p>Intended for tests and diagnostics. In particular it is how the unloading behaviour is
     * verified: drop every reference, collect, and observe the entry go away by itself.
     *
     * @param target the class the entry is anchored on
     * @param key    the configuration key
     * @return whether a live cached class exists
     */
    public static boolean isCached(Class<?> target, Object key) {
        return ENTRIES.get(target).entries.get(key) instanceof Reference<?> reference
                && reference.get() != null;
    }

    /**
     * Removes entries whose class has been collected, and entries whose key reports itself stale.
     *
     * <p>Purely housekeeping, and also performed automatically whenever a generation runs: a dead
     * entry occupies a few dozen bytes and is replaced automatically on the next request for the
     * same key, so leaving them costs almost nothing. Exposed because "almost nothing" times a
     * very large number is still something.
     *
     * @param target the class whose cache should be swept
     * @return how many dead entries were removed
     */
    public static int purgeCollectedEntries(Class<?> target) {
        // Counted as successful removals, not as a size difference: concurrent insertions can
        // grow the map while the purge runs, and size-before minus size-after then goes
        // negative — a wrong answer from a diagnostic whose one job is the number.
        CacheState state = ENTRIES.get(target);
        return drainCollected(state) + purgeStale(state.entries);
    }

    /**
     * Drops every cached class for one target. Does not unload anything still in use.
     *
     * <p>Removes the whole per-target state — entries, reference queue, sweep cursor, and the
     * strong MRU ring when {@code cacheRetention=mru} is active — as one {@link ClassValue}
     * removal; the next request builds fresh state. The ring living <em>inside</em> the state
     * is what makes this airtight against a generation already in flight: its late
     * {@code retain} lands in the detached state, which dies when that call returns, so nothing
     * an invalidation dropped can be resurrected into live retention. See
     * {@link CacheState#mru}.
     *
     * @param target the class whose cached entries should be dropped
     */
    public static void invalidate(Class<?> target) {
        ENTRIES.remove(target);
    }

    /**
     * A snapshot of cache activity.
     *
     * @param hits                       lookups answered by a live cached class
     * @param misses                     lookups that ran the generator
     * @param regeneratedAfterCollection misses whose previous class had been collected — the
     *                                   regeneration-churn signal the retention knobs exist for
     */
    public record Statistics(long hits, long misses, long regeneratedAfterCollection) {

        /**
         * Fraction of requests served from cache, or 1.0 when nothing has been requested.
         *
         * @return hits as a fraction of all lookups, or {@code 1.0} when there have been none
         */
        public double hitRate() {
            long total = hits + misses;
            return total == 0 ? 1.0 : (double) hits / total;
        }

        @Override
        public String toString() {
            return String.format("cache: %d hits, %d misses (%.1f%% hit rate), "
                    + "%d regenerated after collection", hits, misses, hitRate() * 100,
                    regeneratedAfterCollection);
        }
    }

    /**
     * A snapshot of the counters.
     *
     * <p>Counting is opt-in: start the JVM with {@code -Dclasswright.cacheStats=true}, or every
     * figure here stays zero. The property is read once, so the disabled counters cost the hit
     * path nothing at all — and for a path measured in tens of nanoseconds, that is the point.
     *
     * @return the statistics as of now; all zero unless {@code classwright.cacheStats} is set
     */
    public static Statistics statistics() {
        return new Statistics(HITS.sum(), MISSES.sum(), RECLAIMED.sum());
    }

    /** Resets the counters. For tests; does not touch cached classes. */
    public static void resetStatistics() {
        HITS.reset();
        MISSES.reset();
        RECLAIMED.reset();
    }
}
