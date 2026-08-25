package com.classwright.proxy.fixtures;

/**
 * A conventional class to proxy, carrying one method of each interesting kind.
 *
 * <p>{@link #calls} counts invocations of the originals, which is how a test tells "the interceptor
 * delegated" apart from "the interceptor returned the right answer by coincidence".
 */
public class Service {

    /** Incremented by every original method here. */
    public int calls;

    public Service() {
    }

    public Service(int initialCalls) {
        this.calls = initialCalls;
    }

    public String greet(String name) {
        calls++;
        return "hello " + name;
    }

    public int add(int a, int b) {
        calls++;
        return a + b;
    }

    /** Mixed slot widths, so the dispatcher's argument unpacking is exercised. */
    public long total(long a, int b, double c) {
        calls++;
        return a + b + (long) c;
    }

    public void touch() {
        calls++;
    }

    public boolean flag() {
        calls++;
        return true;
    }

    public int[] numbers() {
        calls++;
        return new int[]{1, 2, 3};
    }

    /** Cannot be overridden; a proxy must skip it and say so. */
    public final String cannotOverride() {
        return "final";
    }

    /** Not dispatched virtually, so not proxyable either. */
    public static String staticMethod() {
        return "static";
    }

    protected String protectedMethod() {
        calls++;
        return "protected";
    }

    /** Only overridable when the proxy lands in this exact runtime package. */
    String packagePrivate() {
        calls++;
        return "original";
    }

    /** Public window onto {@link #packagePrivate()}, so another package can observe dispatch. */
    public String callPackagePrivate() {
        return packagePrivate();
    }

    public String throwsChecked() throws java.io.IOException {
        calls++;
        return "no throw";
    }
}
