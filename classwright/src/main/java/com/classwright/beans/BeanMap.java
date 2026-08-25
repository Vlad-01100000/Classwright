package com.classwright.beans;

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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Map} view over a bean's properties.
 *
 * <pre>{@code
 * BeanMap map = BeanMap.create(order);
 * map.get("total");
 * map.put("total", 99);
 * }</pre>
 *
 * <p>Reading and writing go through generated accessors rather than reflection: the key is turned
 * into an index once, and the index selects a direct call through a {@code tableswitch}.
 *
 * <p>The map is a <em>view</em>, not a copy. Changes to the bean are visible through the map and
 * vice versa, and the key set is fixed at generation time — {@link #put} for an unknown property
 * returns {@code null} and does nothing rather than adding an entry, because there is nowhere to
 * add it to.
 */
public abstract class BeanMap extends AbstractMap<String, Object> {

    private Object bean;
    private Lookup lookup;

    /** Adapted no-argument constructor of the generated class, shared by every view of a shape. */
    private MethodHandle constructor;

    /**
     * Property name to switch index, built once per generated class. Serves the iteration
     * surface — {@code keySet}, {@code entrySet}, {@code size}, {@code containsKey},
     * {@code getPropertyType} — in stable sorted order; get and put never touch it, they go
     * through the generated key dispatch.
     */
    private Map<String, Integer> indexes = Map.of();

    /**
     * One fully-initialised view per generated class, from which every further view is copied.
     *
     * <p>Kept on the generated class itself, so it lives exactly as long as the class and pins
     * nothing — the same shape as {@link GenerationCache}, whose {@code Class}-only values it
     * complements: a cache hit recovers the indexes, lookup, and constructor from here instead of
     * re-running introspection.
     */
    private static final ClassValue<AtomicReference<BeanMap>> PROTOTYPES = new ClassValue<>() {
        @Override
        protected AtomicReference<BeanMap> computeValue(Class<?> generated) {
            return new AtomicReference<>();
        }
    };

    /** Cache key under the bean class; a value constant, since the class is the real key. */
    private static final Object SHAPE_KEY = "BeanMap";

    /**
     * For generated subclasses. Use {@link #create} to obtain an instance.
     */
    protected BeanMap() {
    }

    /**
     * Creates a map view over a bean.
     *
     * <p>The accessor class for a bean type is generated once and reused, so every call after the
     * first is a constructor invocation, not a class generation.
     *
     * @param bean the bean to wrap
     * @return the view
     */
    public static BeanMap create(Object bean) {
        if (bean == null) {
            throw new ClasswrightException("bean must not be null");
        }
        return prototypeFor(bean.getClass()).copyOver(bean);
    }

    /**
     * Creates a map view with no bean attached, for use with {@link #newInstance}.
     *
     * @param beanClass the bean type
     * @return a detached view
     */
    public static BeanMap forClass(Class<?> beanClass) {
        return prototypeFor(beanClass).copyOver(null);
    }

    private static BeanMap prototypeFor(Class<?> beanClass) {
        Class<?> generated = GenerationCache.computeIfAbsent(beanClass, SHAPE_KEY,
                () -> defineShape(beanClass));
        BeanMap prototype = PROTOTYPES.get(generated).get();
        if (prototype == null) {
            throw new ClasswrightException("no prototype registered for " + generated.getName());
        }
        return prototype;
    }

    private static Class<?> defineShape(Class<?> beanClass) {
        Map<String, BeanProperties.Property> properties = BeanProperties.of(beanClass);
        List<BeanProperties.Property> ordered = List.copyOf(properties.values());

        ClassDefiner definer = ClassDefiner.alongside(beanClass);
        DefinedClass defined = definer.define(emit(
                definer.generatedNameFor(beanClass, "$$Map"), beanClass, ordered));

        BeanMap prototype = (BeanMap) Instantiator.register(defined).newInstance();
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            indexes.put(ordered.get(i).name(), i);
        }
        // unmodifiableMap, not Map.copyOf: copyOf salts its iteration order per JVM run, and the
        // sorted property order — which keySet(), entrySet(), and any serialisation of this map
        // all expose — is documented to be stable across runs.
        prototype.indexes = Collections.unmodifiableMap(indexes);
        prototype.lookup = defined.maybeLookup().orElse(null);
        prototype.constructor = defined.constructor()
                .asType(MethodType.methodType(Object.class));
        PROTOTYPES.get(defined.type()).compareAndSet(null, prototype);
        return defined.type();
    }

    /** A fresh view sharing this shape's generated class, indexes, and constructor. */
    private BeanMap copyOver(Object newBean) {
        BeanMap copy;
        try {
            // The local is load-bearing: invokeExact takes its expected signature from the call
            // site, and casting the invocation directly would demand ()BeanMap of a ()Object handle.
            Object instance = constructor.invokeExact();
            copy = (BeanMap) instance;
        } catch (Throwable t) {
            throw new ClasswrightException("could not create another BeanMap: " + t, t);
        }
        copy.indexes = indexes;
        copy.lookup = lookup;
        copy.constructor = constructor;
        copy.bean = newBean;
        return copy;
    }

    /**
     * Another view of the same shape over a different bean.
     *
     * <p>Reuses the generated class, so this is far cheaper than generating: one constructor call
     * through an already-adapted handle.
     *
     * @param bean the bean to wrap
     * @return a new view
     */
    public BeanMap newInstance(Object bean) {
        if (constructor == null) {
            throw new ClasswrightException("this map was not created through BeanMap.create or "
                    + "BeanMap.forClass, so it cannot create further views");
        }
        return copyOver(bean);
    }

    /**
     * The bean this view reads and writes.
     *
     * @return the bean this map currently reads and writes
     */
    public Object getBean() {
        return bean;
    }

    /**
     * Points this view at a different bean of the same type.
     *
     * @param bean the bean to read and write from now on
     */
    public void setBean(Object bean) {
        this.bean = bean;
    }

    // ==========================================================================================
    // Generated
    // ==========================================================================================

    /**
     * Reads property {@code index}; generated.
     *
     * @param bean  the bean to read from
     * @param index the property's position
     * @return the property value, boxed if it is a primitive
     */
    protected abstract Object getByIndex(Object bean, int index);

    /**
     * Writes property {@code index}; generated.
     *
     * @param bean  the bean to write to
     * @param index the property's position
     * @param value the new value, boxed if the property is a primitive
     */
    protected abstract void setByIndex(Object bean, int index, Object value);

    /**
     * Reads the property named {@code key} straight off the bean; generated.
     *
     * <p>The hot path: a {@code lookupswitch} on the key's hash jumps directly to the accessor,
     * the way CGLib's generated {@code BeanMap} — and {@code javac}'s own string switch — do it.
     * The map-of-indexes route this replaced cost a hash probe, an {@code Integer} unbox, and a
     * second dispatch per call, which benchmarks showed at 4–5× CGLib's time.
     *
     * @param bean the bean to read from; never null
     * @param key  the property name; never null, any type
     * @return the value, boxed if primitive, or {@code null} for an unknown or write-only key
     */
    protected abstract Object getByKey(Object bean, Object key);

    /**
     * Writes the property named {@code key} straight on the bean; generated.
     *
     * <p>Read-only and unknown keys return {@code null} without touching the bean — the
     * writability of each property is decided at generation time, inside the matched arm, not
     * through a runtime table.
     *
     * @param bean  the bean to write to; never null
     * @param key   the property name; never null, any type
     * @param value the new value, boxed if the property is a primitive
     * @return the previous value, or {@code null} for an unknown, read-only, or write-only key
     */
    protected abstract Object putByKey(Object bean, Object key, Object value);

    // ==========================================================================================
    // Map
    // ==========================================================================================

    @Override
    public Object get(Object key) {
        // Null tolerated as "no such property", as a map lookup would; the generated dispatch
        // starts with key.hashCode() and must never see it.
        return key == null ? null : getByKey(bean, key);
    }

    @Override
    public Object put(String key, Object value) {
        // Unknown key: the key set is fixed by the bean's shape, so there is nowhere to put
        // it. Read-only property: nothing will be written, and answering with the current
        // value would claim a write happened. Both answer null, as CGLib answered, which
        // also keeps a bulk putAll of a wider map from failing outright. The generated
        // dispatch encodes those rules per key.
        return key == null ? null : putByKey(bean, key, value);
    }

    /**
     * Reads a property directly off {@code bean}, without repointing this view.
     *
     * <p>The generated accessors already take the bean as a parameter — the wrapped bean is
     * just what the {@link Map} methods pass — so reading another bean of the same shape costs
     * an index probe and the generated call, nothing more. This is the CGLib
     * {@code get(bean, key)} contract, served without constructing another view per call.
     *
     * @param bean a bean of the type this map was created for
     * @param key  the property name
     * @return the property's value on {@code bean}, or {@code null} for an unknown property
     */
    public Object get(Object bean, Object key) {
        return key == null ? null : getByKey(bean, key);
    }

    /**
     * Writes a property directly on {@code bean}, without repointing this view.
     *
     * @param bean  a bean of the type this map was created for
     * @param key   the property name
     * @param value the value to set
     * @return the property's previous value on {@code bean}, or {@code null} for an unknown
     *         property
     */
    public Object put(Object bean, Object key, Object value) {
        return key == null ? null : putByKey(bean, key, value);
    }

    @Override
    public Set<String> keySet() {
        return indexes.keySet();
    }

    @Override
    public boolean containsKey(Object key) {
        return indexes.containsKey(key);
    }

    @Override
    public int size() {
        return indexes.size();
    }

    /**
     * The declared type of a property, or {@code null} if there is no such property.
     *
     * @param name the property name
     * @return its declared type, or {@code null} if there is no such property
     */
    public Class<?> getPropertyType(String name) {
        Integer index = indexes.get(name);
        return index == null ? null : propertyTypeAt(index);
    }

    /**
     * Overridden by the generated class to report types without reflection at call time.
     *
     * @param index the property's position
     * @return its declared type
     */
    protected Class<?> propertyTypeAt(int index) {
        return Object.class;
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<Entry<String, Object>> iterator() {
                Iterator<String> keys = keySet().iterator();
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return keys.hasNext();
                    }

                    @Override
                    public Entry<String, Object> next() {
                        String key = keys.next();
                        // A live entry, per the Map contract for views: setValue writes through
                        // to the bean. A detached SimpleEntry here silently swallowed writes.
                        return new Entry<>() {
                            @Override
                            public String getKey() {
                                return key;
                            }

                            @Override
                            public Object getValue() {
                                return get(key);
                            }

                            @Override
                            public Object setValue(Object value) {
                                return put(key, value);
                            }

                            @Override
                            public boolean equals(Object other) {
                                return other instanceof Entry<?, ?> entry
                                        && java.util.Objects.equals(key, entry.getKey())
                                        && java.util.Objects.equals(getValue(), entry.getValue());
                            }

                            @Override
                            public int hashCode() {
                                Object value = getValue();
                                return key.hashCode() ^ (value == null ? 0 : value.hashCode());
                            }

                            @Override
                            public String toString() {
                                // The Map.Entry convention; the inherited identity string made
                                // printed maps unreadable.
                                return key + "=" + getValue();
                            }
                        };
                    }
                };
            }

            @Override
            public int size() {
                return BeanMap.this.size();
            }
        };
    }

    // ==========================================================================================
    // Generation
    // ==========================================================================================

    private static byte[] emit(String self, Class<?> beanClass,
                               List<BeanProperties.Property> properties) {
        CwClassWriter writer = CwClassWriter
                .of(AccessFlags.PUBLIC | AccessFlags.SUPER, self, internal(BeanMap.class))
                .sourceFile(beanClass.getSimpleName() + "$$Map.java");

        writer.constructor(AccessFlags.PUBLIC, CwMethodType.of(CwType.VOID))
                .code()
                .loadThis()
                .invokeConstructor(internal(BeanMap.class), CwMethodType.of(CwType.VOID))
                .returnValue();

        emitGet(writer, beanClass, properties);
        emitSet(writer, beanClass, properties);
        emitGetByKey(writer, beanClass, properties);
        emitPutByKey(writer, beanClass, properties);
        emitTypes(writer, properties);
        return writer.toByteArray();
    }

    /**
     * Property names grouped by their {@code String.hashCode}, ascending — the shape a
     * {@code lookupswitch} wants, with same-hash names (possible, if never seen in a sane bean)
     * chained behind one case. String keys hash identically through the generated
     * {@code key.hashCode()} virtual call, so the switch and the constant agree; a non-String
     * key either misses every case or fails its {@code equals} guard.
     */
    private static java.util.SortedMap<Integer, List<BeanProperties.Property>> byNameHash(
            List<BeanProperties.Property> properties) {
        java.util.TreeMap<Integer, List<BeanProperties.Property>> groups = new java.util.TreeMap<>();
        for (BeanProperties.Property property : properties) {
            groups.computeIfAbsent(property.name().hashCode(), hash -> new java.util.ArrayList<>(1))
                    .add(property);
        }
        return groups;
    }

    private static void emitGetByKey(CwClassWriter writer, Class<?> beanClass,
                                     List<BeanProperties.Property> properties) {
        CodeBuilder code = writer
                .method(AccessFlags.PUBLIC, "getByKey",
                        CwMethodType.of(CwType.OBJECT, CwType.OBJECT, CwType.OBJECT))
                .code();
        if (properties.isEmpty()) {
            code.pushNull().returnValue(CwType.OBJECT);
            return;
        }
        java.util.SortedMap<Integer, List<BeanProperties.Property>> groups =
                byNameHash(properties);
        int[] hashes = groups.keySet().stream().mapToInt(Integer::intValue).toArray();
        code.loadArgument(1);
        code.invokeVirtual("java/lang/Object", "hashCode", CwMethodType.of(CwType.INT));
        code.lookupSwitch(hashes,
                hash -> {
                    for (BeanProperties.Property property : groups.get(hash)) {
                        code.pushString(property.name());
                        code.loadArgument(1);
                        code.invokeVirtual("java/lang/String", "equals",
                                CwMethodType.of(CwType.BOOLEAN, CwType.OBJECT));
                        code.ifIntComparison(CodeBuilder.IntTest.NOT_ZERO,
                                () -> {
                                    if (!property.isReadable()) {
                                        code.pushNull().returnValue(CwType.OBJECT);
                                        return;
                                    }
                                    code.loadArgument(0).checkCast(CwType.of(beanClass));
                                    invoke(code, beanClass, property.getter());
                                    code.box(CwType.of(property.getter().getReturnType()));
                                    code.returnValue(CwType.OBJECT);
                                },
                                () -> {
                                    // Fall through to the next same-hash candidate, or to the
                                    // no-match answer below.
                                });
                    }
                    code.pushNull().returnValue(CwType.OBJECT);
                },
                () -> code.pushNull().returnValue(CwType.OBJECT));
    }

    private static void emitPutByKey(CwClassWriter writer, Class<?> beanClass,
                                     List<BeanProperties.Property> properties) {
        CodeBuilder code = writer
                .method(AccessFlags.PUBLIC, "putByKey",
                        CwMethodType.of(CwType.OBJECT, CwType.OBJECT, CwType.OBJECT,
                                CwType.OBJECT))
                .code();
        if (properties.isEmpty()) {
            code.pushNull().returnValue(CwType.OBJECT);
            return;
        }
        java.util.SortedMap<Integer, List<BeanProperties.Property>> groups =
                byNameHash(properties);
        int[] hashes = groups.keySet().stream().mapToInt(Integer::intValue).toArray();
        code.loadArgument(1);
        code.invokeVirtual("java/lang/Object", "hashCode", CwMethodType.of(CwType.INT));
        code.lookupSwitch(hashes,
                hash -> {
                    for (BeanProperties.Property property : groups.get(hash)) {
                        code.pushString(property.name());
                        code.loadArgument(1);
                        code.invokeVirtual("java/lang/String", "equals",
                                CwMethodType.of(CwType.BOOLEAN, CwType.OBJECT));
                        code.ifIntComparison(CodeBuilder.IntTest.NOT_ZERO,
                                () -> emitPutArm(code, beanClass, property),
                                () -> {
                                    // Fall through to the next same-hash candidate.
                                });
                    }
                    code.pushNull().returnValue(CwType.OBJECT);
                },
                () -> code.pushNull().returnValue(CwType.OBJECT));
    }

    /**
     * One matched put: writability decided here, at generation time, per key.
     *
     * <p>Read-only answers {@code null} without touching the bean. A readable-and-writable
     * property reads the previous value first and keeps it on the stack across the write, so no
     * local is needed; a write-only property writes and answers {@code null}, there being no
     * previous value to report. Strict unbox throughout: null for a primitive property fails
     * with the natural NPE before anything is written, exactly as {@code setByIndex} behaves.
     */
    private static void emitPutArm(CodeBuilder code, Class<?> beanClass,
                                   BeanProperties.Property property) {
        if (!property.isWritable()) {
            code.pushNull().returnValue(CwType.OBJECT);
            return;
        }
        if (property.isReadable()) {
            code.loadArgument(0).checkCast(CwType.of(beanClass));
            invoke(code, beanClass, property.getter());
            code.box(CwType.of(property.getter().getReturnType()));
        } else {
            code.pushNull();
        }
        code.loadArgument(0).checkCast(CwType.of(beanClass));
        code.loadArgument(2);
        code.unbox(CwType.of(property.setter().getParameterTypes()[0]));
        invoke(code, beanClass, property.setter());
        code.returnValue(CwType.OBJECT);
    }

    private static void emitGet(CwClassWriter writer, Class<?> beanClass,
                                List<BeanProperties.Property> properties) {
        CodeBuilder code = writer
                .method(AccessFlags.PUBLIC, "getByIndex",
                        CwMethodType.of(CwType.OBJECT, CwType.OBJECT, CwType.INT))
                .code();
        if (properties.isEmpty()) {
            code.pushNull().returnValue(CwType.OBJECT);
            return;
        }
        code.loadArgument(1);
        code.tableSwitch(0, properties.size() - 1,
                index -> {
                    BeanProperties.Property property = properties.get(index);
                    if (!property.isReadable()) {
                        code.pushNull().returnValue(CwType.OBJECT);
                        return;
                    }
                    code.loadArgument(0).checkCast(CwType.of(beanClass));
                    invoke(code, beanClass, property.getter());
                    code.box(CwType.of(property.getter().getReturnType()));
                    code.returnValue(CwType.OBJECT);
                },
                () -> code.pushNull().returnValue(CwType.OBJECT));
    }

    private static void emitSet(CwClassWriter writer, Class<?> beanClass,
                                List<BeanProperties.Property> properties) {
        CodeBuilder code = writer
                .method(AccessFlags.PUBLIC, "setByIndex",
                        CwMethodType.of(CwType.VOID, CwType.OBJECT, CwType.INT, CwType.OBJECT))
                .code();
        if (properties.isEmpty()) {
            code.returnValue();
            return;
        }
        code.loadArgument(1);
        code.tableSwitch(0, properties.size() - 1,
                index -> {
                    BeanProperties.Property property = properties.get(index);
                    if (!property.isWritable()) {
                        code.returnValue(CwType.VOID);
                        return;
                    }
                    code.loadArgument(0).checkCast(CwType.of(beanClass));
                    code.loadArgument(2);
                    // Strict unbox: null for a primitive property fails with the natural NPE and
                    // leaves the bean untouched — CGLib's behaviour, and silently writing zero
                    // hides real bugs either way. Reference properties accept null unchanged.
                    code.unbox(CwType.of(property.setter().getParameterTypes()[0]));
                    invoke(code, beanClass, property.setter());
                    code.returnValue(CwType.VOID);
                },
                () -> code.returnValue(CwType.VOID));
    }

    private static void emitTypes(CwClassWriter writer,
                                  List<BeanProperties.Property> properties) {
        CodeBuilder code = writer
                .method(AccessFlags.PUBLIC, "propertyTypeAt",
                        CwMethodType.of(CwType.CLASS, CwType.INT))
                .code();
        if (properties.isEmpty()) {
            code.pushNull().returnValue(CwType.CLASS);
            return;
        }
        code.loadArgument(0);
        code.tableSwitch(0, properties.size() - 1,
                index -> code.pushClassConstant(CwType.of(properties.get(index).type()))
                        .returnValue(CwType.CLASS),
                () -> code.pushNull().returnValue(CwType.CLASS));
    }

    private static void invoke(CodeBuilder code, Class<?> owner, java.lang.reflect.Method method) {
        CwMethodType signature = CwMethodType.of(method);
        if (owner.isInterface()) {
            code.invokeInterface(internal(owner), method.getName(), signature);
        } else {
            code.invokeVirtual(internal(owner), method.getName(), signature);
        }
    }

    private static String internal(Class<?> type) {
        return type.getName().replace('.', '/');
    }
}
