package com.classwright.runtime;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A set of generated classes with a deliberate, bounded lifetime.
 *
 * <pre>{@code
 * try (ClasswrightScope scope = ClasswrightScope.open("plugin-42")) {
 *     DefinedClass proxy = scope.define(Service.class, bytes);
 *     // ... use it ...
 * }   // every class generated here becomes collectible at this point
 * }</pre>
 *
 * <h2>When this is worth using</h2>
 *
 * <p>Most applications never need it. Generated classes are already collected when nothing refers
 * to them, and {@link GenerationCache} holds only weak references, so the normal lifetime &mdash;
 * "as long as instances exist" &mdash; is usually exactly right.
 *
 * <p>A scope is for the cases where "eventually, when the collector notices" is not good enough:
 * an application server undeploying an application, a plugin container unloading a plugin, a test
 * suite that wants each case to start clean. There, teardown is a defined moment, and it is useful
 * to be able to say so.
 *
 * <p>Note what closing does and does not do. It drops this scope's references, making its classes
 * eligible for collection. It cannot unload a class that something else still holds &mdash; a live
 * instance, a cached {@code Method}, a thread-local. That is not a limitation of this API but of
 * how the JVM works, and a scope that claimed otherwise would be lying.
 */
public final class ClasswrightScope implements AutoCloseable {

    /**
     * Strong references, which is the entire mechanism.
     *
     * <p>While the scope is open these keep its classes loaded even if no instances exist, so
     * behaviour inside the scope is predictable. Closing clears the list and hands the classes back
     * to ordinary reachability rules.
     */
    private final List<DefinedClass> defined = new CopyOnWriteArrayList<>();

    private final String name;
    private volatile boolean closed;

    private ClasswrightScope(String name) {
        this.name = name;
    }

    /**
     * Opens an unnamed scope.
     *
     * @return a new, open scope
     */
    public static ClasswrightScope open() {
        return new ClasswrightScope("anonymous");
    }

    /**
     * Opens a named scope.
     *
     * @param name identifies the scope in diagnostics; use something meaningful such as the
     *             plugin or deployment it belongs to
     *
     * @return a new, open scope
     */
    public static ClasswrightScope open(String name) {
        return new ClasswrightScope(name);
    }

    /**
     * Defines a class within this scope.
     *
     * @param neighbour  the class being extended or proxied
     * @param classBytes a complete class file
     * @return the loaded class
     * @throws IllegalStateException if the scope is already closed
     */
    public DefinedClass define(Class<?> neighbour, byte[] classBytes) {
        return define(ClassDefiner.alongside(neighbour), classBytes);
    }

    /**
     * Defines a class within this scope using a specific definer.
     *
     * @param definer    controls placement and strategy
     * @param classBytes a complete class file
     * @return the loaded class
     * @throws IllegalStateException if the scope is already closed
     */
    public DefinedClass define(ClassDefiner definer, byte[] classBytes) {
        if (closed) {
            throw new IllegalStateException("scope '" + name + "' is closed");
        }
        DefinedClass result = definer.define(classBytes);
        defined.add(result);
        if (closed) {
            // Raced with close(): the flag was set and the list cleared after our check but
            // possibly before our add. Undoing the add keeps the scope's one guarantee — closed
            // means released — instead of stranding a strong reference in a closed scope.
            defined.remove(result);
            throw new IllegalStateException("scope '" + name + "' was closed during define");
        }
        return result;
    }

    /**
     * How many classes this scope is holding open.
     *
     * @return how many classes this scope has defined and not yet released
     */
    public int size() {
        return defined.size();
    }

    /**
     * Whether {@link #close} has been called.
     *
     * @return whether this scope is closed
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Whether every class in this scope could in principle be unloaded once released.
     *
     * <p>False if any of them was defined with {@link DefinitionStrategy#named()}, which never
     * unloads. Worth checking in a container that depends on reclaiming memory at undeploy: a
     * single named class silently defeats the whole scope.
     *
     * @return whether every class defined here can actually be reclaimed on close; false if any was defined by a strategy that never unloads
     */
    public boolean isFullyReclaimable() {
        return defined.stream().allMatch(DefinedClass::isUnloadable);
    }

    /**
     * Releases this scope's hold on its classes.
     *
     * <p>Idempotent. After this the classes are collectible as soon as nothing else refers to them,
     * which for hidden classes means genuinely unloaded and their metaspace returned.
     */
    @Override
    public void close() {
        closed = true;
        defined.clear();
    }

    @Override
    public String toString() {
        return "ClasswrightScope[" + name + ", " + defined.size() + " classes"
                + (closed ? ", closed" : "") + "]";
    }
}
