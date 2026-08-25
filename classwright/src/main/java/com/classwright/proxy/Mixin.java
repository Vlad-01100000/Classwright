package com.classwright.proxy;

import com.classwright.ClasswrightException;
import com.classwright.core.AccessFlags;
import com.classwright.core.CodeBuilder;
import com.classwright.core.CwClassWriter;
import com.classwright.core.CwMethodType;
import com.classwright.core.CwType;
import com.classwright.runtime.ClassDefiner;
import com.classwright.runtime.DefinedClass;
import com.classwright.runtime.GenerationCache;
import com.classwright.runtime.Instantiator;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Combines several objects into one that implements all of their interfaces.
 *
 * <pre>{@code
 * Mixin mixed = Mixin.create(new Object[]{reader, writer});
 * ((Reader) mixed).read();      // goes to reader
 * ((Writer) mixed).write("x");  // goes to writer
 * }</pre>
 *
 * <p>Composition where Java would otherwise require inheritance: an object that satisfies several
 * unrelated contracts by holding one implementation of each. Each method is routed at
 * <em>generation</em> time to the delegate that implements it, so a call costs an array read, a
 * cast, and an interface dispatch — there is no per-call search.
 *
 * <p>Where two delegates implement the same method, the earlier one wins. That is CGLib's rule and
 * it is worth being deliberate about: order the array by precedence.
 */
public abstract class Mixin {

    private Object[] delegates = new Object[0];

    /**
     * For generated subclasses. Use {@link #create} to obtain an instance.
     */
    protected Mixin() {
    }

    /**
     * Combines the delegates, implementing every interface each of them implements.
     *
     * @param delegates the objects to combine, in precedence order
     * @return an object implementing the union of their interfaces
     */
    public static Mixin create(Object[] delegates) {
        return create(interfacesOf(delegates), delegates);
    }

    /**
     * Combines the delegates, implementing exactly the interfaces named.
     *
     * <p>Each interface is routed to the first delegate that implements it.
     *
     * @param interfaces the interfaces the result should implement
     * @param delegates  the objects to combine
     * @return an object implementing those interfaces
     */
    public static Mixin create(Class<?>[] interfaces, Object[] delegates) {
        if (delegates == null || delegates.length == 0) {
            throw new ClasswrightException("a mixin needs at least one delegate");
        }
        if (interfaces == null || interfaces.length == 0) {
            throw new ClasswrightException("none of the delegates implement an interface, so "
                    + "there is nothing for the mixin to expose. Mixins combine interfaces, not "
                    + "classes.");
        }
        for (Class<?> each : interfaces) {
            if (!each.isInterface()) {
                throw new ClasswrightException(each.getName() + " is not an interface");
            }
            if (each.isSealed()) {
                // A sealed interface names its permitted implementors, and a generated class is
                // not among them. The JVM rejects the class with IncompatibleClassChangeError,
                // so it is worth catching here with an explanation.
                throw new ClasswrightException(each.getName() + " is sealed, so a generated class "
                        + "cannot implement it. Only its permitted implementors may.");
            }
        }

        int[] routing = routeInterfaces(interfaces, delegates);

        // The generated class depends only on the interface list and the routing, so it is cached
        // under the first interface — CGLib cached mixin classes the same way, and per-request
        // create() calls rely on it. The delegate array stays per instance.
        Class<?>[] interfaceKey = interfaces.clone();
        MixinKey key = new MixinKey(List.of(interfaceKey), intList(routing));
        Class<?> generated = GenerationCache.computeIfAbsent(interfaceKey[0], key,
                () -> defineMixin(interfaceKey, routing));

        Mixin mixin = (Mixin) Instantiator.forGenerated(generated).newInstance();
        mixin.delegates = delegates.clone();
        return mixin;
    }

    /**
     * The generation-cache key: interface list and routing.
     *
     * <p>The entry is anchored on the first interface, but the key names <em>all</em> of them —
     * and a long-lived anchor (a platform-library interface, say) must not pin a later,
     * shorter-lived interface's loader through the key. Non-anchor identity is therefore held
     * weakly with hashes captured up front, the same shape as {@code Enhancer}'s key: a collected
     * referent can only cause a miss, never a wrong hit, and the stale key is swept.
     */
    private static final class MixinKey implements GenerationCache.StaleKey {

        private final List<java.lang.ref.WeakReference<Class<?>>> interfaces;
        private final List<Integer> routing;
        private final int hash;

        MixinKey(List<Class<?>> interfaces, List<Integer> routing) {
            List<java.lang.ref.WeakReference<Class<?>>> weak =
                    new java.util.ArrayList<>(interfaces.size());
            int interfacesHash = 1;
            for (Class<?> each : interfaces) {
                weak.add(new java.lang.ref.WeakReference<>(each));
                interfacesHash = 31 * interfacesHash + each.getName().hashCode();
            }
            this.interfaces = weak;
            this.routing = routing;
            this.hash = interfacesHash * 31 + routing.hashCode();
        }

        @Override
        public boolean isStale() {
            for (java.lang.ref.WeakReference<Class<?>> each : interfaces) {
                if (each.get() == null) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MixinKey that)
                    || !routing.equals(that.routing)
                    || interfaces.size() != that.interfaces.size()) {
                return false;
            }
            for (int i = 0; i < interfaces.size(); i++) {
                Class<?> mine = interfaces.get(i).get();
                if (mine == null || mine != that.interfaces.get(i).get()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static List<Integer> intList(int[] values) {
        List<Integer> list = new java.util.ArrayList<>(values.length);
        for (int value : values) {
            list.add(value);
        }
        return list;
    }

    private static Class<?> defineMixin(Class<?>[] interfaces, int[] routing) {
        ClassDefiner definer = ClassDefiner.alongside(interfaces[0]);
        byte[] classBytes = emit(definer.generatedNameFor(interfaces[0], "$$MX"),
                interfaces, routing);
        DefinedClass defined = definer.define(classBytes);
        Instantiator.register(defined);
        return defined.type();
    }

    /**
     * Works out which delegate serves each interface.
     *
     * @return for each interface, the index of the delegate implementing it
     */
    private static int[] routeInterfaces(Class<?>[] interfaces, Object[] delegates) {
        int[] routing = new int[interfaces.length];
        for (int i = 0; i < interfaces.length; i++) {
            routing[i] = -1;
            for (int d = 0; d < delegates.length; d++) {
                if (delegates[d] != null && interfaces[i].isInstance(delegates[d])) {
                    routing[i] = d;
                    break;      // first match wins, so the array is a precedence order
                }
            }
            if (routing[i] < 0) {
                throw new ClasswrightException("none of the delegates implements "
                        + interfaces[i].getName());
            }
        }
        return routing;
    }

    /** Every interface implemented by any delegate, in delegate order. */
    private static Class<?>[] interfacesOf(Object[] delegates) {
        Set<Class<?>> found = new LinkedHashSet<>();
        for (Object delegate : delegates) {
            if (delegate != null) {
                collect(delegate.getClass(), found);
            }
        }
        return found.toArray(Class<?>[]::new);
    }

    /**
     * Collects the interfaces a generated class could actually implement.
     *
     * <p>Sealed and non-public interfaces are skipped rather than reported. This runs on the
     * automatic path, where the caller said "combine these objects" and did not name any
     * interface, so silently leaving out one that is impossible to implement is better than
     * refusing the whole request. {@code java.lang.String}, for instance, implements the sealed
     * {@code ConstantDesc}, which would otherwise make it unusable as a delegate.
     *
     * <p>The explicit {@code create(Class[], Object[])} path reports instead, because there the
     * caller asked for a specific interface by name.
     */
    private static void collect(Class<?> type, Set<Class<?>> into) {
        for (Class<?> each = type; each != null; each = each.getSuperclass()) {
            for (Class<?> implemented : each.getInterfaces()) {
                if (implemented.isSealed()
                        || !Modifier.isPublic(implemented.getModifiers())) {
                    continue;
                }
                if (into.add(implemented)) {
                    collect(implemented, into);
                }
            }
        }
    }

    /**
     * Generates the combining class.
     *
     * <p>Each method body reads its delegate out of the array by a constant index, casts, and
     * forwards. Duplicate signatures across interfaces are emitted once — a class file cannot
     * declare the same name and descriptor twice — with the first interface's routing.
     */
    private static byte[] emit(String self, Class<?>[] interfaces, int[] routing) {
        String[] internalNames = new String[interfaces.length];
        for (int i = 0; i < interfaces.length; i++) {
            internalNames[i] = internal(interfaces[i]);
        }

        CwClassWriter writer = CwClassWriter
                .of(AccessFlags.PUBLIC | AccessFlags.SUPER, self, internal(Mixin.class),
                        internalNames)
                .sourceFile(interfaces[0].getSimpleName() + "$$MX.java");

        writer.constructor(AccessFlags.PUBLIC, CwMethodType.of(CwType.VOID))
                .code()
                .loadThis()
                .invokeConstructor(internal(Mixin.class), CwMethodType.of(CwType.VOID))
                .returnValue();

        Set<String> emitted = new LinkedHashSet<>();
        for (int i = 0; i < interfaces.length; i++) {
            for (Method method : interfaces[i].getMethods()) {
                if (Modifier.isStatic(method.getModifiers())) {
                    continue;   // statics are not inherited and cannot be forwarded
                }
                // Default methods are forwarded too, deliberately: skipping them would run the
                // interface's default body even when the delegate overrides it, silently routing
                // around the delegate. Virtual dispatch on the delegate picks its override, and a
                // delegate that does not override still runs the default — through one forward.
                CwMethodType signature = CwMethodType.of(method);
                if (!emitted.add(method.getName() + signature.descriptor())) {
                    continue;   // an earlier interface already claimed this signature
                }
                emitForward(writer, interfaces[i], method, signature, routing[i]);
            }
        }
        return writer.toByteArray();
    }

    private static void emitForward(CwClassWriter writer, Class<?> declaring, Method method,
                                    CwMethodType signature, int delegateIndex) {
        CodeBuilder code = writer
                .method(AccessFlags.PUBLIC, method.getName(), signature)
                .code();

        code.loadThis()
                .invokeVirtual(internal(Mixin.class), "getDelegates",
                        CwMethodType.of(CwType.OBJECT_ARRAY))
                .pushInt(delegateIndex)
                .arrayLoad(CwType.OBJECT)
                .checkCast(CwType.of(declaring));
        code.loadAllArguments();
        code.invokeInterface(internal(declaring), method.getName(), signature);
        code.returnValue(signature.returnType());
    }

    /**
     * The delegates, in the order they were supplied.
     *
     * @return the objects calls are forwarded to, in order
     */
    public final Object[] getDelegates() {
        return delegates;
    }

    /**
     * Another mixin of the same shape with different delegates.
     *
     * <p>Reuses the generated class, so this is far cheaper than calling {@link #create} again.
     * The new delegates must implement the same interfaces in the same order, since the routing is
     * compiled in.
     *
     * @param delegates the replacements
     * @return a new mixin
     */
    public Mixin newInstance(Object[] delegates) {
        if (delegates == null || delegates.length != this.delegates.length) {
            throw new ClasswrightException("expected " + this.delegates.length
                    + " delegates, because the routing between interfaces and delegates is "
                    + "compiled into the generated class");
        }
        Mixin copy = (Mixin) Instantiator.forGenerated(getClass()).newInstance();
        copy.delegates = delegates.clone();
        return copy;
    }

    /**
     * The interfaces a mixin over these delegates would implement.
     *
     * @param delegates the objects to be mixed together
     * @return every interface they implement, in first-seen order
     */
    public static List<Class<?>> interfacesFor(Object[] delegates) {
        return new ArrayList<>(List.of(interfacesOf(delegates)));
    }

    private static String internal(Class<?> type) {
        return type.getName().replace('.', '/');
    }
}
