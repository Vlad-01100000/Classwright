package com.classwright.testkit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the coverage properties that later phases will rely on.
 *
 * <p>If the matrix silently stopped including {@code long}, every Phase 1 test would still pass
 * while the two-slot handling went untested. These assertions exist so that a shrinking matrix is
 * itself a test failure.
 */
class SignatureMatrixTest {

    @Test
    @DisplayName("covers all eight primitives plus void")
    void coversEveryPrimitive() {
        Set<Class<?>> returns = new HashSet<>(SignatureMatrix.RETURN_TYPES);

        assertTrue(returns.containsAll(List.of(boolean.class, byte.class, char.class, short.class,
                int.class, long.class, float.class, double.class, void.class)));
    }

    @Test
    @DisplayName("void is a return type but never a parameter type")
    void voidIsReturnOnly() {
        assertTrue(SignatureMatrix.RETURN_TYPES.contains(void.class));
        assertFalse(SignatureMatrix.VALUE_TYPES.contains(void.class));
        assertTrue(SignatureMatrix.all().stream()
                .noneMatch(s -> s.parameterTypes().contains(void.class)));
    }

    @Test
    @DisplayName("covers reference, array, and multi-dimensional array types")
    void coversReferenceShapes() {
        assertTrue(SignatureMatrix.VALUE_TYPES.contains(Object.class));
        assertTrue(SignatureMatrix.VALUE_TYPES.contains(int[].class));
        assertTrue(SignatureMatrix.VALUE_TYPES.stream().anyMatch(t ->
                t.isArray() && t.getComponentType().isArray()), "need a multi-dimensional array");
    }

    @Test
    @DisplayName("pairs form a full cross product, which is where slot bugs live")
    void pairsAreExhaustive() {
        int types = SignatureMatrix.VALUE_TYPES.size();

        assertEquals(types * types, SignatureMatrix.parameterPairs().size());
        assertTrue(SignatureMatrix.parameterPairs().contains(
                MethodShape.shape(long.class, long.class, int.class)),
                "the (long, int) case must be present: its second argument lives in slot 3, not 2");
    }

    @Test
    @DisplayName("all() de-duplicates without dropping anything")
    void allIsDeduplicatedAndComplete() {
        List<MethodShape> all = SignatureMatrix.all();

        assertEquals(new HashSet<>(all).size(), all.size(), "all() must not contain duplicates");
        assertTrue(all.containsAll(SignatureMatrix.eachReturnType()));
        assertTrue(all.containsAll(SignatureMatrix.eachSingleParameter()));
        assertTrue(all.containsAll(SignatureMatrix.parameterPairs()));
        assertTrue(all.containsAll(SignatureMatrix.slotStress()));
        assertTrue(all.containsAll(SignatureMatrix.maxArity()));
    }

    @Test
    @DisplayName("instance-method matrix excludes shapes that cannot be instance methods")
    void instanceMatrixIsLegal() {
        assertTrue(SignatureMatrix.allForInstanceMethods().stream()
                .allMatch(MethodShape::isLegalForInstanceMethod));
        assertTrue(SignatureMatrix.allForInstanceMethods().size() < SignatureMatrix.all().size(),
                "the 255-slot static shape should have been filtered out");
    }

    @Test
    @DisplayName("results are deterministic, so a failure is reproducible by index")
    void ordersStably() {
        assertEquals(SignatureMatrix.all(), SignatureMatrix.all());
    }
}
