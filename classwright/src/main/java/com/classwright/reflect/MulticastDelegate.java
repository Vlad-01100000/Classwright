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
import java.util.ArrayList;
import java.util.List;

/**
 * Fans one call out to several implementations of an interface.
 *
 * <pre>{@code
 * MulticastDelegate delegate = MulticastDelegate.create(ChangeListener.class);
 * delegate = delegate.add(first).add(second);
 * ((ChangeListener) delegate).changed("x");    // both are called, in order
 * }</pre>
 *
 * <p>The event-listener pattern, with the fan-out compiled rather than interpreted: the generated
 * method loops over the targets and calls each through a plain interface dispatch.
 *
 * <p>{@link #add} and {@link #remove} return a <em>new</em> delegate and leave this one untouched.
 * That is what makes it safe to hand a delegate to another thread while listeners are still being
 * registered, and it matches CGLib.
 *
 * <p>Where the interface method returns a value, the result of the <em>last</em> target is
 * returned. With no targets at all, the return is the type's zero value, and for {@code void}
 * nothing happens.
 */
public abstract class MulticastDelegate {

    /**
     * Volatile because the class doc promises a delegate can be handed to another thread: the
     * fields are assigned after the constructor returns, so without a fence a reader could see a
     * half-published delegate. One volatile read per fan-out is noise next to the calls it makes.
     */
    private volatile Object[] targets = new Object[0];
    private volatile Class<?> interfaceType;

    /**
     * For generated subclasses. Use {@link #create} to obtain an instance.
     */
    protected MulticastDelegate() {
    }

    /**
     * Creates an empty delegate for a single-method interface.
     *
     * @param interfaceType the interface to fan out
     * @return an object implementing {@code interfaceType} with no targets yet
     */
    public static MulticastDelegate create(Class<?> interfaceType) {
        // One generated fan-out class per interface, cached; CGLib cached these too, and event
        // wiring that calls create() per registration relies on it.
        Class<?> generated = GenerationCache.computeIfAbsent(interfaceType, "MulticastDelegate",
                () -> defineFanOut(interfaceType));
        MulticastDelegate delegate =
                (MulticastDelegate) Instantiator.forGenerated(generated).newInstance();
        delegate.interfaceType = interfaceType;
        return delegate;
    }

    private static Class<?> defineFanOut(Class<?> interfaceType) {
        Method interfaceMethod = Delegates.singleAbstractMethod(interfaceType);

        ClassDefiner definer = ClassDefiner.alongside(interfaceType);
        byte[] classBytes = emit(definer.generatedNameFor(interfaceType, "$$MC"), interfaceType,
                interfaceMethod);
        DefinedClass defined = definer.define(classBytes);
        Instantiator.register(defined);
        return defined.type();
    }

    /**
     * Generates the fan-out.
     *
     * <p>The body is:
     * <pre>{@code
     * Object[] targets = getTargets();
     * R result = <zero>;
     * int i = 0;
     * while (i - targets.length != 0) {
     *     result = ((Iface) targets[i]).method(args);
     *     i = i + 1;
     * }
     * return result;
     * }</pre>
     *
     * <p>The loop condition is a subtraction because the engine compares against zero rather than
     * between two values. Counting up from zero to the length means {@code i - length} is non-zero
     * for exactly the iterations wanted.
     */
    private static byte[] emit(String self, Class<?> interfaceType, Method interfaceMethod) {
        CwMethodType signature = CwMethodType.of(interfaceMethod);
        CwType returnType = signature.returnType();

        CwClassWriter writer = CwClassWriter
                .of(AccessFlags.PUBLIC | AccessFlags.SUPER, self,
                        internal(MulticastDelegate.class), internal(interfaceType))
                .sourceFile(interfaceType.getSimpleName() + "$$MC.java");

        writer.constructor(AccessFlags.PUBLIC, CwMethodType.of(CwType.VOID))
                .code()
                .loadThis()
                .invokeConstructor(internal(MulticastDelegate.class), CwMethodType.of(CwType.VOID))
                .returnValue();

        CodeBuilder code = writer
                .method(AccessFlags.PUBLIC, interfaceMethod.getName(), signature)
                .code();

        int targetsSlot = code.declareLocal(CwType.OBJECT_ARRAY);
        int indexSlot = code.declareLocal(CwType.INT);
        int resultSlot = returnType.isVoid() ? -1 : code.declareLocal(returnType);

        code.loadThis()
                .invokeVirtual(internal(MulticastDelegate.class), "getTargets",
                        CwMethodType.of(CwType.OBJECT_ARRAY))
                .store(targetsSlot, CwType.OBJECT_ARRAY);
        code.pushInt(0).store(indexSlot, CwType.INT);
        if (resultSlot >= 0) {
            code.pushDefault(returnType).store(resultSlot, returnType);
        }

        code.whileLoop(
                () -> code.load(indexSlot, CwType.INT)
                        .load(targetsSlot, CwType.OBJECT_ARRAY).arrayLength()
                        .subtract(CwType.INT),
                () -> {
                    code.load(targetsSlot, CwType.OBJECT_ARRAY)
                            .load(indexSlot, CwType.INT)
                            .arrayLoad(CwType.OBJECT)
                            .checkCast(CwType.of(interfaceType));
                    code.loadAllArguments();
                    code.invokeInterface(internal(interfaceType), interfaceMethod.getName(),
                            signature);
                    if (resultSlot >= 0) {
                        code.store(resultSlot, returnType);
                    }
                    code.load(indexSlot, CwType.INT).pushInt(1).add(CwType.INT)
                            .store(indexSlot, CwType.INT);
                });

        if (resultSlot >= 0) {
            code.load(resultSlot, returnType);
        }
        code.returnValue(returnType);

        return writer.toByteArray();
    }

    /**
     * The current targets, in call order. Never {@code null}.
     *
     * @return the objects calls are forwarded to, in the order they were added
     */
    public final Object[] getTargets() {
        return targets;
    }

    /**
     * How many targets this delegate will call.
     *
     * @return how many targets there are
     */
    public final int size() {
        return targets.length;
    }

    /**
     * A new delegate with {@code target} appended.
     *
     * @param target an implementation of the interface this delegate fans out
     * @return a new delegate; this one is unchanged
     */
    public MulticastDelegate add(Object target) {
        if (target == null) {
            throw new ClasswrightException("target must not be null");
        }
        if (!interfaceType.isInstance(target)) {
            throw new ClasswrightException(target.getClass().getName() + " does not implement "
                    + interfaceType.getName() + ", so it cannot be added to this delegate");
        }
        Object[] snapshot = targets;
        Object[] extended = java.util.Arrays.copyOf(snapshot, snapshot.length + 1);
        extended[snapshot.length] = target;
        return withTargets(extended);
    }

    /**
     * A new delegate with the last occurrence of {@code target} removed.
     *
     * @param target the target to remove; absent targets are ignored
     * @return a new delegate; this one is unchanged
     */
    public MulticastDelegate remove(Object target) {
        List<Object> remaining = new ArrayList<>(List.of(targets));
        for (int i = remaining.size() - 1; i >= 0; i--) {
            if (remaining.get(i) == target) {
                remaining.remove(i);
                break;
            }
        }
        return withTargets(remaining.toArray());
    }

    private MulticastDelegate withTargets(Object[] newTargets) {
        MulticastDelegate copy =
                (MulticastDelegate) Instantiator.forGenerated(getClass()).newInstance();
        copy.targets = newTargets;
        copy.interfaceType = interfaceType;
        return copy;
    }

    private static String internal(Class<?> type) {
        return type.getName().replace('.', '/');
    }
}
