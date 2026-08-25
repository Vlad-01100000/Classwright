package com.classwright.proxy;

import com.classwright.ClasswrightException;
import com.classwright.core.AccessFlags;
import com.classwright.core.CwClassWriter;
import com.classwright.core.CwMethodType;
import com.classwright.runtime.ClassDefiner;
import com.classwright.runtime.DefinitionStrategy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds an interface at runtime from a set of method signatures.
 *
 * <pre>{@code
 * InterfaceMaker maker = new InterfaceMaker();
 * maker.add("compute", int.class, int.class, int.class);
 * Class<?> contract = maker.create();
 *
 * Object proxy = Enhancer.create(Object.class, new Class<?>[]{contract}, callback);
 * }</pre>
 *
 * <p>Useful for building a contract that only exists at runtime — a scripting bridge, a
 * remoting stub, an interface assembled from configuration — which can then be implemented by
 * {@link Enhancer} or by the JDK's own proxies.
 *
 * <h2>These interfaces are not hidden classes, and never unload</h2>
 *
 * <p>Everything else Classwright generates is a hidden class, which is what lets it be reclaimed.
 * An interface cannot be: to implement one, a class file has to name it in its {@code interfaces}
 * list, and a hidden class has no name that can be resolved. So the interface must be defined as an
 * ordinary named class, and an ordinary class is recorded in its loader for as long as the loader
 * lives.
 *
 * <p>The practical rule is to build these once, at startup, and hold on to the {@code Class}.
 * Building one per request leaks metaspace exactly as CGLib did. That is a real constraint rather
 * than an implementation shortcut, and it is why nothing here is cached for you: a cache would hide
 * the cost without removing it.
 */
public class InterfaceMaker {

    /** Creates a maker with no methods; add them and call {@link #create()}. */
    public InterfaceMaker() {
    }

    private static final AtomicLong SEQUENCE = new AtomicLong();

    /** Keyed by name plus descriptor so two overloads can coexist and duplicates collapse. */
    private final Map<String, MethodSpec> methods = new LinkedHashMap<>();

    private Class<?> neighbour;
    private String simpleName;

    private record MethodSpec(String name, CwMethodType type, Class<?>[] exceptions) {
    }

    /**
     * Adds a method with the same signature as an existing one.
     *
     * @param method the method to copy the signature of
     * @return this maker
     */
    public InterfaceMaker add(Method method) {
        if (neighbour == null) {
            neighbour = method.getDeclaringClass();
        }
        return add(method.getName(), CwMethodType.of(method), method.getExceptionTypes());
    }

    /**
     * Adds a method.
     *
     * @param name           the method name
     * @param returnType     its return type
     * @param parameterTypes its parameter types
     * @return this maker
     */
    public InterfaceMaker add(String name, Class<?> returnType, Class<?>... parameterTypes) {
        return add(name, CwMethodType.of(returnType, parameterTypes), new Class<?>[0]);
    }

    private InterfaceMaker add(String name, CwMethodType type, Class<?>[] exceptions) {
        if (name == null || name.isEmpty()) {
            throw new ClasswrightException("a method needs a name");
        }
        methods.put(name + type.descriptor(), new MethodSpec(name, type, exceptions));
        return this;
    }

    /**
     * Adds every public instance method of a type.
     *
     * @param type the class or interface whose methods to copy
     * @return this maker
     */
    public InterfaceMaker addAll(Class<?> type) {
        if (neighbour == null) {
            neighbour = type;
        }
        for (Method method : type.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                    && method.getDeclaringClass() != Object.class) {
                add(method);
            }
        }
        return this;
    }

    /**
     * Sets the package the interface is created in, by naming a class to sit beside.
     *
     * <p>Defaults to the declaring class of the first method added, which is normally where the
     * interface will be used and therefore where it is guaranteed to be visible from.
     *
     * @param neighbour a class in the desired package
     * @return this maker
     */
    public InterfaceMaker setNeighbour(Class<?> neighbour) {
        this.neighbour = neighbour;
        return this;
    }

    /**
     * Sets the interface's simple name. Defaults to a generated, unique one.
     *
     * @param simpleName the name, without a package
     * @return this maker
     */
    public InterfaceMaker setName(String simpleName) {
        this.simpleName = simpleName;
        return this;
    }

    /**
     * Generates the interface.
     *
     * @return the new interface; every call generates a new one
     */
    public Class<?> create() {
        if (methods.isEmpty()) {
            throw new ClasswrightException("no methods were added, so there is no interface to "
                    + "create. Use add(...) or addAll(...) first.");
        }

        Class<?> placement = neighbour == null ? InterfaceMaker.class : neighbour;

        // Not hidden: an interface has to be resolvable by name, because a class implementing it
        // names it in its own class file. Either strategy below produces a resolvable name; the
        // choice is only about where it can be put. A closed package -- java.* being the usual
        // case, reached by copying a signature from a JDK interface -- leaves the child loader.
        DefinitionStrategy strategy =
                DefinitionStrategy.named().isUsableAt(com.classwright.runtime.DefinitionSite
                        .of(placement))
                        ? DefinitionStrategy.named()
                        : DefinitionStrategy.childLoader();
        ClassDefiner definer = ClassDefiner.using(placement, strategy);

        String name = simpleName == null
                ? "CwInterface$" + SEQUENCE.incrementAndGet()
                : simpleName;
        String internalName = definer.generatedNameInPackage(name);

        CwClassWriter writer = CwClassWriter
                .ofInterface(AccessFlags.PUBLIC, internalName)
                .sourceFile(name + ".java");

        for (MethodSpec spec : methods.values()) {
            var method = writer.method(AccessFlags.PUBLIC | AccessFlags.ABSTRACT,
                    spec.name(), spec.type());
            for (Class<?> exception : spec.exceptions()) {
                method.throwsException(exception.getName().replace('.', '/'));
            }
        }
        return definer.define(writer.toByteArray()).type();
    }
}
