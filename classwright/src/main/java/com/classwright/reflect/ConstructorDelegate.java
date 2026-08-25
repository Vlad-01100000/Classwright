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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Binds a constructor to a single-method interface, turning it into a factory.
 *
 * <pre>{@code
 * interface OrderFactory { Order create(String id); }
 *
 * OrderFactory factory = (OrderFactory) ConstructorDelegate.create(
 *         Order.class, OrderFactory.class);
 * Order order = factory.create("A-1");     // calls new Order("A-1")
 * }</pre>
 *
 * <p>A constructor reference covers the same ground when the types are known at compile time. This
 * is for when the class is chosen at runtime, and it produces a real {@code new} followed by
 * {@code invokespecial} rather than a reflective call.
 */
public abstract class ConstructorDelegate {

    /**
     * For generated subclasses. Use {@link #create} to obtain an instance.
     */
    protected ConstructorDelegate() {
    }

    /**
     * Binds the constructor whose parameters match the interface method.
     *
     * @param targetClass   the class to construct
     * @param interfaceType a single-method interface; its parameters select the constructor and
     *                      its return type must accept the constructed object
     * @return an object implementing {@code interfaceType}
     */
    public static ConstructorDelegate create(Class<?> targetClass, Class<?> interfaceType) {
        // One generated factory class per (target, interface), cached like every other generator.
        // The interface is held weakly in the key — a raw Class here would let a long-lived
        // anchor pin the interface's loader.
        Class<?> generated = GenerationCache.computeIfAbsent(targetClass,
                new FactoryKey(GenerationCache.WeakPart.of(interfaceType)),
                () -> defineFactory(targetClass, interfaceType));
        return (ConstructorDelegate) Instantiator.forGenerated(generated).newInstance();
    }

    /** The generation-cache key: just the interface; the target class is the anchor. */
    private record FactoryKey(GenerationCache.WeakPart interfaceType)
            implements GenerationCache.StaleKey {

        @Override
        public boolean isStale() {
            return interfaceType.isStale();
        }
    }

    private static Class<?> defineFactory(Class<?> targetClass, Class<?> interfaceType) {
        Method factoryMethod = Delegates.singleAbstractMethod(interfaceType);

        if (targetClass.isInterface() || Modifier.isAbstract(targetClass.getModifiers())) {
            throw new ClasswrightException(targetClass.getName()
                    + " is abstract and cannot be constructed");
        }
        Constructor<?> constructor;
        try {
            constructor = targetClass.getConstructor(factoryMethod.getParameterTypes());
        } catch (NoSuchMethodException e) {
            throw new ClasswrightException(targetClass.getName()
                    + " has no public constructor matching "
                    + interfaceType.getSimpleName() + "." + factoryMethod.getName()
                    + ". The interface method's parameters select the constructor, so they must "
                    + "match exactly.", e);
        }
        Delegates.requireCompatibleReturn(targetClass, factoryMethod);

        ClassDefiner definer = ClassDefiner.alongside(targetClass);
        byte[] classBytes = emit(definer.generatedNameFor(targetClass, "$$CD"), targetClass,
                interfaceType, factoryMethod, constructor);
        DefinedClass defined = definer.define(classBytes);
        Instantiator.register(defined);
        return defined.type();
    }

    private static byte[] emit(String self, Class<?> targetClass, Class<?> interfaceType,
                               Method factoryMethod, Constructor<?> constructor) {
        CwMethodType signature = CwMethodType.of(factoryMethod);

        CwClassWriter writer = CwClassWriter
                .of(AccessFlags.PUBLIC | AccessFlags.SUPER, self,
                        internal(ConstructorDelegate.class), internal(interfaceType))
                .sourceFile(targetClass.getSimpleName() + "$$CD.java");

        writer.constructor(AccessFlags.PUBLIC, CwMethodType.of(CwType.VOID))
                .code()
                .loadThis()
                .invokeConstructor(internal(ConstructorDelegate.class),
                        CwMethodType.of(CwType.VOID))
                .returnValue();

        CodeBuilder code = writer
                .method(AccessFlags.PUBLIC, factoryMethod.getName(), signature)
                .code();
        code.newInstance(CwType.of(targetClass))
                .dup()
                .loadAllArguments()
                .invokeConstructor(internal(targetClass), CwMethodType.of(constructor))
                .returnValue(signature.returnType());

        return writer.toByteArray();
    }

    private static String internal(Class<?> type) {
        return type.getName().replace('.', '/');
    }
}
