package net.sf.cglib.reflect;

import com.classwright.cglib.Coexistence;
import net.sf.cglib.core.Signature;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Calls a class's methods and constructors without reflection.
 *
 * <p>Reproduces {@code net.sf.cglib.reflect.FastClass}, delegating to
 * {@link com.classwright.reflect.FastClass}.
 *
 * <p>The {@code ClassLoader} argument on {@link #create(ClassLoader, Class)} is accepted and
 * ignored: Classwright places the generated accessor beside its target, which is both more reliable
 * and the only thing that works for a hidden class.
 *
 * @see com.classwright.reflect.FastClass
 */
public class FastClass {

    static {
        Coexistence.check();
    }

    private final com.classwright.reflect.FastClass delegate;

    private FastClass(com.classwright.reflect.FastClass delegate) {
        this.delegate = delegate;
    }

    /**
     * A {@code FastClass} for {@code type}.
     *
     * <p>A fresh wrapper per call, deliberately: real CGLib returns <em>distinct</em> objects
     * from repeated {@code create(X)} that compare equal by target class
     * ({@code a != b}, {@code a.equals(b)}). An earlier revision cached one wrapper per class,
     * which made {@code ==} true — a stronger identity than CGLib's that migrated code could
     * accidentally start depending on, and the reverse migration hazard of the one this layer
     * exists to avoid. The expensive part — the generated accessor — is the native delegate,
     * which stays cached per class; the wrapper is two fields.
     *
     * @param type the class to index
     * @return a fast class; distinct instances for the same class compare equal, as in CGLib
     */
    public static FastClass create(Class type) {
        return new FastClass(com.classwright.reflect.FastClass.create(type));
    }

    /**
     * Value equality on the target class, as CGLib's was.
     *
     * @param other another object
     * @return whether both index the same class
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof FastClass that
                && delegate.getJavaClass().equals(that.delegate.getJavaClass());
    }

    @Override
    public int hashCode() {
        return delegate.getJavaClass().hashCode();
    }

    /**
     * The loader argument is ignored; see the class documentation.
     *
     * @param loader ignored; Classwright chooses placement itself
     * @param type   the class to index
     * @return the fast class
     */
    public static FastClass create(ClassLoader loader, Class type) {
        return create(type);
    }

    /**
     * Invokes a method by index.
     *
     * @param index the method's index
     * @param obj   the receiver, or {@code null} for a static method
     * @param args  the arguments, boxed
     * @return the result, boxed
     * @throws InvocationTargetException if the method threw
     */
    public Object invoke(int index, Object obj, Object[] args) throws InvocationTargetException {
        return delegate.invoke(index, obj, args);
    }

    /**
     * Invokes a method by name and signature.
     *
     * @param name           the method name
     * @param parameterTypes its parameter types
     * @param obj            the receiver, or {@code null} for a static method
     * @param args           the arguments, boxed
     * @return the result, boxed
     * @throws InvocationTargetException if the method threw
     */
    public Object invoke(String name, Class[] parameterTypes, Object obj, Object[] args)
            throws InvocationTargetException {
        // Through this wrapper's own getIndex, so the CGLib-compatible filtering below applies
        // to the by-name route exactly as it does to the index lookups.
        return delegate.invoke(getIndex(name, parameterTypes), obj, args);
    }

    /**
     * Creates an instance using the no-argument constructor.
     *
     * @return the new instance
     * @throws InvocationTargetException if the constructor threw
     */
    public Object newInstance() throws InvocationTargetException {
        return delegate.newInstance();
    }

    /**
     * Creates an instance using the constructor at {@code index}.
     *
     * @param index the constructor's index
     * @param args  the arguments, boxed
     * @return the new instance
     * @throws InvocationTargetException if the constructor threw
     */
    public Object newInstance(int index, Object[] args) throws InvocationTargetException {
        return delegate.newInstance(index, args);
    }

    /**
     * Creates an instance using the constructor with this signature.
     *
     * @param parameterTypes the constructor signature
     * @param args           the arguments, boxed
     * @return the new instance
     * @throws InvocationTargetException if the constructor threw
     */
    public Object newInstance(Class[] parameterTypes, Object[] args)
            throws InvocationTargetException {
        return delegate.newInstance(parameterTypes, args);
    }

    /**
     * The index of a method, by name and signature.
     *
     * @param name           the method name
     * @param parameterTypes its parameter types
     * @return the index, or {@code -1} if there is no such method
     */
    public int getIndex(String name, Class[] parameterTypes) {
        return cglibVisible(delegate.getIndex(name, parameterTypes));
    }

    /**
     * Filters the native index space down to CGLib's view.
     *
     * <p>The native accessor indexes {@code Object}'s final methods — {@code getClass()},
     * {@code notify()}, {@code notifyAll()}, {@code wait(...)} — because they are perfectly
     * callable and a broader index costs nothing. CGLib's did not, and answered {@code -1} for
     * them; migrated code that probes-then-branches on {@code -1} must see CGLib's answer.
     * User-declared {@code final} methods are indexed by both libraries, so the filter is
     * exactly "final and declared by {@code Object}", nothing wider.
     */
    private int cglibVisible(int index) {
        if (index < 0) {
            return index;
        }
        Method method = delegate.getMethods().get(index);
        return method.getDeclaringClass() == Object.class
                && java.lang.reflect.Modifier.isFinal(method.getModifiers()) ? -1 : index;
    }

    /**
     * The index of a method identified by a signature.
     *
     * <p>Works with the reproduced {@link Signature}, which carries a name and a descriptor. CGLib's
     * version also exposed ASM types; see {@link Signature} for why those are absent.
     *
     * @param signature the method's name and descriptor
     * @return the index, or {@code -1} if there is no such method
     */
    public int getIndex(Signature signature) {
        return cglibVisible(delegate.getIndex(signature.getName(), signature.getDescriptor()));
    }

    /**
     * The index of a constructor.
     *
     * @param parameterTypes the constructor signature
     * @return the index, or {@code -1} if there is no such constructor
     */
    public int getIndex(Class[] parameterTypes) {
        return delegate.getConstructorIndex(parameterTypes);
    }

    /**
     * A {@link FastMethod} for a reflective method.
     *
     * @param method the method
     * @return the fast method
     */
    public FastMethod getMethod(Method method) {
        return new FastMethod(delegate.getMethod(method));
    }

    /**
     * A {@link FastMethod} by name and signature.
     *
     * @param name           the method name
     * @param parameterTypes its parameter types
     * @return the fast method
     */
    public FastMethod getMethod(String name, Class[] parameterTypes) {
        return new FastMethod(delegate.getMethod(name, parameterTypes));
    }

    /**
     * A {@link FastConstructor} for a reflective constructor.
     *
     * @param constructor the constructor
     * @return the fast constructor
     */
    public FastConstructor getConstructor(Constructor constructor) {
        return new FastConstructor(delegate.getConstructor(constructor));
    }

    /**
     * A {@link FastConstructor} by signature.
     *
     * @param parameterTypes the constructor signature
     * @return the fast constructor
     */
    public FastConstructor getConstructor(Class[] parameterTypes) {
        return new FastConstructor(delegate.getConstructor(parameterTypes));
    }

    /**
     * The name of the class this indexes.
     *
     * @return the target's binary name
     */
    public String getName() {
        return delegate.getName();
    }

    /**
     * The class this indexes.
     *
     * @return the target class
     */
    public Class getJavaClass() {
        return delegate.getJavaClass();
    }

    /**
     * The highest valid method index — inclusive, as CGLib's was.
     *
     * @return the highest valid index, so {@code 0..getMaxIndex()} are usable
     */
    public int getMaxIndex() {
        return delegate.getMaxIndex();
    }

    /**
     * A method's name and parameter descriptors, without the return type — CGLib's subclass
     * helper, reproduced.
     *
     * @param name           the method name
     * @param parameterTypes the parameter types, in order
     * @return e.g. {@code frob(ILjava/lang/String;)}
     */
    protected static String getSignatureWithoutReturnType(String name, Class[] parameterTypes) {
        StringBuilder signature = new StringBuilder(name).append('(');
        for (Class parameterType : parameterTypes) {
            appendDescriptor(signature, parameterType);
        }
        return signature.append(')').toString();
    }

    private static void appendDescriptor(StringBuilder into, Class type) {
        if (type.isArray()) {
            into.append('[');
            appendDescriptor(into, type.getComponentType());
        } else if (!type.isPrimitive()) {
            into.append('L').append(type.getName().replace('.', '/')).append(';');
        } else if (type == int.class) {
            into.append('I');
        } else if (type == boolean.class) {
            into.append('Z');
        } else if (type == byte.class) {
            into.append('B');
        } else if (type == char.class) {
            into.append('C');
        } else if (type == short.class) {
            into.append('S');
        } else if (type == long.class) {
            into.append('J');
        } else if (type == float.class) {
            into.append('F');
        } else if (type == double.class) {
            into.append('D');
        } else {
            into.append('V');
        }
    }

    @Override
    public String toString() {
        // CGLib returned the target's own representation ("class example.Target"); the richer
        // native diagnostic string stays on the native FastClass, where nothing depends on it.
        return delegate.getJavaClass().toString();
    }
}
