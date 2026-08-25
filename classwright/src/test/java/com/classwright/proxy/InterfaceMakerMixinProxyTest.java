package com.classwright.proxy;

import com.classwright.ClasswrightException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the remaining CGLib-shaped proxy utilities. */
class InterfaceMakerMixinProxyTest {

    public interface Reader {
        String read();
    }

    public interface Writer {
        void write(String value);
    }

    /** Overlaps with {@link Reader}, to exercise precedence between delegates. */
    public interface AlsoReader {
        String read();
    }

    public static class RecordingReader implements Reader, AlsoReader {

        private final String value;

        public RecordingReader(String value) {
            this.value = value;
        }

        @Override
        public String read() {
            return value;
        }
    }

    public static class RecordingWriter implements Writer {

        final List<String> written = new ArrayList<>();

        @Override
        public void write(String value) {
            written.add(value);
        }
    }

    // ==========================================================================================

    @Nested
    @DisplayName("InterfaceMaker")
    class Interfaces {

        @Test
        @DisplayName("builds an interface that Enhancer can then implement")
        void buildsUsableInterface() throws Exception {
            InterfaceMaker maker = new InterfaceMaker();
            maker.setNeighbour(InterfaceMakerMixinProxyTest.class);
            maker.add("compute", int.class, int.class, int.class);
            maker.add("label", String.class);

            Class<?> contract = maker.create();

            assertTrue(contract.isInterface());
            assertEquals(2, contract.getDeclaredMethods().length);

            Object implementation = Enhancer.create(Object.class, new Class<?>[]{contract},
                    (MethodInterceptor) (obj, method, args, methodProxy) ->
                            method.getName().equals("compute") ? 42 : "labelled");

            assertInstanceOf(contract, implementation);
            Method compute = contract.getMethod("compute", int.class, int.class);
            assertEquals(42, compute.invoke(implementation, 1, 2));
        }

        @Test
        @DisplayName("copies the signature of an existing method, exceptions included")
        void copiesExistingSignatures() throws Exception {
            InterfaceMaker maker = new InterfaceMaker();
            maker.add(Appendable.class.getMethod("append", CharSequence.class));

            Class<?> contract = maker.create();
            Method appended = contract.getDeclaredMethods()[0];

            assertEquals("append", appended.getName());
            assertEquals(Appendable.class.getMethod("append", CharSequence.class).getReturnType(),
                    appended.getReturnType());
            assertEquals(1, appended.getExceptionTypes().length,
                    "append declares IOException, which should survive");
        }

        @Test
        @DisplayName("copies every public instance method of a type")
        void copiesWholeTypes() {
            Class<?> contract = new InterfaceMaker()
                    .setNeighbour(InterfaceMakerMixinProxyTest.class)
                    .addAll(Reader.class)
                    .addAll(Writer.class)
                    .create();

            assertEquals(2, contract.getDeclaredMethods().length);
        }

        @Test
        @DisplayName("uses the requested name")
        void honoursExplicitName() {
            Class<?> contract = new InterfaceMaker()
                    .setNeighbour(InterfaceMakerMixinProxyTest.class)
                    .setName("MyContract")
                    .add("go", void.class)
                    .create();

            assertTrue(contract.getName().endsWith(".MyContract"), contract.getName());
        }

        @Test
        @DisplayName("produces a named, resolvable class, because an interface has to be")
        void producesNamedClasses() throws Exception {
            // A hidden class has no resolvable name, and a class file naming an interface it
            // implements resolves it by name. So this one cannot be hidden, and therefore cannot
            // be unloaded -- which is documented rather than worked around.
            Class<?> contract = new InterfaceMaker()
                    .setNeighbour(InterfaceMakerMixinProxyTest.class)
                    .add("go", void.class)
                    .create();

            assertFalse(contract.isHidden());
            assertSame(contract, Class.forName(contract.getName(), false,
                    contract.getClassLoader()));
        }

        @Test
        @DisplayName("refuses to create an empty interface")
        void refusesEmptyInterface() {
            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> new InterfaceMaker().create());

            assertTrue(failure.getMessage().contains("no methods"), failure.getMessage());
        }
    }

    // ==========================================================================================

    @Nested
    @DisplayName("Mixin")
    class Mixins {

        @Test
        @DisplayName("combines objects into one implementing all their interfaces")
        void combinesInterfaces() {
            RecordingWriter writer = new RecordingWriter();

            Mixin mixed = Mixin.create(new Object[]{new RecordingReader("hello"), writer});

            assertEquals("hello", ((Reader) mixed).read());
            ((Writer) mixed).write("out");
            assertEquals(List.of("out"), writer.written);
        }

        @Test
        @DisplayName("routes only the interfaces named, when they are given explicitly")
        void routesExplicitInterfaces() {
            Mixin mixed = Mixin.create(new Class<?>[]{Writer.class},
                    new Object[]{new RecordingWriter()});

            assertInstanceOf(Writer.class, mixed);
            assertFalse(mixed instanceof Reader);
        }

        @Test
        @DisplayName("the earlier delegate wins where two implement the same method")
        void earlierDelegateWins() {
            // A deliberate precedence rule rather than an accident: the array is ordered.
            Mixin mixed = Mixin.create(
                    new Class<?>[]{Reader.class, AlsoReader.class},
                    new Object[]{new RecordingReader("first"), new RecordingReader("second")});

            assertEquals("first", ((Reader) mixed).read());
            assertEquals("first", ((AlsoReader) mixed).read(),
                    "the duplicate signature is emitted once, routed to the first interface");
        }

        @Test
        @DisplayName("rebinds to new delegates without regenerating the class")
        void rebindsDelegates() {
            Mixin first = Mixin.create(new Object[]{new RecordingReader("a"),
                    new RecordingWriter()});

            Mixin second = first.newInstance(new Object[]{new RecordingReader("b"),
                    new RecordingWriter()});

            assertNotSame(first, second);
            assertSame(first.getClass(), second.getClass());
            assertEquals("a", ((Reader) first).read());
            assertEquals("b", ((Reader) second).read());
        }

        @Test
        @DisplayName("rejects a delegate list that does not cover an interface")
        void rejectsUncoveredInterface() {
            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> Mixin.create(new Class<?>[]{Reader.class},
                            new Object[]{new RecordingWriter()}));

            assertTrue(failure.getMessage().contains("none of the delegates"),
                    failure.getMessage());
        }

        @Test
        @DisplayName("rejects delegates with no interfaces at all")
        void rejectsInterfacelessDelegates() {
            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> Mixin.create(new Object[]{new Object()}));

            assertTrue(failure.getMessage().contains("nothing for the mixin"),
                    failure.getMessage());
        }

        @Test
        @DisplayName("skips sealed interfaces on the automatic path rather than failing")
        void skipsSealedInterfaces() {
            // String implements the sealed java.lang.constant.ConstantDesc. A generated class
            // cannot implement it, and refusing the whole request over an interface the caller
            // never asked for would be unhelpful.
            Mixin mixed = Mixin.create(new Object[]{"a string"});

            assertInstanceOf(CharSequence.class, mixed);
            assertEquals(8, ((CharSequence) mixed).length());
        }

        @Test
        @DisplayName("reports a sealed interface when it was named explicitly")
        void reportsExplicitSealedInterface() {
            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> Mixin.create(new Class<?>[]{java.lang.constant.ConstantDesc.class},
                            new Object[]{"a string"}));

            assertTrue(failure.getMessage().contains("sealed"), failure.getMessage());
        }

        @Test
        @DisplayName("rejects an empty delegate array")
        void rejectsEmptyDelegates() {
            assertThrows(ClasswrightException.class, () -> Mixin.create(new Object[0]));
        }

        @Test
        @DisplayName("rebinding with the wrong number of delegates is rejected")
        void rejectsWrongDelegateCount() {
            Mixin mixed = Mixin.create(new Object[]{new RecordingWriter()});

            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> mixed.newInstance(new Object[]{new RecordingWriter(),
                            new RecordingWriter()}));

            assertTrue(failure.getMessage().contains("compiled into"), failure.getMessage());
        }
    }

    // ==========================================================================================

    @Nested
    @DisplayName("Proxy")
    class JdkShaped {

        @Test
        @DisplayName("creates an interface proxy the JDK way")
        void createsInterfaceProxy() {
            Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Reader.class},
                    (proxyObject, method, args) -> "handled");

            assertEquals("handled", ((Reader) proxy).read());
        }

        @Test
        @DisplayName("recognises its own proxies")
        void recognisesProxies() {
            Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Reader.class}, (proxyObject, method, args) -> null);

            assertTrue(Proxy.isProxyClass(proxy.getClass()));
            assertFalse(Proxy.isProxyClass(String.class));
            assertFalse(Proxy.isProxyClass(null));
        }

        @Test
        @DisplayName("hands back the handler it was given")
        void returnsTheHandler() {
            InvocationHandler handler = (proxyObject, method, args) -> null;
            Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Reader.class}, handler);

            assertSame(handler, Proxy.getInvocationHandler(proxy));
        }

        @Test
        @DisplayName("produces the class without an instance")
        void producesProxyClass() {
            Class<?> type = Proxy.getProxyClass(getClass().getClassLoader(),
                    new Class<?>[]{Reader.class});

            assertTrue(Reader.class.isAssignableFrom(type));
            assertTrue(Proxy.isProxyClass(type));
        }

        @Test
        @DisplayName("rejects a class where an interface belongs")
        void rejectsNonInterfaces() {
            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> Proxy.newProxyInstance(null, new Class<?>[]{String.class},
                            (proxyObject, method, args) -> null));

            assertTrue(failure.getMessage().contains("Enhancer"),
                    "it should point at the right tool: " + failure.getMessage());
        }

        @Test
        @DisplayName("rejects a foreign object passed to getInvocationHandler")
        void rejectsForeignObjects() {
            assertThrows(ClasswrightException.class, () -> Proxy.getInvocationHandler("not a proxy"));
        }
    }
}
