package com.classwright.proxy;

import com.classwright.proxy.fixtures.Annotated;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a proxy can be made indistinguishable from its target under reflection.
 *
 * <p>CGLib copied neither annotations nor generic signatures, and every framework built on it grew
 * heuristics to recover what the proxy had thrown away. These tests are the evidence that the
 * heuristics are no longer needed.
 */
class AnnotationPropagationTest {

    private static Class<?> proxyClass(boolean copyAnnotations) {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Annotated.class);
        enhancer.setCallback(NoOp.INSTANCE);
        enhancer.setCopyAnnotations(copyAnnotations);
        return enhancer.create().getClass();
    }

    private static Method methodOn(Class<?> type, String name, Class<?>... parameterTypes)
            throws Exception {
        return type.getDeclaredMethod(name, parameterTypes);
    }

    @Test
    @DisplayName("annotations are not copied by default")
    void offByDefault() throws Exception {
        // Annotations carry semantics; duplicating them onto a class the user never wrote is a
        // change of behaviour and should be asked for.
        Class<?> proxy = proxyClass(false);

        assertNull(proxy.getDeclaredAnnotation(Annotated.Details.class));
        assertEquals(0, methodOn(proxy, "describe", String.class, int.class)
                .getDeclaredAnnotations().length);
    }

    @Test
    @DisplayName("class annotations survive, with every element value intact")
    void copiesClassAnnotations() {
        Class<?> proxy = proxyClass(true);

        Annotated.Details details = proxy.getDeclaredAnnotation(Annotated.Details.class);

        assertNotNull(details, "the class annotation should be present on the proxy");
        assertEquals("the class", details.name());
        assertEquals(7, details.count());
        assertEquals(1234567890123L, details.size(), "a long occupies two constant-pool slots");
        assertEquals(2.5, details.precision());
        assertTrue(details.enabled());
        assertEquals('x', details.letter());
        assertEquals(String.class, details.type());
        assertEquals(Annotated.Flavour.RICH, details.flavour());
        assertArrayEquals(new String[]{"alpha", "beta"}, details.tags());
        assertEquals("inner", details.nested().value(), "a nested annotation should survive");
    }

    @Test
    @DisplayName("method annotations survive")
    void copiesMethodAnnotations() throws Exception {
        Method describe = methodOn(proxyClass(true), "describe", String.class, int.class);

        assertEquals("on the method", describe.getDeclaredAnnotation(Annotated.Marker.class)
                .value());
        assertEquals("the method", describe.getDeclaredAnnotation(Annotated.Details.class).name());
        assertEquals(3, describe.getDeclaredAnnotation(Annotated.Details.class).count());
    }

    @Test
    @DisplayName("parameter annotations survive, on the right parameter")
    void copiesParameterAnnotations() throws Exception {
        Method describe = methodOn(proxyClass(true), "describe", String.class, int.class);

        java.lang.annotation.Annotation[][] byParameter = describe.getParameterAnnotations();

        assertEquals(2, byParameter.length);
        assertEquals(1, byParameter[0].length);
        assertEquals("on the parameter", ((Annotated.Marker) byParameter[0][0]).value());
        assertEquals(0, byParameter[1].length, "the second parameter carries none");
    }

    @Test
    @DisplayName("SOURCE-retained annotations are absent, as they are from the original")
    void dropsSourceRetention() throws Exception {
        Method describe = methodOn(proxyClass(true), "describe", String.class, int.class);

        // Not a loss: SOURCE retention never reaches a class file, so the original has none either.
        assertEquals(2, describe.getDeclaredAnnotations().length,
                "only the two RUNTIME-retained annotations should be visible");
    }

    @Test
    @DisplayName("CLASS-retained annotations go in the invisible attribute and stay invisible")
    void handlesClassRetention() {
        Class<?> proxy = proxyClass(true);

        assertNull(proxy.getDeclaredAnnotation(Annotated.ClassRetained.class),
                "CLASS retention is not visible to reflection, on the proxy or the original");
        assertNull(Annotated.class.getDeclaredAnnotation(Annotated.ClassRetained.class),
                "confirming the original behaves the same way");
    }

    @Test
    @DisplayName("generic signatures survive, so reflection reports the real types")
    void copiesGenericSignatures() throws Exception {
        Method collect = methodOn(proxyClass(true), "collect", Map.class, Number[].class);

        // Return type: List<T>
        Type returnType = collect.getGenericReturnType();
        assertInstanceOf(ParameterizedType.class, returnType);
        assertEquals(List.class, ((ParameterizedType) returnType).getRawType());
        assertInstanceOf(TypeVariable.class,
                ((ParameterizedType) returnType).getActualTypeArguments()[0]);

        // Type parameter: <T extends Number>
        TypeVariable<Method>[] typeParameters = collect.getTypeParameters();
        assertEquals(1, typeParameters.length);
        assertEquals("T", typeParameters[0].getName());
        assertEquals(Number.class, typeParameters[0].getBounds()[0]);

        // Parameter: Map<String, ? extends T>
        Type first = collect.getGenericParameterTypes()[0];
        assertInstanceOf(ParameterizedType.class, first);
        Type[] arguments = ((ParameterizedType) first).getActualTypeArguments();
        assertEquals(String.class, arguments[0]);
        assertInstanceOf(WildcardType.class, arguments[1]);
    }

    @Test
    @DisplayName("lower-bounded wildcards survive")
    void copiesLowerBoundedWildcards() throws Exception {
        Method count = methodOn(proxyClass(true), "count", List.class);

        Type parameter = count.getGenericParameterTypes()[0];
        Type argument = ((ParameterizedType) parameter).getActualTypeArguments()[0];

        assertInstanceOf(WildcardType.class, argument);
        assertEquals(Integer.class, ((WildcardType) argument).getLowerBounds()[0]);
    }

    @Test
    @DisplayName("a non-generic method gets no Signature attribute at all")
    void omitsSignatureWhenNotGeneric() throws Exception {
        Method plain = methodOn(proxyClass(true), "plain");

        assertEquals(String.class, plain.getGenericReturnType());
        assertFalse(plain.getGenericReturnType() instanceof ParameterizedType);
    }

    @Test
    @DisplayName("declared exceptions are copied whether or not annotations are")
    void alwaysCopiesThrownExceptions() throws Exception {
        // Not conditional: the JVM does not enforce checked exceptions, but callers compiling
        // against a proxy read this attribute and need it to match.
        for (boolean copyAnnotations : new boolean[]{false, true}) {
            Method collect = methodOn(proxyClass(copyAnnotations), "collect",
                    Map.class, Number[].class);

            assertArrayEquals(new Class<?>[]{java.io.IOException.class},
                    collect.getExceptionTypes(), "copyAnnotations=" + copyAnnotations);
        }
    }

    @Test
    @DisplayName("a proxy with annotations still works")
    void proxyStillFunctions() {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(Annotated.class);
        enhancer.setCopyAnnotations(true);
        enhancer.setCallback((MethodInterceptor) (obj, method, args, methodProxy) ->
                methodProxy.invokeSuper(obj, args));

        Annotated proxy = (Annotated) enhancer.create();

        assertEquals("x5", proxy.describe("x", 5));
        assertEquals("plain", proxy.plain());
    }
}
