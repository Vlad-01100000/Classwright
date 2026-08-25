package com.classwright.beans;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Discovers a class's JavaBean properties by reflection.
 *
 * <p>Deliberately not {@code java.beans.Introspector}: that lives in the {@code java.desktop}
 * module, which would mean a {@code requires} entry on a module that is absent from most server
 * runtimes and headless images. The convention it implements is a handful of rules, and
 * implementing them here keeps the library dependent on nothing but {@code java.base}.
 *
 * <p>The rules applied: a no-argument, non-{@code void} public method named {@code getX} — or
 * {@code isX} returning primitive {@code boolean}, never boxed {@code Boolean} — is a read
 * accessor, and a single-argument {@code void} public method named {@code setX} is a write
 * accessor. Bridge and synthetic methods are ignored, as are static ones and {@code getClass}.
 */
final class BeanProperties {

    /**
     * One property. Either accessor may be absent — a read-only property has no setter, and a
     * write-only one has no getter.
     *
     * @param name   the property name, with the leading character lower-cased
     * @param getter the read accessor, or {@code null}
     * @param setter the write accessor, or {@code null}
     */
    record Property(String name, Method getter, Method setter) {

        /** The property's type, taken from whichever accessor exists. */
        Class<?> type() {
            return getter != null ? getter.getReturnType() : setter.getParameterTypes()[0];
        }

        boolean isReadable() {
            return getter != null;
        }

        boolean isWritable() {
            return setter != null;
        }
    }

    private BeanProperties() {
    }

    /**
     * The properties of a class, keyed by name.
     *
     * <p>Sorted, so that generated indexes and iteration order are stable across runs. Nothing
     * documents the order, but one that changed between JVM starts would be a nasty surprise for
     * anyone who relied on it accidentally.
     *
     * @param type the bean class
     * @return its properties, in name order
     */
    static Map<String, Property> of(Class<?> type) {
        // Every choice below is order-independent, deliberately: getMethods() enumerates in an
        // unspecified order that has changed across JDK builds, and an accessor picked by
        // arrival order would make the generated class's shape differ between deployments.
        Map<String, Method> getters = new LinkedHashMap<>();
        Map<String, List<Method>> setterCandidates = new LinkedHashMap<>();

        for (Method method : type.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.isBridge()
                    || method.isSynthetic() || method.getDeclaringClass() == Object.class) {
                continue;
            }
            String name = method.getName();
            if (method.getParameterCount() == 0 && method.getReturnType() != void.class) {
                if (name.startsWith("get") && name.length() > 3) {
                    getters.merge(decapitalise(name.substring(3)), method,
                            BeanProperties::preferGetter);
                } else if (name.startsWith("is") && name.length() > 2
                        && method.getReturnType() == boolean.class) {
                    // The is-form counts only for primitive boolean, exactly as Introspector and
                    // CGLib define it. An earlier revision also accepted boxed Boolean as a
                    // "friendlier" deviation — but this model feeds BeanMap and BeanCopier, and
                    // the deviation changed observable data access: a boxed isX() became a live
                    // getter (and outranked getX()) where CGLib read nothing, so a copy or a
                    // map read produced different values after migration. A boxed Boolean isX()
                    // is simply not a JavaBeans accessor; getX() is the boxed form's getter.
                    getters.merge(decapitalise(name.substring(2)), method,
                            BeanProperties::preferGetter);
                }
            } else if (method.getParameterCount() == 1 && method.getReturnType() == void.class
                    && name.startsWith("set") && name.length() > 3) {
                setterCandidates
                        .computeIfAbsent(decapitalise(name.substring(3)),
                                unused -> new java.util.ArrayList<>(2))
                        .add(method);
            }
        }

        Map<String, Property> properties = new TreeMap<>();
        for (Map.Entry<String, Method> entry : getters.entrySet()) {
            String name = entry.getKey();
            Method getter = entry.getValue();
            // chooseSetter enforces the pairing relation itself; a property with no compatible
            // setter degrades to read-only rather than vanishing, as in Introspector.
            Method setter = chooseSetter(setterCandidates.get(name), getter.getReturnType());
            properties.put(name, new Property(name, getter, setter));
        }
        for (Map.Entry<String, List<Method>> entry : setterCandidates.entrySet()) {
            properties.computeIfAbsent(entry.getKey(),
                    name -> new Property(name, null, chooseSetter(entry.getValue(), null)));
        }
        return properties;
    }

    /**
     * Resolves duelling getters deterministically: the {@code is} form wins over {@code get}
     * (Introspector's rule), a more specific return type wins over its supertype (the covariant
     * override), and a residual tie breaks on a stable name comparison rather than on
     * enumeration order.
     */
    private static Method preferGetter(Method current, Method candidate) {
        boolean currentIs = current.getName().startsWith("is");
        boolean candidateIs = candidate.getName().startsWith("is");
        if (currentIs != candidateIs) {
            return currentIs ? current : candidate;
        }
        Class<?> currentType = current.getReturnType();
        Class<?> candidateType = candidate.getReturnType();
        if (currentType != candidateType) {
            if (currentType.isAssignableFrom(candidateType)) {
                return candidate;
            }
            if (candidateType.isAssignableFrom(currentType)) {
                return current;
            }
        }
        return stableChoice(current, candidate);
    }

    /**
     * Picks among overloaded setters the way {@code Introspector} and CGLib do, measured on
     * both.
     *
     * <p>With a getter, a setter belongs to the property only when its parameter is the
     * getter's type <em>or a subtype</em> — {@code G.isAssignableFrom(S)}. A <em>wider</em>
     * setter ({@code setX(CharSequence)} beside {@code getX(): String}) is not the property's
     * writable half, and the property degrades to read-only. An earlier revision tested the
     * relation in the opposite direction and gave an exact getter-type match absolute
     * privilege; both were observable migration differences — a CGLib-read-only property became
     * writable here, a CGLib-writable one read-only, and an overload set resolved to a
     * different method than {@code Introspector} picks. Among compatible candidates the
     * <em>most specific</em> parameter wins, exactness conferring no privilege; unrelated
     * survivors fall back to a stable ordering rather than enumeration order. Without a getter
     * (a write-only property), every candidate competes and the same most-specific rule
     * applies.
     */
    private static Method chooseSetter(List<Method> candidates, Class<?> getterType) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        Method chosen = null;
        for (Method candidate : candidates) {
            if (getterType != null
                    && !getterType.isAssignableFrom(candidate.getParameterTypes()[0])) {
                continue;   // wider than, or unrelated to, the property's type: not its setter
            }
            chosen = chosen == null ? candidate : moreSpecificSetter(chosen, candidate);
        }
        return chosen;
    }

    /** The narrower parameter type wins; unrelated parameters break the tie stably. */
    private static Method moreSpecificSetter(Method current, Method candidate) {
        Class<?> currentParameter = current.getParameterTypes()[0];
        Class<?> candidateParameter = candidate.getParameterTypes()[0];
        if (currentParameter != candidateParameter) {
            if (currentParameter.isAssignableFrom(candidateParameter)) {
                return candidate;
            }
            if (candidateParameter.isAssignableFrom(currentParameter)) {
                return current;
            }
        }
        return stableChoice(current, candidate);
    }

    /** An arbitrary but run-stable winner: by parameter/return type name, then declaring class. */
    private static Method stableChoice(Method a, Method b) {
        String aKey = describeForOrdering(a);
        String bKey = describeForOrdering(b);
        return aKey.compareTo(bKey) <= 0 ? a : b;
    }

    private static String describeForOrdering(Method method) {
        Class<?> significant = method.getParameterCount() == 1
                ? method.getParameterTypes()[0]
                : method.getReturnType();
        return significant.getName() + "#" + method.getDeclaringClass().getName();
    }

    /** JavaBeans decapitalisation: {@code URL} stays {@code URL}, {@code Name} becomes {@code name}. */
    private static String decapitalise(String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(0))
                && Character.isUpperCase(name.charAt(1))) {
            return name;    // an acronym keeps its case, per the JavaBeans convention
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
