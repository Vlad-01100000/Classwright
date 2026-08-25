package com.classwright.reflect;

import com.classwright.ClasswrightException;
import com.classwright.reflect.fixtures.Calculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the three delegate forms and, as much as anything, their error messages. */
class DelegateTest {

    /** A single-method interface matching {@link Calculator#describe(String)}. */
    public interface Describer {
        String describe(String prefix);
    }

    /** Matches {@link Calculator#add(int, int)}. */
    public interface Adder {
        int add(int a, int b);
    }

    /** Matches {@link Calculator#greet(String)}, which is static. */
    public interface Greeter {
        String greet(String name);
    }

    /** Matches the {@code Calculator(int)} constructor. */
    public interface CalculatorFactory {
        Calculator create(int initial);
    }

    /** Matches any no-argument String-returning method, for the closed-package tests. */
    public interface StringCall {
        String call();
    }

    /** A void, single-argument interface for the multicast tests. */
    public interface Listener {
        void notified(String event);
    }

    /** A value-returning listener, so "last result wins" can be observed. */
    public interface Scorer {
        int score(int input);
    }

    @Nested
    @DisplayName("MethodDelegate")
    class MethodDelegates {

        @Test
        @DisplayName("binds an instance method to an interface")
        void bindsInstanceMethod() {
            Calculator calculator = new Calculator(7);

            MethodDelegate delegate = MethodDelegate.create(calculator, "describe",
                    Describer.class);

            assertInstanceOf(Describer.class, delegate);
            assertEquals("value=7", ((Describer) delegate).describe("value="));
            assertSame(calculator, delegate.getTarget());
        }

        @Test
        @DisplayName("binds a method with primitive arguments and return")
        void bindsPrimitiveMethod() {
            MethodDelegate delegate = MethodDelegate.create(new Calculator(), "add", Adder.class);

            assertEquals(7, ((Adder) delegate).add(3, 4));
        }

        @Test
        @DisplayName("binds a static method")
        void bindsStaticMethod() {
            MethodDelegate delegate = MethodDelegate.createStatic(Calculator.class, "greet",
                    Greeter.class);

            assertEquals("hi world", ((Greeter) delegate).greet("world"));
            assertEquals(null, delegate.getTarget(), "a static delegate has no target");
        }

        @Test
        @DisplayName("two delegates for different methods of one closed-package target coexist")
        void twoDelegatesOnOneClosedPackageTarget() {
            // String's package is closed, so both delegates go through the shared child
            // loader — where, before resolvable names were uniquified per definition, the
            // second create() collided with the first's class name and the JVM rejected it.
            MethodDelegate trims = MethodDelegate.create(" padded ", "trim", StringCall.class);
            MethodDelegate strips = MethodDelegate.create(" padded ", "strip", StringCall.class);

            assertEquals("padded", ((StringCall) trims).call());
            assertEquals("padded", ((StringCall) strips).call());
        }

        @Test
        @DisplayName("rebinds to another target without regenerating the class")
        void rebindsToAnotherTarget() {
            MethodDelegate first = MethodDelegate.create(new Calculator(1), "describe",
                    Describer.class);

            MethodDelegate second = first.newInstance(new Calculator(2));

            assertNotSame(first, second);
            assertSame(first.getClass(), second.getClass(), "the class should be reused");
            assertEquals("n=1", ((Describer) first).describe("n="));
            assertEquals("n=2", ((Describer) second).describe("n="));
        }

        @Test
        @DisplayName("rejects a static method passed to create()")
        void rejectsStaticForCreate() {
            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> MethodDelegate.create(new Calculator(), "greet", Greeter.class));

            assertTrue(failure.getMessage().contains("createStatic"), failure.getMessage());
        }

        @Test
        @DisplayName("rejects an interface with more than one abstract method")
        void rejectsMultiMethodInterface() {
            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> MethodDelegate.create(new Calculator(), "add", List.class));

            assertTrue(failure.getMessage().contains("abstract methods"), failure.getMessage());
            assertTrue(failure.getMessage().contains("exactly one"), failure.getMessage());
        }

        @Test
        @DisplayName("rejects a class where an interface belongs")
        void rejectsNonInterface() {
            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> MethodDelegate.create(new Calculator(), "add", Calculator.class));

            assertTrue(failure.getMessage().contains("not an interface"), failure.getMessage());
        }

        @Test
        @DisplayName("rejects a missing method, naming the signature it looked for")
        void rejectsMissingMethod() {
            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> MethodDelegate.create(new Calculator(), "nonexistent", Adder.class));

            assertTrue(failure.getMessage().contains("nonexistent"), failure.getMessage());
            assertTrue(failure.getMessage().contains("int, int"),
                    "it should name the signature: " + failure.getMessage());
        }

        @Test
        @DisplayName("rejects an incompatible return type rather than converting silently")
        void rejectsIncompatibleReturn() {
            // describe() returns String; Supplier.get() takes no arguments, so this fails on the
            // signature first -- the point is that it fails loudly rather than half-working.
            assertThrows(ClasswrightException.class,
                    () -> MethodDelegate.create(new Calculator(), "describe", Supplier.class));
        }

        @Test
        @DisplayName("rejects a null target, pointing at createStatic")
        void rejectsNullTarget() {
            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> MethodDelegate.create(null, "add", Adder.class));

            assertTrue(failure.getMessage().contains("createStatic"), failure.getMessage());
        }
    }

    @Nested
    @DisplayName("ConstructorDelegate")
    class ConstructorDelegates {

        @Test
        @DisplayName("turns a constructor into a factory")
        void createsInstances() {
            ConstructorDelegate delegate = ConstructorDelegate.create(Calculator.class,
                    CalculatorFactory.class);

            Calculator created = ((CalculatorFactory) delegate).create(12);

            assertEquals(12, created.value);
            assertEquals(Calculator.class, created.getClass(), "a real instance, not a proxy");
        }

        @Test
        @DisplayName("rejects a missing constructor, explaining how the match is made")
        void rejectsMissingConstructor() {
            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> ConstructorDelegate.create(Calculator.class, Adder.class));

            assertTrue(failure.getMessage().contains("no public constructor"),
                    failure.getMessage());
        }

        @Test
        @DisplayName("rejects an abstract target")
        void rejectsAbstractTarget() {
            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> ConstructorDelegate.create(java.io.InputStream.class,
                            CalculatorFactory.class));

            assertTrue(failure.getMessage().contains("abstract"), failure.getMessage());
        }
    }

    @Nested
    @DisplayName("MulticastDelegate")
    class MulticastDelegates {

        @Test
        @DisplayName("calls every target, in order")
        void callsEveryTarget() {
            List<String> calls = new ArrayList<>();
            MulticastDelegate delegate = MulticastDelegate.create(Listener.class)
                    .add((Listener) event -> calls.add("first:" + event))
                    .add((Listener) event -> calls.add("second:" + event));

            ((Listener) delegate).notified("go");

            assertEquals(List.of("first:go", "second:go"), calls);
        }

        @Test
        @DisplayName("an empty delegate is a no-op")
        void emptyDelegateDoesNothing() {
            MulticastDelegate delegate = MulticastDelegate.create(Listener.class);

            ((Listener) delegate).notified("ignored");

            assertEquals(0, delegate.size());
        }

        @Test
        @DisplayName("returns the last target's result")
        void returnsTheLastResult() {
            MulticastDelegate delegate = MulticastDelegate.create(Scorer.class)
                    .add((Scorer) input -> input + 1)
                    .add((Scorer) input -> input + 100);

            assertEquals(105, ((Scorer) delegate).score(5));
        }

        @Test
        @DisplayName("with no targets, a value-returning method yields the zero value")
        void emptyValueReturningDelegateYieldsZero() {
            assertEquals(0, ((Scorer) MulticastDelegate.create(Scorer.class)).score(5));
        }

        @Test
        @DisplayName("add and remove leave the original untouched")
        void isImmutable() {
            // What makes it safe to hand a delegate to another thread while registration
            // continues elsewhere.
            List<String> calls = new ArrayList<>();
            Listener listener = event -> calls.add(event);

            MulticastDelegate empty = MulticastDelegate.create(Listener.class);
            MulticastDelegate withOne = empty.add(listener);
            MulticastDelegate backToEmpty = withOne.remove(listener);

            assertEquals(0, empty.size());
            assertEquals(1, withOne.size());
            assertEquals(0, backToEmpty.size());

            ((Listener) empty).notified("a");
            assertEquals(List.of(), calls, "the original must not have gained a target");
        }

        @Test
        @DisplayName("removing something absent is harmless")
        void removingAbsentTargetIsHarmless() {
            MulticastDelegate delegate = MulticastDelegate.create(Listener.class);

            assertEquals(0, delegate.remove((Listener) event -> { }).size());
        }

        @Test
        @DisplayName("rejects a target that does not implement the interface")
        void rejectsForeignTarget() {
            MulticastDelegate delegate = MulticastDelegate.create(Listener.class);

            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> delegate.add("not a listener"));

            assertTrue(failure.getMessage().contains("does not implement"), failure.getMessage());
        }

        @Test
        @DisplayName("calls many targets correctly, so the generated loop is right")
        void handlesManyTargets() {
            MulticastDelegate delegate = MulticastDelegate.create(Listener.class);
            List<String> calls = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                int index = i;
                delegate = delegate.add((Listener) event -> calls.add(event + index));
            }

            ((Listener) delegate).notified("e");

            assertEquals(50, calls.size());
            assertEquals("e0", calls.get(0));
            assertEquals("e49", calls.get(49));
        }
    }
}
