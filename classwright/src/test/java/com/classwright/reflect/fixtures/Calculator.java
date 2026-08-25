package com.classwright.reflect.fixtures;

/** A target with one member of each shape a fast accessor has to handle. */
public class Calculator {

    public int value;

    public Calculator() {
    }

    public Calculator(int initial) {
        this.value = initial;
    }

    public Calculator(String text) {
        this.value = text.length();
    }

    /** Throws, so constructor exception wrapping can be observed. */
    public Calculator(boolean explode) {
        if (explode) {
            throw new IllegalArgumentException("constructor exploded");
        }
    }

    public int add(int a, int b) {
        return a + b;
    }

    /** Mixed slot widths, so argument unpacking is exercised. */
    public long total(long a, int b, double c) {
        return a + b + (long) c;
    }

    public String describe(String prefix) {
        return prefix + value;
    }

    public void reset() {
        value = 0;
    }

    public boolean isZero() {
        return value == 0;
    }

    public int[] counted(int count) {
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = i;
        }
        return result;
    }

    /** Static: callable, with no receiver. */
    public static String greet(String name) {
        return "hi " + name;
    }

    /** Final: cannot be overridden, but can perfectly well be called. */
    public final int finalMethod() {
        return 99;
    }

    /** Package-private: reachable only when the accessor lands in this package. */
    String packagePrivate() {
        return "package";
    }

    /** Private: never reachable from generated code. */
    @SuppressWarnings("unused")
    private String secret() {
        return "secret";
    }

    public void boom() {
        throw new IllegalStateException("method exploded");
    }
}
