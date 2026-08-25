package com.classwright.reflect;

import com.classwright.ClasswrightException;
import com.classwright.core.AccessFlags;
import com.classwright.core.CodeBuilder;
import com.classwright.core.CwClassWriter;
import com.classwright.core.CwMethodType;
import com.classwright.core.CwType;
import com.classwright.runtime.ClassDefiner;
import com.classwright.runtime.DefinedClass;
import com.classwright.runtime.GenerationCache;
import com.classwright.runtime.Instantiator;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Binds one method of one object to a single-method interface.
 *
 * <pre>{@code
 * Runnable task = (Runnable) MethodDelegate.create(report, "refresh", Runnable.class);
 * task.run();     // calls report.refresh()
 * }</pre>
 *
 * <p>A method reference does the same thing in modern Java and should be preferred where the types
 * are known at compile time. This is for when they are not: the method name arrives as a string, or
 * the interface is chosen at runtime. It is also here because CGLib had it, and the resulting call
 * is a plain interface dispatch to a direct call &mdash; no reflection, no handle adaptation.
 *
 * <p>The returned object implements the requested interface; cast it. It is also a
 * {@code MethodDelegate}, so {@link #getTarget()} and {@link #newInstance(Object)} are available.
 */
public abstract class MethodDelegate {

    private Object target;

    /**
     * For generated subclasses. Use {@link #create} or {@link #createStatic}.
     */
    protected MethodDelegate() {
    }

    /**
     * Binds an instance method.
     *
     * @param target        the object to forward to
     * @param methodName    the method to call on it
     * @param interfaceType a single-method interface whose signature the method matches
     * @return an object implementing {@code interfaceType}
     */
    public static MethodDelegate create(Object target, String methodName, Class<?> interfaceType) {
        if (target == null) {
            throw new ClasswrightException("target must not be null; use createStatic for a "
                    + "static method");
        }
        return build(target.getClass(), target, methodName, interfaceType, false);
    }

    /**
     * Binds a static method.
     *
     * @param targetClass   the class declaring the method
     * @param methodName    the method to call
     * @param interfaceType a single-method interface whose signature the method matches
     * @return an object implementing {@code interfaceType}
     */
    public static MethodDelegate createStatic(Class<?> targetClass, String methodName,
                                              Class<?> interfaceType) {
        return build(targetClass, null, methodName, interfaceType, true);
    }

    private static MethodDelegate build(Class<?> targetClass, Object target, String methodName,
                                        Class<?> interfaceType, boolean expectStatic) {
        // One generated class per (target class, method, interface, static-ness); CGLib cached
        // these, and code migrated from it calls create() per event on that assumption.
        Class<?> generated = GenerationCache.computeIfAbsent(targetClass,
                new DelegateKey(methodName, GenerationCache.WeakPart.of(interfaceType),
                        expectStatic),
                () -> defineDelegate(targetClass, methodName, interfaceType, expectStatic));
        MethodDelegate delegate =
                (MethodDelegate) Instantiator.forGenerated(generated).newInstance();
        delegate.target = target;
        return delegate;
    }

    /**
     * The generation-cache key; the target class is the anchor and needs no repeating. The
     * interface is held weakly — it may belong to a shorter-lived loader than the anchor.
     */
    private record DelegateKey(String methodName, GenerationCache.WeakPart interfaceType,
                               boolean expectStatic) implements GenerationCache.StaleKey {

        @Override
        public boolean isStale() {
            return interfaceType.isStale();
        }
    }

    private static Class<?> defineDelegate(Class<?> targetClass, String methodName,
                                           Class<?> interfaceType, boolean expectStatic) {
        Method interfaceMethod = Delegates.singleAbstractMethod(interfaceType);
        Method implementation = Delegates.matchingMethod(targetClass, methodName, interfaceMethod);

        boolean isStatic = Modifier.isStatic(implementation.getModifiers());
        if (isStatic != expectStatic) {
            throw new ClasswrightException(targetClass.getName() + "." + methodName + " is "
                    + (isStatic ? "static; use createStatic()" : "an instance method; use create()"));
        }

        ClassDefiner definer = ClassDefiner.alongside(targetClass);
        byte[] classBytes = emit(definer.generatedNameFor(targetClass, "$$MD"), targetClass,
                interfaceType, interfaceMethod, implementation, isStatic);
        DefinedClass defined = definer.define(classBytes);
        Instantiator.register(defined);
        return defined.type();
    }

    /**
     * Generates a class implementing {@code interfaceType} that forwards to {@code implementation}.
     *
     * <p>The target is read through {@link #getTarget()} rather than from a field. A field would be
     * {@code protected} and declared in another package, and the JVM only permits protected access
     * on a receiver of the accessing class's own type — a rule easy to satisfy here but easy to
     * trip over later. A public accessor sidesteps it, and being {@code final} it inlines away.
     */
    private static byte[] emit(String self, Class<?> targetClass, Class<?> interfaceType,
                               Method interfaceMethod, Method implementation, boolean isStatic) {
        CwMethodType signature = CwMethodType.of(interfaceMethod);
        CwMethodType implementationSignature = CwMethodType.of(implementation);

        CwClassWriter writer = CwClassWriter
                .of(AccessFlags.PUBLIC | AccessFlags.SUPER, self, internal(MethodDelegate.class),
                        internal(interfaceType))
                .sourceFile(targetClass.getSimpleName() + "$$MD.java");

        writer.constructor(AccessFlags.PUBLIC, CwMethodType.of(CwType.VOID))
                .code()
                .loadThis()
                .invokeConstructor(internal(MethodDelegate.class), CwMethodType.of(CwType.VOID))
                .returnValue();

        CodeBuilder code = writer
                .method(AccessFlags.PUBLIC, interfaceMethod.getName(), signature)
                .code();

        if (!isStatic) {
            code.loadThis()
                    .invokeVirtual(internal(MethodDelegate.class), "getTarget",
                            CwMethodType.of(CwType.OBJECT))
                    .checkCast(CwType.of(targetClass));
        }
        code.loadAllArguments();
        if (isStatic) {
            // Interface statics need an InterfaceMethodref; a plain Methodref fails at link time
            // with IncompatibleClassChangeError (JVMS 5.4.3.3).
            code.invokeStatic(internal(implementation.getDeclaringClass()),
                    implementation.getName(), implementationSignature,
                    implementation.getDeclaringClass().isInterface());
        } else if (targetClass.isInterface()) {
            code.invokeInterface(internal(targetClass), implementation.getName(),
                    implementationSignature);
        } else {
            code.invokeVirtual(internal(targetClass), implementation.getName(),
                    implementationSignature);
        }
        code.returnValue(signature.returnType());

        return writer.toByteArray();
    }

    /**
     * The object calls are forwarded to, or {@code null} for a static delegate.
     *
     * @return the object calls are forwarded to, or {@code null} for a static delegate
     */
    public final Object getTarget() {
        return target;
    }

    /**
     * Another delegate of the same shape, bound to a different target.
     *
     * <p>Reuses the generated class, so this is far cheaper than calling {@link #create} again.
     *
     * @param target the new target; must be an instance of the same class as this delegate's
     * @return a new delegate
     */
    public MethodDelegate newInstance(Object target) {
        if (this.target != null && target != null
                && !this.target.getClass().equals(target.getClass())) {
            throw new ClasswrightException("this delegate is bound to "
                    + this.target.getClass().getName() + "; the generated class calls that type's "
                    + "method directly, so it cannot be rebound to a "
                    + target.getClass().getName());
        }
        MethodDelegate rebound =
                (MethodDelegate) Instantiator.forGenerated(getClass()).newInstance();
        rebound.target = target;
        return rebound;
    }

    private static String internal(Class<?> type) {
        return type.getName().replace('.', '/');
    }
}
