package com.classwright.core;

import com.classwright.testkit.MethodShape;
import com.classwright.testkit.SignatureMatrix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests method descriptors and, more importantly, parameter slot arithmetic. */
class CwMethodTypeTest {

    static List<MethodShape> everyShape() {
        return SignatureMatrix.all();
    }

    private static CwMethodType toMethodType(MethodShape shape) {
        return CwMethodType.of(CwType.of(shape.returnType()),
                shape.parameterTypes().stream().map(CwType::of).toList());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyShape")
    @DisplayName("descriptor matches the test kit's independent renderer")
    void descriptorMatchesOracle(MethodShape shape) {
        assertEquals(shape.descriptor(), toMethodType(shape).descriptor());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyShape")
    @DisplayName("descriptors round-trip through parsing")
    void descriptorsRoundTrip(MethodShape shape) {
        CwMethodType original = toMethodType(shape);

        assertEquals(original, CwMethodType.fromDescriptor(original.descriptor()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyShape")
    @DisplayName("parameter slots match the test kit's independent calculation")
    void parameterSlotsMatchOracle(MethodShape shape) {
        assertEquals(shape.parameterSlots(), toMethodType(shape).parameterSlots());
    }

    @Test
    @DisplayName("a two-slot parameter shifts every slot after it")
    void widePrimitivesShiftLaterParameters() {
        // The single most productive source of bugs in a hand-written emitter. For an instance
        // method m(long a, int b, double c, Object d) the slots are 1, 3, 4, 6 -- not 1, 2, 3, 4.
        CwMethodType type = CwMethodType.of(CwType.VOID,
                CwType.LONG, CwType.INT, CwType.DOUBLE, CwType.OBJECT);

        assertEquals(1, type.parameterSlot(0, true));
        assertEquals(3, type.parameterSlot(1, true));
        assertEquals(4, type.parameterSlot(2, true));
        assertEquals(6, type.parameterSlot(3, true));
        assertEquals(7, type.firstFreeSlot(true));
    }

    @Test
    @DisplayName("a static method starts at slot 0, an instance method at slot 1")
    void staticMethodsHaveNoThis() {
        CwMethodType type = CwMethodType.of(CwType.VOID, CwType.INT, CwType.LONG);

        assertEquals(0, type.parameterSlot(0, false));
        assertEquals(1, type.parameterSlot(1, false));
        assertEquals(3, type.firstFreeSlot(false));

        assertEquals(1, type.parameterSlot(0, true));
        assertEquals(2, type.parameterSlot(1, true));
        assertEquals(4, type.firstFreeSlot(true));
    }

    @Test
    @DisplayName("enforces the 255-slot descriptor limit, counting this")
    void enforcesArityLimit() {
        CwMethodType at254 = CwMethodType.of(CwType.VOID,
                Collections.nCopies(254, CwType.INT));
        CwMethodType at255 = CwMethodType.of(CwType.VOID,
                Collections.nCopies(255, CwType.INT));

        at254.validateArity(true);      // 254 parameters + this = 255, exactly at the limit
        at255.validateArity(false);     // 255 parameters, no this, also exactly at the limit

        CodeGenerationException failure =
                assertThrows(CodeGenerationException.class, () -> at255.validateArity(true));
        assertTrue(failure.getMessage().contains("256"), failure.getMessage());
    }

    @Test
    @DisplayName("counts a long parameter as two slots against the limit")
    void widePrimitivesCountDoubleAgainstTheLimit() {
        CwMethodType type = CwMethodType.of(CwType.VOID, Collections.nCopies(128, CwType.LONG));

        assertEquals(256, type.parameterSlots());
        assertThrows(CodeGenerationException.class, () -> type.validateArity(false));
    }

    @Test
    @DisplayName("builds from reflected methods and constructors")
    void buildsFromReflection() throws Exception {
        CwMethodType fromMethod = CwMethodType.of(String.class.getMethod("substring", int.class));
        assertEquals("(I)Ljava/lang/String;", fromMethod.descriptor());

        CwMethodType fromConstructor =
                CwMethodType.of(String.class.getConstructor(char[].class));
        assertEquals("([C)V", fromConstructor.descriptor(),
                "a constructor's descriptor returns void");
    }

    @Test
    @DisplayName("rejects void parameters and malformed descriptors")
    void rejectsNonsense() {
        assertThrows(CodeGenerationException.class,
                () -> CwMethodType.of(CwType.INT, CwType.VOID));
        assertThrows(CodeGenerationException.class,
                () -> CwMethodType.fromDescriptor("I)V"));
        assertThrows(CodeGenerationException.class,
                () -> CwMethodType.fromDescriptor("(I"));
        assertThrows(CodeGenerationException.class,
                () -> CwMethodType.fromDescriptor("(Q)V"));
    }

    @Test
    @DisplayName("reports a helpful error for an out-of-range parameter index")
    void rejectsBadParameterIndex() {
        CwMethodType type = CwMethodType.of(CwType.VOID, CwType.INT);

        assertThrows(CodeGenerationException.class, () -> type.parameterSlot(1, false));
        assertThrows(CodeGenerationException.class, () -> type.parameterSlot(-1, false));
    }
}
