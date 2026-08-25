/**
 * Runtime proxies: {@link com.classwright.proxy.Enhancer} and its callbacks.
 *
 * <p>The API mirrors {@code net.sf.cglib.proxy} class for class and method for method, so code
 * written against CGLib moves across with a package rename.
 *
 * <pre>{@code
 * Service proxy = (Service) Enhancer.create(Service.class,
 *         (MethodInterceptor) (obj, method, args, methodProxy) ->
 *                 methodProxy.invokeSuper(obj, args));
 * }</pre>
 *
 * <h2>One class per proxy</h2>
 *
 * <p>CGLib emitted roughly three classes for every proxy: the enhanced subclass, plus two
 * {@code FastClass} helpers whose job was to reach the original methods without reflection.
 * Classwright emits one. The super-call switch lives inside the proxy itself, reached through
 * {@link com.classwright.proxy.SuperDispatcher}, which removes both extra classes and one dispatch
 * hop. That is why a Classwright proxy needs about a quarter of the metaspace and generates several
 * times faster.
 *
 * <h2>Each callback compiles to different code</h2>
 *
 * <p>A {@link com.classwright.proxy.NoOp} method becomes a bare {@code invokespecial} — no field
 * read, no argument boxing, nothing for the JIT to see through. A
 * {@link com.classwright.proxy.FixedValue} method never even loads its arguments. Only
 * {@link com.classwright.proxy.MethodInterceptor} and
 * {@link com.classwright.proxy.InvocationHandler} allocate an {@code Object[]}, because only they
 * are given one. Specialising per callback means no method pays for machinery it does not use.
 *
 * <h2>Proxies are collectable</h2>
 *
 * <p>Generated classes are hidden classes by default, so they unload when nothing refers to them.
 * The practical consequence is that {@code proxy.getClass().getName()} cannot be passed to
 * {@code Class.forName}; the name still starts with the target's name followed by {@code $$}, which
 * is what framework heuristics actually look at. Where a resolvable name is genuinely needed,
 * {@link com.classwright.proxy.Enhancer#setDefinitionStrategy} takes
 * {@link com.classwright.runtime.DefinitionStrategy#named()} — and those classes are never
 * reclaimed, exactly as CGLib's were.
 */
package com.classwright.proxy;
