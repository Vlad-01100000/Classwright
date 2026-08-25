package com.classwright.proxy;

import com.classwright.ClasswrightException;

import java.util.List;

/**
 * The callback interfaces, and what each one compiles to.
 *
 * <p>Every kind gets its own generated method body rather than sharing one dispatch path. That is
 * the point: a {@link NoOp} method becomes a bare {@code invokespecial} with no field read and no
 * argument boxing, while only {@link #INTERCEPTOR} and {@link #INVOCATION_HANDLER} pay for an
 * {@code Object[]}. A single generic path would charge every method the price of the most expensive
 * one.
 */
enum CallbackKind {

    /** Runs the original. Compiles to a direct super-call with no callback machinery at all. */
    NO_OP(NoOp.class, false),

    /** Full interception. The only kind that gets a {@link MethodProxy}. */
    INTERCEPTOR(MethodInterceptor.class, true),

    /** Returns a constant. Arguments are never even loaded. */
    FIXED_VALUE(FixedValue.class, false),

    /** JDK-style handling. Needs the {@link java.lang.reflect.Method}, but no super access. */
    INVOCATION_HANDLER(InvocationHandler.class, true),

    /** Forwards to an object resolved on every call. */
    DISPATCHER(Dispatcher.class, false),

    /** Forwards to an object resolved once and cached per instance. */
    LAZY_LOADER(LazyLoader.class, false),

    /** Forwards to an object resolved per call, told which proxy asked. */
    PROXY_REF_DISPATCHER(ProxyRefDispatcher.class, false);

    private final Class<?> callbackType;
    private final boolean needsMethodMetadata;

    CallbackKind(Class<?> callbackType, boolean needsMethodMetadata) {
        this.callbackType = callbackType;
        this.needsMethodMetadata = needsMethodMetadata;
    }

    Class<?> callbackType() {
        return callbackType;
    }

    /** Whether generated code for this kind reads the cached {@code Method} object. */
    boolean needsMethodMetadata() {
        return needsMethodMetadata;
    }

    /** Whether instances of this kind need a per-instance cache field. */
    boolean needsInstanceCache() {
        return this == LAZY_LOADER;
    }

    /**
     * Kinds, resolved once per callback class.
     *
     * <p>The search below walks seven interfaces asking {@code isAssignableFrom}, and it ran once
     * per callback on every {@code create()} — including creations that hit the generation cache.
     * The answer for a class never changes.
     *
     * <p>A {@link ClassValue}, so nothing global holds a callback class alive. Lambdas are hidden
     * classes, and their entries are collected along with them.
     */
    private static final ClassValue<CallbackKind> KINDS = new ClassValue<>() {
        @Override
        protected CallbackKind computeValue(Class<?> type) {
            return resolve(type);
        }
    };

    /**
     * Identifies the kind from a callback's declared type.
     *
     * @param type a class or interface extending {@link Callback}
     * @return the matching kind
     * @throws ClasswrightException if it is not one of the known callback interfaces
     */
    static CallbackKind of(Class<?> type) {
        return KINDS.get(type);
    }

    private static CallbackKind resolve(Class<?> type) {
        for (CallbackKind kind : values()) {
            if (kind.callbackType.isAssignableFrom(type)) {
                return kind;
            }
        }
        throw new ClasswrightException(type.getName() + " is not a recognised callback type. "
                + "Implement one of: " + List.of(values()).stream()
                .map(k -> k.callbackType.getSimpleName()).reduce((a, b) -> a + ", " + b).orElse(""));
    }
}
