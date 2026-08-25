package com.classwright.testkit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.invoke.MethodType;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the descriptor renderer that the rest of the test suite trusts.
 *
 * <p>Checked against {@link MethodType#toMethodDescriptorString()} rather than against hand-written
 * expectations. That is a genuinely independent implementation, written by the JDK team, and it
 * makes this test far stronger than a list of strings someone typed out while holding the same
 * misconception the code might contain.
 */
class MethodShapeTest {

    static List<MethodShape> everyShape() {
        return SignatureMatrix.all();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyShape")
    @DisplayName("descriptor matches the JDK's own renderer")
    void descriptorMatchesJdk(MethodShape shape) {
        MethodType oracle = MethodType.methodType(shape.returnType(), shape.parameterTypes());

        assertEquals(oracle.toMethodDescriptorString(), shape.descriptor());
    }

    @Test
    @DisplayName("renders every primitive descriptor correctly")
    void primitiveDescriptors() {
        assertEquals("Z", MethodShape.descriptorOf(boolean.class));
        assertEquals("B", MethodShape.descriptorOf(byte.class));
        assertEquals("C", MethodShape.descriptorOf(char.class));
        assertEquals("S", MethodShape.descriptorOf(short.class));
        assertEquals("I", MethodShape.descriptorOf(int.class));
        assertEquals("J", MethodShape.descriptorOf(long.class));   // J, not L: the classic slip
        assertEquals("F", MethodShape.descriptorOf(float.class));
        assertEquals("D", MethodShape.descriptorOf(double.class));
        assertEquals("V", MethodShape.descriptorOf(void.class));
    }

    @Test
    @DisplayName("nests array descriptors")
    void arrayDescriptors() {
        assertEquals("[I", MethodShape.descriptorOf(int[].class));
        assertEquals("[[Ljava/lang/String;", MethodShape.descriptorOf(String[][].class));
        assertEquals("[[[J", MethodShape.descriptorOf(long[][][].class));
    }

    @Test
    @DisplayName("counts long and double as two slots")
    void slotCounting() {
        assertEquals(1, MethodShape.slotsOf(int.class));
        assertEquals(1, MethodShape.slotsOf(Object.class));
        assertEquals(2, MethodShape.slotsOf(long.class));
        assertEquals(2, MethodShape.slotsOf(double.class));
        assertEquals(1, MethodShape.slotsOf(long[].class), "an array reference is one slot");

        assertEquals(4, MethodShape.shape(void.class, long.class, int.class, float.class)
                .parameterSlots());
    }

    @Test
    @DisplayName("knows which shapes are too wide to be an instance method")
    void instanceMethodArityLimit() {
        // 255 argument slots is the descriptor limit; `this` consumes one of them (JVMS 4.3.3),
        // so an instance method gets at most 254.
        assertTrue(new MethodShape(void.class, java.util.Collections.nCopies(254, int.class))
                .isLegalForInstanceMethod());
        assertFalse(new MethodShape(void.class, java.util.Collections.nCopies(255, int.class))
                .isLegalForInstanceMethod());
        assertTrue(new MethodShape(void.class, java.util.Collections.nCopies(127, long.class))
                .isLegalForInstanceMethod(), "127 longs is 254 slots");
    }

    @Test
    @DisplayName("rejects void as a parameter type")
    void voidIsNotAParameterType() {
        assertThrows(IllegalArgumentException.class,
                () -> MethodShape.shape(int.class, void.class));
    }
}
