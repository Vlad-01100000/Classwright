package net.sf.cglib;

import net.sf.cglib.beans.BeanMap;
import net.sf.cglib.core.KeyFactory;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.NoOp;
import net.sf.cglib.proxy.Proxy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural CGLib fidelity that structural API comparison cannot see: what the methods
 * <em>do</em>, pinned case by case against behaviour measured on real CGLib 3.3.0. The
 * structural gate is {@code CglibApiParityTest}; this file is its behavioural counterpart.
 */
class BehaviorParityTest {

    @Nested
    @DisplayName("KeyFactory")
    class KeyFactoryBehavior {

        public interface PairKey {
            Object newInstance(String name, int number);
        }

        public interface ArrayKey {
            Object newInstance(int[] numbers, Object[] mixed);
        }

        public interface ObjectKey {
            Object newInstance(Object anything);
        }

        public interface TwoMethods {
            Object newInstance(String value);

            int extra();
        }

        public interface WithDefaultExtra {
            Object newInstance(String value);

            default int extra() {
                return 1;
            }
        }

        public interface WithStaticExtra {
            Object newInstance(String value);

            static int extra() {
                return 1;
            }
        }

        public interface WrongName {
            Object create(String value);
        }

        public interface WrongReturn {
            String newInstance(String value);
        }

        @Test
        @DisplayName("an interface with more than one method is rejected at creation, as in CGLib")
        void rejectsExtraMethodsAtCreation() {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> KeyFactory.create(TwoMethods.class));

            assertTrue(failure.getMessage().contains("exactly 1 method"), failure.getMessage());
        }

        @Test
        @DisplayName("a default extra method counts against the total, as in CGLib")
        void rejectsDefaultExtras() {
            // Measured on real CGLib 3.3.0: an implemented (default) extra is still an extra —
            // CGLib counted the public method shape whole. Skipping defaults quietly accepted
            // interfaces CGLib rejects.
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> KeyFactory.create(WithDefaultExtra.class));

            assertTrue(failure.getMessage().contains("exactly 1 method"), failure.getMessage());
        }

        @Test
        @DisplayName("a static extra method counts against the total, as in CGLib")
        void rejectsStaticExtras() {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> KeyFactory.create(WithStaticExtra.class));

            assertTrue(failure.getMessage().contains("exactly 1 method"), failure.getMessage());
        }

        @Test
        @DisplayName("the factory method must be literally named newInstance, as in CGLib")
        void rejectsWrongFactoryMethodName() {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> KeyFactory.create(WrongName.class));

            assertTrue(failure.getMessage().contains("newInstance"), failure.getMessage());
        }

        @Test
        @DisplayName("the factory method must return Object")
        void rejectsWrongReturnType() {
            assertThrows(IllegalArgumentException.class,
                    () -> KeyFactory.create(WrongReturn.class));
        }

        @Test
        @DisplayName("equal arguments make equal keys across factory instances")
        void equalArgumentsMakeEqualKeys() {
            PairKey first = (PairKey) KeyFactory.create(PairKey.class);
            PairKey second = (PairKey) KeyFactory.create(PairKey.class);

            assertEquals(first.newInstance("EU", 3), second.newInstance("EU", 3));
            assertEquals(first.newInstance("EU", 3).hashCode(),
                    second.newInstance("EU", 3).hashCode());
            assertNotEquals(first.newInstance("EU", 3), first.newInstance("EU", 4));
        }

        @Test
        @DisplayName("a declared array component compares by one level of typed content")
        void declaredArraysCompareByContent() {
            ArrayKey factory = (ArrayKey) KeyFactory.create(ArrayKey.class);

            assertEquals(factory.newInstance(new int[]{4, 5}, new Object[]{"z"}),
                    factory.newInstance(new int[]{4, 5}, new Object[]{"z"}));
            assertNotEquals(factory.newInstance(new int[]{4, 5}, new Object[]{"z"}),
                    factory.newInstance(new int[]{4, 6}, new Object[]{"z"}));
        }

        @Test
        @DisplayName("a nested array inside an Object[] compares by identity, as CGLib's keys did")
        void nestedArraysCompareByIdentity() {
            // Measured on real CGLib 3.3.0: separately created but deeply equal nested arrays
            // are NOT equal — the generated key compared one level, elements by equals(), and
            // an array's equals is identity. Deep recursive comparison silently changed which
            // map entries collide, so it was removed.
            ArrayKey factory = (ArrayKey) KeyFactory.create(ArrayKey.class);

            assertNotEquals(
                    factory.newInstance(new int[]{1}, new Object[]{"z", new int[]{4, 5}}),
                    factory.newInstance(new int[]{1}, new Object[]{"z", new int[]{4, 5}}));
        }

        @Test
        @DisplayName("a declared Object component holding an array compares by identity")
        void declaredObjectHoldingArrayComparesByIdentity() {
            ObjectKey factory = (ObjectKey) KeyFactory.create(ObjectKey.class);

            assertNotEquals(factory.newInstance(new int[]{4, 5}),
                    factory.newInstance(new int[]{4, 5}),
                    "the declared type drives the semantics, not the runtime value");
            int[] shared = {4, 5};
            assertEquals(factory.newInstance(shared), factory.newInstance(shared));
        }
    }

    @Nested
    @DisplayName("Proxy factory identity")
    class ProxyIdentity {

        public interface Greeter {
            String greet(String name);
        }

        @Test
        @DisplayName("isProxyClass is true only for this factory's classes, as in CGLib")
        void isProxyClassIsFactorySpecific() {
            Object factoryProxy = Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{Greeter.class}, (proxy, method, args) -> "hi " + args[0]);

            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(Object.class);
            enhancer.setCallback(NoOp.INSTANCE);
            Object enhancerProxy = enhancer.create();

            assertTrue(Proxy.isProxyClass(factoryProxy.getClass()));
            assertFalse(Proxy.isProxyClass(enhancerProxy.getClass()),
                    "an Enhancer proxy is not a Proxy-factory proxy; CGLib code branches on "
                            + "this distinction");
            assertFalse(Proxy.isProxyClass(String.class));
        }

        @Test
        @DisplayName("getInvocationHandler on a non-factory object fails with CGLib's message")
        void getInvocationHandlerRejectsForeignObjects() {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> Proxy.getInvocationHandler("not a proxy"));

            assertEquals("Object is not a proxy", failure.getMessage());
        }

        @Test
        @DisplayName("getProxyClass marks the class exactly as newProxyInstance does")
        void getProxyClassMarksTheClass() {
            Class proxyClass = Proxy.getProxyClass(getClass().getClassLoader(),
                    new Class[]{Greeter.class});

            assertTrue(Proxy.isProxyClass(proxyClass));
        }
    }

    @Nested
    @DisplayName("BeanMap entries")
    class BeanMapEntries {

        public static class Point {

            private int x = 1;

            public int getX() {
                return x;
            }

            public void setX(int x) {
                this.x = x;
            }
        }

        @Test
        @DisplayName("entry.setValue is refused and the bean stays unchanged, as in CGLib")
        void entrySetValueIsRefused() {
            Point point = new Point();
            BeanMap map = BeanMap.create(point);
            Map.Entry<String, Object> entry = map.entrySet().iterator().next();

            assertThrows(UnsupportedOperationException.class, () -> entry.setValue(7));

            assertEquals(1, point.getX(), "the refused write must not have happened");
            assertEquals(1, map.get("x"), "writes go through put(), which still works");
        }

        @Test
        @DisplayName("entries render as key=value")
        void entryToStringIsConventional() {
            BeanMap map = BeanMap.create(new Point());

            assertEquals("x=1", map.entrySet().iterator().next().toString());
        }
    }
}
