package com.classwright.beans;

import com.classwright.ClasswrightException;
import com.classwright.proxy.Callback;
import com.classwright.proxy.CallbackFilter;
import com.classwright.proxy.Dispatcher;
import com.classwright.proxy.Enhancer;
import com.classwright.proxy.MethodInterceptor;

import java.lang.reflect.Method;

/**
 * Wraps a bean so that its setters throw.
 *
 * <pre>{@code
 * Order readOnly = (Order) ImmutableBean.create(order);
 * readOnly.getTotal();          // fine
 * readOnly.setTotal(0);         // IllegalStateException
 * }</pre>
 *
 * <p>For handing a mutable object to code that should not change it, without copying it. Reads pass
 * through to the original, so the view stays current if the underlying bean changes elsewhere —
 * this prevents modification <em>through this reference</em> rather than making the bean immutable.
 *
 * <p>Only <em>setters</em> throw: any one-argument method named {@code setX}, whatever it returns,
 * so fluent builder-style setters are caught too. A mutator that is not named like a setter —
 * {@code clear()}, {@code addItem(...)} — passes through to the bean and mutates it. If the type
 * has such methods, this view is not protection against them.
 *
 * <p>Built entirely from {@link Enhancer} and a {@link CallbackFilter} rather than its own code
 * generation: setters route to an interceptor that throws, everything else to a {@link Dispatcher}
 * pointing at the original. There was no reason to write a fourth generator for something the proxy
 * machinery already expresses exactly.
 */
public final class ImmutableBean {

    private ImmutableBean() {
    }

    /**
     * Creates a read-only view of a bean.
     *
     * @param bean the bean to wrap; must be a non-final class with an accessible constructor
     * @return a view of the same type whose setters throw {@link IllegalStateException}
     */
    public static Object create(Object bean) {
        if (bean == null) {
            throw new ClasswrightException("bean must not be null");
        }
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(bean.getClass());
        enhancer.setCallbacks(
                (Dispatcher) () -> bean,
                (MethodInterceptor) (proxy, method, args, methodProxy) -> {
                    throw new IllegalStateException("this is an immutable view of "
                            + bean.getClass().getSimpleName() + "; " + method.getName()
                            + " cannot be called on it");
                });
        enhancer.setCallbackFilter(SettersThrow.INSTANCE);
        return enhancer.create();
    }

    /**
     * Routes setters to the throwing interceptor and everything else to the delegate.
     *
     * <p>Stateless, and a named class rather than a lambda, so that {@code equals} can be defined.
     * A filter without one defeats the generation cache: every request would look like a new
     * configuration and generate a fresh class. {@link CallbackFilter} explains why.
     */
    private static final class SettersThrow implements CallbackFilter {

        static final SettersThrow INSTANCE = new SettersThrow();

        @Override
        public int accept(Method method) {
            // No void-return requirement, deliberately: builder-style setters return this, and a
            // fluent setter that slipped through to the delegate would mutate the "immutable"
            // bean — silently, which is the worst version. Mutators not named like setters
            // (clear(), addItem(...)) still pass through; the class javadoc says so.
            boolean isSetter = method.getName().startsWith("set")
                    && method.getName().length() > 3
                    && method.getParameterCount() == 1;
            return isSetter ? 1 : 0;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof SettersThrow;
        }

        @Override
        public int hashCode() {
            return SettersThrow.class.hashCode();
        }
    }
}
