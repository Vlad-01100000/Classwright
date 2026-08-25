package com.classwright.reflect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bridge methods are JVM methods, and a {@link FastClass} must answer for every one of them.
 *
 * <p>The trap these tests pin down: Java-level thinking says a covariant family is one method,
 * but the class file contains one method <em>per descriptor</em>, and calls compiled against the
 * wider signature land on the bridge. An accessor that dropped bridges reported
 * {@code getIndex("value", "()Ljava/lang/Object;")} as -1 for a class that demonstrably has that
 * method — CGLib answered it, so migrated descriptor-based lookups broke.
 */
class FastClassBridgeTest {

    // ==========================================================================================
    // Fixtures: each shape javac solves with a bridge
    // ==========================================================================================

    /** Covariant override of a superclass method: {@code item()} narrows Object to String. */
    public static class Wide {
        public Object item() {
            return "wide";
        }
    }

    public static class Narrow extends Wide {
        @Override
        public String item() {
            return "narrow";
        }
    }

    /** Covariant declarations from two independent interfaces. */
    public interface ObjectValued {
        Object value();
    }

    public interface StringValued {
        String value();
    }

    public static class BothValued implements ObjectValued, StringValued {
        @Override
        public String value() {
            return "x";
        }
    }

    /** Generic specialisation: the erased {@code unwrap(Object)} bridges to the real method. */
    public interface Box<T> {
        T unwrap(T boxed);
    }

    public static class StringBox implements Box<String> {
        @Override
        public String unwrap(String boxed) {
            return boxed + "!";
        }
    }

    /** The bridge lives on a superclass; the target itself declares nothing. */
    public static class InheritedBox extends StringBox {
    }

    // ==========================================================================================
    // JVM identity: one index per descriptor
    // ==========================================================================================

    @Test
    @DisplayName("a covariant class override answers for both descriptors")
    void covariantOverrideKeepsBothDescriptors() throws Exception {
        FastClass fast = FastClass.create(Narrow.class);

        int narrow = fast.getIndex("item", "()Ljava/lang/String;");
        int wide = fast.getIndex("item", "()Ljava/lang/Object;");

        assertTrue(narrow >= 0, "the real method's descriptor must resolve");
        assertTrue(wide >= 0, "the bridge's descriptor is a JVM method the class has");
        assertNotEquals(narrow, wide, "two descriptors are two methods");

        assertEquals("narrow", fast.invoke(narrow, new Narrow(), new Object[0]));
        assertEquals("narrow", fast.invoke(wide, new Narrow(), new Object[0]),
                "the bridge forwards to the override, as it does for any caller");
    }

    @Test
    @DisplayName("covariant declarations from independent interfaces both resolve")
    void independentInterfaceFamilyKeepsBothDescriptors() throws Exception {
        FastClass fast = FastClass.create(BothValued.class);

        int viaString = fast.getIndex("value", "()Ljava/lang/String;");
        int viaObject = fast.getIndex("value", "()Ljava/lang/Object;");

        assertTrue(viaString >= 0);
        assertTrue(viaObject >= 0, "this returned -1 before bridges were indexed");
        assertEquals("x", fast.invoke(viaString, new BothValued(), new Object[0]));
        assertEquals("x", fast.invoke(viaObject, new BothValued(), new Object[0]));
    }

    @Test
    @DisplayName("a generic bridge with erased parameters is its own callable method")
    void genericBridgeIsCallable() throws Exception {
        FastClass fast = FastClass.create(StringBox.class);

        int real = fast.getIndex("unwrap", "(Ljava/lang/String;)Ljava/lang/String;");
        int erased = fast.getIndex("unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;");

        assertTrue(real >= 0);
        assertTrue(erased >= 0);
        assertEquals("hi!", fast.invoke(real, new StringBox(), new Object[]{"hi"}));
        assertEquals("hi!", fast.invoke(erased, new StringBox(), new Object[]{"hi"}));
    }

    @Test
    @DisplayName("a bridge inherited through a superclass still answers")
    void inheritedBridgeAnswers() throws Exception {
        FastClass fast = FastClass.create(InheritedBox.class);

        int erased = fast.getIndex("unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;");

        assertTrue(erased >= 0);
        assertEquals("up!", fast.invoke(erased, new InheritedBox(), new Object[]{"up"}));
    }

    // ==========================================================================================
    // Method-object identity: a Method carries its return type, so it is JVM identity
    // ==========================================================================================

    static java.lang.reflect.Method declared(Class<?> type, String name, boolean bridge) {
        for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.isBridge() == bridge) {
                return method;
            }
        }
        throw new AssertionError("no " + (bridge ? "bridge " : "") + name + " on " + type);
    }

    @Test
    @DisplayName("a bridge Method resolves to the bridge's own index, not the real method's")
    void methodLookupIsDescriptorExact() throws Exception {
        FastClass fast = FastClass.create(Narrow.class);
        java.lang.reflect.Method real = declared(Narrow.class, "item", false);
        java.lang.reflect.Method bridge = declared(Narrow.class, "item", true);

        int realIndex = fast.getIndex(real);
        int bridgeIndex = fast.getIndex(bridge);

        assertTrue(realIndex >= 0);
        assertTrue(bridgeIndex >= 0);
        assertNotEquals(realIndex, bridgeIndex,
                "a Method names one JVM method exactly; collapsing the family loses the bridge");
        assertEquals(realIndex, fast.getIndex("item", "()Ljava/lang/String;"),
                "the Method-based and descriptor-based lookups must agree");
        assertEquals(bridgeIndex, fast.getIndex("item", "()Ljava/lang/Object;"));
        assertEquals("narrow", fast.invoke(bridgeIndex, new Narrow(), new Object[0]));
    }

    @Test
    @DisplayName("getMethod(Method) pairs the bridge Method with the bridge's slot")
    void getMethodFollowsTheExactMethod() throws Exception {
        FastClass fast = FastClass.create(StringBox.class);
        java.lang.reflect.Method real = declared(StringBox.class, "unwrap", false);
        java.lang.reflect.Method bridge = declared(StringBox.class, "unwrap", true);

        FastMethod realFast = fast.getMethod(real);
        FastMethod bridgeFast = fast.getMethod(bridge);

        assertNotEquals(realFast.getIndex(), bridgeFast.getIndex());
        assertEquals("go!", realFast.invoke(new StringBox(), new Object[]{"go"}));
        assertEquals("go!", bridgeFast.invoke(new StringBox(), new Object[]{"go"}),
                "invoking through the bridge's slot dispatches to the real implementation");
    }

    // ==========================================================================================
    // Java identity: name plus parameters means the real method, not the bridge
    // ==========================================================================================

    @Test
    @DisplayName("Java-level lookups resolve the covariant family to the real method")
    void javaLookupPrefersTheRealMethod() {
        FastClass fast = FastClass.create(BothValued.class);

        int index = fast.getIndex("value", new Class<?>[0]);

        assertTrue(index >= 0);
        assertFalse(fast.getMethods().get(index).isBridge(),
                "a caller with no return type in hand means the method they could write a call "
                        + "to, which is the real one");
        assertEquals(String.class, fast.getMethods().get(index).getReturnType());
    }

    @Test
    @DisplayName("erasure bridges keep their own Java-level identity, since their parameters differ")
    void erasureBridgeHasOwnParameterKey() {
        FastClass fast = FastClass.create(StringBox.class);

        int erased = fast.getIndex("unwrap", new Class<?>[]{Object.class});
        int real = fast.getIndex("unwrap", new Class<?>[]{String.class});

        assertTrue(erased >= 0, "unwrap(Object) is what a caller holding an Object can invoke");
        assertTrue(real >= 0);
        assertNotEquals(erased, real);
    }

    @Test
    @DisplayName("indexes stay deterministic: two accessors of one shape agree")
    void indexesAreDeterministic() {
        // Same-process determinism only proves caching, so compare the derived orderings of two
        // classes with identical member shapes instead.
        FastClass first = FastClass.create(StringBox.class);
        FastClass second = FastClass.create(InheritedBox.class);

        assertEquals(first.getIndex("unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;"),
                second.getIndex("unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;"));
        assertEquals(first.getIndex("unwrap", "(Ljava/lang/String;)Ljava/lang/String;"),
                second.getIndex("unwrap", "(Ljava/lang/String;)Ljava/lang/String;"));
    }
}
