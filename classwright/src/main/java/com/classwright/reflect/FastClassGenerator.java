package com.classwright.reflect;

import com.classwright.ClasswrightException;
import com.classwright.core.AccessFlags;
import com.classwright.core.CodeBuilder;
import com.classwright.core.CwClassWriter;
import com.classwright.core.CwMethodType;
import com.classwright.core.CwType;
import com.classwright.runtime.ClassDefiner;
import com.classwright.runtime.DefinedClass;
import com.classwright.runtime.Instantiator;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * Emits the {@link FastClass} subclass for one target.
 *
 * <p>Two {@code tableswitch} methods, one for calls and one for construction. Each case unpacks the
 * argument array into typed values, performs a direct call, and boxes the result.
 *
 * <p>The {@link java.lang.reflect.InvocationTargetException} contract is honoured by <em>one</em>
 * shared {@code Throwable} handler around each switch, with an explicit bounds check ahead of the
 * protected range so an out-of-range index reports as an {@link IllegalArgumentException} rather
 * than being wrapped as though the target method had raised it. This is CGLib's compact shape; an
 * earlier revision emitted a handler per case, which was semantically identical but roughly
 * tripled the generated dispatch method — and dispatch size is JIT parsing and inlining budget,
 * which for an invocation accessor is the product itself.
 */
final class FastClassGenerator {

    private static final String FAST_CLASS = internal(FastClass.class);
    private static final String INVOCATION_TARGET = "java/lang/reflect/InvocationTargetException";

    private FastClassGenerator() {
    }

    /**
     * Generates, defines, and initialises the accessor for {@code target}.
     *
     * @param target the class whose members should be callable
     * @return a ready-to-use accessor
     */
    static FastClass generate(Class<?> target) {
        ClassDefiner definer = ClassDefiner.alongside(target);
        boolean samePackage = definer.canOverridePackagePrivate();

        List<Method> methods = FastClass.callableMethods(target, samePackage);
        List<Constructor<?>> constructors = FastClass.callableConstructors(target, samePackage);

        byte[] classBytes = emit(definer.generatedNameFor(target, "$$FastCW"), target,
                methods, constructors);
        DefinedClass defined = definer.define(classBytes);

        Object instance = Instantiator.forClass(defined).newInstance();
        if (!(instance instanceof FastClass fastClass)) {
            throw new ClasswrightException("the generated accessor for " + target.getName()
                    + " is not a FastClass, which should be impossible");
        }
        fastClass.initialise(target, methods, constructors);
        return fastClass;
    }

    private static byte[] emit(String self, Class<?> target, List<Method> methods,
                               List<Constructor<?>> constructors) {
        CwClassWriter writer = CwClassWriter
                .of(AccessFlags.PUBLIC | AccessFlags.SUPER, self, FAST_CLASS)
                .sourceFile(target.getSimpleName() + "$$FastCW.java");

        writer.constructor(AccessFlags.PUBLIC, CwMethodType.of(CwType.VOID))
                .code()
                .loadThis()
                .invokeConstructor(FAST_CLASS, CwMethodType.of(CwType.VOID))
                .returnValue();

        emitInvoke(writer, self, target, methods);
        emitNewInstance(writer, self, target, constructors);
        return writer.toByteArray();
    }

    // ==========================================================================================
    // invoke(int, Object, Object[])
    // ==========================================================================================

    /**
     * The dispatch-splitting threshold. A case body runs 30-80 bytes plus exception-table
     * entries, so a single switch stops fitting the JVM's 65,535-byte method-code limit somewhere
     * past a thousand cases; 512 keeps every generated method comfortably inside it. A power of
     * two, so selecting a chunk is one unsigned shift.
     */
    private static final int DISPATCH_CHUNK = 512;
    private static final int DISPATCH_SHIFT = Integer.numberOfTrailingZeros(DISPATCH_CHUNK);

    private interface CaseEmitter {
        void emit(CodeBuilder code, int index);
    }

    /**
     * Emits an index dispatcher, splitting into chunk methods when one switch would breach the
     * method-code limit.
     *
     * <p>The exception shape is CGLib's, on purpose: a bounds check up front — outside any
     * protected range, so an invalid index surfaces as {@link IllegalArgumentException} rather
     * than being wrapped as though the target threw it — and then <em>one</em> shared
     * {@code Throwable} handler around the whole switch, wrapping in
     * {@code InvocationTargetException}. An earlier revision gave every case its own handler,
     * which kept the same semantics but tripled the generated method's size (a handler body per
     * case plus an exception-table entry per case); dispatch size is JIT parsing and inlining
     * budget, and this method is the entire point of the class.
     *
     * <p>The split preserves constant-time dispatch: the outer method shifts the index to select
     * a chunk method, and each chunk holds a bounded {@code tableswitch}. The bounds check and
     * the shared handler live in the outer method only, so chunks carry no exception machinery
     * at all.
     */
    private static void emitDispatch(CwClassWriter writer, String self, String methodName,
                                     CwMethodType signature, int count, String defaultMessage,
                                     CaseEmitter caseEmitter) {
        if (count <= DISPATCH_CHUNK) {
            CodeBuilder code = writer
                    .method(AccessFlags.PUBLIC, methodName, signature)
                    .throwsException(INVOCATION_TARGET)
                    .code();
            emitBoundsCheck(code, count, defaultMessage);
            code.tryCatch(
                    () -> {
                        code.loadArgument(0);
                        code.tableSwitch(0, count - 1,
                                index -> caseEmitter.emit(code, index),
                                // Unreachable after the bounds check; the verifier still needs a
                                // default that does not fall through.
                                () -> emitThrow(code, "java/lang/IllegalArgumentException",
                                        defaultMessage));
                    },
                    CwType.THROWABLE,
                    () -> emitWrapAndThrow(code));
            return;
        }

        int chunks = (count + DISPATCH_CHUNK - 1) / DISPATCH_CHUNK;
        CodeBuilder outer = writer
                .method(AccessFlags.PUBLIC, methodName, signature)
                .throwsException(INVOCATION_TARGET)
                .code();
        emitBoundsCheck(outer, count, defaultMessage);
        outer.tryCatch(
                () -> {
                    outer.loadArgument(0).pushInt(DISPATCH_SHIFT).shiftRightUnsigned();
                    outer.tableSwitch(0, chunks - 1,
                            chunk -> {
                                outer.loadThis().loadAllArguments();
                                outer.invokeVirtual(self, methodName + "$chunk" + chunk,
                                        signature);
                                outer.returnValue(CwType.OBJECT);
                            },
                            () -> emitThrow(outer, "java/lang/IllegalArgumentException",
                                    defaultMessage));
                },
                CwType.THROWABLE,
                () -> emitWrapAndThrow(outer));

        for (int chunk = 0; chunk < chunks; chunk++) {
            int from = chunk * DISPATCH_CHUNK;
            int to = Math.min(count - 1, from + DISPATCH_CHUNK - 1);
            CodeBuilder body = writer
                    .method(AccessFlags.PUBLIC | AccessFlags.SYNTHETIC,
                            methodName + "$chunk" + chunk, signature)
                    .throwsException(INVOCATION_TARGET)
                    .code();
            body.loadArgument(0);
            body.tableSwitch(from, to,
                    index -> caseEmitter.emit(body, index),
                    () -> emitThrow(body, "java/lang/IllegalArgumentException", defaultMessage));
        }
    }

    /**
     * Rejects an out-of-range index before the shared handler's protected range begins.
     *
     * <p>Two zero-comparisons; both branches are perfectly predicted for every valid call, and
     * placing the rejection here is what lets the switch share one {@code Throwable} handler
     * without wrapping its own invalid-index exception.
     */
    private static void emitBoundsCheck(CodeBuilder code, int count, String message) {
        code.loadArgument(0);
        code.ifIntComparison(CodeBuilder.IntTest.LESS_THAN_ZERO,
                () -> emitThrow(code, "java/lang/IllegalArgumentException", message),
                () -> { });
        code.loadArgument(0).pushInt(count).subtract(CwType.INT);
        code.ifIntComparison(CodeBuilder.IntTest.AT_LEAST_ZERO,
                () -> emitThrow(code, "java/lang/IllegalArgumentException", message),
                () -> { });
    }

    private static void emitInvoke(CwClassWriter writer, String self, Class<?> target,
                                   List<Method> methods) {
        CwMethodType signature = CwMethodType.of(CwType.OBJECT, CwType.INT, CwType.OBJECT,
                CwType.OBJECT_ARRAY);
        if (methods.isEmpty()) {
            CodeBuilder code = writer
                    .method(AccessFlags.PUBLIC, "invoke", signature)
                    .throwsException(INVOCATION_TARGET)
                    .code();
            emitThrow(code, "java/lang/IllegalArgumentException",
                    target.getName() + " has no methods reachable from generated code");
            return;
        }

        int argumentsSlot = signature.parameterSlot(2, true);
        emitDispatch(writer, self, "invoke", signature, methods.size(),
                "no method at that index on " + target.getName(),
                (code, index) -> emitCall(code, target, methods.get(index), argumentsSlot));
    }

    private static void emitCall(CodeBuilder code, Class<?> target, Method method,
                                 int argumentsSlot) {
        CwMethodType signature = CwMethodType.of(method);
        boolean isStatic = Modifier.isStatic(method.getModifiers());

        // No per-case handler: the dispatcher wraps the whole switch in one shared Throwable
        // handler (see emitDispatch), which is what keeps N cases from costing N handlers.
        if (!isStatic) {
            // Cast to the target rather than the declaring class: resolution walks up
            // from there, and for a protected member the receiver type is what the
            // access check looks at.
            code.loadArgument(1).checkCast(CwType.of(target));
        }
        code.unpackArrayIntoArguments(argumentsSlot, signature.parameterTypes());

        if (isStatic) {
            // The declaring class may be a superinterface: interfaces declare static
            // methods, and referencing one through a plain Methodref fails at link
            // time with IncompatibleClassChangeError (JVMS 5.4.3.3).
            code.invokeStatic(internal(method.getDeclaringClass()), method.getName(),
                    signature, method.getDeclaringClass().isInterface());
        } else if (target.isInterface()) {
            code.invokeInterface(internal(target), method.getName(), signature);
        } else {
            code.invokeVirtual(internal(target), method.getName(), signature);
        }

        if (signature.returnType().isVoid()) {
            code.pushNull();
        } else {
            code.box(signature.returnType());
        }
        code.returnValue(CwType.OBJECT);
    }

    // ==========================================================================================
    // newInstance(int, Object[])
    // ==========================================================================================

    private static void emitNewInstance(CwClassWriter writer, String self, Class<?> target,
                                        List<Constructor<?>> constructors) {
        CwMethodType signature = CwMethodType.of(CwType.OBJECT, CwType.INT, CwType.OBJECT_ARRAY);
        if (constructors.isEmpty()) {
            CodeBuilder code = writer
                    .method(AccessFlags.PUBLIC, "newInstance", signature)
                    .throwsException(INVOCATION_TARGET)
                    .code();
            emitThrow(code, "java/lang/IllegalArgumentException",
                    target.getName() + " has no constructor reachable from generated code"
                            + (Modifier.isAbstract(target.getModifiers())
                            ? " (it is abstract)" : ""));
            return;
        }

        int argumentsSlot = signature.parameterSlot(1, true);
        emitDispatch(writer, self, "newInstance", signature, constructors.size(),
                "no constructor at that index on " + target.getName(),
                (code, index) ->
                        emitConstruct(code, target, constructors.get(index), argumentsSlot));
    }

    private static void emitConstruct(CodeBuilder code, Class<?> target, Constructor<?> constructor,
                                      int argumentsSlot) {
        CwMethodType signature = CwMethodType.of(constructor);

        code.newInstance(CwType.of(target));
        code.dup();
        code.unpackArrayIntoArguments(argumentsSlot, signature.parameterTypes());
        code.invokeConstructor(internal(target), signature);
        code.returnValue(CwType.OBJECT);
    }

    // ==========================================================================================
    // Helpers
    // ==========================================================================================

    /**
     * Wraps the caught throwable in an {@link java.lang.reflect.InvocationTargetException}.
     *
     * <p>Matches {@link Method#invoke}, and matches CGLib. The distinction it preserves is worth
     * keeping: a failure that came out of the target method looks different from a failure of the
     * dispatch machinery itself.
     */
    private static void emitWrapAndThrow(CodeBuilder code) {
        int caught = code.declareLocal(CwType.THROWABLE);
        code.store(caught, CwType.THROWABLE);
        code.newInstance(CwType.objectType(INVOCATION_TARGET))
                .dup()
                .load(caught, CwType.THROWABLE)
                .invokeConstructor(INVOCATION_TARGET,
                        CwMethodType.of(CwType.VOID, CwType.THROWABLE))
                .throwException();
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

    private static String internal(Class<?> type) {
        return type.getName().replace('.', '/');
    }
}
