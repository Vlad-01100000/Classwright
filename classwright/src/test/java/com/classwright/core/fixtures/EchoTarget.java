package com.classwright.core.fixtures;

/**
 * A class for generated subclasses to override.
 *
 * <p>Carries one {@code echo} overload per type in the signature matrix, so a single test can walk
 * every type through the same generate-override-call-super path.
 *
 * <p>{@link #superCalls} is the point of the whole fixture. Without it, a generated override that
 * simply returned its argument would be indistinguishable from one that correctly called
 * {@code super} &mdash; and getting {@code invokespecial} wrong is exactly the failure worth
 * catching, because the plausible alternative ({@code invokevirtual}) produces infinite recursion
 * rather than a wrong value.
 */
public class EchoTarget {

    /** Incremented by every method here, so a test can prove the original really ran. */
    public int superCalls;

    public boolean echo(boolean v) {
        superCalls++;
        return v;
    }

    public byte echo(byte v) {
        superCalls++;
        return v;
    }

    public char echo(char v) {
        superCalls++;
        return v;
    }

    public short echo(short v) {
        superCalls++;
        return v;
    }

    public int echo(int v) {
        superCalls++;
        return v;
    }

    public long echo(long v) {
        superCalls++;
        return v;
    }

    public float echo(float v) {
        superCalls++;
        return v;
    }

    public double echo(double v) {
        superCalls++;
        return v;
    }

    public Object echo(Object v) {
        superCalls++;
        return v;
    }

    public String echo(String v) {
        superCalls++;
        return v;
    }

    public int[] echo(int[] v) {
        superCalls++;
        return v;
    }

    public long[] echo(long[] v) {
        superCalls++;
        return v;
    }

    public Object[] echo(Object[] v) {
        superCalls++;
        return v;
    }

    public String[][] echo(String[][] v) {
        superCalls++;
        return v;
    }

    /** Mixed one- and two-slot parameters, so slot arithmetic is exercised end to end. */
    public String mix(int a, long b, double c, String d) {
        superCalls++;
        return a + "|" + b + "|" + c + "|" + d;
    }

    /** A void method, which needs the bare {@code return} rather than a typed one. */
    public void touch() {
        superCalls++;
    }

    /** A constructor taking arguments, so generated constructors must forward them. */
    public EchoTarget() {
    }

    public EchoTarget(int initialCalls) {
        this.superCalls = initialCalls;
    }
}
