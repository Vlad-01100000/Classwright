package com.classwright.core;

import com.classwright.testkit.MethodShape;
import com.classwright.testkit.SignatureMatrix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the type model against two independent oracles: the test kit's own descriptor renderer,
 * and reflection itself.
 */
class CwTypeTest {

    static List<Class<?>> everyType() {
        return SignatureMatrix.RETURN_TYPES;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyType")
    @DisplayName("descriptor matches the test kit's independent renderer")
    void descriptorMatchesOracle(Class<?> type) {
        assertEquals(MethodShape.descriptorOf(type), CwType.of(type).descriptor());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyType")
    @DisplayName("descriptors round-trip through parsing")
    void descriptorsRoundTrip(Class<?> type) {
        CwType original = CwType.of(type);

        assertEquals(original, CwType.fromDescriptor(original.descriptor()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyType")
    @DisplayName("className matches what reflection reports")
    void classNameMatchesReflection(Class<?> type) {
        // getCanonicalName renders arrays as "int[]", which is what className() produces.
        assertEquals(type.getCanonicalName(), CwType.of(type).className());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyType")
    @DisplayName("slot count matches the test kit's independent calculation")
    void slotsMatchOracle(Class<?> type) {
        int expected = type == void.class ? 0 : MethodShape.slotsOf(type);

        assertEquals(expected, CwType.of(type).slots());
    }

    @Test
    @DisplayName("long is J, not L")
    void longIsJ() {
        // The single most common descriptor mistake, and one that produces a confusing failure:
        // "L" starts a class name, so "(L)V" is read as an unterminated class reference.
        assertEquals("J", CwType.LONG.descriptor());
        assertEquals("D", CwType.DOUBLE.descriptor());
    }

    @Test
    @DisplayName("internal names strip the wrapper for classes but not for arrays")
    void internalNamesDifferForArrays() {
        assertEquals("java/lang/String", CwType.STRING.internalName());
        // Deliberately asymmetric: CONSTANT_Class for an array type holds the descriptor itself.
        assertEquals("[Ljava/lang/String;", CwType.arrayOf(CwType.STRING).internalName());
        assertEquals("[I", CwType.arrayOf(CwType.INT).internalName());
    }

    @Test
    @DisplayName("primitives have no internal name")
    void primitivesHaveNoInternalName() {
        assertThrows(CodeGenerationException.class, CwType.INT::internalName);
    }

    @Test
    @DisplayName("arrays are one slot however deep")
    void arraysAreOneSlot() {
        assertEquals(1, CwType.arrayOf(CwType.LONG).slots());
        assertEquals(1, CwType.arrayOf(CwType.arrayOf(CwType.DOUBLE)).slots());
    }

    @Test
    @DisplayName("knows which primitives live as int on the stack")
    void identifiesIntLikeTypes() {
        assertTrue(CwType.BOOLEAN.isIntLike());
        assertTrue(CwType.BYTE.isIntLike());
        assertTrue(CwType.CHAR.isIntLike());
        assertTrue(CwType.SHORT.isIntLike());
        assertTrue(CwType.INT.isIntLike());
        assertFalse(CwType.LONG.isIntLike());
        assertFalse(CwType.FLOAT.isIntLike());
        assertFalse(CwType.OBJECT.isIntLike());
    }

    @Test
    @DisplayName("boxes each primitive to its wrapper")
    void boxesPrimitives() {
        assertEquals("java/lang/Integer", CwType.INT.boxed().internalName());
        assertEquals("java/lang/Character", CwType.CHAR.boxed().internalName());
        assertEquals("java/lang/Boolean", CwType.BOOLEAN.boxed().internalName());
        assertEquals("java/lang/Long", CwType.LONG.boxed().internalName());
        assertThrows(CodeGenerationException.class, CwType.STRING::boxed);
    }

    @Test
    @DisplayName("int-like types share the i-family load, store and return opcodes")
    void intLikeTypesShareOpcodes() {
        for (CwType type : List.of(CwType.BOOLEAN, CwType.BYTE, CwType.CHAR,
                CwType.SHORT, CwType.INT)) {
            assertEquals(Opcodes.ILOAD, type.loadOpcode(), type + " load");
            assertEquals(Opcodes.ISTORE, type.storeOpcode(), type + " store");
            assertEquals(Opcodes.IRETURN, type.returnOpcode(), type + " return");
        }
    }

    @Test
    @DisplayName("each type family gets its own load, store and return opcodes")
    void opcodeFamiliesAreDistinct() {
        assertEquals(Opcodes.LLOAD, CwType.LONG.loadOpcode());
        assertEquals(Opcodes.FLOAD, CwType.FLOAT.loadOpcode());
        assertEquals(Opcodes.DLOAD, CwType.DOUBLE.loadOpcode());
        assertEquals(Opcodes.ALOAD, CwType.STRING.loadOpcode());
        assertEquals(Opcodes.ALOAD, CwType.arrayOf(CwType.INT).loadOpcode());

        assertEquals(Opcodes.LRETURN, CwType.LONG.returnOpcode());
        assertEquals(Opcodes.ARETURN, CwType.OBJECT.returnOpcode());
        assertEquals(Opcodes.RETURN, CwType.VOID.returnOpcode());
    }

    @Test
    @DisplayName("array access opcodes distinguish the small integral types")
    void arrayOpcodesAreTypeSpecific() {
        // Unlike local variable access, arrays do not collapse these onto iaload/iastore.
        assertEquals(Opcodes.BALOAD, CwType.BOOLEAN.arrayLoadOpcode());
        assertEquals(Opcodes.BALOAD, CwType.BYTE.arrayLoadOpcode());
        assertEquals(Opcodes.CALOAD, CwType.CHAR.arrayLoadOpcode());
        assertEquals(Opcodes.SALOAD, CwType.SHORT.arrayLoadOpcode());
        assertEquals(Opcodes.IALOAD, CwType.INT.arrayLoadOpcode());
        assertEquals(Opcodes.AALOAD, CwType.STRING.arrayLoadOpcode());

        assertEquals(Opcodes.BASTORE, CwType.BOOLEAN.arrayStoreOpcode());
        assertEquals(Opcodes.CASTORE, CwType.CHAR.arrayStoreOpcode());
        assertEquals(Opcodes.AASTORE, CwType.OBJECT.arrayStoreOpcode());
    }

    @Test
    @DisplayName("void cannot be loaded, stored, or made into an array")
    void voidIsRejectedWhereItMakesNoSense() {
        assertThrows(CodeGenerationException.class, CwType.VOID::loadOpcode);
        assertThrows(CodeGenerationException.class, CwType.VOID::storeOpcode);
        assertThrows(CodeGenerationException.class, () -> CwType.arrayOf(CwType.VOID));
        assertEquals(Opcodes.RETURN, CwType.VOID.returnOpcode(), "but returning void is fine");
    }

    @Test
    @DisplayName("component types and dimensions")
    void navigatesArrays() {
        CwType twoDimensional = CwType.arrayOf(CwType.arrayOf(CwType.STRING));

        assertEquals(2, twoDimensional.dimensions());
        assertEquals(CwType.arrayOf(CwType.STRING), twoDimensional.componentType());
        assertEquals(CwType.STRING, twoDimensional.componentType().componentType());
        assertEquals(0, CwType.INT.dimensions());
        assertThrows(CodeGenerationException.class, CwType.INT::componentType);
    }

    @Test
    @DisplayName("rejects a binary name where an internal name belongs")
    void rejectsBinaryNames() {
        CodeGenerationException failure = assertThrows(CodeGenerationException.class,
                () -> CwType.objectType("java.lang.String"));

        assertTrue(failure.getMessage().contains("java/lang/String"),
                "the message should show the correct form: " + failure.getMessage());
    }

    @Test
    @DisplayName("rejects malformed descriptors with a position")
    void rejectsMalformedDescriptors() {
        assertThrows(CodeGenerationException.class, () -> CwType.fromDescriptor("Q"));
        assertThrows(CodeGenerationException.class, () -> CwType.fromDescriptor("Ljava/lang/String"));
        assertThrows(CodeGenerationException.class, () -> CwType.fromDescriptor(""));
        assertThrows(CodeGenerationException.class, () -> CwType.fromDescriptor("II"));
    }

    @Test
    @DisplayName("equal descriptors mean equal types")
    void equalityIsByDescriptor() {
        assertEquals(CwType.of(String.class), CwType.STRING);
        assertEquals(CwType.of(String.class).hashCode(), CwType.STRING.hashCode());
        assertEquals(CwType.of(int[].class), CwType.arrayOf(CwType.INT));
        assertSame(CwType.INT, CwType.of(int.class));
    }
}
