package com.classwright.proxy;

import java.lang.reflect.Constructor;
import java.util.List;

/**
 * Everything the generator needs to build one proxy class.
 *
 * <p>Separated from {@link Enhancer} so that generation is a pure function of a value. That makes
 * the generator testable on its own, and makes the cache key obvious: two specs that are equal
 * produce identical bytecode, so they can share a class.
 *
 * @param superclass                  the class to extend
 * @param interfaces                  additional interfaces to implement
 * @param internalName                the generated class's internal name; must contain {@code $$}
 *                                    so framework heuristics recognise it
 * @param methods                     the override plan
 * @param callbackIndexPerMethod      for each proxied method, which callback handles it
 * @param callbackTypes               the declared type of each callback slot
 * @param useFactory                  whether to implement {@link Factory}
 * @param interceptDuringConstruction whether callbacks are active for calls made from within a
 *                                    constructor
 * @param constructors                superclass constructors to mirror
 * @param copyAnnotations             whether to reproduce annotations and generic signatures from
 *                                    the target onto the generated class
 */
record ProxySpec(
        Class<?> superclass,
        List<Class<?>> interfaces,
        String internalName,
        ProxyMethods methods,
        int[] callbackIndexPerMethod,
        List<Class<?>> callbackTypes,
        boolean useFactory,
        boolean interceptDuringConstruction,
        List<Constructor<?>> constructors,
        boolean copyAnnotations,
        NamingConvention naming) {

    /** The kind of callback handling method {@code index}. */
    CallbackKind kindFor(int methodIndex) {
        return CallbackKind.of(callbackTypes.get(callbackIndexPerMethod[methodIndex]));
    }

    /** The kind of callback in slot {@code callbackIndex}. */
    CallbackKind kindOfCallback(int callbackIndex) {
        return CallbackKind.of(callbackTypes.get(callbackIndex));
    }

    int callbackCount() {
        return callbackTypes.size();
    }
}
