package com.classwright.core;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Optional;

/**
 * Renders a reflective {@link Type} into a JVMS generic signature.
 *
 * <p>Descriptors erase generics; the {@code Signature} attribute is what carries them, and it is
 * what {@code Method.getGenericReturnType()} reads back. Without it a proxy's methods appear to
 * return raw types, and frameworks that inspect generic parameters — dependency injection
 * containers, serialisers, validation — silently lose information.
 *
 * <h2>Refusing rather than guessing</h2>
 *
 * <p>Every method here returns an {@link Optional}, and returns empty when a type cannot be
 * rendered faithfully. The alternative would be to emit a plausible-looking signature that says
 * something slightly different from the truth, and a wrong {@code Signature} attribute is worse
 * than none: reflection reports it confidently, so the error surfaces far from its cause. CGLib
 * emitted no signatures at all, so omitting one is never a regression.
 *
 * <p>The case that is deliberately refused is a parameterized type nested inside another
 * parameterized type ({@code Outer<T>.Inner<U>}). The format has a dedicated encoding for it, it
 * is rare in the signatures a proxy needs to reproduce, and getting it subtly wrong is easy.
 *
 * @see <a href="https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-4.html#jvms-4.7.9.1">
 *      JVMS 4.7.9.1, signatures</a>
 */
final class SignatureRenderer {

    private SignatureRenderer() {
    }

    /**
     * Renders a method's generic signature, or empty if the method has no generic information or
     * cannot be rendered faithfully.
     *
     * @param method the method to describe
     * @return the signature, e.g. {@code <T:Ljava/lang/Object;>(Ljava/util/List<TT;>;)TT;}
     */
    static Optional<String> forMethod(Method method) {
        if (!isGeneric(method)) {
            return Optional.empty();
        }
        StringBuilder signature = new StringBuilder();

        if (method.getTypeParameters().length > 0) {
            signature.append('<');
            for (TypeVariable<?> variable : method.getTypeParameters()) {
                signature.append(variable.getName());
                Type[] bounds = variable.getBounds();
                for (int i = 0; i < bounds.length; i++) {
                    // The first colon-slot is the class bound, and it stays empty when the first
                    // bound is an interface: javac writes <T::Ljava/lang/Runnable;>, not
                    // <T:Ljava/lang/Runnable;>. Reflection reads both identically, but anything
                    // comparing signatures textually notices. A type-variable first bound
                    // occupies the class slot, which is also how javac renders it. Object
                    // bounds are written explicitly, as javac does.
                    if (i == 0 && isInterfaceBound(bounds[i])) {
                        signature.append(':');
                    }
                    signature.append(':');
                    Optional<String> rendered = forType(bounds[i]);
                    if (rendered.isEmpty()) {
                        return Optional.empty();
                    }
                    signature.append(rendered.get());
                }
            }
            signature.append('>');
        }

        signature.append('(');
        for (Type parameter : method.getGenericParameterTypes()) {
            Optional<String> rendered = forType(parameter);
            if (rendered.isEmpty()) {
                return Optional.empty();
            }
            signature.append(rendered.get());
        }
        signature.append(')');

        Optional<String> returnType = forType(method.getGenericReturnType());
        if (returnType.isEmpty()) {
            return Optional.empty();
        }
        signature.append(returnType.get());

        for (Type thrown : method.getGenericExceptionTypes()) {
            if (thrown instanceof Class<?> plain) {
                signature.append('^').append(CwType.of(plain).descriptor());
            } else {
                Optional<String> rendered = forType(thrown);
                if (rendered.isEmpty()) {
                    return Optional.empty();
                }
                signature.append('^').append(rendered.get());
            }
        }
        return Optional.of(signature.toString());
    }

    /** Whether a method carries any generic information worth an attribute. */
    /** Whether a type-variable bound occupies the interface-bound slots rather than the class one. */
    private static boolean isInterfaceBound(Type bound) {
        if (bound instanceof Class<?> clazz) {
            return clazz.isInterface();
        }
        return bound instanceof ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> raw
                && raw.isInterface();
    }

    private static boolean isGeneric(Method method) {
        if (method.getTypeParameters().length > 0) {
            return true;
        }
        if (!(method.getGenericReturnType() instanceof Class<?>)) {
            return true;
        }
        for (Type parameter : method.getGenericParameterTypes()) {
            if (!(parameter instanceof Class<?>)) {
                return true;
            }
        }
        for (Type exception : method.getGenericExceptionTypes()) {
            // `void m() throws E` is generic in its throws clause alone, and omitting the
            // Signature attribute would erase E from the override's reflective view.
            if (!(exception instanceof Class<?>)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Renders one type.
     *
     * @param type any reflective type
     * @return its signature, or empty if it cannot be rendered faithfully
     */
    static Optional<String> forType(Type type) {
        if (type instanceof Class<?> plain) {
            return Optional.of(CwType.of(plain).descriptor());
        }
        if (type instanceof TypeVariable<?> variable) {
            return Optional.of("T" + variable.getName() + ";");
        }
        if (type instanceof GenericArrayType array) {
            return forType(array.getGenericComponentType()).map(component -> "[" + component);
        }
        if (type instanceof WildcardType wildcard) {
            return forWildcard(wildcard);
        }
        if (type instanceof ParameterizedType parameterized) {
            return forParameterized(parameterized);
        }
        return Optional.empty();
    }

    private static Optional<String> forWildcard(WildcardType wildcard) {
        Type[] lower = wildcard.getLowerBounds();
        if (lower.length > 0) {
            return forType(lower[0]).map(bound -> "-" + bound);
        }
        Type[] upper = wildcard.getUpperBounds();
        if (upper.length == 0 || upper[0] == Object.class) {
            return Optional.of("*");
        }
        return forType(upper[0]).map(bound -> "+" + bound);
    }

    private static Optional<String> forParameterized(ParameterizedType parameterized) {
        if (!(parameterized.getRawType() instanceof Class<?> raw)) {
            return Optional.empty();
        }
        if (parameterized.getOwnerType() instanceof ParameterizedType) {
            // Outer<T>.Inner<U> has its own encoding in the format. Refused rather than
            // approximated; see the class documentation.
            return Optional.empty();
        }

        StringBuilder signature = new StringBuilder("L")
                .append(raw.getName().replace('.', '/'))
                .append('<');
        for (Type argument : parameterized.getActualTypeArguments()) {
            Optional<String> rendered = forType(argument);
            if (rendered.isEmpty()) {
                return Optional.empty();
            }
            signature.append(rendered.get());
        }
        return Optional.of(signature.append(">;").toString());
    }
}
