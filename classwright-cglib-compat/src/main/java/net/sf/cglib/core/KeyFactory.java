package net.sf.cglib.core;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

/**
 * Creates composite map keys from a key interface, as CGLib's {@code KeyFactory} did.
 *
 * <pre>{@code
 * interface OrderKey { Object newInstance(String region, int priority); }
 * OrderKey factory = (OrderKey) KeyFactory.create(OrderKey.class);
 * Object key = factory.newInstance("EU", 3);   // equal to any key built from equal arguments
 * }</pre>
 *
 * <p>The returned keys have value {@code equals} and {@code hashCode} over the arguments, with
 * CGLib's exact component semantics: a component whose <em>declared</em> type is an array is
 * compared by one level of {@code Arrays.equals} on that type, and everything else — including a
 * declared {@code Object} that happens to hold an array — is compared by {@code equals()}, which
 * for arrays is identity. Deep recursive comparison was deliberately <em>not</em> adopted: CGLib
 * keys treated nested arrays inside an {@code Object[]} by element identity, and migrated code's
 * map behaviour must not change under this facade.
 *
 * <p>One deliberate difference remains: CGLib generated a bespoke key class per interface, where
 * this implementation uses a {@code java.lang.reflect.Proxy} for the factory and a small generic
 * object for the keys. Key comparison follows CGLib's semantics as above, but key
 * <em>creation</em> pays a reflective dispatch and an argument-array copy that CGLib's generated
 * factory did not; a generated typed factory (built with Classwright's own engine) is the
 * planned replacement in the performance milestone. Until then, treat key creation as adequate
 * for configuration-time use rather than per-request hot paths.
 */
public class KeyFactory {

    /** Present so subclasses compile, as in CGLib, whose generated key classes extended this. */
    protected KeyFactory() {
    }

    /**
     * Creates a factory for the given key interface.
     *
     * <p>Validation is CGLib's, measured on the real artifact, and every rule fails here at
     * creation rather than later at first use: the interface must expose exactly one public
     * method — {@code static} and {@code default} methods <em>count against</em> that total,
     * they are not quietly skipped — and that method must be literally named
     * {@code newInstance}. An earlier revision searched for "the single abstract method,
     * whatever its name" and ignored default/static extras, which accepted interface shapes
     * CGLib rejects; migrated code relies on the rejection to catch miswritten key interfaces.
     *
     * @param keyInterface the key interface
     * @return an implementation of {@code keyInterface}
     */
    public static Object create(Class keyInterface) {
        if (keyInterface == null || !keyInterface.isInterface()) {
            throw new IllegalArgumentException(keyInterface + " is not an interface");
        }
        Method[] methods = keyInterface.getMethods();
        if (methods.length != 1) {
            // CGLib counted the public method shape whole: a default or static extra is still
            // an extra.
            throw new IllegalArgumentException(
                    "expecting exactly 1 method in interface " + keyInterface.getName());
        }
        Method factoryMethod = methods[0];
        if (!factoryMethod.getName().equals("newInstance")) {
            throw new IllegalArgumentException(keyInterface.getName()
                    + " must declare a newInstance method; found " + factoryMethod.getName());
        }
        if (factoryMethod.getReturnType() != Object.class) {
            throw new IllegalArgumentException("the newInstance method must return Object, but "
                    + factoryMethod + " does not");
        }

        return Proxy.newProxyInstance(keyInterface.getClassLoader(),
                new Class<?>[]{keyInterface},
                new FactoryHandler(keyInterface, factoryMethod,
                        factoryMethod.getParameterTypes()));
    }

    /**
     * @param componentTypes cached once: reflection clones the parameter-type array on every
     *                       {@code getParameterTypes()} call, and the factory method's shape is
     *                       invariant for the factory's life — re-fetching it allocated on every
     *                       key creation for nothing.
     */
    private record FactoryHandler(Class<?> keyInterface, Method factoryMethod,
                                  Class<?>[] componentTypes) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            if (method.equals(factoryMethod)) {
                return new Key(keyInterface, componentTypes,
                        arguments == null ? new Object[0] : arguments.clone());
            }
            return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "KeyFactory for " + keyInterface.getName();
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }

    private static final class Key {

        private final Class<?> keyInterface;
        private final Class<?>[] componentTypes;
        private final Object[] components;
        private final int hash;

        Key(Class<?> keyInterface, Class<?>[] componentTypes, Object[] components) {
            this.keyInterface = keyInterface;
            this.componentTypes = componentTypes;
            this.components = components;
            int h = keyInterface.hashCode();
            for (int i = 0; i < components.length; i++) {
                h = 31 * h + componentHash(componentTypes[i], components[i]);
            }
            this.hash = h;
        }

        /**
         * CGLib's per-component semantics, driven by the <em>declared</em> component type: one
         * level of typed array hashing for a declared array, ordinary {@code hashCode()} for
         * everything else — so a declared {@code Object} holding an array hashes by identity,
         * exactly as a generated CGLib key did.
         */
        private static int componentHash(Class<?> declared, Object value) {
            if (value == null) {
                return 0;
            }
            if (declared == int[].class) {
                return java.util.Arrays.hashCode((int[]) value);
            }
            if (declared == long[].class) {
                return java.util.Arrays.hashCode((long[]) value);
            }
            if (declared == boolean[].class) {
                return java.util.Arrays.hashCode((boolean[]) value);
            }
            if (declared == byte[].class) {
                return java.util.Arrays.hashCode((byte[]) value);
            }
            if (declared == char[].class) {
                return java.util.Arrays.hashCode((char[]) value);
            }
            if (declared == short[].class) {
                return java.util.Arrays.hashCode((short[]) value);
            }
            if (declared == float[].class) {
                return java.util.Arrays.hashCode((float[]) value);
            }
            if (declared == double[].class) {
                return java.util.Arrays.hashCode((double[]) value);
            }
            if (declared.isArray()) {
                // One level only: elements by their own hashCode, nested arrays by identity.
                return java.util.Arrays.hashCode((Object[]) value);
            }
            return value.hashCode();
        }

        private static boolean componentEquals(Class<?> declared, Object mine, Object theirs) {
            if (declared == int[].class) {
                return java.util.Arrays.equals((int[]) mine, (int[]) theirs);
            }
            if (declared == long[].class) {
                return java.util.Arrays.equals((long[]) mine, (long[]) theirs);
            }
            if (declared == boolean[].class) {
                return java.util.Arrays.equals((boolean[]) mine, (boolean[]) theirs);
            }
            if (declared == byte[].class) {
                return java.util.Arrays.equals((byte[]) mine, (byte[]) theirs);
            }
            if (declared == char[].class) {
                return java.util.Arrays.equals((char[]) mine, (char[]) theirs);
            }
            if (declared == short[].class) {
                return java.util.Arrays.equals((short[]) mine, (short[]) theirs);
            }
            if (declared == float[].class) {
                return java.util.Arrays.equals((float[]) mine, (float[]) theirs);
            }
            if (declared == double[].class) {
                return java.util.Arrays.equals((double[]) mine, (double[]) theirs);
            }
            if (declared.isArray()) {
                // One level only, as in CGLib: elements by equals(), nested arrays by identity.
                return java.util.Arrays.equals((Object[]) mine, (Object[]) theirs);
            }
            return java.util.Objects.equals(mine, theirs);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key that) || keyInterface != that.keyInterface
                    || hash != that.hash) {
                return false;
            }
            for (int i = 0; i < components.length; i++) {
                if (!componentEquals(componentTypes[i], components[i], that.components[i])) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public String toString() {
            return keyInterface.getSimpleName() + java.util.Arrays.toString(components);
        }
    }
}
