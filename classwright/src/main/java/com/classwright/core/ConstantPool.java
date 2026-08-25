package com.classwright.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds a class file's constant pool.
 *
 * <p>The constant pool is the table every other part of a class file points into: class names,
 * method names, descriptors, string literals, numeric constants, and the composite entries that
 * describe a field or method reference. Entries are added on demand as the rest of the class is
 * emitted, and this class hands back the index to reference them by.
 *
 * <h2>Three things the format gets strange about</h2>
 *
 * <ol>
 *   <li><strong>Indices start at 1</strong>, not 0. Index 0 is reserved to mean "no entry" in the
 *       handful of places that allow it.</li>
 *   <li><strong>{@code long} and {@code double} occupy two consecutive indices.</strong> The second
 *       is unusable and simply skipped. JVMS calls this "a poor choice"; it is preserved for
 *       compatibility, and forgetting it corrupts every index allocated afterwards.</li>
 *   <li><strong>{@code constant_pool_count} is one more than the number of entries</strong>, being
 *       the next free index rather than a count.</li>
 * </ol>
 *
 * <p>Entries are de-duplicated. That is not primarily an optimisation: the same descriptor and the
 * same method name recur constantly across a generated class, and a pool without de-duplication
 * grows large enough to matter for both metaspace and class-definition time, which is the cost this
 * library is trying to minimise.
 *
 * <h2>How de-duplication is keyed</h2>
 *
 * <p>UTF-8 entries are keyed by the string itself. Every other entry is keyed by its <em>resolved
 * child indices</em>, packed with its tag into one {@code long}. Two properties fall out, and both
 * are load-bearing:
 *
 * <ul>
 *   <li><strong>No allocation on a hit.</strong> This is the hottest path in the engine — every
 *       emitted field access, method call, and constant load resolves through here. An earlier
 *       design keyed on concatenated strings ({@code "M" + owner + "." + name + descriptor}),
 *       which built and hashed a fresh key string per reference; the packed form probes a
 *       primitive-keyed table instead.</li>
 *   <li><strong>Keys are exact.</strong> Concatenated keys were ambiguous: JVMS 4.2.2 forbids so
 *       few characters in member names that {@code ("a", "LxLa/b;")} and {@code ("aLx", "La/b;")}
 *       could produce one key and silently alias two different members to one entry. Child indices
 *       cannot collide that way — each was interned exactly.</li>
 * </ul>
 */
public final class ConstantPool {

    /** Creates an empty pool; entries are interned as the writer references them. */
    public ConstantPool() {
    }

    // JVMS 4.4, Table 4.4-A.
    private static final int TAG_UTF8 = 1;
    private static final int TAG_INTEGER = 3;
    private static final int TAG_FLOAT = 4;
    private static final int TAG_LONG = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_CLASS = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_FIELDREF = 9;
    private static final int TAG_METHODREF = 10;
    private static final int TAG_INTERFACE_METHODREF = 11;
    private static final int TAG_NAME_AND_TYPE = 12;
    private static final int TAG_METHOD_HANDLE = 15;
    private static final int TAG_METHOD_TYPE = 16;

    /** Reference kinds for {@code CONSTANT_MethodHandle} (JVMS 4.4.8). */
    public static final int REF_GET_FIELD = 1;
    /**
     * Reference kind: read a static field.
     */
    public static final int REF_GET_STATIC = 2;
    /**
     * Reference kind: write an instance field.
     */
    public static final int REF_PUT_FIELD = 3;
    /**
     * Reference kind: write a static field.
     */
    public static final int REF_PUT_STATIC = 4;
    /**
     * Reference kind: invoke an instance method virtually.
     */
    public static final int REF_INVOKE_VIRTUAL = 5;
    /**
     * Reference kind: invoke a static method.
     */
    public static final int REF_INVOKE_STATIC = 6;
    /**
     * Reference kind: invoke a method without virtual dispatch.
     */
    public static final int REF_INVOKE_SPECIAL = 7;
    /**
     * Reference kind: allocate and invoke a constructor.
     */
    public static final int REF_NEW_INVOKE_SPECIAL = 8;
    /**
     * Reference kind: invoke an interface method.
     */
    public static final int REF_INVOKE_INTERFACE = 9;

    /**
     * The highest index an entry may occupy. {@code constant_pool_count} is a u2 holding
     * {@code nextIndex}, and valid indices run 1 to count-1, so the last usable index is 65534.
     * Allowing 65535 would make the count wrap to zero when written &mdash; a silently corrupt
     * class file instead of the exception this guard exists to throw.
     */
    private static final int MAX_INDEX = 0xFFFE;

    private final ByteWriter body = new ByteWriter(512);

    /**
     * UTF-8 entries, keyed by the string itself.
     *
     * <p>Pre-sized for a typical generated class (a few hundred entries), because rehashing a map
     * of strings re-hashes every key.
     */
    private final Map<String, Integer> utf8s = new HashMap<>(256);

    /**
     * Everything except UTF-8, {@code long}, and {@code double}, keyed by tag and child indices
     * packed into a {@code long}: tag in bits 32-39, and either a 32-bit payload (int and float
     * raw bits) or two u2 child indices (composites) below. Tags are distinct, so the namespaces
     * cannot collide.
     */
    private final LongIntMap composites = new LongIntMap(256);

    /** {@code long} entries by value, and {@code double} entries by raw bits. Rare; boxed is fine. */
    private final Map<Long, Integer> longs = new HashMap<>(4);
    private final Map<Long, Integer> doubles = new HashMap<>(4);

    /** The next index to hand out. Starts at 1 because index 0 is reserved. */
    private int nextIndex = 1;

    /**
     * Adds a modified-UTF-8 string entry.
     *
     * @param value the string
     * @return its constant-pool index
     */
    public int utf8(String value) {
        Integer existing = utf8s.get(value);
        if (existing != null) {
            return existing;
        }
        // Atomic against the oversized-string failure: the tag byte and the allocated index
        // must not survive the throw, or a caller that catches and carries on serialises a
        // pool whose count and bytes disagree — the exact invariant
        // ByteWriterTest.failedWriteDoesNotCorruptBuffer pins one level down.
        int mark = body.length();
        int index;
        try {
            body.u1(TAG_UTF8);
            body.modifiedUtf8(value);
            index = allocate(1);
        } catch (CodeGenerationException failure) {
            body.truncate(mark);
            throw failure;
        }
        utf8s.put(value, index);
        return index;
    }

    /**
     * Adds an {@code int} constant.
     *
     * @param value the constant
     * @return the entry's index in the constant pool
     */
    public int integer(int value) {
        long key = pack(TAG_INTEGER, value);
        int existing = composites.get(key);
        if (existing >= 0) {
            return existing;
        }
        int index = allocate(1);
        body.u1(TAG_INTEGER).u4(value);
        composites.put(key, index);
        return index;
    }

    /**
     * Adds a {@code float} constant.
     *
     * <p>Keyed by raw bits rather than by value so that {@code 0.0f} and {@code -0.0f} stay
     * distinct (they are {@code ==} but not bit-identical) and so that {@code NaN} is stable
     * (it is not {@code ==} to itself).
     *
     * @param value the constant
     * @return the entry's index in the constant pool
     */
    public int floatConstant(float value) {
        int bits = Float.floatToRawIntBits(value);
        long key = pack(TAG_FLOAT, bits);
        int existing = composites.get(key);
        if (existing >= 0) {
            return existing;
        }
        int index = allocate(1);
        body.u1(TAG_FLOAT).u4(bits);
        composites.put(key, index);
        return index;
    }

    /**
     * Adds a {@code long} constant. Consumes two pool indices.
     *
     * @param value the constant
     * @return the entry's index in the constant pool (which occupies two slots)
     */
    public int longConstant(long value) {
        Integer existing = longs.get(value);
        if (existing != null) {
            return existing;
        }
        int index = allocate(2);
        body.u1(TAG_LONG).u8(value);
        longs.put(value, index);
        return index;
    }

    /**
     * Adds a {@code double} constant. Consumes two pool indices. See {@link #floatConstant}.
     *
     * @param value the constant
     * @return the entry's index in the constant pool (which occupies two slots)
     */
    public int doubleConstant(double value) {
        long bits = Double.doubleToRawLongBits(value);
        Integer existing = doubles.get(bits);
        if (existing != null) {
            return existing;
        }
        int index = allocate(2);
        body.u1(TAG_DOUBLE).u8(bits);
        doubles.put(bits, index);
        return index;
    }

    /**
     * Adds a class reference.
     *
     * @param internalName e.g. {@code java/lang/String}, or {@code [I} for an array type
     * @return its constant-pool index
     */
    public int classEntry(String internalName) {
        int nameIndex = utf8(internalName);
        long key = pack(TAG_CLASS, nameIndex, 0);
        int existing = composites.get(key);
        if (existing >= 0) {
            return existing;
        }
        int index = allocate(1);
        body.u1(TAG_CLASS).u2(nameIndex);
        composites.put(key, index);
        return index;
    }

    /**
     * Adds a class reference for a type.
     *
     * @param type the class or array type
     * @return the entry's index in the constant pool
     */
    public int classEntry(CwType type) {
        return classEntry(type.internalName());
    }

    /**
     * Adds a {@code String} literal.
     *
     * @param value the string
     * @return the entry's index in the constant pool
     */
    public int stringConstant(String value) {
        int text = utf8(value);
        long key = pack(TAG_STRING, text, 0);
        int existing = composites.get(key);
        if (existing >= 0) {
            return existing;
        }
        int index = allocate(1);
        body.u1(TAG_STRING).u2(text);
        composites.put(key, index);
        return index;
    }

    /**
     * Adds a name-and-descriptor pair, the shared tail of every field and method reference.
     *
     * @param name       the member name
     * @param descriptor its descriptor
     * @return the entry's index in the constant pool
     */
    public int nameAndType(String name, String descriptor) {
        int nameIndex = utf8(name);
        int descriptorIndex = utf8(descriptor);
        long key = pack(TAG_NAME_AND_TYPE, nameIndex, descriptorIndex);
        int existing = composites.get(key);
        if (existing >= 0) {
            return existing;
        }
        int index = allocate(1);
        body.u1(TAG_NAME_AND_TYPE).u2(nameIndex).u2(descriptorIndex);
        composites.put(key, index);
        return index;
    }

    /**
     * Adds a field reference.
     *
     * @param owner      internal name of the declaring class
     * @param name       the field name
     * @param descriptor the field's type descriptor
     * @return the entry's index in the constant pool
     */
    public int fieldRef(String owner, String name, String descriptor) {
        return memberRef(TAG_FIELDREF, owner, name, descriptor);
    }

    /**
     * Adds a method reference for a method declared by a <em>class</em>.
     *
     * <p>The distinction from {@link #interfaceMethodRef} is not cosmetic: the JVM checks that the
     * reference kind matches the owner's kind, and using the wrong one produces an
     * {@code IncompatibleClassChangeError} at link time rather than anything more helpful.
     *
     * @param owner      internal name of the declaring class
     * @param name       the method name
     * @param descriptor the method descriptor
     * @return the entry's index in the constant pool
     */
    public int methodRef(String owner, String name, String descriptor) {
        return memberRef(TAG_METHODREF, owner, name, descriptor);
    }

    /**
     * Adds a method reference for a method declared by an <em>interface</em>.
     *
     * @param owner      internal name of the declaring interface
     * @param name       the method name
     * @param descriptor the method descriptor
     * @return the entry's index in the constant pool
     */
    public int interfaceMethodRef(String owner, String name, String descriptor) {
        return memberRef(TAG_INTERFACE_METHODREF, owner, name, descriptor);
    }

    /**
     * Adds a method handle, loadable with {@code ldc}.
     *
     * @param referenceKind one of the {@code REF_*} constants
     * @param owner         internal name of the declaring type
     * @param name          member name
     * @param descriptor    member descriptor
     * @param ownerIsInterface whether the owner is an interface
     * @return its constant-pool index
     */
    public int methodHandle(int referenceKind, String owner, String name, String descriptor,
                            boolean ownerIsInterface) {
        int reference = switch (referenceKind) {
            case REF_GET_FIELD, REF_GET_STATIC, REF_PUT_FIELD, REF_PUT_STATIC ->
                    fieldRef(owner, name, descriptor);
            case REF_INVOKE_INTERFACE -> interfaceMethodRef(owner, name, descriptor);
            default -> ownerIsInterface
                    ? interfaceMethodRef(owner, name, descriptor)
                    : methodRef(owner, name, descriptor);
        };
        long key = pack(TAG_METHOD_HANDLE, referenceKind, reference);
        int existing = composites.get(key);
        if (existing >= 0) {
            return existing;
        }
        int index = allocate(1);
        body.u1(TAG_METHOD_HANDLE).u1(referenceKind).u2(reference);
        composites.put(key, index);
        return index;
    }

    /**
     * Adds a method type, loadable with {@code ldc}.
     *
     * @param descriptor the method descriptor
     * @return the entry's index in the constant pool
     */
    public int methodType(String descriptor) {
        int descriptorIndex = utf8(descriptor);
        long key = pack(TAG_METHOD_TYPE, descriptorIndex, 0);
        int existing = composites.get(key);
        if (existing >= 0) {
            return existing;
        }
        int index = allocate(1);
        body.u1(TAG_METHOD_TYPE).u2(descriptorIndex);
        composites.put(key, index);
        return index;
    }

    /**
     * The value of the {@code constant_pool_count} field.
     *
     * <p>One greater than the highest index in use, per JVMS 4.1.
     *
     * @return the number of slots used, which is one more than the highest valid index
     */
    public int count() {
        return nextIndex;
    }

    /**
     * The serialised size of the pool in bytes, including the count field.
     *
     * <p>For pre-sizing the buffer the class file is assembled into; a proxy's pool runs to
     * several kilobytes, and omitting it from the estimate guarantees a copy-and-grow of the
     * largest buffer in the build.
     *
     * @return bytes {@link #writeTo} will produce
     */
    int byteLength() {
        return 2 + body.length();
    }

    /** Serialises the pool: the count field followed by every entry, in index order. */
    void writeTo(ByteWriter out) {
        out.u2(nextIndex);
        out.bytes(body);
    }

    private int memberRef(int tag, String owner, String name, String descriptor) {
        int ownerIndex = classEntry(owner);
        int nameAndTypeIndex = nameAndType(name, descriptor);
        long key = pack(tag, ownerIndex, nameAndTypeIndex);
        int existing = composites.get(key);
        if (existing >= 0) {
            return existing;
        }
        int index = allocate(1);
        body.u1(tag).u2(ownerIndex).u2(nameAndTypeIndex);
        composites.put(key, index);
        return index;
    }

    /** Tag in bits 32-39, two u2 child indices (or a small pair) below. */
    private static long pack(int tag, int high, int low) {
        return ((long) tag << 32) | ((long) (high & 0xFFFF) << 16) | (low & 0xFFFF);
    }

    /** Tag in bits 32-39, a full 32-bit payload below (int and float raw bits). */
    private static long pack(int tag, int payload) {
        return ((long) tag << 32) | (payload & 0xFFFFFFFFL);
    }

    private int allocate(int slots) {
        int index = nextIndex;
        if (index + slots - 1 > MAX_INDEX) {
            throw new CodeGenerationException("constant pool overflow: the class-file format "
                    + "allows indices up to " + MAX_INDEX + " and this class needs more. Split "
                    + "the generated class, or generate less per class.");
        }
        nextIndex += slots;
        return index;
    }

    /**
     * A {@code long → int} hash table: open addressing, linear probing, power-of-two capacity.
     *
     * <p>Exists so a pool lookup allocates nothing. {@code HashMap<Long, Integer>} boxes the key
     * on every probe and the value on every miss, and this table sits on the hottest path of
     * generation. Key 0 is the empty sentinel, which is safe because every packed key carries a
     * non-zero tag in bits 32-39.
     */
    private static final class LongIntMap {

        private long[] keys;
        private int[] values;
        private int size;

        LongIntMap(int initialCapacity) {
            keys = new long[initialCapacity];
            values = new int[initialCapacity];
        }

        /** The stored value, or -1 when absent. Pool indices start at 1, so -1 is unambiguous. */
        int get(long key) {
            long[] table = keys;
            int mask = table.length - 1;
            int i = spread(key) & mask;
            while (true) {
                long candidate = table[i];
                if (candidate == key) {
                    return values[i];
                }
                if (candidate == 0) {
                    return -1;
                }
                i = (i + 1) & mask;
            }
        }

        void put(long key, int value) {
            if ((size + 1) * 4 > keys.length * 3) {
                grow();
            }
            insert(keys, values, key, value);
            size++;
        }

        private void grow() {
            long[] oldKeys = keys;
            int[] oldValues = values;
            keys = new long[oldKeys.length * 2];
            values = new int[oldValues.length * 2];
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] != 0) {
                    insert(keys, values, oldKeys[i], oldValues[i]);
                }
            }
        }

        private static void insert(long[] intoKeys, int[] intoValues, long key, int value) {
            int mask = intoKeys.length - 1;
            int i = spread(key) & mask;
            while (intoKeys[i] != 0) {
                i = (i + 1) & mask;
            }
            intoKeys[i] = key;
            intoValues[i] = value;
        }

        /** Fibonacci hashing; the multiply spreads the low-entropy packed bits across the table. */
        private static int spread(long key) {
            long h = key * 0x9E3779B97F4A7C15L;
            return (int) (h >>> 32);
        }
    }
}
