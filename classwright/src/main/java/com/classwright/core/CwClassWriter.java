package com.classwright.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Assembles a complete class file.
 *
 * <p>The engine's entry point. A generator describes a class &mdash; its name, supertype,
 * interfaces, fields, and methods &mdash; and receives the bytes.
 *
 * <pre>{@code
 * CwClassWriter writer = CwClassWriter.of(
 *         AccessFlags.PUBLIC | AccessFlags.SUPER,
 *         "com/example/Greeter$$CW", "com/example/Greeter");
 *
 * writer.constructor(AccessFlags.PUBLIC, CwMethodType.of(CwType.VOID))
 *       .code()
 *       .loadThis()
 *       .invokeConstructor("com/example/Greeter", CwMethodType.of(CwType.VOID))
 *       .returnValue();
 *
 * byte[] bytes = writer.toByteArray();
 * }</pre>
 *
 * <h2>Writing only</h2>
 *
 * <p>There is no reader here, and there never will be. Classwright learns about existing classes
 * through core reflection, which is a public API the JDK keeps working across releases by
 * definition. A class-file parser would have to be taught every new format version, and a parser
 * that falls behind is precisely how CGLib went from ubiquitous to unusable. See the
 * {@code com.classwright} package documentation.
 *
 * <p>Instances are single-use and not thread-safe.
 */
public final class CwClassWriter {

    private static final int MAGIC = 0xCAFEBABE;

    private final ConstantPool pool = new ConstantPool();
    private final List<FieldBuilder> fields = new ArrayList<>();
    private final List<MethodBuilder> methods = new ArrayList<>();
    private final Set<String> memberKeys = new LinkedHashSet<>();

    private final int accessFlags;
    private final String internalName;
    private final String superInternalName;
    private final List<String> interfaces;

    private int majorVersion = ClassFileVersion.DEFAULT;
    private String sourceFile;
    private String genericSignature;
    private java.lang.annotation.Annotation[] annotations;

    private CwClassWriter(int accessFlags, String internalName, String superInternalName,
                          List<String> interfaces) {
        this.accessFlags = accessFlags;
        this.internalName = internalName;
        this.superInternalName = superInternalName;
        this.interfaces = List.copyOf(interfaces);
    }

    /**
     * Begins a class.
     *
     * @param accessFlags       see {@link AccessFlags}; should normally include
     *                          {@link AccessFlags#SUPER}
     * @param internalName      the new class's internal name, e.g. {@code com/example/Foo}
     * @param superInternalName its superclass, or {@code java/lang/Object}
     * @param interfaces        internal names of implemented interfaces
     * @return a writer for the class
     */
    public static CwClassWriter of(int accessFlags, String internalName, String superInternalName,
                                   String... interfaces) {
        Objects.requireNonNull(internalName, "internalName");
        Objects.requireNonNull(superInternalName, "superInternalName");
        requireInternalName(internalName, "class name");
        requireInternalName(superInternalName, "superclass name");
        for (String each : interfaces) {
            requireInternalName(each, "interface name");
        }
        if ((accessFlags & AccessFlags.INTERFACE) == 0 && (accessFlags & AccessFlags.SUPER) == 0) {
            // ACC_SUPER selects modern invokespecial semantics. Every JVM since 1.1 wants it on a
            // class, and omitting it can make a super call dispatch to the wrong method.
            throw new CodeGenerationException("a generated class must set ACC_SUPER; without it, "
                    + "invokespecial uses pre-1.1 lookup rules and super calls can misdispatch");
        }
        return new CwClassWriter(accessFlags, internalName, superInternalName,
                List.of(interfaces));
    }

    /**
     * Begins an interface.
     *
     * @param accessFlags    flags for the interface; {@code ACC_INTERFACE} is added automatically
     * @param internalName   the interface's internal name
     * @param superInterfaces internal names of interfaces it extends
     * @return a writer for the interface
     */
    public static CwClassWriter ofInterface(int accessFlags, String internalName,
                                            String... superInterfaces) {
        // The same guardrails as of(): a dotted name here dies much later as a baffling
        // NoClassDefFoundError, which is exactly what these checks exist to prevent.
        Objects.requireNonNull(internalName, "internalName");
        requireInternalName(internalName, "interface name");
        for (String each : superInterfaces) {
            requireInternalName(each, "superinterface name");
        }
        if ((accessFlags & AccessFlags.FINAL) != 0) {
            throw new CodeGenerationException(
                    "an interface cannot be final (JVMS 4.1); drop ACC_FINAL");
        }
        return new CwClassWriter(
                accessFlags | AccessFlags.INTERFACE | AccessFlags.ABSTRACT,
                internalName, "java/lang/Object", List.of(superInterfaces));
    }

    /**
     * Sets the class-file version to emit.
     *
     * <p>Defaults to {@link ClassFileVersion#DEFAULT}. Lower is better; see
     * {@link ClassFileVersion} for why.
     *
     * @param major the class-file major version to emit
     * @return this writer, so calls can be chained
     */
    public CwClassWriter version(int major) {
        ClassFileVersion.validate(major);
        this.majorVersion = major;
        return this;
    }

    /**
     * Sets the {@code SourceFile} attribute, which is what appears in stack traces.
     *
     * <p>Worth setting on generated classes. A stack frame reading {@code Greeter$$CW.java} tells a
     * reader immediately that they are looking at generated code, where the default &mdash; no file
     * at all, rendered as {@code Unknown Source} &mdash; tells them nothing.
     *
     * @param name the value for the {@code SourceFile} attribute
     * @return this writer, so calls can be chained
     */
    public CwClassWriter sourceFile(String name) {
        this.sourceFile = name;
        return this;
    }

    /**
     * Sets the class's generic signature.
     *
     * @param signature the generic signature of the class
     * @return this writer, so calls can be chained
     */
    public CwClassWriter genericSignature(String signature) {
        this.genericSignature = signature;
        return this;
    }

    /**
     * Attaches annotations to the generated class.
     *
     * @param annotations typically from {@code Class.getDeclaredAnnotations()} of the type being
     *                    extended
     *
     * @return this writer, so calls can be chained
     */
    public CwClassWriter annotations(java.lang.annotation.Annotation... annotations) {
        this.annotations = annotations == null ? null : annotations.clone();
        return this;
    }

    /**
     * The internal name of the class being built.
     *
     * @return the internal name
     */
    public String internalName() {
        return internalName;
    }

    /**
     * The internal name of its superclass.
     *
     * @return the superclass's internal name
     */
    public String superInternalName() {
        return superInternalName;
    }

    /**
     * The constant pool, for generators that need to add entries directly.
     *
     * @return the pool being built, for callers that need to add entries directly
     */
    public ConstantPool constantPool() {
        return pool;
    }

    /**
     * Declares a field.
     *
     * @param accessFlags the field's access flags
     * @param name        the field name
     * @param type        the field's type
     * @return a builder for the field
     */
    public FieldBuilder field(int accessFlags, String name, CwType type) {
        claim("field " + name, name + " " + type.descriptor());
        FieldBuilder field = new FieldBuilder(pool, accessFlags, name, type);
        fields.add(field);
        return field;
    }

    /**
     * Declares a method.
     *
     * @param accessFlags the method's access flags
     * @param name        the method name
     * @param type        the method's signature
     * @return a builder for the method
     */
    public MethodBuilder method(int accessFlags, String name, CwMethodType type) {
        if (name.equals("<init>") || name.equals("<clinit>")) {
            throw new CodeGenerationException(
                    "use constructor() or staticInitializer() for " + name);
        }
        claim("method " + name, name + type.descriptor());
        MethodBuilder method = new MethodBuilder(pool, internalName, accessFlags, name, type);
        methods.add(method);
        return method;
    }

    /**
     * Declares a constructor. Its descriptor must return {@code void}.
     *
     * @param accessFlags the constructor's access flags
     * @param type        its parameter types, with a {@code void} return
     * @return a builder for the constructor
     */
    public MethodBuilder constructor(int accessFlags, CwMethodType type) {
        if (!type.returnType().isVoid()) {
            throw new CodeGenerationException(
                    "a constructor's descriptor returns void, not " + type.returnType());
        }
        claim("constructor", "<init>" + type.descriptor());
        MethodBuilder constructor =
                new MethodBuilder(pool, internalName, accessFlags, "<init>", type);
        methods.add(constructor);
        return constructor;
    }

    /**
     * Declares the static initialiser, {@code <clinit>}.
     *
     * <p>Where a generated class sets up its static state: cached {@code Method} objects, dispatch
     * tables, and so on.
     *
     * @return a builder for the static initialiser
     */
    public MethodBuilder staticInitializer() {
        claim("static initializer", "<clinit>()V");
        MethodBuilder initializer = new MethodBuilder(pool, internalName,
                AccessFlags.STATIC, "<clinit>", CwMethodType.of(CwType.VOID));
        methods.add(initializer);
        return initializer;
    }

    /**
     * Produces the class file.
     *
     * @return a complete, verifiable class file
     */
    public byte[] toByteArray() {
        // Every method body is serialised first, because doing so adds constant-pool entries and
        // the pool has to be complete before its size and contents can be written.
        ByteWriter body = new ByteWriter(512);
        body.u2(requireU2Count(fields.size(), "fields"));
        for (FieldBuilder field : fields) {
            field.writeTo(body);
        }
        body.u2(requireU2Count(methods.size(), "methods"));
        for (MethodBuilder method : methods) {
            method.writeTo(body);
        }

        ByteWriter classAttributes = new ByteWriter(32);
        int attributeCount = 0;
        if (sourceFile != null) {
            classAttributes.u2(pool.utf8("SourceFile"));
            classAttributes.u4(2);
            classAttributes.u2(pool.utf8(sourceFile));
            attributeCount++;
        }
        if (genericSignature != null) {
            classAttributes.u2(pool.utf8("Signature"));
            classAttributes.u4(2);
            classAttributes.u2(pool.utf8(genericSignature));
            attributeCount++;
        }
        attributeCount += Attributes.writeAnnotations(classAttributes, pool, annotations);

        int thisClass = pool.classEntry(internalName);
        int superClass = pool.classEntry(superInternalName);
        int[] interfaceEntries = new int[interfaces.size()];
        for (int i = 0; i < interfaces.size(); i++) {
            interfaceEntries[i] = pool.classEntry(interfaces.get(i));
        }

        // The pool is several kilobytes for a real proxy class; omitting it from the estimate
        // guaranteed one doubling-and-copy of the largest buffer in the build.
        ByteWriter out = new ByteWriter(body.length() + pool.byteLength() + 64);
        out.u4(MAGIC);
        out.u2(0);                          // minor version
        out.u2(majorVersion);
        pool.writeTo(out);
        out.u2(accessFlags);
        out.u2(thisClass);
        out.u2(superClass);
        out.u2(requireU2Count(interfaceEntries.length, "interfaces"));
        for (int entry : interfaceEntries) {
            out.u2(entry);
        }
        out.bytes(body);
        out.u2(attributeCount);
        out.bytes(classAttributes);
        return out.toByteArray();
    }

    /**
     * Rejects a duplicate member.
     *
     * <p>The JVM permits two methods with the same name if their descriptors differ, and rejects
     * two that match on both. Catching it here names the member; letting the JVM catch it produces
     * a {@code ClassFormatError} that does not.
     */
    private void claim(String description, String key) {
        if (!memberKeys.add(key)) {
            throw new CodeGenerationException(internalName + " already declares a " + description
                    + " with this exact signature");
        }
    }

    private static void requireInternalName(String name, String what) {
        if (name.isEmpty()) {
            throw new CodeGenerationException(what + " must not be empty");
        }
        if (name.indexOf('.') >= 0) {
            throw new CodeGenerationException(what + " '" + name + "' looks like a binary name; "
                    + "internal names separate packages with '/', e.g. java/lang/Object");
        }
    }

    /**
     * Guards a count about to be serialised as a {@code u2}.
     *
     * <p>Without the guard an overflowing count wraps silently and the class file is corrupt in
     * a way the eventual {@code ClassFormatError} does not explain. The low-level writer cannot
     * enforce this globally — some u2 payloads legitimately mask values — so every <em>count</em>
     * boundary names itself here instead.
     */
    private static int requireU2Count(int count, String what) {
        if (count > 0xFFFF) {
            throw new CodeGenerationException("the class declares " + count + " " + what
                    + "; the class-file format caps the count at 65535. Split the generated "
                    + "class.");
        }
        return count;
    }
}
