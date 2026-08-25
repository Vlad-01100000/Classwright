package com.classwright.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests constant pool index allocation.
 *
 * <p>Indices are the one thing in a class file that nothing else can compensate for: every
 * reference is by index, so an allocation bug corrupts the whole file in a way that produces
 * spectacularly unhelpful load errors. Hence the attention to the two-slot rule.
 */
class ConstantPoolTest {

    @Test
    @DisplayName("indices start at 1, because 0 means 'no entry'")
    void firstIndexIsOne() {
        ConstantPool pool = new ConstantPool();

        assertEquals(1, pool.utf8("first"));
        assertEquals(2, pool.count(), "count is one past the highest index in use");
    }

    @Test
    @DisplayName("long and double consume two indices each")
    void widePrimitivesConsumeTwoIndices() {
        // JVMS 4.4.5 calls this "a poor choice", and it is, but every index allocated afterwards
        // depends on honouring it.
        ConstantPool pool = new ConstantPool();

        int first = pool.longConstant(1L);
        int second = pool.utf8("after");

        assertEquals(first + 2, second, "the index after a long must skip its unusable second slot");
    }

    @Test
    @DisplayName("double also consumes two indices")
    void doubleConsumesTwoIndices() {
        ConstantPool pool = new ConstantPool();

        int first = pool.doubleConstant(1.5);
        int second = pool.integer(7);

        assertEquals(first + 2, second);
    }

    @Test
    @DisplayName("identical entries are shared")
    void deduplicates() {
        ConstantPool pool = new ConstantPool();

        assertEquals(pool.utf8("shared"), pool.utf8("shared"));
        assertEquals(pool.classEntry("java/lang/String"), pool.classEntry("java/lang/String"));
        assertEquals(pool.integer(42), pool.integer(42));
        assertEquals(pool.longConstant(42L), pool.longConstant(42L));
        assertEquals(pool.methodRef("A", "m", "()V"), pool.methodRef("A", "m", "()V"));
    }

    @Test
    @DisplayName("entries of different tags with the same text stay distinct")
    void differentTagsAreNotConfused() {
        ConstantPool pool = new ConstantPool();

        int asUtf8 = pool.utf8("java/lang/String");
        int asClass = pool.classEntry("java/lang/String");
        int asString = pool.stringConstant("java/lang/String");

        assertNotEquals(asUtf8, asClass);
        assertNotEquals(asClass, asString);
        assertNotEquals(asUtf8, asString);
    }

    @Test
    @DisplayName("a class reference and an interface method reference are different entries")
    void classAndInterfaceMethodRefsAreDistinct() {
        // Using the wrong one produces an IncompatibleClassChangeError at link time, which does
        // not obviously point at the constant pool.
        ConstantPool pool = new ConstantPool();

        assertNotEquals(pool.methodRef("A", "m", "()V"),
                pool.interfaceMethodRef("A", "m", "()V"));
    }

    @Test
    @DisplayName("float and double constants are keyed by bits, so signed zero survives")
    void signedZeroIsPreserved() {
        ConstantPool pool = new ConstantPool();

        assertNotEquals(pool.floatConstant(0.0f), pool.floatConstant(-0.0f));
        assertNotEquals(pool.doubleConstant(0.0), pool.doubleConstant(-0.0));
    }

    @Test
    @DisplayName("NaN constants are shared rather than accumulating")
    void nanIsStable() {
        // Keying by value would fail here: NaN != NaN, so every request would allocate a new entry.
        ConstantPool pool = new ConstantPool();

        assertEquals(pool.doubleConstant(Double.NaN), pool.doubleConstant(Double.NaN));
        assertEquals(pool.floatConstant(Float.NaN), pool.floatConstant(Float.NaN));
    }

    @Test
    @DisplayName("nested entries are written before the entry that refers to them")
    void nestedEntriesComeFirst() {
        ConstantPool pool = new ConstantPool();

        int methodRef = pool.methodRef("java/lang/Object", "toString", "()Ljava/lang/String;");
        int owner = pool.classEntry("java/lang/Object");

        assertTrue(owner < methodRef,
                "the class entry a method reference points at must have a lower index");
    }

    @Test
    @DisplayName("reports pool overflow rather than emitting a corrupt index")
    void reportsOverflow() {
        ConstantPool pool = new ConstantPool();

        CodeGenerationException failure = assertThrows(CodeGenerationException.class, () -> {
            for (int i = 0; i < 70_000; i++) {
                pool.utf8("entry" + i);
            }
        });

        assertTrue(failure.getMessage().contains("overflow"), failure.getMessage());
    }
}
