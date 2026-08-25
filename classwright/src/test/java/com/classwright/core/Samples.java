package com.classwright.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A representative value for each type in the signature matrix.
 *
 * <p>Values are chosen to catch the mistakes that a bland choice would miss. Negative bytes and
 * shorts expose sign-extension errors. A {@code char} above 127 exposes the classic bug of treating
 * it as signed. Values outside the small-integer opcode range force the constant-pool path rather
 * than the compact {@code iconst} shortcuts. Reference values are singletons so that identity, not
 * merely equality, can be asserted after a round trip.
 */
final class Samples {

    /** Fixed identity, so tests can assert the very same reference came back. */
    static final Object OBJECT_SAMPLE = new Object();

    private static final Map<Class<?>, Object> VALUES = new LinkedHashMap<>();

    static {
        VALUES.put(boolean.class, true);
        VALUES.put(byte.class, (byte) -7);              // negative: sign extension
        VALUES.put(char.class, 'Ω');                    // above 127: char is unsigned
        VALUES.put(short.class, (short) -30_000);       // negative and beyond byte range
        VALUES.put(int.class, 1_234_567);               // beyond sipush, so ldc is used
        VALUES.put(long.class, 1_234_567_890_123L);     // beyond int range entirely
        VALUES.put(float.class, 3.5f);
        VALUES.put(double.class, -2.75);
        VALUES.put(Object.class, OBJECT_SAMPLE);
        VALUES.put(String.class, "sample");
        VALUES.put(int[].class, new int[]{1, 2, 3});
        VALUES.put(long[].class, new long[]{1L, 2L});
        VALUES.put(Object[].class, new Object[]{OBJECT_SAMPLE});
        VALUES.put(String[][].class, new String[][]{{"a"}, {"b"}});
    }

    private Samples() {
    }

    /**
     * The sample value for a type.
     *
     * @param type any type in {@link com.classwright.testkit.SignatureMatrix#VALUE_TYPES}
     * @return a value assignable to it
     */
    static Object of(Class<?> type) {
        Object value = VALUES.get(type);
        if (value == null) {
            throw new IllegalArgumentException("no sample value registered for " + type
                    + "; add one so the matrix stays complete");
        }
        return value;
    }

    /** The zero value a freshly-defaulted variable of this type holds. */
    static Object defaultOf(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        throw new IllegalArgumentException("no default for " + type);
    }
}
