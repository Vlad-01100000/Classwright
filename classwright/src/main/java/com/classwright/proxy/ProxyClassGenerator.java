package com.classwright.proxy;

import com.classwright.core.AccessFlags;
import com.classwright.core.CodeBuilder;
import com.classwright.core.CwClassWriter;
import com.classwright.core.CwMethodType;
import com.classwright.core.CwType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Emits the proxy class.
 *
 * <h2>One class per proxy</h2>
 *
 * <p>Everything lives in the generated subclass: the overridden methods, the callback fields, the
 * super-call dispatcher, and the {@link Factory} implementation. CGLib split this across roughly
 * three classes — the enhanced subclass plus two {@code FastClass} helpers — which is most of why
 * it needed four times the metaspace per proxy and why its generation cost was an order of
 * magnitude higher.
 *
 * <h2>The shape of a generated method</h2>
 *
 * <pre>{@code
 * public int compute(int a, int b) {
 *     MethodInterceptor cb = this.CW$callback$0;
 *     if (cb == null) {
 *         return super.compute(a, b);          // unconfigured proxy: one predicted branch
 *     }
 *     return (Integer) cb.intercept(this, CW$method$3, new Object[]{a, b}, CW$proxy$3);
 * }
 * }</pre>
 *
 * <p>Three decisions in there are deliberate. The callback lives in a <em>per-instance field of its
 * declared type</em>, not in a shared {@code Callback[]}, so the call site is monomorphic and the
 * JIT can inline through it. The null check means an unconfigured proxy costs a field read and a
 * branch the predictor always gets right. And the method body is kept small enough that when the
 * interceptor is inlined, escape analysis can remove the argument array entirely.
 *
 * <p>The super-call path is {@link SuperDispatcher}: one interface method holding a
 * {@code tableswitch} whose cases perform direct {@code invokespecial} calls. That replaces
 * CGLib's generated {@code FastClass} with no extra class and one fewer hop.
 */
final class ProxyClassGenerator {

    // Generated member names all carry the configured prefix so they cannot collide with the
    // target's own members, and so that reflection-based frameworks filtering on a known prefix
    // keep working. The compatibility layer sets it to CGLIB$ for exactly that reason.
    private final String callbackField;
    private final String lazyField;
    private final String methodField;
    private final String proxyField;
    private final String constructedField;

    /**
     * The lookup field's name is fixed rather than prefixed.
     *
     * <p>{@link ProxySupport} finds it reflectively to recognise a generated proxy and to create
     * further instances of a cached class, and it has no way to know which convention any given
     * proxy was generated with.
     */
    static final String LOOKUP_FIELD = ProxySupport.LOOKUP_FIELD;

    /** Fixed for the same reason: {@link Enhancer} invokes it by name after defining the class. */
    static final String INIT_METHOD = "CW$init";

    private static final CwType METHOD = CwType.objectType("java/lang/reflect/Method");
    private static final CwType METHOD_ARRAY = CwType.arrayOf(METHOD);
    private static final CwType METHOD_PROXY = CwType.objectType(internal(MethodProxy.class));
    private static final CwType METHOD_PROXY_ARRAY = CwType.arrayOf(METHOD_PROXY);
    private static final CwType CALLBACK = CwType.objectType(internal(Callback.class));
    private static final CwType CALLBACK_ARRAY = CwType.arrayOf(CALLBACK);
    private static final CwType LOOKUP =
            CwType.objectType("java/lang/invoke/MethodHandles$Lookup");
    private static final CwType CLASS_ARRAY = CwType.arrayOf(CwType.CLASS);

    private static final String CALLBACK_REGISTRY = internal(CallbackRegistry.class);
    private static final String PROXY_SUPPORT = internal(ProxySupport.class);

    private final ProxySpec spec;
    private final String self;
    private final String superName;
    private final CwClassWriter writer;

    private ProxyClassGenerator(ProxySpec spec) {
        this.spec = spec;
        this.self = spec.internalName();
        this.superName = internal(spec.superclass());

        String prefix = spec.naming().memberPrefix();
        this.callbackField = prefix + "callback$";
        this.lazyField = prefix + "lazy$";
        this.methodField = prefix + "method$";
        this.proxyField = prefix + "proxy$";
        this.constructedField = prefix + "constructed";

        this.writer = CwClassWriter.of(
                        AccessFlags.PUBLIC | AccessFlags.SUPER,
                        self, superName, declaredInterfaces(spec))
                .sourceFile(spec.superclass().getSimpleName() + "$$CW.java");
        if (spec.copyAnnotations()) {
            // Only *declared* annotations. Anything meta-annotated @Inherited is already visible
            // on a subclass, so copying those would produce duplicates.
            writer.annotations(spec.superclass().getDeclaredAnnotations());
        }
    }

    /**
     * Generates the class file.
     *
     * @param spec what to build
     * @return a complete, verifiable class file
     */
    static byte[] generate(ProxySpec spec) {
        return new ProxyClassGenerator(spec).build();
    }

    private byte[] build() {
        declareFields();
        generateInitialiser();
        generateConstructors();
        generateOverrides();
        generateBridges();
        generateSuperDispatcher();
        if (spec.useFactory()) {
            generateFactory();
        }
        return writer.toByteArray();
    }

    /**
     * Emits the covariant sibling descriptors as bridges, exactly as javac would.
     *
     * <p>Each forwards virtually to the canonical override, so a call through either interface
     * descriptor is intercepted identically. See {@link ProxyMethods.RequiredBridge}.
     */
    private void generateBridges() {
        for (ProxyMethods.RequiredBridge bridge : spec.methods().bridges()) {
            Method canonical = bridge.canonical();
            CwMethodType canonicalType = CwMethodType.of(canonical);
            CwType[] parameters = canonicalType.parameterTypes().toArray(new CwType[0]);
            CwMethodType bridgeType =
                    CwMethodType.of(CwType.of(bridge.bridgeReturn()), parameters);

            CodeBuilder code = writer
                    .method(AccessFlags.PUBLIC | AccessFlags.BRIDGE | AccessFlags.SYNTHETIC,
                            canonical.getName(), bridgeType)
                    .code();
            code.loadThis().loadAllArguments();
            code.invokeVirtual(self, canonical.getName(), canonicalType);
            code.returnValue(bridgeType.returnType());
        }
    }

    // ==========================================================================================
    // Structure
    // ==========================================================================================

    /**
     * The interfaces the generated class declares.
     *
     * <p>{@link SuperDispatcher} always, {@link Factory} on request, and any interface that
     * contributes a <em>default method</em> we override. That last one is a JVM requirement rather
     * than a nicety: {@code invokespecial} on an interface method is only legal when the interface
     * is a direct superinterface of the calling class, so calling {@code Interface.super.method()}
     * means declaring the interface even though it is already implied by the superclass.
     */
    private static String[] declaredInterfaces(ProxySpec spec) {
        Set<String> names = new LinkedHashSet<>();
        for (Class<?> each : spec.interfaces()) {
            names.add(internal(each));
        }
        names.add(internal(SuperDispatcher.class));
        if (spec.useFactory()) {
            names.add(internal(Factory.class));
        }
        for (ProxyMethods.Proxied proxied : spec.methods().proxied()) {
            Class<?> declaring = proxied.method().getDeclaringClass();
            if (declaring.isInterface() && !proxied.isAbstract()) {
                names.add(internal(declaring));
            }
        }
        return names.toArray(String[]::new);
    }

    private void declareFields() {
        for (int i = 0; i < spec.callbackCount(); i++) {
            writer.field(AccessFlags.PRIVATE, callbackField + i,
                    CwType.objectType(internal(spec.callbackTypes().get(i))));
            if (spec.kindOfCallback(i).needsInstanceCache()) {
                // Volatile: the delegate is built once and read by every thread thereafter, and
                // a plain field gives a racing reader no happens-before edge — it could observe
                // a partially constructed delegate. The volatile read on the hot path is cheap;
                // the store happens once.
                writer.field(AccessFlags.PRIVATE | AccessFlags.VOLATILE, lazyField + i,
                        CwType.OBJECT);
            }
        }
        for (ProxyMethods.Proxied proxied : spec.methods().proxied()) {
            // Individual static fields rather than an array: a getstatic is one instruction where
            // an array element read is three plus a bounds check, and these sit on the hot path of
            // every intercepted call.
            writer.field(AccessFlags.PRIVATE | AccessFlags.STATIC,
                    methodField + proxied.index(), METHOD);
            writer.field(AccessFlags.PRIVATE | AccessFlags.STATIC,
                    proxyField + proxied.index(), METHOD_PROXY);
        }
        // Public so that ProxySupport can read it back reflectively when creating further
        // instances of a cached proxy class. Synthetic keeps it out of the way of frameworks that
        // scan fields.
        writer.field(AccessFlags.PUBLIC | AccessFlags.STATIC | AccessFlags.SYNTHETIC,
                LOOKUP_FIELD, LOOKUP);
        if (!spec.interceptDuringConstruction()) {
            writer.field(AccessFlags.PRIVATE, constructedField, CwType.BOOLEAN);
        }
    }

    /**
     * Generates {@code CW$init}, which populates the static metadata after the class is defined.
     *
     * <p>A {@code <clinit>} cannot do this itself: the {@link Method} and {@link MethodProxy}
     * objects come from reflection over the target, which the generated class has no way to perform
     * on itself when it is hidden and has no resolvable name. Passing them in through one static
     * call is simpler than any alternative and costs one invocation per generated class.
     *
     * <p>The fields are not {@code final}, so the JIT cannot constant-fold them. Making them so
     * would require the condy-based class-data mechanism scheduled for Phase 7.
     */
    private void generateInitialiser() {
        CwMethodType signature = CwMethodType.of(CwType.VOID, METHOD_ARRAY, METHOD_PROXY_ARRAY,
                LOOKUP);
        CodeBuilder code = writer
                .method(AccessFlags.PUBLIC | AccessFlags.STATIC | AccessFlags.SYNTHETIC,
                        INIT_METHOD, signature)
                .code();

        for (ProxyMethods.Proxied proxied : spec.methods().proxied()) {
            code.loadArgument(0).pushInt(proxied.index()).arrayLoad(METHOD)
                    .putStatic(self, methodField + proxied.index(), METHOD);
            code.loadArgument(1).pushInt(proxied.index()).arrayLoad(METHOD_PROXY)
                    .putStatic(self, proxyField + proxied.index(), METHOD_PROXY);
        }
        code.loadArgument(2).putStatic(self, LOOKUP_FIELD, LOOKUP);
        code.returnValue();
    }

    /**
     * Mirrors the superclass constructors, binding callbacks immediately after chaining to super.
     *
     * <p>The field writes cannot happen sooner — nothing may touch {@code this} before the
     * superclass constructor returns — but that does <em>not</em> mean calls made from the
     * superclass constructor run unintercepted: an override invoked during {@code super()} finds
     * its field null and asks {@link ProxySupport#bindPending} for the construction's parked
     * frame, which binds mid-construction. That matches CGLib's default. The explicit binding
     * here covers everything the constructor did not trigger.
     */
    private void generateConstructors() {
        for (Constructor<?> constructor : spec.constructors()) {
            CwMethodType signature = CwMethodType.of(constructor);
            CodeBuilder code = writer
                    .constructor(visibilityOf(constructor.getModifiers()), signature)
                    .code();

            code.loadThis().loadAllArguments().invokeConstructor(superName, signature);
            emitCallbackBinding(code);
            if (!spec.interceptDuringConstruction()) {
                code.loadThis().pushInt(1).putField(self, constructedField, CwType.BOOLEAN);
            }
            code.returnValue();
        }
    }

    /** Reads the pending or registered callbacks and assigns them, if any. */
    private void emitCallbackBinding(CodeBuilder code) {
        int slot = code.declareLocal(CALLBACK_ARRAY);
        code.loadThis()
                .invokeStatic(CALLBACK_REGISTRY, "collect",
                        CwMethodType.of(CALLBACK_ARRAY, CwType.OBJECT))
                .store(slot, CALLBACK_ARRAY);
        code.load(slot, CALLBACK_ARRAY);
        // Null when somebody constructed the proxy directly rather than through Classwright. Its
        // callback fields then stay null and every method falls through to the original.
        code.ifNonNull(() -> {
            for (int i = 0; i < spec.callbackCount(); i++) {
                CwType callbackType = CwType.objectType(internal(spec.callbackTypes().get(i)));
                code.loadThis()
                        .load(slot, CALLBACK_ARRAY).pushInt(i).arrayLoad(CALLBACK)
                        .checkCast(callbackType)
                        .putField(self, callbackField + i, callbackType);
            }
        });
    }

    // ==========================================================================================
    // Overridden methods
    // ==========================================================================================

    private void generateOverrides() {
        for (ProxyMethods.Proxied proxied : spec.methods().proxied()) {
            Method method = proxied.method();
            CwMethodType signature = CwMethodType.of(method);
            if (collidesWithGeneratedMember(method.getName(), signature)) {
                // A superclass method with the same name and descriptor as a member this
                // generator emits (cwInvokeSuper, or Factory's methods) cannot also be
                // overridden — the duplicate would be rejected at definition with a
                // ClassFormatError naming neither cause. The method stays unintercepted; its
                // dispatch-table slot still works, since super dispatch never needed the
                // override.
                continue;
            }
            var builder = writer.method(AccessFlags.forGeneratedOverrideOf(method.getModifiers()),
                    method.getName(), signature);

            // The throws clause is always reproduced: the JVM does not enforce checked exceptions,
            // but Method.getExceptionTypes() reports the attribute, and callers compiling against
            // a proxy need it to match.
            for (Class<?> exception : method.getExceptionTypes()) {
                builder.throwsException(exception.getName().replace('.', '/'));
            }
            if (spec.copyAnnotations()) {
                builder.annotations(method.getDeclaredAnnotations())
                        .parameterAnnotations(method.getParameterAnnotations())
                        .genericSignatureFrom(method);
            }
            CodeBuilder code = builder.code();

            int callbackIndex = spec.callbackIndexPerMethod()[proxied.index()];
            CallbackKind kind = spec.kindFor(proxied.index());

            if (kind == CallbackKind.NO_OP) {
                emitOriginal(code, proxied);
                continue;
            }
            if (!spec.interceptDuringConstruction()) {
                // Calls made while the constructor is still running go straight to the original.
                code.loadThis().getField(self, constructedField, CwType.BOOLEAN);
                code.ifIntComparison(CodeBuilder.IntTest.NOT_ZERO,
                        () -> emitDispatch(code, proxied, callbackIndex, kind),
                        () -> emitOriginal(code, proxied));
                continue;
            }
            emitDispatch(code, proxied, callbackIndex, kind);
        }
    }

    /** The callback-field read, null check, and the kind-specific body. */
    private void emitDispatch(CodeBuilder code, ProxyMethods.Proxied proxied, int callbackIndex,
                              CallbackKind kind) {
        CwType callbackType = CwType.objectType(internal(spec.callbackTypes().get(callbackIndex)));
        int slot = code.declareLocal(callbackType);

        code.loadThis().getField(self, callbackField + callbackIndex, callbackType)
                .store(slot, callbackType);
        // A null field may mean "constructed without Classwright but callbacks were registered
        // for the class" — the Objenesis-then-setCallbacks pattern CGLib served with
        // CGLIB$BIND_CALLBACKS. One extra predicted branch on the bound path; the unbound path
        // asks ProxySupport to bind and re-reads.
        code.load(slot, callbackType);
        code.ifNull(() -> {
            code.loadThis()
                    .invokeStatic(PROXY_SUPPORT, "bindPending",
                            CwMethodType.of(CwType.VOID, CwType.OBJECT));
            code.loadThis().getField(self, callbackField + callbackIndex, callbackType)
                    .store(slot, callbackType);
        });
        code.load(slot, callbackType);
        code.ifNullElse(
                () -> emitOriginal(code, proxied),
                () -> emitCallbackBody(code, proxied, callbackIndex, kind, slot, callbackType));
    }

    private void emitCallbackBody(CodeBuilder code, ProxyMethods.Proxied proxied,
                                  int callbackIndex, CallbackKind kind, int callbackSlot,
                                  CwType callbackType) {
        Method method = proxied.method();
        CwType returnType = CwType.of(method.getReturnType());
        String callbackOwner = callbackType.internalName();

        switch (kind) {
            case INTERCEPTOR -> {
                code.load(callbackSlot, callbackType);
                code.loadThis();
                code.getStatic(self, methodField + proxied.index(), METHOD);
                code.packArgumentsIntoArray();
                code.getStatic(self, proxyField + proxied.index(), METHOD_PROXY);
                code.invokeInterface(callbackOwner, "intercept", CwMethodType.of(
                        CwType.OBJECT, CwType.OBJECT, METHOD, CwType.OBJECT_ARRAY, METHOD_PROXY));
                emitReturnFromObject(code, returnType);
            }
            case INVOCATION_HANDLER -> {
                code.load(callbackSlot, callbackType);
                code.loadThis();
                code.getStatic(self, methodField + proxied.index(), METHOD);
                code.packArgumentsIntoArray();
                code.invokeInterface(callbackOwner, "invoke", CwMethodType.of(
                        CwType.OBJECT, CwType.OBJECT, METHOD, CwType.OBJECT_ARRAY));
                emitReturnFromObject(code, returnType);
            }
            case FIXED_VALUE -> {
                // The arguments are never touched, which is the whole appeal of this callback.
                code.load(callbackSlot, callbackType);
                code.invokeInterface(callbackOwner, "loadObject", CwMethodType.of(CwType.OBJECT));
                emitReturnFromObject(code, returnType);
            }
            case DISPATCHER -> {
                code.load(callbackSlot, callbackType);
                code.invokeInterface(callbackOwner, "loadObject", CwMethodType.of(CwType.OBJECT));
                emitDelegatingCall(code, proxied);
            }
            case PROXY_REF_DISPATCHER -> {
                code.load(callbackSlot, callbackType);
                code.loadThis();
                code.invokeInterface(callbackOwner, "loadObject",
                        CwMethodType.of(CwType.OBJECT, CwType.OBJECT));
                emitDelegatingCall(code, proxied);
            }
            case LAZY_LOADER -> {
                emitLazyResolution(code, callbackIndex, callbackSlot, callbackType);
                emitDelegatingCall(code, proxied);
            }
            case NO_OP -> throw new IllegalStateException("handled before dispatch");
        }
    }

    /**
     * Resolves and caches a {@link LazyLoader}'s delegate, leaving it on the stack.
     *
     * <p>The fast path is a volatile field read and a null check. Only the unresolved path calls
     * into {@link ProxySupport#lazyInitialise}, whose per-instance monitor makes the first
     * resolution at-most-once — CGLib's guarantee, which migrated loaders with non-idempotent
     * construction rely on. Steady state never touches the lock.
     */
    private void emitLazyResolution(CodeBuilder code, int callbackIndex, int callbackSlot,
                                    CwType callbackType) {
        int cached = code.declareLocal(CwType.OBJECT);
        code.loadThis().getField(self, lazyField + callbackIndex, CwType.OBJECT)
                .store(cached, CwType.OBJECT);

        code.load(cached, CwType.OBJECT);
        code.ifNull(() -> {
            code.loadThis();
            code.load(callbackSlot, callbackType);
            code.pushInt(callbackIndex);
            code.invokeStatic(PROXY_SUPPORT, "lazyInitialise", CwMethodType.of(
                    CwType.OBJECT, CwType.OBJECT, CwType.objectType(internal(LazyLoader.class)),
                    CwType.INT));
            code.store(cached, CwType.OBJECT);
        });
        code.load(cached, CwType.OBJECT);
    }

    /** Casts the delegate on the stack to the declaring type and forwards the call to it. */
    private void emitDelegatingCall(CodeBuilder code, ProxyMethods.Proxied proxied) {
        Method method = proxied.method();
        Class<?> declaring = method.getDeclaringClass();
        CwMethodType signature = CwMethodType.of(method);

        code.checkCast(CwType.of(declaring));
        code.loadAllArguments();
        if (declaring.isInterface()) {
            code.invokeInterface(internal(declaring), method.getName(), signature);
        } else {
            code.invokeVirtual(internal(declaring), method.getName(), signature);
        }
        code.returnValue();
    }

    /** Runs the original implementation, or reports that there is not one. */
    private void emitOriginal(CodeBuilder code, ProxyMethods.Proxied proxied) {
        Method method = proxied.method();
        if (proxied.isAbstract()) {
            emitThrow(code, "java/lang/AbstractMethodError",
                    method.getDeclaringClass().getName() + "." + method.getName()
                            + " is abstract and has no implementation to call");
            return;
        }
        Class<?> declaring = method.getDeclaringClass();
        code.loadThis().loadAllArguments();
        if (declaring.isInterface()) {
            // Interface.super.method() -- legal because declaredInterfaces() made sure the
            // interface is a direct superinterface of this class.
            code.invokeSpecial(internal(declaring), method.getName(),
                    CwMethodType.of(method), true);
        } else {
            code.invokeSpecial(superName, method.getName(), CwMethodType.of(method));
        }
        code.returnValue();
    }

    /** Converts an {@code Object} on the stack into this method's return type and returns it. */
    private static void emitReturnFromObject(CodeBuilder code, CwType returnType) {
        if (returnType.isVoid()) {
            code.pop();
            code.returnValue(CwType.VOID);
            return;
        }
        // Null becomes the zero value rather than a NullPointerException, matching CGLib: an
        // interceptor with no opinion returns null and expects 0 or false to come out.
        code.unboxOrDefault(returnType);
        code.returnValue(returnType);
    }

    /** Whether an override of this signature would duplicate a member the generator emits. */
    private boolean collidesWithGeneratedMember(String name, CwMethodType signature) {
        return collidesWithGeneratedMember(name, signature.descriptor(), spec.useFactory());
    }

    /** Static so diagnostics ({@code describeSkippedMethods}) can apply the same rule. */
    static boolean collidesWithGeneratedMember(String name, String descriptor,
                                               boolean useFactory) {
        if (name.equals("cwInvokeSuper") && descriptor.equals("(I[Ljava/lang/Object;)Ljava/lang/Object;")) {
            return true;
        }
        if (!useFactory) {
            return false;
        }
        return switch (name) {
            case "getCallback" -> descriptor.equals("(I)Lcom/classwright/proxy/Callback;");
            case "setCallback" -> descriptor.equals("(ILcom/classwright/proxy/Callback;)V");
            case "getCallbacks" -> descriptor.equals("()[Lcom/classwright/proxy/Callback;");
            case "setCallbacks" -> descriptor.equals("([Lcom/classwright/proxy/Callback;)V");
            case "newInstance" -> descriptor.equals("(Lcom/classwright/proxy/Callback;)Ljava/lang/Object;")
                    || descriptor.equals("([Lcom/classwright/proxy/Callback;)Ljava/lang/Object;")
                    || descriptor.equals(
                            "([Ljava/lang/Class;[Ljava/lang/Object;[Lcom/classwright/proxy/Callback;)Ljava/lang/Object;");
            default -> false;
        };
    }

    // ==========================================================================================
    // Super dispatch
    // ==========================================================================================

    /**
     * Generates {@link SuperDispatcher#cwInvokeSuper}: a switch whose cases call {@code super}.
     *
     * <p>This is what replaces CGLib's {@code FastClass}. One indirect jump, then a direct
     * {@code invokespecial} with the arguments unpacked from the array — no reflection, no method
     * handles, and no second generated class.
     */
    /**
     * The dispatch-splitting threshold; see {@code FastClassGenerator} for the arithmetic. A
     * proxy rarely has this many methods, but a general-purpose library must not fall over on
     * the class that does — a single switch past a thousand-odd cases breaches the JVM's
     * 65,535-byte method-code limit.
     */
    private static final int DISPATCH_CHUNK = 512;
    private static final int DISPATCH_SHIFT = Integer.numberOfTrailingZeros(DISPATCH_CHUNK);

    private void generateSuperDispatcher() {
        CwMethodType signature = CwMethodType.of(CwType.OBJECT, CwType.INT, CwType.OBJECT_ARRAY);
        List<ProxyMethods.Proxied> proxied = spec.methods().proxied();

        if (proxied.isEmpty()) {
            CodeBuilder code = writer
                    .method(AccessFlags.PUBLIC | AccessFlags.SYNTHETIC, "cwInvokeSuper",
                            signature)
                    .code();
            emitThrow(code, "java/lang/IllegalArgumentException", "this proxy overrides no methods");
            return;
        }

        int argumentsSlot = signature.parameterSlot(1, true);
        if (proxied.size() <= DISPATCH_CHUNK) {
            CodeBuilder code = writer
                    .method(AccessFlags.PUBLIC | AccessFlags.SYNTHETIC, "cwInvokeSuper",
                            signature)
                    .code();
            code.loadArgument(0);
            code.tableSwitch(0, proxied.size() - 1,
                    index -> emitSuperCase(code, proxied.get(index), argumentsSlot),
                    () -> emitThrow(code, "java/lang/IllegalArgumentException",
                            "no proxied method at that index"));
            return;
        }

        // Chunked: the outer method shifts the index to pick a chunk, each chunk switches over
        // its own range. Dispatch stays constant-time; no method approaches the code limit.
        String chunkPrefix = spec.naming().memberPrefix() + "invokeSuper$chunk";
        int chunks = (proxied.size() + DISPATCH_CHUNK - 1) / DISPATCH_CHUNK;
        CodeBuilder outer = writer
                .method(AccessFlags.PUBLIC | AccessFlags.SYNTHETIC, "cwInvokeSuper", signature)
                .code();
        outer.loadArgument(0).pushInt(DISPATCH_SHIFT).shiftRightUnsigned();
        outer.tableSwitch(0, chunks - 1,
                chunk -> {
                    outer.loadThis().loadAllArguments();
                    outer.invokeVirtual(self, chunkPrefix + chunk, signature);
                    outer.returnValue(CwType.OBJECT);
                },
                () -> emitThrow(outer, "java/lang/IllegalArgumentException",
                        "no proxied method at that index"));
        for (int chunk = 0; chunk < chunks; chunk++) {
            int from = chunk * DISPATCH_CHUNK;
            int to = Math.min(proxied.size() - 1, from + DISPATCH_CHUNK - 1);
            CodeBuilder body = writer
                    .method(AccessFlags.PUBLIC | AccessFlags.SYNTHETIC, chunkPrefix + chunk,
                            signature)
                    .code();
            body.loadArgument(0);
            body.tableSwitch(from, to,
                    index -> emitSuperCase(body, proxied.get(index), argumentsSlot),
                    () -> emitThrow(body, "java/lang/IllegalArgumentException",
                            "no proxied method at that index"));
        }
    }

    private void emitSuperCase(CodeBuilder code, ProxyMethods.Proxied proxied, int argumentsSlot) {
        Method method = proxied.method();
        if (proxied.isAbstract()) {
            emitThrow(code, "java/lang/AbstractMethodError",
                    method.getName() + " is abstract; there is no original implementation");
            return;
        }

        Class<?> declaring = method.getDeclaringClass();
        CwMethodType signature = CwMethodType.of(method);

        code.loadThis();
        code.unpackArrayIntoArguments(argumentsSlot, signature.parameterTypes());
        if (declaring.isInterface()) {
            code.invokeSpecial(internal(declaring), method.getName(), signature, true);
        } else {
            code.invokeSpecial(superName, method.getName(), signature);
        }

        CwType returnType = signature.returnType();
        if (returnType.isVoid()) {
            code.pushNull();
        } else {
            code.box(returnType);
        }
        code.returnValue(CwType.OBJECT);
    }

    // ==========================================================================================
    // Factory
    // ==========================================================================================

    private void generateFactory() {
        generateGetCallback();
        generateSetCallback();
        generateGetCallbacks();
        generateSetCallbacks();
        generateNewInstanceVariants();
    }

    private void generateGetCallback() {
        CodeBuilder code = writer
                .method(AccessFlags.PUBLIC, "getCallback", CwMethodType.of(CALLBACK, CwType.INT))
                .code();
        code.loadArgument(0);
        code.tableSwitch(0, spec.callbackCount() - 1,
                index -> {
                    CwType type = CwType.objectType(internal(spec.callbackTypes().get(index)));
                    code.loadThis().getField(self, callbackField + index, type)
                            .returnValue(CALLBACK);
                },
                () -> code.pushNull().returnValue(CALLBACK));
    }

    private void generateSetCallback() {
        CodeBuilder code = writer
                .method(AccessFlags.PUBLIC, "setCallback",
                        CwMethodType.of(CwType.VOID, CwType.INT, CALLBACK))
                .code();
        code.loadArgument(0);
        code.tableSwitch(0, spec.callbackCount() - 1,
                index -> {
                    Class<?> callbackType = spec.callbackTypes().get(index);
                    CwType type = CwType.objectType(internal(callbackType));
                    code.loadThis().loadArgument(1);
                    emitCallbackTypeCheck(code, callbackType);
                    code.checkCast(type).putField(self, callbackField + index, type);
                    code.returnValue(CwType.VOID);
                },
                () -> code.returnValue(CwType.VOID));
    }

    private void generateGetCallbacks() {
        CodeBuilder code = writer
                .method(AccessFlags.PUBLIC, "getCallbacks", CwMethodType.of(CALLBACK_ARRAY))
                .code();
        code.pushInt(spec.callbackCount()).newArray(CALLBACK);
        for (int i = 0; i < spec.callbackCount(); i++) {
            CwType type = CwType.objectType(internal(spec.callbackTypes().get(i)));
            code.dup().pushInt(i).loadThis().getField(self, callbackField + i, type)
                    .arrayStore(CALLBACK);
        }
        code.returnValue(CALLBACK_ARRAY);
    }

    private void generateSetCallbacks() {
        CodeBuilder code = writer
                .method(AccessFlags.PUBLIC, "setCallbacks",
                        CwMethodType.of(CwType.VOID, CALLBACK_ARRAY))
                .code();
        for (int i = 0; i < spec.callbackCount(); i++) {
            Class<?> callbackType = spec.callbackTypes().get(i);
            CwType type = CwType.objectType(internal(callbackType));
            code.loadThis().loadArgument(0).pushInt(i).arrayLoad(CALLBACK);
            emitCallbackTypeCheck(code, callbackType);
            code.checkCast(type).putField(self, callbackField + i, type);
        }
        code.returnValue(CwType.VOID);
    }

    /**
     * The three {@code newInstance} overloads, all delegating to {@link ProxySupport}.
     *
     * <p>Constructor selection in bytecode would be a great deal of generated code for something
     * that runs once per instance rather than once per call. Doing it in Java keeps the generator
     * small, and a smaller generator is one whose mistakes are not {@code VerifyError}s.
     */
    private void generateNewInstanceVariants() {
        CwMethodType support = CwMethodType.of(CwType.OBJECT, LOOKUP, CLASS_ARRAY,
                CwType.OBJECT_ARRAY, CALLBACK_ARRAY);

        CodeBuilder single = writer
                .method(AccessFlags.PUBLIC, "newInstance", CwMethodType.of(CwType.OBJECT, CALLBACK))
                .code();
        single.getStatic(self, LOOKUP_FIELD, LOOKUP);
        single.pushInt(0).newArray(CwType.CLASS);
        single.pushInt(0).newArray(CwType.OBJECT);
        single.pushInt(1).newArray(CALLBACK).dup().pushInt(0).loadArgument(0).arrayStore(CALLBACK);
        single.invokeStatic(PROXY_SUPPORT, "newInstance", support).returnValue(CwType.OBJECT);

        CodeBuilder array = writer
                .method(AccessFlags.PUBLIC, "newInstance",
                        CwMethodType.of(CwType.OBJECT, CALLBACK_ARRAY))
                .code();
        array.getStatic(self, LOOKUP_FIELD, LOOKUP);
        array.pushInt(0).newArray(CwType.CLASS);
        array.pushInt(0).newArray(CwType.OBJECT);
        array.loadArgument(0);
        array.invokeStatic(PROXY_SUPPORT, "newInstance", support).returnValue(CwType.OBJECT);

        CodeBuilder full = writer
                .method(AccessFlags.PUBLIC, "newInstance", CwMethodType.of(CwType.OBJECT,
                        CLASS_ARRAY, CwType.OBJECT_ARRAY, CALLBACK_ARRAY))
                .code();
        full.getStatic(self, LOOKUP_FIELD, LOOKUP);
        full.loadAllArguments();
        full.invokeStatic(PROXY_SUPPORT, "newInstance", support).returnValue(CwType.OBJECT);
    }

    // ==========================================================================================
    // Helpers
    // ==========================================================================================

    /**
     * Routes a replacement callback through the type check, leaving it on the stack.
     *
     * <p>Only used by the {@link Factory} setters, which are not a hot path. The check turns a bare
     * {@link ClassCastException} from generated code into an explanation of why callback types
     * cannot be swapped.
     */
    private static void emitCallbackTypeCheck(CodeBuilder code, Class<?> callbackType) {
        code.pushClassConstant(CwType.objectType(internal(callbackType)));
        code.invokeStatic(PROXY_SUPPORT, "requireCallbackType",
                CwMethodType.of(CALLBACK, CALLBACK, CwType.CLASS));
    }

    private static void emitThrow(CodeBuilder code, String exceptionInternalName, String message) {
        CwType exception = CwType.objectType(exceptionInternalName);
        code.newInstance(exception)
                .dup()
                .pushString(message)
                .invokeConstructor(exceptionInternalName,
                        CwMethodType.of(CwType.VOID, CwType.STRING))
                .throwException();
    }

    /** Keeps the original visibility; a proxy must not widen or narrow access. */
    private static int visibilityOf(int modifiers) {
        return modifiers & (AccessFlags.PUBLIC | AccessFlags.PROTECTED | AccessFlags.PRIVATE);
    }

    private static String internal(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    /** Whether a constructor can be called from a subclass in the given package. */
    static boolean isMirrorable(Constructor<?> constructor, boolean samePackage) {
        int modifiers = constructor.getModifiers();
        if (Modifier.isPrivate(modifiers)) {
            return false;
        }
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers) || samePackage;
    }

    /** The superclass constructors a proxy should mirror. */
    static List<Constructor<?>> mirrorableConstructors(Class<?> superclass, boolean samePackage) {
        List<Constructor<?>> constructors = new ArrayList<>();
        for (Constructor<?> constructor : superclass.getDeclaredConstructors()) {
            if (isMirrorable(constructor, samePackage)) {
                constructors.add(constructor);
            }
        }
        return constructors;
    }
}
