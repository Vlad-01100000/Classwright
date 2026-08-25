package com.classwright.reflect;

import com.classwright.ClasswrightException;
import com.classwright.reflect.fixtures.Calculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests reflection-free invocation, including what is and is not reachable. */
class FastClassTest {

    private static final FastClass FAST = FastClass.create(Calculator.class);

    private static int indexOf(String name, Class<?>... parameterTypes) {
        int index = FAST.getIndex(name, parameterTypes);
        assertTrue(index >= 0, name + " should be callable");
        return index;
    }

    @Test
    @DisplayName("invokes a method with primitive arguments and return")
    void invokesPrimitiveMethods() throws Exception {
        Object result = FAST.invoke(indexOf("add", int.class, int.class),
                new Calculator(), new Object[]{3, 4});

        assertEquals(7, result);
    }

    @Test
    @DisplayName("handles mixed one- and two-slot arguments")
    void handlesMixedSlotWidths() throws Exception {
        Object result = FAST.invoke(indexOf("total", long.class, int.class, double.class),
                new Calculator(), new Object[]{10L, 2, 3.9});

        assertEquals(15L, result);
    }

    @Test
    @DisplayName("returns null for a void method, having actually run it")
    void handlesVoidMethods() throws Exception {
        Calculator target = new Calculator(42);

        Object result = FAST.invoke(indexOf("reset"), target, new Object[0]);

        assertNull(result);
        assertEquals(0, target.value, "the method must have run");
    }

    @Test
    @DisplayName("handles reference and array returns")
    void handlesReferenceReturns() throws Exception {
        Calculator target = new Calculator(7);

        assertEquals("value=7", FAST.invoke(indexOf("describe", String.class),
                target, new Object[]{"value="}));
        assertArrayEquals(new int[]{0, 1, 2}, (int[]) FAST.invoke(indexOf("counted", int.class),
                target, new Object[]{3}));
        assertEquals(false, FAST.invoke(indexOf("isZero"), target, new Object[0]));
    }

    @Test
    @DisplayName("invokes a static method with no receiver")
    void invokesStaticMethods() throws Exception {
        assertEquals("hi world", FAST.invoke(indexOf("greet", String.class),
                null, new Object[]{"world"}));
    }

    @Test
    @DisplayName("invokes a final method, which is callable even though it is not overridable")
    void invokesFinalMethods() throws Exception {
        // The set of callable methods is deliberately wider than the set a proxy can override:
        // this calls methods rather than overriding them.
        assertEquals(99, FAST.invoke(indexOf("finalMethod"), new Calculator(), new Object[0]));
    }

    @Test
    @DisplayName("invokes a package-private method when the accessor shares the package")
    void invokesPackagePrivateMethods() throws Exception {
        assertEquals("package", FAST.invoke(indexOf("packagePrivate"),
                new Calculator(), new Object[0]));
    }

    @Test
    @DisplayName("does not cover private methods")
    void excludesPrivateMethods() {
        assertEquals(-1, FAST.getIndex("secret", new Class<?>[0]));
    }

    @Test
    @DisplayName("covers inherited public methods")
    void coversInheritedMethods() throws Exception {
        Calculator target = new Calculator();

        assertEquals(target.toString(),
                FAST.invoke(indexOf("toString"), target, new Object[0]));
    }

    @Test
    @DisplayName("wraps whatever the target method threw")
    void wrapsMethodExceptions() {
        // Matching Method.invoke, and CGLib: a failure from inside the target should not be
        // confusable with a failure of the dispatch machinery.
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> FAST.invoke(indexOf("boom"), new Calculator(), new Object[0]));

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals("method exploded", failure.getCause().getMessage());
    }

    @Test
    @DisplayName("an out-of-range index is an argument error, not a wrapped one")
    void rejectsBadIndex() {
        assertThrows(IllegalArgumentException.class,
                () -> FAST.invoke(FAST.getMaxIndex() + 1, new Calculator(), new Object[0]));
        assertThrows(IllegalArgumentException.class,
                () -> FAST.invoke(-1, new Calculator(), new Object[0]));
    }

    // ==========================================================================================
    // Construction
    // ==========================================================================================

    @Test
    @DisplayName("constructs with each accessible constructor")
    void constructsInstances() throws Exception {
        assertEquals(0, ((Calculator) FAST.newInstance()).value);
        assertEquals(5, ((Calculator) FAST.newInstance(
                new Class<?>[]{int.class}, new Object[]{5})).value);
        assertEquals(4, ((Calculator) FAST.newInstance(
                new Class<?>[]{String.class}, new Object[]{"abcd"})).value);
    }

    @Test
    @DisplayName("wraps whatever a constructor threw")
    void wrapsConstructorExceptions() {
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> FAST.newInstance(new Class<?>[]{boolean.class}, new Object[]{true}));

        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals("constructor exploded", failure.getCause().getMessage());
    }

    @Test
    @DisplayName("reports a missing constructor clearly")
    void reportsMissingConstructor() {
        ClasswrightException failure = assertThrows(ClasswrightException.class,
                () -> FAST.newInstance(new Class<?>[]{float.class}, new Object[]{1f}));

        assertTrue(failure.getMessage().contains("constructor"), failure.getMessage());
    }

    // ==========================================================================================
    // Lookup and handles
    // ==========================================================================================

    @Test
    @DisplayName("finds a method by name, by reflective Method, and by descriptor")
    void findsMethodsSeveralWays() throws Exception {
        Method add = Calculator.class.getMethod("add", int.class, int.class);
        int byName = FAST.getIndex("add", new Class<?>[]{int.class, int.class});

        assertEquals(byName, FAST.getIndex(add));
        assertEquals(byName, FAST.getIndex("add", "(II)I"),
                "the descriptor form replaces CGLib's ASM-typed Signature");
        assertEquals(-1, FAST.getIndex("add", "(JJ)J"), "a wrong descriptor should not match");
    }

    @Test
    @DisplayName("indexes are stable across accessors for the same class")
    void indexesAreDeterministic() {
        // Not part of the contract, but an index that shifted between runs would be a nasty
        // surprise for anyone who logged or persisted one.
        assertEquals(FAST.getIndex("add", new Class<?>[]{int.class, int.class}),
                FastClass.create(Calculator.class)
                        .getIndex("add", new Class<?>[]{int.class, int.class}));
    }

    @Test
    @DisplayName("FastMethod carries its index so the lookup happens once")
    void fastMethodInvokes() throws Exception {
        FastMethod describe = FAST.getMethod("describe", new Class<?>[]{String.class});

        assertEquals("n=3", describe.invoke(new Calculator(3), new Object[]{"n="}));
        assertEquals(String.class, describe.getReturnType());
        assertEquals("describe", describe.getName());
        assertTrue(describe.getIndex() >= 0);
    }

    @Test
    @DisplayName("FastConstructor carries its index too")
    void fastConstructorInvokes() throws Exception {
        FastConstructor constructor = FAST.getConstructor(new Class<?>[]{int.class});

        assertEquals(11, ((Calculator) constructor.newInstance(new Object[]{11})).value);
        assertArrayEquals(new Class<?>[]{int.class}, constructor.getParameterTypes());
    }

    @Test
    @DisplayName("reports an unknown method rather than returning a broken handle")
    void reportsUnknownMethod() {
        ClasswrightException failure = assertThrows(ClasswrightException.class,
                () -> FAST.getMethod("nonexistent", new Class<?>[0]));

        assertTrue(failure.getMessage().contains("nonexistent"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Private methods"),
                "the message should explain what is unreachable: " + failure.getMessage());
    }

    @Test
    @DisplayName("the name-based convenience overload works, at the cost of a lookup per call")
    void convenienceInvokeWorks() throws Exception {
        assertEquals(9, FAST.invoke("add", new Class<?>[]{int.class, int.class},
                new Calculator(), new Object[]{4, 5}));
    }

    // ==========================================================================================
    // Lifetime
    // ==========================================================================================

    @Test
    @DisplayName("one accessor per class, cached")
    void cachesPerClass() {
        assertSame(FastClass.create(Calculator.class), FastClass.create(Calculator.class));
        assertEquals(Calculator.class, FAST.getJavaClass());
    }

    @Test
    @DisplayName("the generated accessor is a hidden class, so it can be unloaded")
    void generatesHiddenClasses() {
        assertTrue(FAST.getClass().isHidden(),
                "accessors should participate in unloading like everything else");
        assertEquals(FastClass.class, FAST.getClass().getSuperclass());
    }

    @Test
    @DisplayName("rejects targets with nothing to call")
    void rejectsImpossibleTargets() {
        assertThrows(ClasswrightException.class, () -> FastClass.create(int.class));
        assertThrows(ClasswrightException.class, () -> FastClass.create(String[].class));
        assertThrows(ClasswrightException.class, () -> FastClass.create(null));
    }

    @Test
    @DisplayName("works for a class with no accessible constructor")
    void handlesUnconstructableTargets() throws Exception {
        // java.lang.Math has only a private constructor. It also exercises the relocation rule:
        // java.* packages are closed, and a child loader may not define into them at all, so the
        // accessor is placed in Classwright's own generated-code package instead.
        FastClass fast = FastClass.create(Math.class);

        assertThrows(ClasswrightException.class, fast::newInstance);
        assertTrue(fast.getMaxIndex() > 0, "its static methods should still be callable");
        assertEquals(4.0, fast.invoke("sqrt", new Class<?>[]{double.class}, null,
                new Object[]{16.0}));
    }
}
