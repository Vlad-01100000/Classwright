package com.classwright.core;

/**
 * Declares one field on the class being generated.
 *
 * <p>Obtained from {@link CwClassWriter#field}; not constructed directly.
 */
public final class FieldBuilder {

    private final ConstantPool pool;
    private final int accessFlags;
    private final String name;
    private final CwType type;

    private String genericSignature;

    FieldBuilder(ConstantPool pool, int accessFlags, String name, CwType type) {
        this.pool = pool;
        this.accessFlags = accessFlags;
        this.name = name;
        this.type = type;
    }

    /**
     * Attaches a generic type signature, e.g. {@code Ljava/util/List<Ljava/lang/String;>;}.
     *
     * <p>Descriptors erase generics; the {@code Signature} attribute is what carries them, and it
     * is what {@code Field.getGenericType()} reads back. Frameworks routinely inspect generic types
     * on generated classes, and CGLib dropped them entirely &mdash; a small, permanent papercut for
     * everything downstream.
     *
     * @param signature a JVMS 4.7.9.1 field signature
     * @return this builder, so calls can be chained
     */
    public FieldBuilder genericSignature(String signature) {
        this.genericSignature = signature;
        return this;
    }

    /**
     * The field's name.
     *
     * @return the name
     */
    public String name() {
        return name;
    }

    /**
     * The field's type.
     *
     * @return the type
     */
    public CwType type() {
        return type;
    }

    void writeTo(ByteWriter out) {
        out.u2(accessFlags);
        out.u2(pool.utf8(name));
        out.u2(pool.utf8(type.descriptor()));

        if (genericSignature == null) {
            out.u2(0);
            return;
        }
        out.u2(1);
        out.u2(pool.utf8("Signature"));
        out.u4(2);
        out.u2(pool.utf8(genericSignature));
    }
}
