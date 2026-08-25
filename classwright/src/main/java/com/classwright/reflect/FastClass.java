package com.classwright.reflect;

import com.classwright.ClasswrightException;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Calls a class's methods and constructors without reflection.
 *
 * <pre>{@code
 * FastClass fast = FastClass.create(Service.class);
 * int index = fast.getIndex("greet", new Class<?>[]{String.class});
 * Object result = fast.invoke(index, service, new Object[]{"world"});
 * }</pre>
 *
 * <p>A generated subclass holds one {@code tableswitch} per operation, whose cases perform direct
 * calls. Once the index is known, invoking costs an indirect jump and the call itself.
 *
 * <h2>Is this still worth using?</h2>
 *
 * <p>Less than it once was, and the honest answer matters more than the flattering one.
 * {@link Method#invoke} was genuinely slow in 2005; today the JVM generates an accessor after a few
 * invocations and the gap has narrowed a great deal. {@link java.lang.invoke.MethodHandle} is also
 * available and is faster still when the handle can be held in a {@code static final} field.
 *
 * <p>What {@code FastClass} continues to be good at is <em>index-based</em> dispatch: when the
 * method to call is chosen at runtime from a set known at generation time, one integer selects it
 * with no lookup, no handle adaptation, and no per-call allocation beyond the argument array.
 * Serialisation frameworks and dispatch tables are the natural fit.
 *
 * <p>It is also here because CGLib had it and code migrating from CGLib uses it. The API mirrors
 * {@code net.sf.cglib.reflect.FastClass}, with one deliberate difference noted on
 * {@link #getIndex(String, String)}.
 *
 * <h2>Lifetime</h2>
 *
 * <p>Instances are cached per target class in a {@link ClassValue}, so repeated {@link #create}
 * calls return the same object and nothing global holds the target class alive. The generated class
 * is a hidden class and is reclaimed along with its target.
 */
public abstract class FastClass {

    /**
     * One instance per target class.
     *
     * <p>A {@link ClassValue} rather than a map: entries live on the target class itself, so
     * nothing here can keep a class loader alive. The value references the class it is keyed by
     * — a cycle the collector handles as a unit.
     */
    private static final ClassValue<FastClass> CACHE = new ClassValue<>() {
        @Override
        protected FastClass computeValue(Class<?> type) {
            return FastClassGenerator.generate(type);
        }
    };

    private Class<?> javaClass;
    private List<Method> methods;
    private List<Constructor<?>> constructors;
    private Map<String, Integer> methodIndexes;
    private Map<String, Integer> descriptorIndexes;
    private Map<String, Integer> constructorIndexes;

    /** Only the generator subclasses this. */
    protected FastClass() {
    }

    /**
     * Creates, or returns the cached, fast accessor for a class.
     *
     * @param type the class whose members should be callable
     * @return the accessor
     * @throws ClasswrightException if the class cannot be accessed from generated code
     */
    public static FastClass create(Class<?> type) {
        if (type == null) {
            throw new ClasswrightException("type must not be null");
        }
        if (type.isPrimitive() || type.isArray()) {
            throw new ClasswrightException(
                    type.getTypeName() + " has no methods a FastClass could call");
        }
        return CACHE.get(type);
    }

    /** Called once by the generator, immediately after the instance is created. */
    final void initialise(Class<?> javaClass, List<Method> methods,
                          List<Constructor<?>> constructors) {
        this.javaClass = javaClass;
        this.methods = List.copyOf(methods);
        this.constructors = List.copyOf(constructors);

        Map<String, Integer> byMethod = new HashMap<>(methods.size() * 2);
        Map<String, Integer> byDescriptor = new HashMap<>(methods.size() * 2);
        for (int i = 0; i < methods.size(); i++) {
            Method method = methods.get(i);
            byDescriptor.put(method.getName()
                    + com.classwright.core.CwMethodType.of(method).descriptor(), i);
            // The two identities a method has. The descriptor map is JVM identity: one entry per
            // descriptor, bridges included. The Java map is name-plus-parameters, where a
            // covariant bridge and the method it forwards to share a key — and the real method
            // is the one a Java-level caller means, so it wins the slot whichever order the
            // methods arrived in.
            String key = keyOf(method);
            Integer existing = byMethod.get(key);
            if (existing == null || (methods.get(existing).isBridge() && !method.isBridge())) {
                byMethod.put(key, i);
            }
        }
        this.methodIndexes = Map.copyOf(byMethod);
        this.descriptorIndexes = Map.copyOf(byDescriptor);

        Map<String, Integer> byConstructor = new HashMap<>(constructors.size() * 2);
        for (int i = 0; i < constructors.size(); i++) {
            byConstructor.put(parameterKey(constructors.get(i).getParameterTypes()), i);
        }
        this.constructorIndexes = Map.copyOf(byConstructor);
    }

    // ==========================================================================================
    // Generated
    // ==========================================================================================

    /**
     * Invokes the method at {@code index}.
     *
     * @param index     from {@link #getIndex}
     * @param target    the receiver, or {@code null} for a static method
     * @param arguments the arguments, boxed
     * @return the result, boxed; {@code null} for a {@code void} method
     * @throws InvocationTargetException wrapping anything the method threw
     */
    public abstract Object invoke(int index, Object target, Object[] arguments)
            throws InvocationTargetException;

    /**
     * Invokes the constructor at {@code index}.
     *
     * @param index     from {@link #getConstructorIndex}
     * @param arguments the arguments, boxed
     * @return the new instance
     * @throws InvocationTargetException wrapping anything the constructor threw
     */
    public abstract Object newInstance(int index, Object[] arguments)
            throws InvocationTargetException;

    // ==========================================================================================
    // Lookup
    // ==========================================================================================

    /**
     * The index of a method, or -1 if this accessor does not cover it.
     *
     * @param name           the method name
     * @param parameterTypes the parameter types, in order
     * @return the index, or -1
     */
    public int getIndex(String name, Class<?>[] parameterTypes) {
        Integer index = methodIndexes.get(name + parameterKey(parameterTypes));
        return index == null ? -1 : index;
    }

    /**
     * The index of a method identified by name and JVM descriptor.
     *
     * <p>CGLib's equivalent took a {@code Signature} object, which wraps ASM's {@code Type} and so
     * cannot be reproduced without the dependency this library exists to avoid. A descriptor string
     * carries exactly the same information and needs no types from anywhere.
     *
     * <p>Every descriptor the class declares answers, bridge descriptors included: a covariant
     * family — {@code Object value()} bridging to {@code String value()} — is two JVM methods and
     * two valid indexes here, exactly as it was in CGLib. Invoking the bridge's index dispatches
     * to the real implementation, as any caller of the bridge does.
     *
     * @param name       the method name
     * @param descriptor the JVM method descriptor, e.g. {@code (Ljava/lang/String;)I}
     * @return the index, or -1
     */
    public int getIndex(String name, String descriptor) {
        // A map probe, like the other overloads. This is the CGLib getIndex(Signature)
        // replacement — the lookup callers run before caching the index — and a linear scan that
        // rebuilt every candidate's descriptor made its cost scale with class size.
        Integer index = descriptorIndexes.get(name + descriptor);
        return index == null ? -1 : index;
    }

    /**
     * The index of a method.
     *
     * <p>By complete JVM identity, not by name and parameters: a {@link Method} carries its
     * return type, so a bridge {@code Method} and the method it forwards to — one Java method,
     * two JVM descriptors — resolve to their own distinct indexes here, exactly as CGLib's
     * {@code Method}-based lookups did. The name-and-parameters view, for callers that
     * genuinely have no return type in hand, is {@link #getIndex(String, Class[])}.
     *
     * @param method the method
     * @return the index, or -1
     */
    public int getIndex(Method method) {
        Integer index = descriptorIndexes.get(method.getName()
                + com.classwright.core.CwMethodType.of(method).descriptor());
        return index == null ? -1 : index;
    }

    /**
     * The index of a constructor.
     *
     * @param parameterTypes the constructor's parameter types
     * @return the index, or -1
     */
    public int getConstructorIndex(Class<?>[] parameterTypes) {
        Integer index = constructorIndexes.get(parameterKey(parameterTypes));
        return index == null ? -1 : index;
    }

    // ==========================================================================================
    // Convenience
    // ==========================================================================================

    /**
     * Looks the method up and invokes it in one step.
     *
     * <p>Convenient, but it does the lookup on every call. Hold on to the index from
     * {@link #getIndex} if this is on a hot path — the whole point of the class is that the index
     * is what makes dispatch cheap.
     *
     * @param name           the method name
     * @param parameterTypes the parameter types
     * @param target         the receiver, or {@code null} for a static method
     * @param arguments      the arguments, boxed
     * @return the result, boxed
     * @throws InvocationTargetException wrapping anything the method threw
     */
    public Object invoke(String name, Class<?>[] parameterTypes, Object target, Object[] arguments)
            throws InvocationTargetException {
        return invoke(requireIndex(getIndex(name, parameterTypes), name, parameterTypes),
                target, arguments);
    }

    private static final Class<?>[] NO_TYPES = {};
    private static final Object[] NO_ARGUMENTS = {};

    /**
     * Creates an instance using the no-argument constructor.
     *
     * @return the new instance
     * @throws InvocationTargetException wrapping anything the constructor threw
     */
    public Object newInstance() throws InvocationTargetException {
        return newInstance(NO_TYPES, NO_ARGUMENTS);
    }

    /**
     * Creates an instance using a specific constructor.
     *
     * @param parameterTypes the constructor signature
     * @param arguments      the arguments, boxed
     * @return the new instance
     * @throws InvocationTargetException wrapping anything the constructor threw
     */
    public Object newInstance(Class<?>[] parameterTypes, Object[] arguments)
            throws InvocationTargetException {
        int index = getConstructorIndex(parameterTypes);
        if (index < 0) {
            throw new ClasswrightException(javaClass.getName() + " has no accessible constructor "
                    + parameterKey(parameterTypes));
        }
        return newInstance(index, arguments);
    }

    /**
     * A handle for one method, pairing its index with its reflective description.
     *
     * @param method the method
     * @return the fast accessor
     */
    public FastMethod getMethod(Method method) {
        return new FastMethod(this, method, requireIndex(getIndex(method), method.getName(),
                method.getParameterTypes()));
    }

    /**
     * A handle for one method, by name.
     *
     * @param name           the method name
     * @param parameterTypes the parameter types
     * @return the fast accessor
     */
    public FastMethod getMethod(String name, Class<?>[] parameterTypes) {
        int index = requireIndex(getIndex(name, parameterTypes), name, parameterTypes);
        return new FastMethod(this, methods.get(index), index);
    }

    /**
     * A handle for one constructor.
     *
     * @param constructor the constructor
     * @return the fast accessor
     */
    public FastConstructor getConstructor(Constructor<?> constructor) {
        int index = getConstructorIndex(constructor.getParameterTypes());
        if (index < 0) {
            throw new ClasswrightException(constructor + " is not accessible from generated code");
        }
        return new FastConstructor(this, constructor, index);
    }

    /**
     * A handle for one constructor, by signature.
     *
     * @param parameterTypes the constructor signature
     * @return the fast accessor
     */
    public FastConstructor getConstructor(Class<?>[] parameterTypes) {
        int index = getConstructorIndex(parameterTypes);
        if (index < 0) {
            throw new ClasswrightException(javaClass.getName() + " has no accessible constructor "
                    + parameterKey(parameterTypes));
        }
        return new FastConstructor(this, constructors.get(index), index);
    }

    /**
     * The class this accessor covers.
     *
     * @return the class this was generated for
     */
    public Class<?> getJavaClass() {
        return javaClass;
    }

    /**
     * The name of the class this was generated for.
     *
     * @return the target's binary name
     */
    public String getName() {
        return javaClass.getName();
    }

    /**
     * The highest valid method index.
     *
     * @return the highest valid index, so {@code 0..getMaxIndex()} inclusive are usable; {@code -1}
     *         when the class has no callable methods
     */
    public int getMaxIndex() {
        return methods.size() - 1;
    }

    /**
     * The methods this accessor covers, in index order.
     *
     * @return the indexed methods, in index order
     */
    public List<Method> getMethods() {
        return methods;
    }

    /**
     * The constructors this accessor covers, in index order.
     *
     * @return the indexed constructors, in index order
     */
    public List<Constructor<?>> getConstructors() {
        return constructors;
    }

    @Override
    public String toString() {
        return "FastClass[" + javaClass.getName() + ", " + methods.size() + " methods, "
                + constructors.size() + " constructors]";
    }

    // ==========================================================================================
    // Member selection
    // ==========================================================================================

    /**
     * The methods a fast accessor should cover.
     *
     * <p>Broader than the set a proxy overrides, and for a good reason: this <em>calls</em> methods
     * rather than overriding them, so {@code final} and {@code static} methods are perfectly
     * callable and are included — and so are bridge methods, each under its own JVM descriptor.
     * Left out: private methods, non-bridge synthetics, non-public methods when the accessor
     * cannot be placed in the target's package, and {@code Object}'s own final methods
     * ({@code getClass}, {@code notify}, {@code notifyAll}, {@code wait}) — language and monitor
     * primitives nobody dispatches through an accessor, whose six cases would enlarge every
     * generated switch. CGLib drew the same line.
     *
     * <p>Ordering is deterministic — by name, then descriptor — so indexes are stable across runs.
     * They are not part of the API contract, but an index that shifted between JVM starts would be
     * a nasty surprise for anyone who logged one.
     *
     * @param type          the target class
     * @param samePackage   whether the generated class shares the target's runtime package
     * @return the callable methods, in index order
     */
    static List<Method> callableMethods(Class<?> type, boolean samePackage) {
        // The sort key, kept beside the method so it is built once. A comparator that derived
        // descriptors on demand rebuilt them O(n log n) times during the sort.
        record Candidate(Method method, String name, String descriptor) {
        }

        // De-duplicated by JVM identity — name plus full descriptor — not by the Java-level
        // name-plus-parameters key. The two differ exactly where it matters: a covariant family
        // declares several descriptors for one Java method, and each is a real, invokable JVM
        // method. Folding them (as a name+parameters key does) reported one of the class's own
        // signatures as nonexistent, which broke descriptor-based lookups CGLib answered.
        Set<String> seen = new LinkedHashSet<>();
        List<Candidate> candidates = new ArrayList<>();

        for (Class<?> each : hierarchyOf(type)) {
            for (Method method : each.getDeclaredMethods()) {
                if (!isCallable(method, type, samePackage)) {
                    continue;
                }
                String descriptor = com.classwright.core.CwMethodType.of(method).descriptor();
                if (!seen.add(method.getName() + descriptor)) {
                    continue;           // a more derived declaration of this JVM method won
                }
                candidates.add(new Candidate(method, method.getName(), descriptor));
            }
        }
        candidates.sort(Comparator.comparing(Candidate::name)
                .thenComparing(Candidate::descriptor));
        List<Method> callable = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            callable.add(candidate.method());
        }
        return callable;
    }

    /** The constructors a fast accessor should cover, in index order. */
    static List<Constructor<?>> callableConstructors(Class<?> type, boolean samePackage) {
        List<Constructor<?>> callable = new ArrayList<>();
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            return callable;    // nothing to construct
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            int modifiers = constructor.getModifiers();
            if (Modifier.isPrivate(modifiers)) {
                continue;
            }
            if (!Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers) && !samePackage) {
                continue;
            }
            callable.add(constructor);
        }
        callable.sort(Comparator.comparing(c -> parameterKey(c.getParameterTypes())));
        return callable;
    }

    private static boolean isCallable(Method method, Class<?> target, boolean samePackage) {
        int modifiers = method.getModifiers();
        // Bridges are deliberately callable. A bridge is a real JVM method with its own
        // descriptor — calls compiled against the wider signature of a covariant family land on
        // it — so an accessor that dropped it would deny a signature the class actually has.
        // (A proxy generator must skip bridges; an invoker must not. Different job.) Other
        // synthetics stay out: they are compiler plumbing with unstable names.
        if (Modifier.isPrivate(modifiers) || (method.isSynthetic() && !method.isBridge())) {
            return false;
        }
        // Object's final methods — getClass, notify, notifyAll, the three waits — are left out,
        // as CGLib left them out. They are language and monitor primitives nobody dispatches
        // through an accessor, and indexing them is not free: they enlarge every generated
        // switch (six extra cases on every accessor ever emitted), which is JIT parsing and
        // inlining budget on the exact method this class exists to keep hot. User-declared
        // final methods remain callable and indexed; the exclusion is precisely Object's.
        if (method.getDeclaringClass() == Object.class && Modifier.isFinal(modifiers)) {
            return false;
        }
        if (Modifier.isPublic(modifiers)) {
            return true;
        }
        if (Modifier.isProtected(modifiers)) {
            // Protected members are only reachable on a receiver of the accessing class's own
            // type, which a FastClass never has -- it calls the target, it does not extend it.
            // So protected only works when the package matches, where ordinary package access
            // applies instead.
            return samePackage
                    && method.getDeclaringClass().getPackageName().equals(target.getPackageName());
        }
        return samePackage
                && method.getDeclaringClass().getPackageName().equals(target.getPackageName());
    }

    private static List<Class<?>> hierarchyOf(Class<?> type) {
        Set<Class<?>> ordered = new LinkedHashSet<>();
        for (Class<?> each = type; each != null; each = each.getSuperclass()) {
            ordered.add(each);
        }
        for (Class<?> each = type; each != null; each = each.getSuperclass()) {
            collectInterfaces(each, ordered);
        }
        return List.copyOf(ordered);
    }

    private static void collectInterfaces(Class<?> type, Set<Class<?>> into) {
        for (Class<?> each : type.getInterfaces()) {
            if (into.add(each)) {
                collectInterfaces(each, into);
            }
        }
    }

    private static String keyOf(Method method) {
        return method.getName() + parameterKey(method.getParameterTypes());
    }

    private static String parameterKey(Class<?>[] parameterTypes) {
        StringBuilder key = new StringBuilder("(");
        for (Class<?> parameterType : parameterTypes) {
            key.append(parameterType.getName()).append(';');
        }
        return key.append(')').toString();
    }

    private int requireIndex(int index, String name, Class<?>[] parameterTypes) {
        if (index < 0) {
            throw new ClasswrightException(javaClass.getName() + " has no accessible method "
                    + name + parameterKey(parameterTypes)
                    + ". Private methods, and non-public methods outside the accessor's package, "
                    + "cannot be reached from generated code.");
        }
        return index;
    }
}
