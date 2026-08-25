/**
 * Reflection-free member access: {@link com.classwright.reflect.FastClass} and the delegates.
 *
 * <p>Mirrors {@code net.sf.cglib.reflect}. Everything here generates a small class whose method
 * bodies perform direct calls, so dispatch costs an indirect jump rather than a reflective
 * invocation.
 *
 * <h2>How much of this is still worth using</h2>
 *
 * <p>Worth being straight about, since the answer has changed since CGLib was written.
 * {@link java.lang.reflect.Method#invoke} was slow in 2005; today the JVM generates an accessor
 * after a few calls and the gap has narrowed a great deal, and
 * {@link java.lang.invoke.MethodHandle} is faster still when it can live in a {@code static final}
 * field. Reaching for {@link com.classwright.reflect.FastClass} to speed up a single known call is
 * unlikely to be worth the generated class.
 *
 * <p>Where it does still earn its place is <em>index-based dispatch</em>: choosing at runtime from
 * a set of members fixed at generation time, where one integer selects the call with no lookup and
 * no handle adaptation. Serialisation frameworks and dispatch tables are the natural fit. And of
 * course it is here because code migrating from CGLib uses it.
 *
 * <p>The delegates — {@link com.classwright.reflect.MethodDelegate},
 * {@link com.classwright.reflect.ConstructorDelegate},
 * {@link com.classwright.reflect.MulticastDelegate} — overlap with method references, which are
 * better whenever the types are known at compile time. They remain useful when the method name is a
 * string or the interface is chosen at runtime.
 *
 * <h2>Lifetime</h2>
 *
 * <p>Generated classes are hidden classes, as everywhere else in Classwright, so they are reclaimed
 * rather than accumulating. {@code FastClass} instances are cached per target class in a
 * {@link java.lang.ClassValue}, which keeps the cache from holding any class loader alive.
 */
package com.classwright.reflect;
