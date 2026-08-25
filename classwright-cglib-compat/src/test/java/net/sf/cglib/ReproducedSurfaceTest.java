package net.sf.cglib;

import net.sf.cglib.beans.BeanGenerator;
import net.sf.cglib.beans.BeanMap;
import net.sf.cglib.core.DefaultNamingPolicy;
import net.sf.cglib.proxy.InvocationHandler;
import net.sf.cglib.proxy.Mixin;
import net.sf.cglib.proxy.Proxy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of CGLib's surface that exist for <em>shape</em> — constants code references,
 * classes code subclasses, helpers code calls statically. Exercised the way an application
 * would; nothing here imports {@code com.classwright}.
 */
class ReproducedSurfaceTest {

    // ==========================================================================================
    // net.sf.cglib.proxy.Proxy: CGLib's class shape
    // ==========================================================================================

    /** The subclassing pattern CGLib permitted: protected constructor, protected h. */
    static class HandlerCarrier extends Proxy {

        HandlerCarrier(InvocationHandler handler) {
            super(handler);
        }

        InvocationHandler handler() {
            return h;
        }
    }

    @Test
    @DisplayName("Proxy is subclassable, Serializable, and hands subclasses the protected h")
    void proxyKeepsCglibShape() {
        InvocationHandler handler = (proxy, method, args) -> null;

        HandlerCarrier carrier = new HandlerCarrier(handler);

        assertSame(handler, carrier.handler(), "the protected field CGLib exposed");
        assertTrue(carrier instanceof Serializable, "CGLib's Proxy implements Serializable");
        assertTrue(!Modifier.isFinal(Proxy.class.getModifiers()),
                "CGLib's Proxy was subclassable");
    }

    // ==========================================================================================
    // net.sf.cglib.proxy.Mixin: constants and helpers
    // ==========================================================================================

    @Test
    @DisplayName("Mixin exposes CGLib's style constants with CGLib's values")
    void mixinStyleConstants() {
        assertEquals(0, Mixin.STYLE_INTERFACES);
        assertEquals(1, Mixin.STYLE_BEANS);
        assertEquals(2, Mixin.STYLE_EVERYTHING);
    }

    @Test
    @DisplayName("Mixin.getClasses returns each delegate's class, in order")
    void mixinGetClasses() {
        Object[] delegates = {"text", 42};

        assertArrayEquals(new Class[]{String.class, Integer.class},
                Mixin.getClasses(delegates));
    }

    // ==========================================================================================
    // net.sf.cglib.beans.BeanMap: constants and direct-bean accessors
    // ==========================================================================================

    /** A conventional bean. */
    public static class Person {

        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @Test
    @DisplayName("BeanMap exposes CGLib's require flags with CGLib's values")
    void beanMapRequireConstants() {
        assertEquals(1, BeanMap.REQUIRE_GETTER);
        assertEquals(2, BeanMap.REQUIRE_SETTER);
    }

    @Test
    @DisplayName("BeanMap reads and writes another bean directly, without setBean")
    void beanMapDirectBeanAccessors() {
        Person first = new Person();
        first.setName("first");
        Person second = new Person();
        second.setName("second");

        BeanMap map = BeanMap.create(first);

        assertEquals("second", map.get(second, "name"),
                "the three-argument get must read the bean it was handed");
        assertEquals("second", map.put(second, "name", "renamed"),
                "put returns the previous value, as Map contracts do");
        assertEquals("renamed", second.getName());
        assertEquals("first", first.getName(), "the wrapped bean must be untouched");
        assertEquals("first", map.get("name"), "the view itself must not have moved");
    }

    // ==========================================================================================
    // net.sf.cglib.beans.BeanGenerator: static addProperties helpers
    // ==========================================================================================

    @Test
    @DisplayName("addProperties(Map) reproduces every entry as a property")
    void addPropertiesFromMap() throws Exception {
        BeanGenerator generator = new BeanGenerator();
        Map<String, Class<?>> properties = new LinkedHashMap<>();
        properties.put("label", String.class);
        properties.put("count", int.class);

        BeanGenerator.addProperties(generator, properties);
        Object bean = generator.create();

        bean.getClass().getMethod("setLabel", String.class).invoke(bean, "x");
        assertEquals("x", bean.getClass().getMethod("getLabel").invoke(bean));
        assertEquals(0, bean.getClass().getMethod("getCount").invoke(bean));
    }

    @Test
    @DisplayName("addProperties(Class) reproduces a bean's own properties")
    void addPropertiesFromClass() throws Exception {
        BeanGenerator generator = new BeanGenerator();

        BeanGenerator.addProperties(generator, Person.class);
        Object bean = generator.create();

        bean.getClass().getMethod("setName", String.class).invoke(bean, "copied");
        assertEquals("copied", bean.getClass().getMethod("getName").invoke(bean));
    }

    @Test
    @DisplayName("addProperties(PropertyDescriptor[]) reproduces the descriptors given")
    void addPropertiesFromDescriptors() throws Exception {
        BeanGenerator generator = new BeanGenerator();
        java.beans.PropertyDescriptor[] descriptors = java.beans.Introspector
                .getBeanInfo(Person.class, Object.class).getPropertyDescriptors();

        BeanGenerator.addProperties(generator, descriptors);
        Object bean = generator.create();

        assertNull(bean.getClass().getMethod("getName").invoke(bean));
    }

    // ==========================================================================================
    // net.sf.cglib.reflect.FastClass: CGLib's identity, filtering and rendering semantics
    // ==========================================================================================

    /** A user-declared final method, which must stay indexed — only Object's finals are not. */
    public static class WithFinal {
        public final int id() {
            return 7;
        }
    }

    @Test
    @DisplayName("FastClass instances are distinct but equal by target class, as CGLib's were")
    void fastClassHasCglibIdentitySemantics() {
        net.sf.cglib.reflect.FastClass first =
                net.sf.cglib.reflect.FastClass.create(WithFinal.class);
        net.sf.cglib.reflect.FastClass second =
                net.sf.cglib.reflect.FastClass.create(WithFinal.class);

        // Measured against real CGLib 3.3.0: a == b false, a.equals(b) true, hashCodes equal.
        // Reproducing the weaker identity matters in both directions — returning one cached
        // instance made == true, which migrated-to-Classwright code could silently start
        // relying on and then break on the way back.
        assertNotSame(first, second, "CGLib returned distinct wrappers per create()");
        assertEquals(first, second, "equal by target class");
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, net.sf.cglib.reflect.FastClass.create(Person.class));
    }

    @Test
    @DisplayName("Object's final methods answer -1, as CGLib answered")
    void objectFinalMethodsAreFiltered() {
        net.sf.cglib.reflect.FastClass fast =
                net.sf.cglib.reflect.FastClass.create(WithFinal.class);

        assertEquals(-1, fast.getIndex("getClass", new Class[0]));
        assertEquals(-1, fast.getIndex("notify", new Class[0]));
        assertEquals(-1, fast.getIndex("notifyAll", new Class[0]));
        assertEquals(-1, fast.getIndex("wait", new Class[0]));
        assertEquals(-1, fast.getIndex(
                new net.sf.cglib.core.Signature("getClass", "()Ljava/lang/Class;")));
        assertTrue(fast.getIndex("id", new Class[0]) >= 0,
                "a user-declared final method is callable and indexed in both libraries");
        assertTrue(fast.getIndex("hashCode", new Class[0]) >= 0,
                "Object's non-final methods are ordinary overridable methods and stay indexed");
    }

    @Test
    @DisplayName("FastClass.toString is the target's own representation, as CGLib rendered it")
    void fastClassToStringMatchesCglib() {
        assertEquals(WithFinal.class.toString(),
                net.sf.cglib.reflect.FastClass.create(WithFinal.class).toString());
    }

    // ==========================================================================================
    // net.sf.cglib.beans.BulkBean: CGLib's BulkBeanException contract
    // ==========================================================================================

    /** A bean whose setter can be made to throw, for the wrapping contract. */
    public static class Fragile {

        private String name;
        private int count;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            if ("boom".equals(name)) {
                throw new IllegalArgumentException("setter");
            }
            this.name = name;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public String getBroken() {
            throw new IllegalStateException("getter");
        }

        public void setBroken(String ignored) {
        }
    }

    private static net.sf.cglib.beans.BulkBean fragileBulk() {
        return net.sf.cglib.beans.BulkBean.create(Fragile.class,
                new String[]{"getName", "getCount"},
                new String[]{"setName", "setCount"},
                new Class[]{String.class, int.class});
    }

    @Test
    @DisplayName("an unresolvable accessor reports BulkBeanException with the failing index")
    void bulkCreateReportsFailingIndex() {
        net.sf.cglib.beans.BulkBeanException failure =
                org.junit.jupiter.api.Assertions.assertThrows(
                        net.sf.cglib.beans.BulkBeanException.class,
                        () -> net.sf.cglib.beans.BulkBean.create(Fragile.class,
                                new String[]{"getName", "getMissing"},
                                new String[]{"setName", "setCount"},
                                new Class[]{String.class, int.class}));

        assertEquals(1, failure.getIndex(), "the second position is the unresolvable one");
        assertEquals("Cannot find specified property", failure.getMessage());
    }

    @Test
    @DisplayName("a throwing setter is wrapped with its index and original cause, as in CGLib")
    void bulkSetterExceptionIsWrapped() {
        net.sf.cglib.beans.BulkBeanException failure =
                org.junit.jupiter.api.Assertions.assertThrows(
                        net.sf.cglib.beans.BulkBeanException.class,
                        () -> fragileBulk().setPropertyValues(new Fragile(),
                                new Object[]{"boom", 1}));

        assertEquals(0, failure.getIndex());
        assertTrue(failure.getCause() instanceof IllegalArgumentException,
                "the setter's own exception must be the cause, not replaced");
        assertEquals("setter", failure.getCause().getMessage());
        assertEquals("setter", failure.getMessage(),
                "CGLib's cause constructor takes the cause's message as its own");
    }

    @Test
    @DisplayName("a wrongly-typed value is wrapped with its index, not a raw ClassCastException")
    void bulkWrongValueTypeIsWrapped() {
        net.sf.cglib.beans.BulkBeanException failure =
                org.junit.jupiter.api.Assertions.assertThrows(
                        net.sf.cglib.beans.BulkBeanException.class,
                        () -> fragileBulk().setPropertyValues(new Fragile(),
                                new Object[]{42, 1}));

        assertEquals(0, failure.getIndex(), "the String property was handed an Integer");
        assertTrue(failure.getCause() instanceof ClassCastException);
    }

    @Test
    @DisplayName("an undersized values array is wrapped with the index that fell off the end")
    void bulkUndersizedArrayIsWrapped() {
        Fragile bean = new Fragile();

        net.sf.cglib.beans.BulkBeanException failure =
                org.junit.jupiter.api.Assertions.assertThrows(
                        net.sf.cglib.beans.BulkBeanException.class,
                        () -> fragileBulk().setPropertyValues(bean, new Object[]{"ok"}));

        assertEquals(1, failure.getIndex(), "position 0 fit; position 1 fell off the end");
        assertTrue(failure.getCause() instanceof ArrayIndexOutOfBoundsException);
        assertEquals("ok", bean.getName(),
                "earlier positions were already written, as in CGLib");
    }

    @Test
    @DisplayName("a throwing getter propagates raw; only the write side wraps, as in CGLib")
    void bulkGetterExceptionPropagatesRaw() {
        net.sf.cglib.beans.BulkBean bulk = net.sf.cglib.beans.BulkBean.create(Fragile.class,
                new String[]{"getBroken"}, new String[]{"setBroken"},
                new Class[]{String.class});

        IllegalStateException raw = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> bulk.getPropertyValues(new Fragile()));

        assertEquals("getter", raw.getMessage(),
                "CGLib's exception contract was asymmetric: getter failures were not wrapped");
    }

    @Test
    @DisplayName("an undersized destination array on the read side propagates raw")
    void bulkGetterUndersizedArrayPropagatesRaw() {
        org.junit.jupiter.api.Assertions.assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> fragileBulk().getPropertyValues(new Fragile(), new Object[1]));
    }

    @Test
    @DisplayName("null for a primitive property fails with the index, not a silent zero")
    void bulkNullForPrimitiveIsWrapped() {
        Fragile bean = new Fragile();

        net.sf.cglib.beans.BulkBeanException failure =
                org.junit.jupiter.api.Assertions.assertThrows(
                        net.sf.cglib.beans.BulkBeanException.class,
                        () -> fragileBulk().setPropertyValues(bean, new Object[]{"ok", null}));

        assertEquals(1, failure.getIndex(), "the primitive position is the failing one");
        assertTrue(failure.getCause() instanceof NullPointerException);
        assertEquals("ok", bean.getName(), "earlier positions were already written");
        assertEquals(0, bean.getCount(), "the failing position must not have been written");
    }

    // ==========================================================================================
    // net.sf.cglib.beans.BeanMap: CGLib's null, read-only and create semantics
    // ==========================================================================================

    /** A bean with a primitive property and a read-only property. */
    public static class Account {

        private int limit;
        private final String id = "fixed";

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public String getId() {
            return id;
        }
    }

    @Test
    @DisplayName("BeanMap.create(null) fails with CGLib's exact IllegalArgumentException")
    void beanMapCreateNullMatchesCglib() {
        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> BeanMap.create(null));

        assertEquals("Class of bean unknown", failure.getMessage());
    }

    @Test
    @DisplayName("null for a primitive property throws and leaves the bean unchanged")
    void beanMapNullForPrimitiveThrows() {
        Account account = new Account();
        account.setLimit(7);
        BeanMap map = BeanMap.create(account);

        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> map.put("limit", null));

        assertEquals(7, account.getLimit(), "a failed write must not have zeroed the bean");
    }

    @Test
    @DisplayName("null keys throw on every keyed operation except containsKey, as in CGLib")
    void beanMapNullKeysThrow() {
        Account account = new Account();
        BeanMap map = BeanMap.create(account);

        assertFalse(map.containsKey(null), "the one null-tolerant keyed operation");
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> map.get(null));
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> map.put(null, "x"));
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> map.getPropertyType(null));
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> map.get(account, null));
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> map.put(account, null, "x"));
    }

    @Test
    @DisplayName("put on a read-only property returns null and writes nothing, as in CGLib")
    void beanMapReadOnlyPutReturnsNull() {
        Account account = new Account();
        BeanMap map = BeanMap.create(account);

        assertNull(map.put("id", "changed"),
                "answering with the current value would claim a write happened");
        assertEquals("fixed", account.getId());
        assertNull(map.put(account, "id", "changed"), "the direct-bean overload agrees");
    }

    // ==========================================================================================
    // Exception shapes: CGLib's legacy message forms
    // ==========================================================================================

    @Test
    @DisplayName("exception cause and index survive a serialization round trip")
    void exceptionSerializedFormCarriesCauseAndIndex() throws Exception {
        // The fields CGLib serialized — its own private cause and index — must exist here, or a
        // matching serialVersionUID just makes the data loss silent: the stream deserializes
        // and the unmatched field's value is dropped.
        net.sf.cglib.beans.BulkBeanException original = new net.sf.cglib.beans.BulkBeanException(
                new IllegalArgumentException("bad"), 3);

        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        net.sf.cglib.beans.BulkBeanException copy;
        try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(
                new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            copy = (net.sf.cglib.beans.BulkBeanException) in.readObject();
        }

        assertEquals(3, copy.getIndex());
        assertTrue(copy.getCause() instanceof IllegalArgumentException,
                "the cause travels in the class's own serialized field, as CGLib's did");
        assertEquals("bad", copy.getCause().getMessage());
        assertEquals("bad", copy.getMessage());
    }

    @Test
    @DisplayName("CodeGenerationException renders CGLib's legacy cls-->msg message form")
    void codeGenerationExceptionLegacyMessage() {
        IllegalStateException cause = new IllegalStateException("boom");

        net.sf.cglib.core.CodeGenerationException wrapped =
                new net.sf.cglib.core.CodeGenerationException(cause);

        // Verified against real CGLib 3.3.0: message and hierarchy exactly.
        assertEquals("java.lang.IllegalStateException-->boom", wrapped.getMessage());
        assertSame(cause, wrapped.getCause());
        assertEquals(RuntimeException.class, net.sf.cglib.core.CodeGenerationException.class
                .getSuperclass(), "CGLib extended RuntimeException directly");
    }

    // ==========================================================================================
    // net.sf.cglib.core.DefaultNamingPolicy: value semantics
    // ==========================================================================================

    /** CGLib's own pattern for a derived policy: change the tag, inherit the rest. */
    static class TaggedPolicy extends DefaultNamingPolicy {
        @Override
        protected String getTag() {
            return "ByTest";
        }
    }

    @Test
    @DisplayName("naming policies compare by tag, as CGLib's did")
    void namingPolicyValueSemantics() {
        assertEquals(new DefaultNamingPolicy(), DefaultNamingPolicy.INSTANCE,
                "same tag, so interchangeable");
        assertEquals(new DefaultNamingPolicy().hashCode(),
                DefaultNamingPolicy.INSTANCE.hashCode());
        assertNotEquals(DefaultNamingPolicy.INSTANCE, new TaggedPolicy(),
                "a different tag names classes differently, so it is a different policy");
    }
}
