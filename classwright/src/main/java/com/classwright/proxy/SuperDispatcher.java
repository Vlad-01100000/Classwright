package com.classwright.proxy;

/**
 * Implemented by every generated proxy so that {@link MethodProxy} can reach the original methods.
 *
 * <p><strong>Not part of the public API.</strong> It is {@code public} only because generated
 * classes live in the <em>target's</em> package, not Classwright's, and so must be able to see it.
 * Nothing outside this library should implement or call it.
 *
 * <h2>Why an interface, and why this shape</h2>
 *
 * <p>{@link MethodProxy#invokeSuper} has to invoke {@code super.someMethod(...)} on a class whose
 * name it cannot write down: hidden classes have no resolvable name, so no {@code invokevirtual}
 * against them can be compiled ahead of time. The obvious workaround is a {@link
 * java.lang.invoke.MethodHandle} per method, but that adds an adaptation chain to every intercepted
 * call.
 *
 * <p>An interface avoids it entirely. The generated proxy implements this one method as a
 * {@code tableswitch} over the index, with each case performing a direct {@code invokespecial} to
 * the superclass. {@code invokeSuper} then costs one interface call plus one indirect jump, with no
 * handles and no reflection anywhere.
 *
 * <p>It also removes a whole generated class. CGLib produced a separate {@code FastClass} to hold
 * the equivalent switch — in fact two, one for the proxy and one for the superclass — which is most
 * of why it emitted roughly three classes per proxy where Classwright emits one.
 */
@com.classwright.Internal
public interface SuperDispatcher {

    /**
     * Invokes the original implementation of the method at {@code index}.
     *
     * @param index     the method's position in the proxy's generated dispatch table
     * @param arguments the arguments, boxed
     * @return the result, boxed; {@code null} for a {@code void} method
     * @throws Throwable whatever the original method throws
     */
    Object cwInvokeSuper(int index, Object[] arguments) throws Throwable;
}
