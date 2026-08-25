package com.classwright.proxy;

import com.classwright.ClasswrightException;
import com.classwright.runtime.ClassDefiner;
import com.classwright.runtime.DefinedClass;
import com.classwright.runtime.DefinitionSite;
import com.classwright.runtime.DefinitionStrategy;
import com.classwright.runtime.GenerationCache;
import com.classwright.runtime.LookupResolver;

import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Creates proxies by generating a subclass at runtime.
 *
 * <pre>{@code
 * Service proxy = (Service) Enhancer.create(Service.class,
 *         (MethodInterceptor) (obj, method, args, methodProxy) -> {
 *             System.out.println("calling " + method.getName());
 *             return methodProxy.invokeSuper(obj, args);
 *         });
 * }</pre>
 *
 * <p>The API deliberately mirrors {@code net.sf.cglib.proxy.Enhancer}, method for method, so that
 * migrating from CGLib is a package rename rather than a rewrite.
 *
 * <h2>Differences worth knowing about</h2>
 *
 * <p><strong>Generated classes are hidden by default and can be unloaded.</strong> This is the
 * point of the library, and it means {@code proxy.getClass().getName()} is not resolvable with
 * {@code Class.forName}. The name still contains {@code $$} followed by the target's name, so the
 * framework heuristics that look for that keep working. Where a resolvable name is genuinely
 * required, {@link #setDefinitionStrategy} accepts {@link DefinitionStrategy#named()} — at the cost
 * of those classes never being reclaimed.
 *
 * <p><strong>Skipped methods can be explained.</strong> {@link #describeSkippedMethods()} reports
 * every method that will not be intercepted and why. CGLib silently ignored final methods, which
 * produced a long tail of "why is my interceptor not firing".
 *
 * <p>Instances are not thread-safe; configure one and call {@link #create()} from a single thread.
 * The generated classes and the cache behind them are thread-safe.
 */
public class Enhancer {

    private static final Class<?>[] NO_TYPES = new Class<?>[0];
    private static final Object[] NO_ARGUMENTS = new Object[0];

    /**
     * Ahead-of-time proxies already handed their reflective metadata. See {@code useAheadOfTime}.
     *
     * <p>A {@link ClassValue} rather than a static map, because a map's keys would pin every
     * adopted proxy class — and with it that class's loader — for the life of the JVM. In a native
     * image the classes are permanent anyway, but on an ordinary JVM ahead-of-time proxies can
     * belong to a redeployable loader (a web application that pre-generates, say), and a global
     * map would be precisely the redeploy leak this library exists to end. The flag lives on the
     * class itself and is collected with it.
     */
    private static final ClassValue<AtomicBoolean> AOT_INITIALISED = new ClassValue<>() {
        @Override
        protected AtomicBoolean computeValue(Class<?> proxyClass) {
            return new AtomicBoolean();
        }
    };

    private Class<?> superclass = Object.class;
    private List<Class<?>> interfaces = List.of();
    private Callback[] callbacks;
    private Class<?>[] callbackTypes;
    private CallbackFilter callbackFilter;
    private DefinitionStrategy definitionStrategy;

    private boolean useFactory = true;
    private boolean interceptDuringConstruction = true;
    private boolean useCache = true;
    private boolean copyAnnotations;
    private NamingConvention naming = NamingConvention.DEFAULT;

    /** Populated by the last {@link #createClass()} or {@link #create()}, for diagnostics. */
    private ProxyMethods lastPlan;

    /**
     * Creates an unconfigured enhancer; set at least a superclass or interfaces and a callback,
     * then call {@link #create()}.
     */
    public Enhancer() {
    }

    // ==========================================================================================
    // Convenience factories, matching CGLib
    // ==========================================================================================

    /**
     * Creates a proxy extending {@code type} with a single callback.
     *
     * @param type     the class to subclass
     * @param callback handles every proxied method
     * @return the proxy instance
     */
    public static Object create(Class<?> type, Callback callback) {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(type);
        enhancer.setCallback(callback);
        return enhancer.create();
    }

    /**
     * Creates a proxy extending {@code type} and implementing additional interfaces.
     *
     * @param type       the class to subclass
     * @param interfaces additional interfaces to implement
     * @param callback   handles every proxied method
     * @return the proxy instance
     */
    public static Object create(Class<?> type, Class<?>[] interfaces, Callback callback) {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(type);
        enhancer.setInterfaces(interfaces);
        enhancer.setCallback(callback);
        return enhancer.create();
    }

    /**
     * Registers callbacks for instances of a generated class created on <em>this thread</em>.
     *
     * <p>Serves the deferred-binding flow CGLib supported and frameworks rely on:
     * {@link #createClass()} first, instances later — through the class's own constructor, or
     * through no constructor at all (Objenesis-style allocation). A constructed instance binds
     * the registration during construction; a constructor-less instance binds it lazily on its
     * first dispatched call. Array slots may be {@code null} to leave a slot unbound.
     *
     * @param proxyClass a class generated by an {@code Enhancer}
     * @param callbacks  the callbacks, or {@code null} to remove this thread's registration
     */
    public static void registerCallbacks(Class<?> proxyClass, Callback[] callbacks) {
        requireGenerated(proxyClass);
        CallbackRegistry.register(proxyClass, callbacks);
    }

    /**
     * As {@link #registerCallbacks}, but visible to every thread. A per-thread registration wins
     * over this one.
     *
     * @param proxyClass a class generated by an {@code Enhancer}
     * @param callbacks  the callbacks, or {@code null} to remove the registration
     */
    public static void registerStaticCallbacks(Class<?> proxyClass, Callback[] callbacks) {
        requireGenerated(proxyClass);
        CallbackRegistry.registerStatic(proxyClass, callbacks);
    }

    private static void requireGenerated(Class<?> proxyClass) {
        if (!ProxySupport.isGeneratedProxy(proxyClass)) {
            throw new ClasswrightException(proxyClass.getName() + " is not a class generated by "
                    + "Enhancer, so callbacks cannot be registered for it");
        }
    }

    // ==========================================================================================
    // Configuration
    // ==========================================================================================

    /**
     * The class the proxy will extend. Defaults to {@link Object}.
     *
     * @param superclass a non-final, non-sealed class
     */
    public void setSuperclass(Class<?> superclass) {
        if (superclass != null && superclass.isInterface()) {
            // A pointed error rather than a confusing one later: passing an interface here is an
            // easy mistake, and CGLib quietly reinterpreted it.
            throw new ClasswrightException(superclass.getName() + " is an interface. Use "
                    + "setInterfaces() and leave the superclass as Object.");
        }
        this.superclass = superclass == null ? Object.class : superclass;
    }

    /**
     * Additional interfaces the proxy will implement.
     *
     * @param interfaces interfaces, or {@code null} for none
     */
    public void setInterfaces(Class<?>... interfaces) {
        this.interfaces = interfaces == null ? List.of() : List.of(interfaces);
    }

    /**
     * The single callback for every proxied method.
     *
     * <p>Implemented directly rather than via {@code setCallbacks(new Callback[]{...})}: this is
     * the overwhelmingly common configuration and sits on the cache-hit path, where building an
     * array only for {@code setCallbacks} to clone it again was two allocations for the price of
     * none.
     *
     * @param callback the callback
     */
    public void setCallback(Callback callback) {
        if (callback == null) {
            throw new ClasswrightException("callback 0 is null; use NoOp.INSTANCE to "
                    + "leave a method alone");
        }
        this.callbacks = new Callback[]{callback};
        this.callbackTypes = new Class<?>[]{callbackInterfaceOf(callback)};
    }

    /**
     * Several callbacks, selected per method by a {@link CallbackFilter}.
     *
     * @param callbacks one per index the filter can return
     */
    public void setCallbacks(Callback... callbacks) {
        if (callbacks == null || callbacks.length == 0) {
            throw new ClasswrightException("at least one callback is required");
        }
        for (int i = 0; i < callbacks.length; i++) {
            if (callbacks[i] == null) {
                throw new ClasswrightException("callback " + i + " is null; use NoOp.INSTANCE to "
                        + "leave a method alone");
            }
        }
        this.callbacks = callbacks.clone();
        Class<?>[] types = new Class<?>[callbacks.length];
        for (int i = 0; i < callbacks.length; i++) {
            types[i] = callbackInterfaceOf(callbacks[i]);
        }
        this.callbackTypes = types;
    }

    /**
     * The callback types, for generating a class without instantiating it.
     *
     * <p>Only needed with {@link #createClass()}, where there are no callback instances to infer
     * types from.
     *
     * @param callbackTypes the callback interfaces, in index order
     */
    public void setCallbackTypes(Class<?>... callbackTypes) {
        this.callbackTypes = callbackTypes == null ? null : callbackTypes.clone();
        this.callbacks = null;
    }

    /**
     * Chooses which callback handles which method.
     *
     * <p>Required when there is more than one callback. Implementations must define
     * {@code equals} and {@code hashCode}; see {@link CallbackFilter}.
     *
     * @param callbackFilter the filter, or {@code null} to send everything to callback 0
     */
    public void setCallbackFilter(CallbackFilter callbackFilter) {
        this.callbackFilter = callbackFilter;
    }

    /**
     * Whether the proxy implements {@link Factory}. Defaults to {@code true}.
     *
     * @param useFactory whether to add the interface
     */
    public void setUseFactory(boolean useFactory) {
        this.useFactory = useFactory;
    }

    /**
     * Whether callbacks are active for calls made from within a constructor. Defaults to
     * {@code true}, and that includes calls the <em>superclass</em> constructor makes on
     * {@code this} — an override invoked during {@code super()} binds the construction's parked
     * callbacks on entry, exactly as CGLib's proxies did. The interceptor then runs against a
     * half-built object, which is the classic hazard of constructor-time virtual calls; if that
     * is unacceptable, set this to {@code false}.
     *
     * <p>Setting it to {@code false} adds a flag check to every proxied method and routes every
     * call made before construction completes to the original implementation.
     *
     * @param interceptDuringConstruction whether to intercept during construction
     */
    public void setInterceptDuringConstruction(boolean interceptDuringConstruction) {
        this.interceptDuringConstruction = interceptDuringConstruction;
    }

    /**
     * Whether to reuse an equivalent generated class. Defaults to {@code true}.
     *
     * <p>Turning this off generates a fresh class for every call, which is occasionally useful in
     * tests and is a reliable way to exhaust metaspace in production.
     *
     * @param useCache whether to consult the cache
     */
    public void setUseCache(boolean useCache) {
        this.useCache = useCache;
    }

    /**
     * Whether to reproduce the target's annotations and generic signatures on the proxy. Defaults
     * to {@code false}.
     *
     * <p>With this on, {@code proxy.getClass().getAnnotation(...)} and
     * {@code method.getGenericReturnType()} answer the same way the original does. CGLib copied
     * neither, which is why frameworks built on it grew heuristics — unwrap the proxy, walk to the
     * superclass, look for {@code $$} in the name — that exist purely to recover information the
     * proxy had discarded.
     *
     * <p>Off by default nonetheless, because annotations carry semantics. A framework that scans
     * for {@code @Transactional} and finds it on a class the user never wrote may behave
     * differently, and that surprise should be opted into rather than inherited.
     *
     * <p>Only annotations the target <em>declares</em> are copied, and only
     * <em>runtime-visible</em> ones — those retained with {@code RetentionPolicy.RUNTIME}. The
     * copy is made through reflection ({@code getDeclaredAnnotations()}), which is all this
     * library uses; a {@code RetentionPolicy.CLASS} annotation exists only in the class file,
     * and recovering it would mean parsing class files — the architecture Classwright
     * deliberately avoids. Anything meta-annotated {@code @Inherited} is already visible on a
     * subclass without copying.
     *
     * @param copyAnnotations whether to copy annotations and generic signatures
     */
    public void setCopyAnnotations(boolean copyAnnotations) {
        this.copyAnnotations = copyAnnotations;
    }

    /**
     * How the generated class and its members are named. Defaults to
     * {@link NamingConvention#DEFAULT}.
     *
     * <p>Set this to {@link NamingConvention#CGLIB_COMPATIBLE} when migrating an application whose
     * code inspects proxy names — matching {@code EnhancerByCGLIB}, or skipping members prefixed
     * {@code CGLIB$}. That is what the {@code classwright-cglib-compat} artifact does.
     *
     * @param naming the convention to use
     */
    public void setNamingConvention(NamingConvention naming) {
        this.naming = naming == null ? NamingConvention.DEFAULT : naming;
    }

    /**
     * Overrides how the generated class is defined.
     *
     * <p>Leave unset unless something specifically requires a class resolvable by name, in which
     * case pass {@link DefinitionStrategy#named()} and accept that those classes are never
     * unloaded.
     *
     * @param definitionStrategy the strategy, or {@code null} to choose automatically
     */
    public void setDefinitionStrategy(DefinitionStrategy definitionStrategy) {
        this.definitionStrategy = definitionStrategy;
    }

    // ==========================================================================================
    // Creation
    // ==========================================================================================

    /**
     * Generates the proxy class and creates an instance using the no-argument constructor.
     *
     * @return the proxy
     */
    public Object create() {
        return createInstance(NO_TYPES, NO_ARGUMENTS);
    }

    /**
     * Generates the proxy class and creates an instance using a specific superclass constructor.
     *
     * @param parameterTypes the constructor signature to invoke
     * @param arguments      the constructor arguments
     * @return the proxy
     */
    public Object create(Class<?>[] parameterTypes, Object[] arguments) {
        return createInstance(parameterTypes, arguments);
    }

    /**
     * Generates the proxy class without creating an instance.
     *
     * <p>Callbacks are then set per instance through {@link Factory}, or by passing them when the
     * instance is created. Requires {@link #setCallbackTypes} if no callbacks were supplied.
     *
     * @return the generated class
     */
    public Class<?> createClass() {
        return generateClass();
    }

    /**
     * Explains which methods of the configured superclass will not be intercepted.
     *
     * <p>Available after {@link #create()} or {@link #createClass()}. The answer to "why is my
     * interceptor not firing", which CGLib left users to work out themselves.
     *
     * @return one line per skipped method, with a reason
     */
    public String describeSkippedMethods() {
        ProxyMethods plan = lastPlan;
        if (plan == null) {
            // Not generated in this process, or served from cache without re-running discovery.
            // Work it out now: this is a diagnostic call, so the cost does not matter.
            Class<?> neighbour = placementNeighbour();
            ClassDefiner definer = definitionStrategy == null
                    ? ClassDefiner.alongside(neighbour)
                    : ClassDefiner.using(neighbour, definitionStrategy);
            plan = ProxyMethods.discover(superclass, interfaces,
                    definer.canOverridePackagePrivate());
        }
        StringBuilder description = new StringBuilder(plan.describeSkipped());
        // The generator additionally leaves a method unintercepted when its name and descriptor
        // collide with a member it emits (cwInvokeSuper, or Factory's methods). Discovery cannot
        // know that, so the answer would otherwise be silently incomplete for exactly the case a
        // user is most confused by.
        for (ProxyMethods.Proxied proxied : plan.proxied()) {
            Method method = proxied.method();
            if (ProxyClassGenerator.collidesWithGeneratedMember(method.getName(),
                    com.classwright.core.CwMethodType.of(method).descriptor(), useFactory)) {
                description.append("\n  ")
                        .append(method.getDeclaringClass().getSimpleName()).append('.')
                        .append(method.getName())
                        .append(" (its name and descriptor collide with a member the generated "
                                + "class must declare, so it is not intercepted)");
            }
        }
        return description.toString();
    }

    // ==========================================================================================
    // Internals
    // ==========================================================================================

    private Object createInstance(Class<?>[] parameterTypes, Object[] arguments) {
        if (callbacks == null) {
            throw new ClasswrightException("no callbacks were set. Use setCallback(...) before "
                    + "create(), or createClass() if you intend to supply them per instance.");
        }
        Class<?> proxyClass = generateClass();
        return ProxySupport.newInstance(proxyClass, parameterTypes, arguments, callbacks);
    }

    private Class<?> generateClass() {
        if (callbackTypes == null || callbackTypes.length == 0) {
            throw new ClasswrightException("no callbacks or callback types were set");
        }
        if (callbackTypes.length > 1 && callbackFilter == null) {
            throw new ClasswrightException("there are " + callbackTypes.length + " callbacks but "
                    + "no CallbackFilter, so there is no way to decide which handles which method. "
                    + "Set a filter, or use a single callback.");
        }
        // Superclass validation happens in generateAndDefine, on the miss path: a cache hit
        // proves the configuration already generated once, so re-checking it per create would
        // tax exactly the path that decides warm creation cost. Bad configurations still fail
        // with the same message — they can never have populated the cache.

        Class<?> neighbour = placementNeighbour();

        // Explicit choices win over ahead-of-time adoption: setUseCache(false) promises a fresh
        // class and setDefinitionStrategy promises a particular definition, and an adopted class
        // honours neither. A native image is the exception — nothing can be defined at runtime
        // there, so adoption is the only path that can work at all.
        if (!useCache || definitionStrategy != null) {
            ClassDefiner definer = definitionStrategy == null
                    ? ClassDefiner.alongside(neighbour)
                    : ClassDefiner.using(neighbour, definitionStrategy);
            if (NATIVE_IMAGE) {
                Class<?> adopted = tryAheadOfTime(neighbour);
                if (adopted != null) {
                    return adopted;
                }
            }
            return generateAndDefine(definer);
        }

        ClassDefiner definer = defaultDefinerFor(neighbour);

        // A probe key: strong references, one allocation, converted to the weak stored form by
        // the cache itself only when it actually misses. See ProxyKey.
        ProxyKey key = new ProxyKey(superclass, interfaces, callbackTypes,
                callbackFilter, useFactory, interceptDuringConstruction,
                definer.strategy().cacheIdentity(), copyAnnotations, naming);

        // Method discovery deliberately happens inside the generator, not before it. Walking the
        // whole hierarchy with getDeclaredMethods() costs tens of microseconds and produces the
        // same answer every time; doing it ahead of the cache lookup made a cache *hit* five times
        // more expensive than CGLib's, for no benefit at all. The ahead-of-time probe sits inside
        // for the same reason: an adopted class is installed in the cache by its first adoption,
        // so the steady state for an AOT-packaged application is the same 155 ns cache hit as for
        // a generated one — not a blueprint build and key join per create.
        //
        // Anchored on the placement neighbour, not the superclass. For an interface proxy the
        // superclass is Object — permanent — and an entry anchored there would hold the key's
        // interface references for the life of the JVM, pinning their loader: the redeploy leak
        // this library exists to end. The neighbour is the first interface in that case, so the
        // entry dies with the code it serves.
        return GenerationCache.computeIfAbsent(neighbour, key, () -> {
            Class<?> adopted = tryAheadOfTime(neighbour);
            return adopted != null ? adopted : generateAndDefine(definer);
        });
    }

    /** Set in a GraalVM native image, at build time and at run time. */
    private static final boolean NATIVE_IMAGE =
            System.getProperty("org.graalvm.nativeimage.imagecode") != null;

    /**
     * The default-strategy definer per neighbour, memoized because the warm create path needs
     * only its {@code cacheIdentity()} for the key — and building a fresh definer per create
     * (site read, capability checks, an allocation) taxed exactly the path that decides warm
     * creation cost. One mutable slot per class rather than a computed value, because the site
     * underneath can be re-probed: {@link DefinitionSite#forget} exists so a package opened
     * after the first probe is seen, and a memo that ignored it would pin the stale verdict.
     * The slot is validated against the current site on every read and rebuilt on mismatch.
     * Racy by design: concurrent creates may briefly each build the same definer, and the last
     * write wins — both are correct, nothing tears.
     */
    private static final ClassValue<ClassDefiner[]> DEFAULT_DEFINERS = new ClassValue<>() {
        @Override
        protected ClassDefiner[] computeValue(Class<?> neighbour) {
            return new ClassDefiner[1];
        }
    };

    private static ClassDefiner defaultDefinerFor(Class<?> neighbour) {
        ClassDefiner[] slot = DEFAULT_DEFINERS.get(neighbour);
        ClassDefiner cached = slot[0];
        if (cached == null || cached.site() != DefinitionSite.of(neighbour)) {
            cached = ClassDefiner.alongside(neighbour);
            slot[0] = cached;
        }
        return cached;
    }

    /**
     * The pre-generated class for this configuration, adopted, or {@code null} when there is
     * none.
     *
     * <p>Guarded by the cheapest possible test that any exist: {@code isEmpty(neighbour)} is a
     * {@code ClassValue} read after the one-time scan of the neighbour's loader, so an
     * application that has not opted in pays nothing here. The neighbour decides which loader's
     * index applies — its loader is the one the generated class ships beside and the one that
     * can resolve it.
     */
    private Class<?> tryAheadOfTime(Class<?> neighbour) {
        if (AotProxies.isEmpty(neighbour)) {
            return null;
        }
        Optional<AotProxies.PreGenerated> pregenerated =
                AotProxies.preGenerated(neighbour, blueprint().key());
        return pregenerated.map(this::useAheadOfTime).orElse(null);
    }

    private Class<?> generateAndDefine(ClassDefiner definer) {
        ProxyMethods.requireProxyable(superclass);
        ProxyMethods plan = ProxyMethods.discover(superclass, interfaces,
                definer.canOverridePackagePrivate());
        lastPlan = plan;

        List<Constructor<?>> constructors = ProxyClassGenerator.mirrorableConstructors(
                superclass, definer.canOverridePackagePrivate());
        if (constructors.isEmpty()) {
            throw new ClasswrightException(superclass.getName() + " has no constructor a subclass "
                    + "could call. Every constructor is private"
                    + (definer.canOverridePackagePrivate() ? "" : ", or package-private and out of "
                    + "reach from where the proxy must be placed") + ".");
        }

        ProxySpec spec = new ProxySpec(
                superclass,
                interfaces,
                proxyInternalName(definer, placementNeighbour()),
                plan,
                callbackIndexes(plan),
                List.of(callbackTypes),
                useFactory,
                interceptDuringConstruction,
                constructors,
                copyAnnotations,
                naming);

        DefinedClass defined = definer.define(ProxyClassGenerator.generate(spec));
        initialise(defined, plan);
        return defined.type();
    }

    /**
     * Describes this configuration the way {@link AheadOfTime} does, so the two agree on the key.
     *
     * <p>Built from the same values rather than duplicating the key format, which is the only way
     * to be sure a build-time key and a runtime key cannot drift apart. Interface order is part of
     * the key and is preserved here exactly as configured — the build side does the same with its
     * configured list, so the two agree.
     */
    private ProxyBlueprint blueprint() {
        ProxyBlueprint.Builder builder = ProxyBlueprint.of(superclass)
                .implementing(interfaces.toArray(new Class<?>[0]))
                .callbacks(callbackTypes)
                .useFactory(useFactory)
                .interceptDuringConstruction(interceptDuringConstruction)
                .copyAnnotations(copyAnnotations)
                .naming(naming);
        if (callbackFilter != null) {
            builder.filteredBy(callbackFilter.getClass());
        }
        return builder.build();
    }

    /**
     * Adopts a class that was generated before the program started.
     *
     * <p>Generation and definition are skipped, but the reflective metadata still has to be
     * installed: {@code CW$init} cannot be run at build time because {@link Method} and
     * {@link MethodProxy} instances belong to the running JVM.
     *
     * <p>Verification runs on <em>every</em> adoption, not once per class. Adoption happens only
     * on a generation-cache miss, so the cost is rare — and once-per-class verification had a
     * hole: a faithful filter instance arriving first would vouch for the class, and a drifted
     * instance of the same filter class arriving later (unequal by {@code equals}, so a cache
     * miss) would silently adopt the wrong routing. Verified per adoption, the drifted instance
     * gets the loud fingerprint refusal whatever the arrival order.
     *
     * <p>Discovery and the fingerprint run the user's {@link CallbackFilter}, so they run
     * <em>before</em> the initialisation monitor below is taken: user code inside that monitor
     * could construct another adopted proxy on another thread and deadlock the two adoptions
     * against each other. Only the initialise-and-set sequence needs the lock — the flag lives
     * on the class itself (see {@link #AOT_INITIALISED}), a second thread blocks until the first
     * finishes rather than seeing a half-initialised class, and a failed initialisation leaves
     * the flag unset for the next attempt.
     */
    private Class<?> useAheadOfTime(AotProxies.PreGenerated entry) {
        Class<?> proxyClass = entry.type();

        // Discovery must use the same answer the build used, and the build used true: an
        // ahead-of-time proxy is placed in its target's package, so package-private methods
        // are overridable. Disagreeing here would misalign the dispatch table.
        ProxyMethods plan = ProxyMethods.discover(superclass, interfaces, true);
        lastPlan = plan;

        // The dispatch order is deterministic (sorted at discovery), but the target may have
        // been recompiled since the proxy was generated. A drifted method set would pour the
        // wrong Method into each slot — silent misdispatch, and in a native image there is no
        // regenerating. Failing loudly is the only honest option. Kept ahead of the
        // fingerprint: a field count is cheaper than a digest, and its message is more
        // specific for the commonest drift.
        int compiledSlots = 0;
        for (java.lang.reflect.Field field : proxyClass.getDeclaredFields()) {
            if (field.getType() == Method.class && field.getName().contains("method$")) {
                compiledSlots++;
            }
        }
        if (compiledSlots != plan.proxied().size()) {
            throw new ClasswrightException("the ahead-of-time proxy " + proxyClass.getName()
                    + " was compiled for " + compiledSlots + " methods, but "
                    + superclass.getName() + " now has " + plan.proxied().size()
                    + " proxyable methods. The target changed since the proxy was generated; "
                    + "regenerate the ahead-of-time proxies.");
        }

        // The method set can match while the routing does not: a stateful CallbackFilter of
        // the same class, or a flag that drifted. The fingerprint digests what the build
        // compiled in — each method with its assigned callback index, plus the generation
        // flags — and this side recomputes it from its own discovery and its own filter
        // instance. Adopting on a mismatch would dispatch methods to the wrong callback,
        // silently and permanently.
        String fingerprint = AheadOfTime.routingFingerprint(plan, callbackIndexes(plan),
                useFactory, interceptDuringConstruction, copyAnnotations);
        if (!fingerprint.equals(entry.routingFingerprint())) {
            throw new ClasswrightException("the ahead-of-time proxy " + proxyClass.getName()
                    + " for blueprint '" + blueprint().key() + "' was compiled with a "
                    + "different callback routing than this Enhancer produces (built "
                    + entry.routingFingerprint() + ", runtime " + fingerprint + "). The "
                    + "CallbackFilter's decisions or the target have drifted since the proxy "
                    + "was generated — a stateful filter that answered differently at build "
                    + "time is the usual cause. Regenerate the ahead-of-time proxies, or fix "
                    + "the filter so that build and runtime agree.");
        }

        AtomicBoolean initialised = AOT_INITIALISED.get(proxyClass);
        if (initialised.get()) {
            return proxyClass;
        }
        synchronized (initialised) {
            if (initialised.get()) {
                return proxyClass;
            }
            Lookup lookup = LookupResolver.tryResolve(proxyClass).orElseThrow(
                    () -> new ClasswrightException("the ahead-of-time proxy "
                            + proxyClass.getName()
                            + " was found, but its package is not open to Classwright, so it "
                            + "cannot be initialised. Add an `opens` directive for "
                            + proxyClass.getPackageName() + "."));
            initialise(new DefinedClass(proxyClass, lookup, DefinitionStrategy.named()), plan);
            initialised.set(true);
        }
        return proxyClass;
    }

    /**
     * Runs the generated {@code CW$init}, handing the class its reflective metadata.
     *
     * <p>The generated class cannot obtain these itself. A hidden class has no resolvable name, so
     * it cannot look itself up, and building {@link MethodProxy} instances in bytecode would be a
     * great deal of generated code for something that happens once per class.
     */
    private void initialise(DefinedClass defined, ProxyMethods plan) {
        List<ProxyMethods.Proxied> proxied = plan.proxied();
        Method[] methods = new Method[proxied.size()];
        MethodProxy[] proxies = new MethodProxy[proxied.size()];

        for (ProxyMethods.Proxied each : proxied) {
            Method method = each.method();
            // Best effort, and deliberately not required. Generated code reaches the original with
            // invokespecial and needs no accessibility at all; this only helps an interceptor that
            // chooses to call Method.invoke. Insisting on it would mean failing outright on any
            // method from a closed module -- Object.clone() among them -- which is precisely the
            // reflex that made CGLib stop working on JDK 16.
            try {
                method.setAccessible(true);
            } catch (RuntimeException notPermitted) {
                // The Method still works for identification and for reflective calls the caller
                // is itself permitted to make.
            }
            methods[each.index()] = method;
            proxies[each.index()] = new MethodProxy(each.index(), method,
                    com.classwright.core.CwMethodType.of(method).descriptor(), !each.isAbstract());
        }

        Lookup lookup = defined.maybeLookup().orElseThrow(() -> new ClasswrightException(
                "the definition strategy produced no lookup, so the proxy cannot be initialised"));
        try {
            lookup.findStatic(defined.type(), ProxyClassGenerator.INIT_METHOD,
                            MethodType.methodType(void.class, Method[].class, MethodProxy[].class,
                                    Lookup.class))
                    .invokeExact(methods, proxies, lookup);
        } catch (Throwable t) {
            throw new ClasswrightException(
                    "could not initialise the generated proxy " + defined.type().getName(), t);
        }
    }

    /**
     * The class the proxy should be placed beside.
     *
     * <p>Normally the superclass, since sharing its package is what allows package-private methods
     * to be overridden. But a pure interface proxy has {@link Object} as its superclass, and
     * placing a class beside {@code java.lang.Object} is doubly useless: the package is closed to
     * everyone, and {@code Object}'s loader is the bootstrap loader, which can see neither the
     * interfaces being implemented nor Classwright itself. The first interface is a far better
     * neighbour, and interface methods are public anyway so nothing is lost.
     */
    private Class<?> placementNeighbour() {
        if (superclass != Object.class || interfaces.isEmpty()) {
            return superclass;
        }
        return interfaces.get(0);
    }

    /**
     * The name for the generated class.
     *
     * <p>Always contains {@code $$} immediately after the target's binary name, because framework
     * heuristics — Spring's {@code ClassUtils.getUserClass} being the well-known one — identify a
     * generated class that way and then walk to its superclass.
     *
     * <p>Derived from the <em>neighbour</em> rather than the superclass, because the two can differ
     * for a pure interface proxy and the name has to land in the package the class is actually
     * defined into. A lookup rejects a class named for a different package outright. Uniqueness
     * for resolvable names is the definer's job, so every generator gets it, not just this one.
     */
    private String proxyInternalName(ClassDefiner definer, Class<?> neighbour) {
        return definer.generatedNameFor(neighbour, naming.classNameSuffix());
    }

    /** Asks the filter which callback handles each method, or sends everything to callback 0. */
    private int[] callbackIndexes(ProxyMethods plan) {
        int[] indexes = new int[plan.proxied().size()];
        if (callbackFilter == null) {
            return indexes;
        }
        for (ProxyMethods.Proxied proxied : plan.proxied()) {
            int index = callbackFilter.accept(proxied.method());
            if (index < 0 || index >= callbackTypes.length) {
                throw new ClasswrightException("the CallbackFilter returned " + index + " for "
                        + proxied.method() + ", but only " + callbackTypes.length
                        + " callbacks were supplied");
            }
            indexes[proxied.index()] = index;
        }
        return indexes;
    }

    /**
     * The callback interface a given callback implements.
     *
     * <p>Generated code declares its field with this type, so it has to be the interface rather
     * than the lambda's or anonymous class's own type — which for a lambda is a hidden class the
     * generated code could not name.
     */
    private static Class<?> callbackInterfaceOf(Callback callback) {
        return CallbackKind.of(callback.getClass()).callbackType();
    }

    /**
     * Identifies a generated class for caching. One class, two lives.
     *
     * <p>As a <em>probe</em> — what every {@code create()} builds — it holds its parts strongly
     * and directly: the interface list, the callback-type array, the filter. A probe exists for
     * one map lookup and is then garbage, so it may hold anything; what it must not do is cost
     * anything, because almost every lookup is a hit. Building the weak representation here used
     * to allocate a {@code WeakReference} per interface, a part object per callback type, and a
     * boxed {@code Objects.hash} — per {@code create()}, before knowing whether the class was
     * already cached. The probe allocates one object: itself.
     *
     * <p>As a <em>stored key</em> — built by {@link #retained()} only when the cache misses —
     * the same parts become weak. The cache entry lives as long as the anchor class does, so a
     * stored key must not pin the interface list, the user's {@link CallbackFilter}, or a
     * user-defined callback interface from a longer-lived anchor. The superclass stays strong:
     * the anchor cannot outlive it. A stored key whose referent has been collected can never
     * match again and reports itself stale so the cache can sweep it; it cannot cause a wrong
     * hit, only a miss.
     *
     * <p>The hash is computed once, in the probe, from collection-stable values (class
     * <em>name</em> hashes, the filter's value hash) and carried into the stored twin, so the
     * two forms hash and compare identically — which the map relies on every time a probe meets
     * a stored key in a bucket.
     *
     * <p>This is also why {@link CallbackFilter} implementations are required to have value
     * {@code equals}/{@code hashCode}: the filter participates in the key, and an identity-only
     * filter (a lambda, say) turns every {@code create()} into a miss.
     */
    private static final class ProxyKey
            implements GenerationCache.StaleKey, GenerationCache.ProbeKey {

        private final Class<?> superclass;

        /** {@code List<Class<?>>} in a probe; {@code WeakReference<Class<?>>[]} once stored. */
        private final Object interfaces;

        /** {@code Class<?>[]} in a probe; {@code WeakReference<Class<?>>[]} once stored. */
        private final Object callbackTypes;

        /** {@code CallbackFilter} in a probe; a {@code WeakReference} to it once stored. */
        private final Object filter;

        /**
         * The strategy's {@link DefinitionStrategy#cacheIdentity()}: a String for stateless
         * strategies (held directly in both forms), anything else held weakly once stored so a
         * configured strategy (an explicit child loader, say) is never pinned by the cache.
         */
        private final Object strategyToken;

        private final boolean useFactory;
        private final boolean interceptDuringConstruction;
        private final boolean copyAnnotations;
        private final NamingConvention naming;
        private final boolean weak;
        private final int hash;

        /** The probe form: strong parts, no allocation beyond this object. */
        ProxyKey(Class<?> superclass, List<Class<?>> interfaces, Class<?>[] callbackTypes,
                 CallbackFilter filter, boolean useFactory, boolean interceptDuringConstruction,
                 Object strategyIdentity, boolean copyAnnotations, NamingConvention naming) {
            this.superclass = superclass;
            this.interfaces = interfaces;
            this.callbackTypes = callbackTypes;
            this.filter = filter;
            this.strategyToken = strategyIdentity;
            this.useFactory = useFactory;
            this.interceptDuringConstruction = interceptDuringConstruction;
            this.copyAnnotations = copyAnnotations;
            this.naming = naming;
            this.weak = false;

            // Manual, not Objects.hash: no varargs array, no boxing. Name hashes rather than
            // identity hashes for the weakly-storable parts, so equal keys hash equally even
            // after a stored twin's referent is gone.
            int h = superclass.hashCode();
            for (int i = 0, count = interfaces.size(); i < count; i++) {
                h = 31 * h + interfaces.get(i).getName().hashCode();
            }
            for (Class<?> each : callbackTypes) {
                h = 31 * h + each.getName().hashCode();
            }
            h = 31 * h + (filter == null ? 0 : filter.hashCode());
            h = 31 * h + (strategyIdentity instanceof String stateless
                    ? stateless.hashCode() : System.identityHashCode(strategyIdentity));
            h = 31 * h + Boolean.hashCode(useFactory);
            h = 31 * h + Boolean.hashCode(interceptDuringConstruction);
            h = 31 * h + Boolean.hashCode(copyAnnotations);
            h = 31 * h + naming.hashCode();
            this.hash = h;
        }

        /** The stored form; only {@link #retained()} calls this. */
        private ProxyKey(ProxyKey probe) {
            this.superclass = probe.superclass;
            int interfaceCount = probe.interfaceCount();
            java.lang.ref.WeakReference<?>[] weakInterfaces =
                    new java.lang.ref.WeakReference<?>[interfaceCount];
            for (int i = 0; i < interfaceCount; i++) {
                weakInterfaces[i] = new java.lang.ref.WeakReference<>(probe.interfaceAt(i));
            }
            this.interfaces = weakInterfaces;
            Class<?>[] probeCallbackTypes = (Class<?>[]) probe.callbackTypes;
            java.lang.ref.WeakReference<?>[] weakCallbackTypes =
                    new java.lang.ref.WeakReference<?>[probeCallbackTypes.length];
            for (int i = 0; i < probeCallbackTypes.length; i++) {
                weakCallbackTypes[i] = new java.lang.ref.WeakReference<>(probeCallbackTypes[i]);
            }
            this.callbackTypes = weakCallbackTypes;
            this.filter = probe.filter == null
                    ? null : new java.lang.ref.WeakReference<>(probe.filter);
            this.strategyToken = probe.strategyToken instanceof String
                    ? probe.strategyToken
                    : new java.lang.ref.WeakReference<>(probe.strategyToken);
            this.useFactory = probe.useFactory;
            this.interceptDuringConstruction = probe.interceptDuringConstruction;
            this.copyAnnotations = probe.copyAnnotations;
            this.naming = probe.naming;
            this.weak = true;
            this.hash = probe.hash;
        }

        @Override
        public Object retained() {
            return weak ? this : new ProxyKey(this);
        }

        private static Object unwrap(Object part) {
            return part instanceof java.lang.ref.WeakReference<?> reference
                    ? reference.get() : part;
        }

        private int interfaceCount() {
            return weak ? ((Object[]) interfaces).length : ((List<?>) interfaces).size();
        }

        private Class<?> interfaceAt(int index) {
            Object part = weak ? ((Object[]) interfaces)[index] : ((List<?>) interfaces).get(index);
            return (Class<?>) unwrap(part);
        }

        private int callbackTypeCount() {
            return ((Object[]) callbackTypes).length;
        }

        private Class<?> callbackTypeAt(int index) {
            return (Class<?>) unwrap(((Object[]) callbackTypes)[index]);
        }

        @Override
        public boolean isStale() {
            if (!weak) {
                return false;           // a probe is never stored, so it is never stale
            }
            if (filter != null && unwrap(filter) == null) {
                return true;
            }
            if (strategyToken instanceof java.lang.ref.WeakReference<?> reference
                    && reference.get() == null) {
                return true;
            }
            for (int i = 0, count = interfaceCount(); i < count; i++) {
                if (interfaceAt(i) == null) {
                    return true;
                }
            }
            for (int i = 0, count = callbackTypeCount(); i < count; i++) {
                if (callbackTypeAt(i) == null) {
                    return true;
                }
            }
            return false;
        }

        private boolean strategyMatches(ProxyKey that) {
            Object mine = unwrap(strategyToken);
            Object theirs = unwrap(that.strategyToken);
            if (mine instanceof String stateless) {
                return stateless.equals(theirs);
            }
            // A collected referent matches nothing, ever.
            return mine != null && mine == theirs;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ProxyKey that) || hash != that.hash) {
                return false;
            }
            if (superclass != that.superclass
                    || useFactory != that.useFactory
                    || interceptDuringConstruction != that.interceptDuringConstruction
                    || copyAnnotations != that.copyAnnotations
                    || !strategyMatches(that)
                    || !naming.equals(that.naming)
                    || interfaceCount() != that.interfaceCount()
                    || callbackTypeCount() != that.callbackTypeCount()) {
                return false;
            }
            for (int i = 0, count = interfaceCount(); i < count; i++) {
                Class<?> mine = interfaceAt(i);
                if (mine == null || mine != that.interfaceAt(i)) {
                    return false;       // a collected referent matches nothing, ever
                }
            }
            for (int i = 0, count = callbackTypeCount(); i < count; i++) {
                Class<?> mine = callbackTypeAt(i);
                if (mine == null || mine != that.callbackTypeAt(i)) {
                    return false;
                }
            }
            if (filter == null) {
                return that.filter == null;
            }
            if (that.filter == null) {
                return false;
            }
            Object mine = unwrap(filter);
            Object theirs = unwrap(that.filter);
            return mine != null && theirs != null && mine.equals(theirs);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /** The methods that would be proxied for a given configuration, without generating anything. */
    static List<Method> planFor(Class<?> superclass, List<Class<?>> interfaces,
                                boolean canOverridePackagePrivate) {
        return new ArrayList<>(
                ProxyMethods.discover(superclass, interfaces, canOverridePackagePrivate).methods());
    }

    /**
     * The methods a proxy of this configuration will dispatch, in no particular order.
     *
     * <p>For code that routes methods to callbacks ahead of generation — the
     * {@code CallbackHelper} pattern. Using this rather than {@code getMethods()}-style reflection
     * matters because the answer applies the same rules generation applies: {@code final},
     * {@code static}, and private methods are absent, {@code protected} ones are present, bridges
     * are resolved, and signatures are de-duplicated across the hierarchy. A filter built from any
     * other enumeration disagrees with the generated dispatch table for exactly those cases.
     *
     * @param superclass the class the proxy would extend
     * @param interfaces the interfaces the proxy would implement
     * @return the methods a generated proxy would dispatch
     */
    public static List<Method> proxiedMethods(Class<?> superclass, Class<?>... interfaces) {
        return planFor(superclass == null ? Object.class : superclass,
                interfaces == null ? List.of() : List.of(interfaces), true);
    }
}
