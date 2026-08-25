package com.classwright.reflect;

import com.classwright.ClasswrightException;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Shared checks for the delegate types.
 *
 * <p>All three &mdash; {@link MethodDelegate}, {@link ConstructorDelegate},
 * {@link MulticastDelegate} &mdash; work the same way: take an interface with a single abstract
 * method and generate a class implementing it that forwards somewhere. The validation is identical
 * and the error messages are the valuable part, since the failures here are configuration mistakes
 * a person makes while wiring things up.
 */
final class Delegates {

    private Delegates() {
    }

    /**
     * The one abstract method of a single-method interface.
     *
     * @param interfaceType the interface to implement
     * @return its sole abstract method
     * @throws ClasswrightException if it is not an interface, or does not have exactly one
     */
    static Method singleAbstractMethod(Class<?> interfaceType) {
        if (interfaceType == null || !interfaceType.isInterface()) {
            throw new ClasswrightException((interfaceType == null ? "null" : interfaceType.getName())
                    + " is not an interface. A delegate implements one interface with a single "
                    + "abstract method.");
        }

        List<Method> abstractMethods = new ArrayList<>();
        for (Method method : interfaceType.getMethods()) {
            if (Modifier.isAbstract(method.getModifiers()) && !isObjectMethod(method)) {
                abstractMethods.add(method);
            }
        }
        if (abstractMethods.size() != 1) {
            throw new ClasswrightException(interfaceType.getName() + " has "
                    + abstractMethods.size() + " abstract methods"
                    + (abstractMethods.isEmpty() ? "" : ": " + abstractMethods.stream()
                    .map(Method::getName).collect(Collectors.joining(", ")))
                    + ". A delegate needs exactly one, so there is no ambiguity about what to "
                    + "forward.");
        }
        return abstractMethods.get(0);
    }

    /**
     * Finds the method a delegate should forward to.
     *
     * @param target         the class declaring the implementation
     * @param methodName     its name
     * @param interfaceMethod the interface method whose signature it must match
     * @return the matching method
     * @throws ClasswrightException if there is no match, or its return type is incompatible
     */
    static Method matchingMethod(Class<?> target, String methodName, Method interfaceMethod) {
        Method found;
        try {
            found = target.getMethod(methodName, interfaceMethod.getParameterTypes());
        } catch (NoSuchMethodException e) {
            throw new ClasswrightException(target.getName() + " has no public method "
                    + methodName + describe(interfaceMethod.getParameterTypes())
                    + ", which is what " + interfaceMethod.getDeclaringClass().getSimpleName()
                    + "." + interfaceMethod.getName() + " requires.\n"
                    + "A delegate forwards one call to one method, so the parameter types must "
                    + "match exactly.", e);
        }
        requireCompatibleReturn(found.getReturnType(), interfaceMethod);
        return found;
    }

    /**
     * Checks that a value of {@code produced} can be returned from {@code interfaceMethod}.
     *
     * <p>Widening a reference is fine: a method returning {@code String} satisfies an interface
     * declaring {@code Object}. Anything else is rejected rather than silently boxed or truncated,
     * because a delegate that quietly changed a value's type would be worse than one that refused
     * to exist.
     */
    static void requireCompatibleReturn(Class<?> produced, Method interfaceMethod) {
        Class<?> required = interfaceMethod.getReturnType();
        if (required == produced) {
            return;
        }
        if (!required.isPrimitive() && !produced.isPrimitive()
                && required.isAssignableFrom(produced)) {
            return;
        }
        throw new ClasswrightException("cannot return " + produced.getSimpleName() + " from "
                + interfaceMethod.getDeclaringClass().getSimpleName() + "."
                + interfaceMethod.getName() + ", which is declared to return "
                + required.getSimpleName() + ".\n"
                + "Delegates forward a value; they do not box, unbox, or convert it.");
    }

    private static boolean isObjectMethod(Method method) {
        // equals, hashCode and toString are abstract on an interface only in the sense that Object
        // supplies them. They must not count towards the single-abstract-method total.
        try {
            Object.class.getMethod(method.getName(), method.getParameterTypes());
            return true;
        } catch (NoSuchMethodException notFromObject) {
            return false;
        }
    }

    private static String describe(Class<?>[] parameterTypes) {
        return Arrays.stream(parameterTypes).map(Class::getSimpleName)
                .collect(Collectors.joining(", ", "(", ")"));
    }
}
