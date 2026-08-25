package net.sf.cglib.proxy;

import com.classwright.ClasswrightException;
import com.classwright.cglib.CallbackAdapters;
import com.classwright.cglib.Coexistence;
import com.classwright.proxy.NamingConvention;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates proxies by generating a subclass at runtime.
 *
 * <p>Reproduces {@code net.sf.cglib.proxy.Enhancer} on top of
 * {@link com.classwright.proxy.Enhancer}. Existing code compiles and runs unchanged; the only
 * change is which artifact provides the class.
 *
 * <h2>What differs, and it is deliberate</h2>
 *
 * <p><strong>Generated classes are hidden, and can be unloaded.</strong> This is the reason to
 * migrate. Two consequences follow: {@code proxy.getClass().getName()} is not resolvable with
 * {@code Class.forName}, and the name carries a {@code /0x...} suffix. The name still contains
 * {@code $$EnhancerByCGLIB$$} and the generated members are still prefixed {@code CGLIB$}, so the
 * framework heuristics that look for those keep working.
 *
 * <p>Where a resolvable name is genuinely required, {@link #setUseHiddenClasses(boolean)} turns it
 * off — at the cost of those classes never being reclaimed, which is exactly CGLib's behaviour and
 * exactly the problem this library exists to solve.
 *
 * <p>Not reproduced: {@code setStrategy}, whose {@code GeneratorStrategy} parameter type exposes
 * ASM and cannot exist here. {@link #setNamingPolicy} is accepted and ignored — hidden classes are
 * named by the JVM. {@code setSerialVersionUID} is accepted and ignored, because hidden proxies
 * are not serialisable. See the migration guide.
 *
 * <p><strong>Method-set differences.</strong> {@code finalize()} and {@code Object.clone()} are
 * never intercepted here. CGLib proxied both and asked the {@code CallbackFilter} about them —
 * famously forcing frameworks to route {@code finalize} to {@code NoOp} to undo the damage — so a
 * filter's {@code accept} is not called for them and an interceptor that handled them will not
 * fire. For {@code finalize} this is strictly an improvement; for {@code clone} it is a
 * limitation worth knowing about.
 */
public class Enhancer {

    static {
        Coexistence.check();
    }

    /** Creates an unconfigured enhancer, as CGLib's constructor did. */
    public Enhancer() {
    }

    private Class superclass = Object.class;
    private Class[] interfaces = new Class[0];
    private Callback[] callbacks;
    private Class[] callbackTypes;
    private CallbackFilter callbackFilter;

    private boolean useFactory = true;
    private boolean interceptDuringConstruction = true;
    private boolean useCache = true;
    private boolean useHiddenClasses = true;

    private ClassLoader requestedClassLoader;

    // ==========================================================================================
    // Static conveniences, as in CGLib
    // ==========================================================================================

    /**
     * Creates a proxy extending {@code type} with a single callback.
     *
     * @param type     the class to extend
     * @param callback handles every intercepted method
     * @return the proxy
     */
    public static Object create(Class type, Callback callback) {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(type);
        enhancer.setCallback(callback);
        return enhancer.create();
    }

    /**
     * Creates a proxy extending {@code type} and implementing {@code interfaces}.
     *
     * @param type       the class to extend
     * @param interfaces additional interfaces to implement
     * @param callback   handles every intercepted method
     * @return the proxy
     */
    public static Object create(Class type, Class[] interfaces, Callback callback) {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(type);
        enhancer.setInterfaces(interfaces);
        enhancer.setCallback(callback);
        return enhancer.create();
    }

    /**
     * Creates a proxy with several callbacks, routed by a filter.
     *
     * @param type       the class to extend
     * @param interfaces additional interfaces to implement
     * @param filter     decides which callback handles which method
     * @param callbacks  the callbacks, in slot order
     * @return the proxy
     */
    public static Object create(Class type, Class[] interfaces, CallbackFilter filter,
                                Callback[] callbacks) {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(type);
        enhancer.setInterfaces(interfaces);
        enhancer.setCallbackFilter(filter);
        enhancer.setCallbacks(callbacks);
        return enhancer.create();
    }

    /**
     * Whether a class was generated as a proxy.
     *
     * @param type any class
     * @return whether it came from here
     */
    public static boolean isEnhanced(Class type) {
        return com.classwright.proxy.ProxySupport.isGeneratedProxy(type);
    }

    /**
     * Registers callbacks for instances of a generated class created on this thread.
     *
     * <p>The {@code createClass()}-then-instantiate flow: Spring's Objenesis-based proxying and
     * Hibernate both register callbacks this way before creating instances outside any
     * constructor. An instance created through a constructor binds them during construction; one
     * created without a constructor binds them on its first intercepted call.
     *
     * @param generatedClass a class from {@link #createClass()}
     * @param callbacks      the callbacks, or {@code null} to remove this thread's registration
     */
    public static void registerCallbacks(Class generatedClass, Callback[] callbacks) {
        com.classwright.proxy.Enhancer.registerCallbacks(generatedClass,
                adaptRegistration(generatedClass, callbacks));
    }

    /**
     * As {@link #registerCallbacks}, visible to every thread; the per-thread registration wins.
     *
     * @param generatedClass a class from {@link #createClass()}
     * @param callbacks      the callbacks, or {@code null} to remove the registration
     */
    public static void registerStaticCallbacks(Class generatedClass, Callback[] callbacks) {
        com.classwright.proxy.Enhancer.registerStaticCallbacks(generatedClass,
                adaptRegistration(generatedClass, callbacks));
    }

    private static com.classwright.proxy.Callback[] adaptRegistration(Class<?> generatedClass,
                                                                      Callback[] callbacks) {
        if (callbacks == null) {
            return null;
        }
        boolean factory = Factory.class.isAssignableFrom(generatedClass);
        com.classwright.proxy.Callback[] adapted =
                new com.classwright.proxy.Callback[callbacks.length + (factory ? 1 : 0)];
        for (int i = 0; i < callbacks.length; i++) {
            adapted[i] = callbacks[i] == null ? null
                    : CallbackAdapters.toClasswright(callbacks[i]);
        }
        if (factory) {
            adapted[callbacks.length] = new FactoryBridge(callbacks.length);
        }
        return adapted;
    }

    // ==========================================================================================
    // Configuration
    // ==========================================================================================

    /**
     * Sets the class to extend.
     *
     * @param superclass the class to extend; an interface here is reinterpreted as {@link #setInterfaces}, as CGLib did
     */
    public void setSuperclass(Class superclass) {
        if (superclass != null && superclass.isInterface()) {
            // CGLib quietly reinterpreted this as setInterfaces. Reproducing that would hide a
            // mistake; saying so costs one line of migration and prevents confusion later.
            setInterfaces(new Class[]{superclass});
            this.superclass = Object.class;
            return;
        }
        this.superclass = superclass == null ? Object.class : superclass;
    }

    /**
     * Sets additional interfaces for the proxy to implement.
     *
     * @param interfaces the interfaces
     */
    public void setInterfaces(Class[] interfaces) {
        this.interfaces = interfaces == null ? new Class[0] : interfaces.clone();
    }

    /**
     * Sets the single callback.
     *
     * @param callback handles every intercepted method
     */
    public void setCallback(Callback callback) {
        setCallbacks(new Callback[]{callback});
    }

    /**
     * Sets several callbacks, which needs a {@link CallbackFilter}.
     *
     * @param callbacks the callbacks, in slot order
     */
    public void setCallbacks(Callback[] callbacks) {
        // CGLib's exception types, because migrated code catches and tests assert on them.
        if (callbacks == null || callbacks.length == 0) {
            throw new IllegalArgumentException("Array cannot be empty");
        }
        this.callbacks = callbacks.clone();
        this.callbackTypes = new Class[callbacks.length];
        for (int i = 0; i < callbacks.length; i++) {
            if (callbacks[i] == null) {
                throw new IllegalArgumentException("callback " + i + " is null; use NoOp.INSTANCE");
            }
            this.callbackTypes[i] = callbacks[i].getClass();
        }
    }

    /**
     * Declares the callback type, for use with {@link #createClass}.
     *
     * @param callbackType the callback interface
     */
    public void setCallbackType(Class callbackType) {
        setCallbackTypes(new Class[]{callbackType});
    }

    /**
     * Declares the callback types, for use with {@link #createClass}.
     *
     * @param callbackTypes the callback interfaces, in slot order
     */
    public void setCallbackTypes(Class[] callbackTypes) {
        this.callbackTypes = callbackTypes == null ? null : callbackTypes.clone();
        this.callbacks = null;
    }

    /**
     * Sets the filter that routes methods to callbacks.
     *
     * @param callbackFilter the filter; it must implement {@code equals} and {@code hashCode}
     */
    public void setCallbackFilter(CallbackFilter callbackFilter) {
        this.callbackFilter = callbackFilter;
    }

    /**
     * Whether the proxy implements {@link Factory}.
     *
     * @param useFactory whether to implement it
     */
    public void setUseFactory(boolean useFactory) {
        this.useFactory = useFactory;
    }

    /**
     * Whether callbacks fire for calls made from within a constructor.
     *
     * @param interceptDuringConstruction whether they fire
     */
    public void setInterceptDuringConstruction(boolean interceptDuringConstruction) {
        this.interceptDuringConstruction = interceptDuringConstruction;
    }

    /**
     * Whether generated classes are cached and reused.
     *
     * @param useCache whether to cache
     */
    public void setUseCache(boolean useCache) {
        this.useCache = useCache;
    }

    /**
     * Accepts a class loader for compatibility; placement is chosen by the target instead.
     *
     * <p>In CGLib the loader decided where the generated class lived. Classwright always defines
     * the proxy alongside its superclass (or first interface), which is what makes hidden classes
     * and package-private overrides work. A loader that can already see the superclass is
     * therefore satisfied by construction; anything else cannot be honoured, and saying so beats
     * silently generating into the wrong place.
     *
     * <p>The check runs when the proxy is created, not here, so this call may come before or
     * after {@link #setSuperclass} — CGLib imposed no ordering, and code in the wild calls the
     * setters in every order.
     *
     * @param classLoader the loader migrated code passes; must be able to see the superclass
     */
    public void setClassLoader(ClassLoader classLoader) {
        this.requestedClassLoader = classLoader;
    }

    /**
     * The {@link #setClassLoader} validation, run at creation time so setter order cannot defeat
     * it. The loader is still not used for placement; see the setter.
     */
    private void checkRequestedClassLoader() {
        if (requestedClassLoader == null || superclass == Object.class) {
            return;
        }
        try {
            if (Class.forName(superclass.getName(), false, requestedClassLoader) != superclass) {
                throw new IllegalArgumentException("the requested ClassLoader resolves "
                        + superclass.getName() + " to a different class than the configured "
                        + "superclass. Classwright places proxies beside the superclass, so a "
                        + "loader that disagrees about it cannot be honoured.");
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("the requested ClassLoader cannot see "
                    + superclass.getName() + ". Classwright places proxies beside the "
                    + "superclass; pass a loader that delegates to the superclass's loader, or "
                    + "drop the call — it is not needed here.", e);
        }
    }

    /**
     * Whether generated classes are cached and reused.
     *
     * @return whether the cache is consulted
     */
    public boolean getUseCache() {
        return useCache;
    }

    /**
     * Accepted and ignored: hidden proxies are not serialisable, so there is no stream to stamp.
     *
     * @param serialVersionUID ignored
     */
    public void setSerialVersionUID(Long serialVersionUID) {
        // Deliberately nothing. CGLib stamped the field onto its named, serialisable proxies.
    }

    /**
     * Accepted and ignored: CGLib used this to try {@code Class.forName} for an already-generated
     * class before generating. Hidden classes are not resolvable by name, and the generation
     * cache already serves the purpose the flag existed for.
     *
     * @param attemptLoad ignored
     */
    public void setAttemptLoad(boolean attemptLoad) {
        // Deliberately nothing.
    }

    /**
     * Fills {@code methods} with the methods a proxy of this configuration would dispatch.
     *
     * <p>The enumeration applies the same rules generation applies — final, static and private
     * methods absent, bridges resolved, signatures de-duplicated across the hierarchy — which is
     * what makes it safe to build {@link CallbackFilter} routing from, and is what CGLib's
     * version was for.
     *
     * @param superclass the class the proxy would extend
     * @param interfaces the interfaces it would implement, or {@code null}
     * @param methods    receives the methods, in dispatch order
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void getMethods(Class superclass, Class[] interfaces, List methods) {
        methods.addAll(com.classwright.proxy.Enhancer.proxiedMethods(superclass,
                interfaces == null ? new Class<?>[0] : interfaces));
    }

    /**
     * Accepted and ignored: hidden classes are named by the JVM, not by a policy.
     *
     * @param namingPolicy ignored
     */
    public void setNamingPolicy(net.sf.cglib.core.NamingPolicy namingPolicy) {
        // Deliberately nothing. The generated name still contains $$EnhancerByCGLIB$$, which is
        // the part framework heuristics depend on.
    }

    /**
     * Whether generated classes may be unloaded. Defaults to {@code true}.
     *
     * <p>Not part of CGLib's API, and the one addition here. Turning it off produces ordinary named
     * classes that {@code Class.forName} can resolve, for tooling that requires it — and which are
     * retained for the life of the class loader, exactly as CGLib's were.
     *
     * @param useHiddenClasses whether to generate unloadable classes
     */
    public void setUseHiddenClasses(boolean useHiddenClasses) {
        this.useHiddenClasses = useHiddenClasses;
    }

    // ==========================================================================================
    // Creation
    // ==========================================================================================

    /**
     * Generates the proxy class and creates an instance.
     *
     * @return the proxy
     */
    public Object create() {
        try {
            Object proxy = configure(false).create();
            registerBridgeDefaults(proxy.getClass());
            return proxy;
        } catch (net.sf.cglib.core.CodeGenerationException | IllegalStateException
                 | IllegalArgumentException already) {
            throw already;
        } catch (ClasswrightException e) {
            // CGLib surfaced generation failures as CodeGenerationException, and migrated catch
            // blocks are written for it.
            throw new net.sf.cglib.core.CodeGenerationException(e);
        }
    }

    /**
     * Generates the proxy class and creates an instance using a specific constructor.
     *
     * @param argumentTypes the constructor signature to invoke
     * @param arguments     the constructor arguments
     * @return the proxy
     */
    public Object create(Class[] argumentTypes, Object[] arguments) {
        try {
            Object proxy = configure(false).create(argumentTypes, arguments);
            registerBridgeDefaults(proxy.getClass());
            return proxy;
        } catch (net.sf.cglib.core.CodeGenerationException | IllegalStateException
                 | IllegalArgumentException already) {
            throw already;
        } catch (ClasswrightException e) {
            throw new net.sf.cglib.core.CodeGenerationException(e);
        }
    }

    /**
     * Generates the proxy class without creating an instance.
     *
     * <p>The class is immediately usable in the deferred-binding flow: create instances however
     * you like — {@link Factory#newInstance}, a mirrored constructor, or no constructor at all —
     * and attach callbacks through {@link #registerCallbacks} or {@link Factory#setCallbacks}.
     *
     * @return the generated class
     */
    public Class createClass() {
        try {
            return createClassInternal();
        } catch (net.sf.cglib.core.CodeGenerationException | IllegalStateException
                 | IllegalArgumentException already) {
            throw already;
        } catch (ClasswrightException e) {
            throw new net.sf.cglib.core.CodeGenerationException(e);
        }
    }

    private Class createClassInternal() {
        com.classwright.proxy.Enhancer enhancer = configure(true);
        Class<?> proxyClass = enhancer.createClass();
        registerBridgeDefaults(proxyClass);
        return proxyClass;
    }

    /**
     * Installs the Factory bridge as the class's fallback for its hidden slot.
     *
     * <p>This is what makes {@link Factory} methods work on an instance that has no callbacks
     * bound yet — created without a constructor (the Objenesis-then-{@code setCallbacks} pattern
     * Spring uses), or through a mirrored constructor directly. CGLib emitted Factory's methods
     * as bytecode, reachable on any instance; here they dispatch through a callback slot, so the
     * slot needs a fallback. User slots stay {@code null} until callbacks are set.
     *
     * <p>Installed as a <em>default</em>, not as a static registration: the static registration
     * is the user's channel ({@link #registerStaticCallbacks}), and CGLib's {@code create()}
     * never touched it. Writing the bridge there overwrote a user's registration on every
     * {@code create()}; the defaults tier is consulted only when no registration exists, so both
     * flows keep working. Idempotent — the bridge is a value object — so re-installing per
     * create is harmless.
     */
    private void registerBridgeDefaults(Class<?> proxyClass) {
        if (!useFactory) {
            return;
        }
        com.classwright.proxy.Callback[] defaults =
                new com.classwright.proxy.Callback[callbackTypes.length + 1];
        defaults[callbackTypes.length] = new FactoryBridge(callbackTypes.length);
        com.classwright.proxy.CallbackRegistry.registerDefaults(proxyClass, defaults);
    }

    /**
     * Builds the underlying Classwright enhancer.
     *
     * <p>The interesting part is {@link Factory}. CGLib's generated proxies implement it, and code
     * casts to it constantly — Spring among others. Classwright's generator knows nothing about
     * CGLib's version, so it is added as an ordinary interface and its methods are routed to a
     * bridge callback that implements them by delegating to Classwright's own {@code Factory}.
     * The bridge occupies one extra callback slot, invisible to the caller, and its methods are not
     * on any hot path.
     *
     * @param typesOnly whether callbacks are absent, as they are for {@code createClass()}
     */
    private com.classwright.proxy.Enhancer configure(boolean typesOnly) {
        checkRequestedClassLoader();
        if (callbackTypes == null || callbackTypes.length == 0) {
            throw new ClasswrightException("no callbacks or callback types were set");
        }
        if (!typesOnly && callbacks == null) {
            // setCallbackTypes() followed by create(): CGLib's validate() names this state, and
            // migrated code may rely on the exception type.
            throw new IllegalStateException("Callbacks are required");
        }
        if (callbackTypes.length > 1 && callbackFilter == null) {
            // CGLib refuses this loudly; silently routing everything to slot 0 hides real bugs.
            throw new IllegalStateException(
                    "Multiple callback types possible but no filter specified");
        }
        int userCallbackCount = callbackTypes.length;

        com.classwright.proxy.Enhancer enhancer = new com.classwright.proxy.Enhancer();
        enhancer.setSuperclass(superclass);
        enhancer.setInterceptDuringConstruction(interceptDuringConstruction);
        enhancer.setUseCache(useCache);
        // Passed through: with useFactory off, CGLib emitted no Factory-shaped members at all,
        // and code turns it off precisely to keep those members out of reflection scans.
        enhancer.setUseFactory(useFactory);
        enhancer.setNamingConvention(NamingConvention.CGLIB_COMPATIBLE);
        if (!useHiddenClasses) {
            enhancer.setDefinitionStrategy(
                    com.classwright.runtime.DefinitionStrategy.named());
        }

        // Copied element by element rather than with Arrays.asList: CGLib's API is written in raw
        // types, and a raw Class[] does not convert to a Collection<? extends Class<?>>. The raw
        // signatures are reproduced faithfully, so the awkwardness stays on this side of the API.
        List<Class<?>> allInterfaces = new ArrayList<>();
        for (Class each : interfaces) {
            allInterfaces.add(each);
        }
        if (useFactory) {
            allInterfaces.add(Factory.class);
        }
        enhancer.setInterfaces(allInterfaces.toArray(Class<?>[]::new));

        FactoryBridge bridge = useFactory ? new FactoryBridge(userCallbackCount) : null;

        if (typesOnly) {
            List<Class<?>> types = new ArrayList<>();
            for (Class<?> callbackType : callbackTypes) {
                types.add(CallbackAdapters.classwrightTypeOf(callbackType));
            }
            if (bridge != null) {
                types.add(com.classwright.proxy.MethodInterceptor.class);
            }
            enhancer.setCallbackTypes(types.toArray(Class<?>[]::new));
        } else {
            List<com.classwright.proxy.Callback> adapted = new ArrayList<>();
            for (Callback callback : callbacks) {
                adapted.add(CallbackAdapters.toClasswright(callback));
            }
            if (bridge != null) {
                adapted.add(bridge);
            }
            enhancer.setCallbacks(adapted.toArray(com.classwright.proxy.Callback[]::new));
        }

        // A filter is needed whenever there is more than one slot, which the Factory bridge
        // guarantees. It also has to be equal-comparable, or the generation cache never hits.
        enhancer.setCallbackFilter(new FilterBridge(callbackFilter, userCallbackCount,
                useFactory));
        return enhancer;
    }

    /**
     * Routes {@link Factory}'s own methods to the bridge and everything else to the caller's
     * filter.
     */
    private record FilterBridge(CallbackFilter delegate, int userCallbackCount,
                                boolean useFactory) implements com.classwright.proxy.CallbackFilter {

        @Override
        public int accept(Method method) {
            if (useFactory && method.getDeclaringClass() == Factory.class) {
                return userCallbackCount;
            }
            if (delegate == null) {
                return 0;
            }
            int index = delegate.accept(method);
            if (index < 0 || index >= userCallbackCount) {
                // CGLib's exception type and phrasing, for code that asserts on either.
                throw new IllegalArgumentException("Callback filter returned an index that is "
                        + "too large: " + index + " (only " + userCallbackCount
                        + " callbacks were supplied, for " + method + ")");
            }
            return index;
        }
    }

    /**
     * Implements {@link Factory} by delegating to Classwright's equivalent, translating callbacks
     * at the boundary.
     *
     * <p>Stateless apart from the slot count, and equal-comparable, so it does not defeat the
     * generation cache.
     */
    private record FactoryBridge(int userCallbackCount)
            implements com.classwright.proxy.MethodInterceptor {

        @Override
        public Object intercept(Object proxy, Method method, Object[] arguments,
                                com.classwright.proxy.MethodProxy methodProxy) {
            com.classwright.proxy.Factory factory = (com.classwright.proxy.Factory) proxy;

            return switch (method.getName()) {
                case "getCallback" -> CallbackAdapters.toCglib(
                        factory.getCallback((Integer) arguments[0]));
                case "setCallback" -> {
                    factory.setCallback((Integer) arguments[0],
                            CallbackAdapters.toClasswright((Callback) arguments[1]));
                    yield null;
                }
                case "getCallbacks" -> {
                    Callback[] visible = new Callback[userCallbackCount];
                    for (int i = 0; i < userCallbackCount; i++) {
                        visible[i] = CallbackAdapters.toCglib(factory.getCallback(i));
                    }
                    yield visible;
                }
                case "setCallbacks" -> {
                    Callback[] replacements = (Callback[]) arguments[0];
                    // Set one at a time rather than wholesale: the underlying proxy has one more
                    // slot than the caller knows about, and it must keep this bridge. Iterating
                    // the slot count (not the array's) means a short array fails with the same
                    // ArrayIndexOutOfBoundsException CGLib's generated setter produced, instead
                    // of silently leaving stale callbacks in the tail slots.
                    for (int i = 0; i < userCallbackCount; i++) {
                        factory.setCallback(i, replacements[i] == null ? null
                                : CallbackAdapters.toClasswright(replacements[i]));
                    }
                    yield null;
                }
                case "newInstance" -> newInstance(factory, method, arguments);
                default -> throw new ClasswrightException(
                        "unexpected Factory method " + method.getName());
            };
        }

        private Object newInstance(com.classwright.proxy.Factory factory, Method method,
                                   Object[] arguments) {
            // Branch on the declared parameter type, not instanceof: a null argument is still a
            // valid single callback for the Callback overload, and instanceof cannot see that.
            Callback[] requested = switch (method.getParameterCount()) {
                case 1 -> method.getParameterTypes()[0].isArray()
                        ? (Callback[]) arguments[0]
                        : new Callback[]{(Callback) arguments[0]};
                case 3 -> (Callback[]) arguments[2];
                default -> throw new ClasswrightException(
                        "unexpected newInstance arity " + method.getParameterCount());
            };
            if (requested.length != userCallbackCount) {
                // CGLib's generated newInstance(Callback) refused multi-callback proxies rather
                // than guessing which slot the one callback belongs to.
                throw new IllegalStateException(requested.length < userCallbackCount
                        ? "More than one callback object required"
                        : "too many callbacks: " + requested.length + " for "
                                + userCallbackCount + " slots");
            }

            com.classwright.proxy.Callback[] full =
                    new com.classwright.proxy.Callback[userCallbackCount + 1];
            for (int i = 0; i < userCallbackCount; i++) {
                full[i] = requested[i] == null ? null
                        : CallbackAdapters.toClasswright(requested[i]);
            }
            full[userCallbackCount] = this;

            if (method.getParameterCount() == 3) {
                return factory.newInstance((Class<?>[]) arguments[0], (Object[]) arguments[1],
                        toCwArray(full));
            }
            return factory.newInstance(toCwArray(full));
        }

        private static com.classwright.proxy.Callback[] toCwArray(
                com.classwright.proxy.Callback[] callbacks) {
            return callbacks;
        }
    }
}
