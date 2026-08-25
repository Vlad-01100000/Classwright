package com.classwright.proxy;

import com.classwright.beans.BeanGenerator;
import com.classwright.reflect.FastClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dispatch beyond the single-switch ceiling.
 *
 * <p>A {@code tableswitch} with enough cases breaches the JVM's 65,535-byte method-code limit;
 * these tests generate types wide enough that dispatch must run through the chunked form, and
 * assert indexes on both sides of the chunk boundary still route correctly. Before chunking,
 * generation for these shapes failed outright at the code-size guard.
 */
class WideDispatchTest {

    private static final int WIDE = 600;   // comfortably past the 512-per-chunk threshold

    @Test
    @DisplayName("FastClass dispatches a 600-method type through chunked switches")
    void fastClassBeyondOneChunk() throws Throwable {
        InterfaceMaker maker = new InterfaceMaker();
        for (int i = 0; i < WIDE; i++) {
            maker.add("m" + i, String.class);
        }
        Class<?> wide = maker.create();

        Object implementation = java.lang.reflect.Proxy.newProxyInstance(
                wide.getClassLoader(), new Class<?>[]{wide},
                (proxy, method, args) -> method.getName());

        FastClass fast = FastClass.create(wide);
        assertEquals(WIDE, fast.getMaxIndex() + 1);

        // Selected by ACTUAL table index, not by name: the table sorts names lexicographically
        // (m0, m1, m10, m100, ...), so the source-numbered name "m511" lands at an unrelated
        // index — an earlier revision asserted on m511/m512 believing those names WERE indexes
        // 511/512, and never actually pinned the chunk edge. Indexes 511 and 512 are the last
        // case of chunk 0 and the first case of chunk 1 by construction (index >>> 9 selects
        // the chunk), whatever methods happen to occupy them.
        for (int index : new int[]{0, 511, 512, fast.getMaxIndex()}) {
            Method occupant = fast.getMethods().get(index);
            assertTrue(fast.getIndex(occupant) == index, "the table must be self-consistent");
            assertEquals(occupant.getName(), fast.invoke(index, implementation, new Object[0]),
                    "index " + index + " (" + occupant.getName()
                            + ") must route through its chunk to the right method");
        }
    }

    @Test
    @DisplayName("invokeSuper reaches the original through chunked dispatch on a 600-method proxy")
    void invokeSuperBeyondOneChunk() throws Exception {
        BeanGenerator generator = new BeanGenerator();
        for (int i = 0; i < WIDE / 2; i++) {
            generator.addProperty("p" + i, String.class);
        }
        Class<?> beanClass = generator.createClass();

        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(beanClass);
        enhancer.setCallback((MethodInterceptor) (obj, method, args, methodProxy) ->
                methodProxy.invokeSuper(obj, args));
        Object proxy = enhancer.create();

        // Round-trip through accessors that land in different chunks of the dispatch table.
        for (int i : new int[]{0, WIDE / 2 - 1}) {
            Method setter = beanClass.getMethod("setP" + i, String.class);
            Method getter = beanClass.getMethod("getP" + i);
            setter.invoke(proxy, "value-" + i);
            assertEquals("value-" + i, getter.invoke(proxy));
        }
    }
}
