package com.classwright.proxy;

import com.classwright.ClasswrightException;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Works out which methods a proxy should override, and which it cannot.
 *
 * <p>Everything here comes from core reflection. No class file is read, which is what keeps this
 * working on JDK releases that do not exist yet.
 *
 * <h2>The parts that are easy to get wrong</h2>
 *
 * <p><strong>Bridge methods are skipped.</strong> When a class narrows a return type or implements a
 * generic interface, {@code javac} emits an extra synthetic method with the erased signature that
 * forwards to the real one. Overriding the bridge instead of the real method breaks that forwarding;
 * overriding both is worse. Skipping bridges is correct because the inherited bridge dispatches
 * <em>virtually</em> to the real method, so a call arriving through the erased signature still
 * reaches our override.
 *
 * <p><strong>Abstract methods are always included.</strong> They are not optional: a proxy that
 * leaves one unimplemented cannot be instantiated. They also have no original to call, which
 * {@link MethodProxy#invokeSuper} has to report rather than crash on.
 *
 * <p><strong>Package-private methods depend on placement.</strong> They can only be overridden when
 * the proxy shares a <em>runtime</em> package with the target, meaning the same package name and the
 * same class loader. When that is not the case they are skipped and said so, because emitting an
 * override that quietly never runs is the worst available outcome.
 *
 * <p>Everything skipped is recorded with a reason. CGLib silently ignored final methods, which
 * produced a long tail of "why is my interceptor not firing" confusion; {@link #describeSkipped()}
 * exists so the answer is available.
 */
final class ProxyMethods {

    /** A method that will be overridden, with its index in the generated dispatch table. */
    public record Proxied(int index, Method method) {

        /** True when there is no original implementation to delegate to. */
        public boolean isAbstract() {
            return Modifier.isAbstract(method.getModifiers());
        }

        /** True when the declaring type is an interface, which decides the invoke opcode. */
        public boolean declaredByInterface() {
            return method.getDeclaringClass().isInterface();
        }
    }

    /** A method that cannot be proxied, and why. */
    public record Skipped(Method method, String reason) {

        @Override
        public String toString() {
            return method.getDeclaringClass().getSimpleName() + "." + method.getName()
                    + " (" + reason + ")";
        }
    }

    /**
     * A covariant sibling descriptor the generated class must also declare.
     *
     * <p>Java-level overriding treats {@code Object value()} and {@code String value()} as one
     * method, but the JVM does not: a class implementing two independent interfaces that declare
     * both must provide <em>both descriptors</em>, or calls through one of them fail with
     * {@link AbstractMethodError}. javac solves this with bridge methods; a generated class must
     * do the same. The bridge's body forwards virtually to the canonical override, so it is
     * intercepted exactly as the canonical method is.
     */
    public record RequiredBridge(Method canonical, Class<?> bridgeReturn) {
    }

    private final List<Proxied> proxied;
    private final List<Skipped> skipped;
    private final List<RequiredBridge> bridges;

    private ProxyMethods(List<Proxied> proxied, List<Skipped> skipped,
                         List<RequiredBridge> bridges) {
        this.proxied = List.copyOf(proxied);
        this.skipped = List.copyOf(skipped);
        this.bridges = List.copyOf(bridges);
    }

    /**
     * Plans the overrides for a proxy.
     *
     * @param superclass                the class to extend
     * @param interfaces                additional interfaces to implement
     * @param canOverridePackagePrivate whether the proxy will share a runtime package with the
     *                                  superclass; see
     *                                  {@link com.classwright.runtime.ClassDefiner#canOverridePackagePrivate()}
     * @return the plan
     */
    public static ProxyMethods discover(Class<?> superclass, List<Class<?>> interfaces,
                                        boolean canOverridePackagePrivate) {
        requireProxyable(superclass);
        for (Class<?> each : interfaces) {
            if (!each.isInterface()) {
                throw new ClasswrightException(each.getName() + " is not an interface");
            }
        }

        // Most-derived first, so the first declaration seen for a signature wins.
        Map<String, Method> chosen = new LinkedHashMap<>();
        List<Skipped> skipped = new ArrayList<>();

        List<Class<?>> hierarchy = hierarchyOf(superclass, interfaces);
        for (Class<?> type : hierarchy) {
            for (Method method : type.getDeclaredMethods()) {
                String signature = signatureOf(method);
                if (chosen.containsKey(signature)) {
                    continue;               // a more derived declaration already won
                }
                String rejection = rejectionReasonFor(method, superclass,
                        canOverridePackagePrivate);
                if (rejection != null) {
                    if (!isUninteresting(method)) {
                        skipped.add(new Skipped(method, rejection));
                    }
                    // A final, private, or static declaration seals the signature: a less-derived
                    // declaration must not resurrect it. A bridge does not — the compiler emits
                    // bridges *alongside* real methods (covariant returns, generic specialisation,
                    // and the public bridge a subclass gains for a package-private parent's
                    // method), and getDeclaredMethods() order is unspecified, so the bridge may be
                    // seen first. Sealing on a bridge would silently un-proxy the real method.
                    if (!method.isBridge() && !method.isSynthetic()) {
                        chosen.put(signature, null);
                    }
                    continue;
                }
                chosen.put(signature, method);
            }
        }

        // Canonical refinement for covariant families: when two abstract declarations share a
        // family — Object value() from one interface, String value() from another — the one with
        // the narrowest return must be the canonical override, because only then can every wider
        // sibling be satisfied by a bridge that forwards to it. A concrete winner is never
        // displaced: its body is the real implementation, and a narrower abstract sibling it
        // cannot satisfy is reported rather than papered over.
        for (Class<?> type : hierarchy) {
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }
                Method current = chosen.get(signatureOf(method));
                if (current != null && current != method
                        && Modifier.isAbstract(current.getModifiers())
                        && current.getReturnType() != method.getReturnType()
                        && current.getReturnType().isAssignableFrom(method.getReturnType())) {
                    chosen.put(signatureOf(method), method);
                }
            }
        }

        // Sorted by name and descriptor before indexes are assigned. The walk above follows
        // getDeclaredMethods(), whose order is documented as unspecified and differs across JVM
        // implementations and releases — and these indexes are compiled into dispatch tables. An
        // ahead-of-time proxy generated on one JVM and initialised on another must agree on them,
        // or the interceptor receives the wrong Method and invokeSuper calls the wrong super
        // method, silently. Sorting makes the order a function of the configuration alone.
        // The sort key is built once per method, not inside the comparator: a comparator key is
        // re-derived O(n log n) times during the sort, and deriving this one builds a
        // CwMethodType — parameter walk, list, descriptor string — every time.
        record Sortable(Method method, String name, String descriptor) {
        }
        List<Sortable> ordered = new ArrayList<>();
        for (Method method : chosen.values()) {
            if (method != null) {
                ordered.add(new Sortable(method, method.getName(),
                        com.classwright.core.CwMethodType.of(method).descriptor()));
            }
        }
        ordered.sort(java.util.Comparator.comparing(Sortable::name)
                .thenComparing(Sortable::descriptor));
        List<Proxied> proxied = new ArrayList<>(ordered.size());
        for (Sortable sortable : ordered) {
            proxied.add(new Proxied(proxied.size(), sortable.method()));
        }

        // Second pass, for the JVM's view of the world: an abstract declaration whose descriptor
        // differs from its family's canonical override — a covariant sibling from an independent
        // interface — still has to exist on the generated class, as a bridge. Without it, a call
        // through that interface fails with AbstractMethodError.
        List<RequiredBridge> bridges = new ArrayList<>();
        Set<String> bridgeDescriptors = new HashSet<>();
        for (Class<?> type : hierarchy) {
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }
                Method canonical = chosen.get(signatureOf(method));
                if (canonical == null || method.getReturnType() == canonical.getReturnType()) {
                    continue;
                }
                if (!method.getReturnType().isAssignableFrom(canonical.getReturnType())) {
                    // Incomparable returns (or a wider canonical): no single implementation can
                    // satisfy both descriptors, in Java or in bytecode. Say so rather than emit
                    // a class the verifier or a caller will reject confusingly.
                    skipped.add(new Skipped(method, "abstract declaration whose return type is "
                            + "incompatible with the chosen override's; no legal implementation "
                            + "can satisfy both"));
                    continue;
                }
                if (bridgeDescriptors.add(signatureOf(method)
                        + method.getReturnType().getName())) {
                    bridges.add(new RequiredBridge(canonical, method.getReturnType()));
                }
            }
        }
        return new ProxyMethods(proxied, skipped, bridges);
    }

    /** The methods that will be overridden, in dispatch-table order. */
    public List<Proxied> proxied() {
        return proxied;
    }

    /** Covariant sibling descriptors the generated class must declare as bridges. */
    public List<RequiredBridge> bridges() {
        return bridges;
    }

    /** The methods that will not be, each with a reason. */
    public List<Skipped> skipped() {
        return skipped;
    }

    /** The reflected methods that will be overridden. */
    public List<Method> methods() {
        return proxied.stream().map(Proxied::method).toList();
    }

    /**
     * A readable summary of what was left out, for diagnostics.
     *
     * @return one line per skipped method, or a note that nothing was skipped
     */
    public String describeSkipped() {
        if (skipped.isEmpty()) {
            return "No methods were skipped.";
        }
        return skipped.stream()
                .map(Skipped::toString)
                .collect(Collectors.joining("\n  ",
                        "These methods will not be intercepted:\n  ", ""));
    }

    /**
     * Rejects types that cannot be subclassed at all, with an explanation.
     *
     * <p>Checked before anything is generated so the failure names the actual problem rather than
     * surfacing as a {@code VerifyError} or an {@code IncompatibleClassChangeError} later.
     */
    static void requireProxyable(Class<?> superclass) {
        String problem = null;
        if (superclass.isPrimitive()) {
            problem = "a primitive type cannot be subclassed";
        } else if (superclass.isArray()) {
            problem = "an array type cannot be subclassed";
        } else if (superclass.isInterface()) {
            problem = "it is an interface; pass it via setInterfaces() instead of setSuperclass()";
        } else if (Modifier.isFinal(superclass.getModifiers())) {
            problem = "it is final";
        } else if (superclass.isHidden()) {
            problem = "it is a hidden class, which cannot be named as a superclass";
        } else if (superclass.isRecord()) {
            problem = "records are implicitly final";
        } else if (superclass.isEnum()) {
            problem = "enum classes cannot be meaningfully subclassed";
        } else if (superclass.isSealed()) {
            problem = "it is sealed, so only its permitted subclasses may extend it: "
                    + Arrays.stream(superclass.getPermittedSubclasses())
                    .map(Class::getSimpleName).collect(Collectors.joining(", "));
        }
        if (problem != null) {
            throw new ClasswrightException(
                    "Cannot create a proxy for " + superclass.getName() + ": " + problem + ".");
        }
    }

    /**
     * Every type whose declared methods matter, most derived first.
     *
     * <p>Ordering carries the override semantics: the first declaration found for a signature is
     * the one that would actually run, so it is the one to override.
     */
    private static List<Class<?>> hierarchyOf(Class<?> superclass, List<Class<?>> interfaces) {
        Set<Class<?>> ordered = new LinkedHashSet<>();
        for (Class<?> type = superclass; type != null; type = type.getSuperclass()) {
            ordered.add(type);
        }
        // Interfaces after classes: a concrete method always beats a default method.
        Set<Class<?>> closure = new LinkedHashSet<>();
        for (Class<?> each : interfaces) {
            collectInterfaces(each, closure);
        }
        for (Class<?> type = superclass; type != null; type = type.getSuperclass()) {
            for (Class<?> each : type.getInterfaces()) {
                collectInterfaces(each, closure);
            }
        }
        // Ordered by specificity, not by the order the caller happened to list them: a
        // subinterface's default method overrides its ancestor's, so the subinterface must be
        // seen first — Java resolves `x()` to Child's default whether the interfaces arrive as
        // [Child, Parent] or [Parent, Child], and so must this walk. Unrelated interfaces keep
        // their discovery order, which keeps the answer deterministic.
        ordered.addAll(bySpecificity(closure));
        return List.copyOf(ordered);
    }

    private static void collectInterfaces(Class<?> type, Set<Class<?>> into) {
        if (into.add(type)) {
            for (Class<?> parent : type.getInterfaces()) {
                collectInterfaces(parent, into);
            }
        }
    }

    /** Topologically orders interfaces so every subinterface precedes its ancestors. */
    private static List<Class<?>> bySpecificity(Set<Class<?>> closure) {
        List<Class<?>> remaining = new ArrayList<>(closure);
        List<Class<?>> ordered = new ArrayList<>(closure.size());
        while (!remaining.isEmpty()) {
            boolean progressed = false;
            for (java.util.Iterator<Class<?>> iterator = remaining.iterator();
                    iterator.hasNext(); ) {
                Class<?> candidate = iterator.next();
                boolean hasUnplacedSubinterface = false;
                for (Class<?> other : remaining) {
                    if (other != candidate && candidate.isAssignableFrom(other)) {
                        hasUnplacedSubinterface = true;
                        break;
                    }
                }
                if (!hasUnplacedSubinterface) {
                    ordered.add(candidate);
                    iterator.remove();
                    progressed = true;
                }
            }
            if (!progressed) {
                // Interfaces cannot form cycles; purely defensive.
                ordered.addAll(remaining);
                break;
            }
        }
        return ordered;
    }

    /**
     * Identity for override purposes: name plus parameter types.
     *
     * <p>Return type is excluded deliberately. Java lets a subclass narrow a return type, and the
     * two declarations are the same method as far as overriding goes even though their bytecode
     * descriptors differ.
     */
    private static String signatureOf(Method method) {
        StringBuilder key = new StringBuilder(method.getName()).append('(');
        for (Class<?> parameter : method.getParameterTypes()) {
            key.append(parameter.getName()).append(';');
        }
        return key.append(')').toString();
    }

    /**
     * Why this method cannot be overridden, or {@code null} if it can.
     */
    private static String rejectionReasonFor(Method method, Class<?> superclass,
                                             boolean canOverridePackagePrivate) {
        int modifiers = method.getModifiers();
        if (method.isBridge()) {
            return "bridge method; the method it forwards to is overridden instead";
        }
        if (method.isSynthetic()) {
            return "compiler-generated";
        }
        if (Modifier.isStatic(modifiers)) {
            return "static methods are not dispatched virtually and cannot be overridden";
        }
        if (Modifier.isPrivate(modifiers)) {
            return "private";
        }
        if (Modifier.isFinal(modifiers)) {
            return "final";
        }
        if (method.getName().equals("finalize") && method.getParameterCount() == 0) {
            return "finalize() is deprecated and is deliberately left alone";
        }
        if (method.getDeclaringClass() == Object.class && !Modifier.isPublic(modifiers)) {
            // In practice this is Object.clone(). It is protected and declared in java.lang, and
            // Java only permits access to a protected member on a receiver of the accessing
            // class's own type. A Dispatcher or LazyLoader has to invoke the method on some other
            // object, which that rule forbids outright -- the JVM rejects the class with
            // "Bad access to protected data". Proxying clone() is rarely wanted and never worth
            // that, so it is left alone. A clone() the target declares itself is unaffected.
            return "protected and declared by java.lang.Object, which cannot be dispatched to "
                    + "another object";
        }
        if (!Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers)) {
            if (!canOverridePackagePrivate) {
                return "package-private, and the proxy cannot be placed in "
                        + superclass.getPackageName() + " (same package name is not enough; "
                        + "the class loader must match too)";
            }
            if (!method.getDeclaringClass().getPackageName().equals(superclass.getPackageName())) {
                return "package-private in " + method.getDeclaringClass().getPackageName()
                        + ", which differs from the proxy's package";
            }
        }
        return null;
    }

    /**
     * Whether a skipped method is worth mentioning.
     *
     * <p>Every class inherits a handful of final and private methods from {@code Object} that
     * nobody expects to intercept. Listing them buries the one line a user actually needs.
     */
    private static boolean isUninteresting(Method method) {
        return method.getDeclaringClass() == Object.class || method.isBridge()
                || method.isSynthetic();
    }
}
