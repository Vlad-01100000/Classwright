package com.classwright.core;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Declares one method on the class being generated, and gives access to its body.
 *
 * <p>Obtained from {@link CwClassWriter#method} or {@link CwClassWriter#constructor}; not
 * constructed directly. Call {@link #code()} to emit a body, or leave it alone for an
 * {@code abstract} or {@code native} method.
 */
public final class MethodBuilder {

    private final ConstantPool pool;
    private final int accessFlags;
    private final String name;
    private final CwMethodType type;
    private final CodeBuilder code;
    private final List<String> declaredExceptions = new ArrayList<>();

    private String genericSignature;
    private Annotation[] annotations = NO_ANNOTATIONS;
    private Annotation[][] parameterAnnotations;

    private static final Annotation[] NO_ANNOTATIONS = new Annotation[0];

    MethodBuilder(ConstantPool pool, String ownerInternalName, int accessFlags, String name,
                  CwMethodType type) {
        this.pool = pool;
        this.accessFlags = accessFlags;
        this.name = name;
        this.type = type;

        boolean isStatic = (accessFlags & AccessFlags.STATIC) != 0;
        boolean isAbstract = (accessFlags & (AccessFlags.ABSTRACT | AccessFlags.NATIVE)) != 0;
        type.validateArity(!isStatic);

        this.code = isAbstract
                ? null
                : new CodeBuilder(pool, ownerInternalName, type, !isStatic, name.equals("<init>"));
    }

    /**
     * The body of this method.
     *
     * @return the code builder
     * @throws CodeGenerationException if the method is abstract or native, and so has no body
     */
    public CodeBuilder code() {
        if (code == null) {
            throw new CodeGenerationException(
                    AccessFlags.describe(accessFlags) + " method " + name + " cannot have a body");
        }
        return code;
    }

    /**
     * Adds a checked exception to the {@code throws} clause.
     *
     * <p>The JVM does not enforce checked exceptions &mdash; that is entirely a compiler rule
     * &mdash; but the attribute is what {@code Method.getExceptionTypes()} reports, and generated
     * overrides that lose it break callers who compile against them.
     *
     * @param internalName internal name of the exception type
     * @return this builder, so calls can be chained
     */
    public MethodBuilder throwsException(String internalName) {
        declaredExceptions.add(internalName);
        return this;
    }

    /**
     * Attaches a generic method signature.
     *
     * @param signature a JVMS 4.7.9.1 method signature
     * @see FieldBuilder#genericSignature
     * @return this builder, so calls can be chained
     */
    public MethodBuilder genericSignature(String signature) {
        this.genericSignature = signature;
        return this;
    }

    /**
     * Attaches annotations to this method.
     *
     * @param annotations the annotations, typically from {@code Method.getDeclaredAnnotations()}
     * @return this builder, so calls can be chained
     */
    public MethodBuilder annotations(Annotation... annotations) {
        this.annotations = annotations == null ? NO_ANNOTATIONS : annotations.clone();
        return this;
    }

    /**
     * Attaches per-parameter annotations.
     *
     * @param parameterAnnotations one array per parameter, in order, as returned by
     *                             {@code Method.getParameterAnnotations()}
     *
     * @return this builder, so calls can be chained
     */
    public MethodBuilder parameterAnnotations(Annotation[][] parameterAnnotations) {
        // A shallow copy, matching annotations() above: the outer array is the caller's to
        // mutate, and emitted output must not change under a setter that already returned.
        // The inner arrays are freshly allocated by Method.getParameterAnnotations().
        this.parameterAnnotations =
                parameterAnnotations == null ? null : parameterAnnotations.clone();
        return this;
    }

    /**
     * Copies the annotations, parameter annotations, generic signature, and {@code throws} clause
     * from an existing method.
     *
     * <p>What a generated override needs in order to be indistinguishable from the method it
     * replaces, as far as reflection is concerned. The generic signature is omitted rather than
     * approximated when it cannot be rendered faithfully; see {@link SignatureRenderer}.
     *
     * @param source the method being overridden
     * @return this builder, so calls can be chained
     */
    public MethodBuilder copyMetadataFrom(Method source) {
        annotations(source.getDeclaredAnnotations());
        parameterAnnotations(source.getParameterAnnotations());
        genericSignatureFrom(source);
        for (Class<?> exception : source.getExceptionTypes()) {
            throwsException(exception.getName().replace('.', '/'));
        }
        return this;
    }

    /**
     * Derives the generic signature from an existing method, if one can be rendered faithfully.
     *
     * <p>Does nothing when the method is not generic, or when its type cannot be represented
     * exactly. A wrong {@code Signature} attribute is worse than a missing one — reflection reports
     * it confidently — so the renderer refuses rather than approximates.
     *
     * @param source the method to describe
     * @return this builder, so calls can be chained
     */
    public MethodBuilder genericSignatureFrom(Method source) {
        SignatureRenderer.forMethod(source).ifPresent(this::genericSignature);
        return this;
    }

    /**
     * The method's name.
     *
     * @return the name
     */
    public String name() {
        return name;
    }

    /**
     * The method's signature.
     *
     * @return the signature
     */
    public CwMethodType type() {
        return type;
    }

    void writeTo(ByteWriter out) {
        out.u2(accessFlags);
        out.u2(pool.utf8(name));
        out.u2(pool.utf8(type.descriptor()));

        ByteWriter attributes = new ByteWriter(64);
        int attributeCount = 0;

        if (code != null) {
            code.writeCodeAttribute(attributes, pool.utf8("Code"));
            attributeCount++;
        }
        if (!declaredExceptions.isEmpty()) {
            attributes.u2(pool.utf8("Exceptions"));
            attributes.u4(2 + declaredExceptions.size() * 2);
            attributes.u2(declaredExceptions.size());
            for (String exception : declaredExceptions) {
                attributes.u2(pool.classEntry(exception));
            }
            attributeCount++;
        }
        if (genericSignature != null) {
            attributes.u2(pool.utf8("Signature"));
            attributes.u4(2);
            attributes.u2(pool.utf8(genericSignature));
            attributeCount++;
        }
        attributeCount += Attributes.writeAnnotations(attributes, pool, annotations);
        attributeCount += Attributes.writeParameterAnnotations(attributes, pool,
                parameterAnnotations);

        out.u2(attributeCount);
        out.bytes(attributes);
    }
}
