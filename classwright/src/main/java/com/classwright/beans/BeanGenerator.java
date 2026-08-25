package com.classwright.beans;

import com.classwright.ClasswrightException;
import com.classwright.core.AccessFlags;
import com.classwright.core.CwClassWriter;
import com.classwright.core.CwMethodType;
import com.classwright.core.CwType;
import com.classwright.runtime.ClassDefiner;
import com.classwright.runtime.DefinitionSite;
import com.classwright.runtime.DefinitionStrategy;
import com.classwright.runtime.DefinedClass;
import com.classwright.runtime.GenerationCache;
import com.classwright.runtime.Instantiator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds a bean class at runtime from a set of property names and types.
 *
 * <pre>{@code
 * BeanGenerator generator = new BeanGenerator();
 * generator.addProperty("name", String.class);
 * generator.addProperty("count", int.class);
 *
 * Object bean = generator.create();
 * BeanMap.create(bean).put("name", "x");
 * }</pre>
 *
 * <p>For shapes only known at runtime: a row from a query, a record described by configuration, a
 * structure assembled from a schema. The result is an ordinary class with a private field and a
 * matching getter and setter per property, so anything that speaks JavaBeans understands it —
 * including {@link BeanMap} and {@link BulkBean}.
 *
 * <h2>These classes are not hidden, and never unload</h2>
 *
 * <p>Almost everything Classwright generates is a hidden class, which is what lets it be reclaimed.
 * A generated bean cannot be, and the reason is worth understanding: a hidden class has no
 * resolvable name, so no other bytecode can refer to it — and {@link BeanMap}, {@link BulkBean} and
 * {@link BeanCopier} all work by generating code that calls the bean's accessors <em>by name</em>.
 * A hidden bean would be unreachable by exactly the tools it exists to be used with.
 *
 * <p>So the class is ordinary and named, and is retained for the life of its class loader. Build
 * these once per shape and hold on to the {@code Class}; building one per request leaks metaspace
 * exactly as CGLib did. The same constraint applies to
 * {@link com.classwright.proxy.InterfaceMaker}, and for the same underlying reason.
 */
public class BeanGenerator {

    /** Creates a generator with no properties; add them and call {@link #create()}. */
    public BeanGenerator() {
    }

    /** Distinguishes generated bean classes, which must have unique resolvable names. */
    private static final java.util.concurrent.atomic.AtomicLong SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();

    private final Map<String, Class<?>> properties = new LinkedHashMap<>();

    private Class<?> superclass = Object.class;
    private Class<?> neighbour;

    /**
     * Adds a property.
     *
     * @param name the property name; the accessors are derived from it
     * @param type the property type
     * @return this generator
     */
    public BeanGenerator addProperty(String name, Class<?> type) {
        if (name == null || name.isEmpty() || type == null || type == void.class) {
            throw new ClasswrightException("a property needs a name and a non-void type");
        }
        if (properties.containsKey(name)) {
            // CGLib threw for duplicates too; silently overwriting hides a caller bug.
            throw new ClasswrightException("duplicate property name: " + name);
        }
        String capitalised = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String existing : properties.keySet()) {
            String existingCapitalised =
                    Character.toUpperCase(existing.charAt(0)) + existing.substring(1);
            if (existingCapitalised.equals(capitalised)) {
                // "name" and "Name" both derive getName/setName; the collision would otherwise
                // surface at create() as a duplicate-member error naming the method, not the
                // property the caller actually typed.
                throw new ClasswrightException("property '" + name + "' derives the same "
                        + "accessors as the already-added property '" + existing + "'");
            }
        }
        if (capitalised.equals("Class")) {
            // getClass() would override Object's final method; the JVM rejects the class at
            // definition with an error that points nowhere near this call.
            throw new ClasswrightException(
                    "'class' is not usable as a property name: getClass() is final on Object");
        }
        properties.put(name, type);
        return this;
    }

    /**
     * Sets a superclass for the generated bean. Defaults to {@link Object}.
     *
     * @param superclass a class with an accessible no-argument constructor
     * @return this generator
     */
    public BeanGenerator setSuperclass(Class<?> superclass) {
        this.superclass = superclass == null ? Object.class : superclass;
        return this;
    }

    /**
     * Sets the package the bean is created in, by naming a class to sit beside.
     *
     * <p>Defaults to the superclass, or to Classwright's own package when that is {@link Object}.
     *
     * @param neighbour a class in the desired package
     * @return this generator
     */
    public BeanGenerator setNeighbour(Class<?> neighbour) {
        this.neighbour = neighbour;
        return this;
    }

    /**
     * Generates the class and creates one instance.
     *
     * @return the new bean
     */
    public Object create() {
        return Instantiator.forClass(define()).newInstance();
    }

    /**
     * Generates the class without creating an instance.
     *
     * @return the generated class
     */
    public Class<?> createClass() {
        return define().type();
    }

    private DefinedClass define() {
        if (properties.isEmpty()) {
            throw new ClasswrightException("no properties were added, so there is no bean to "
                    + "generate. Use addProperty(...) first.");
        }
        Class<?> placement = neighbour != null ? neighbour
                : superclass != Object.class ? superclass : firstApplicationType();

        // Cached per shape: these are *named* classes, so every distinct generation is retained
        // for the life of its loader — CGLib reused the class for an identical configuration,
        // and code migrated from it generates per request on that assumption. The key holds the
        // property names and types and the superclass; the anchor is the placement class, and
        // the classes in the key are held weakly so the key cannot pin a loader the anchor
        // outlives.
        ShapeKey key = new ShapeKey(GenerationCache.WeakPart.of(superclass),
                new java.util.ArrayList<>(properties.keySet()),
                GenerationCache.WeakPart.ofClasses(
                        new java.util.ArrayList<>(properties.values())));
        Class<?> generated = GenerationCache.computeIfAbsent(placement, key, () -> {
            DefinitionStrategy strategy = DefinitionStrategy.named()
                    .isUsableAt(DefinitionSite.of(placement))
                    ? DefinitionStrategy.named()
                    : DefinitionStrategy.childLoader();
            ClassDefiner definer = ClassDefiner.using(placement, strategy);
            DefinedClass defined = definer.define(emit(definer.generatedNameInPackage(
                    "CwBean$" + Long.toHexString(SEQUENCE.incrementAndGet()))));
            Instantiator.register(defined);
            DEFINITIONS.get(defined.type()).compareAndSet(null, defined);
            return defined.type();
        });
        DefinedClass defined = DEFINITIONS.get(generated).get();
        if (defined == null) {
            throw new ClasswrightException("no definition registered for " + generated.getName());
        }
        return defined;
    }

    /**
     * The generation-cache key: superclass plus ordered property names and types, the classes
     * held weakly so the key cannot pin a foreign loader from its anchor.
     */
    private record ShapeKey(GenerationCache.WeakPart superclass, java.util.List<String> names,
                            java.util.List<GenerationCache.WeakPart> types)
            implements GenerationCache.StaleKey {

        @Override
        public boolean isStale() {
            return superclass.isStale() || GenerationCache.WeakPart.anyStale(types);
        }
    }

    /**
     * Where a bean goes when nothing chose a package: beside the first property type that came
     * from an application loader, or beside Classwright when the shape is all JDK types.
     *
     * <p>The choice is load-bearing for redeploys. The generated class is named, retained for
     * the life of the loader it is defined into, and it references every property type — so a
     * bean anchored on Classwright's own permanent class would pin an application's types, and
     * with them their loader, past any undeploy. Placed beside the application's own type, the
     * bean lives in that type's loader (or a child of it) and dies with the application. An
     * all-JDK shape references nothing an undeploy could reclaim, so Classwright's package
     * remains the right home for it.
     */
    private Class<?> firstApplicationType() {
        for (Class<?> type : properties.values()) {
            Class<?> element = type;
            while (element.isArray()) {
                element = element.getComponentType();
            }
            if (!element.isPrimitive() && element.getClassLoader() != null) {
                return element;
            }
        }
        return BeanGenerator.class;
    }

    /** One {@link DefinedClass} per generated class; the same recovery shape as {@code BeanMap}. */
    private static final ClassValue<java.util.concurrent.atomic.AtomicReference<DefinedClass>>
            DEFINITIONS = new ClassValue<>() {
        @Override
        protected java.util.concurrent.atomic.AtomicReference<DefinedClass> computeValue(
                Class<?> generated) {
            return new java.util.concurrent.atomic.AtomicReference<>();
        }
    };

    private byte[] emit(String self) {
        String superName = superclass.getName().replace('.', '/');
        CwClassWriter writer = CwClassWriter
                .of(AccessFlags.PUBLIC | AccessFlags.SUPER, self, superName)
                .sourceFile("CwBean.java");

        writer.constructor(AccessFlags.PUBLIC, CwMethodType.of(CwType.VOID))
                .code()
                .loadThis()
                .invokeConstructor(superName, CwMethodType.of(CwType.VOID))
                .returnValue();

        for (Map.Entry<String, Class<?>> property : properties.entrySet()) {
            String name = property.getKey();
            CwType type = CwType.of(property.getValue());
            String capitalised = Character.toUpperCase(name.charAt(0)) + name.substring(1);

            writer.field(AccessFlags.PRIVATE, name, type);

            writer.method(AccessFlags.PUBLIC, accessorPrefix(type) + capitalised,
                            CwMethodType.of(type))
                    .code()
                    .loadThis()
                    .getField(self, name, type)
                    .returnValue(type);

            writer.method(AccessFlags.PUBLIC, "set" + capitalised,
                            CwMethodType.of(CwType.VOID, type))
                    .code()
                    .loadThis()
                    .loadArgument(0)
                    .putField(self, name, type)
                    .returnValue();
        }
        return writer.toByteArray();
    }

    /**
     * Always {@code getX}, matching CGLib's {@code BeanGenerator} exactly — including for
     * {@code boolean}, where CGLib did <em>not</em> follow the JavaBeans {@code isX} convention.
     * Reflection-driven code migrated from CGLib looks the accessor up by that name, so the
     * conventional spelling here would be a silent incompatibility, not an improvement.
     */
    private static String accessorPrefix(CwType type) {
        return "get";
    }
}
