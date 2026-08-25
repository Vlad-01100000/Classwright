package com.classwright.reflect;

import java.lang.reflect.Member;

/**
 * A member paired with its {@link FastClass} index.
 *
 * <p>Reads like a {@link java.lang.reflect.Method} or {@link java.lang.reflect.Constructor} — same
 * questions, same answers — but invoking it goes through the generated switch instead of
 * reflection. Convenient when a method is looked up once and called repeatedly, since it carries
 * the index that makes the call cheap.
 */
public abstract class FastMember {

    final FastClass fastClass;
    final Member member;
    final int index;

    FastMember(FastClass fastClass, Member member, int index) {
        this.fastClass = fastClass;
        this.member = member;
        this.index = index;
    }

    /**
     * This member's index in its {@link FastClass}.
     *
     * @return this member's index in its {@link FastClass}
     */
    public int getIndex() {
        return index;
    }

    /**
     * The member's name.
     *
     * @return the name
     */
    public String getName() {
        return member.getName();
    }

    /**
     * The class that declares this member.
     *
     * @return the declaring class
     */
    public Class<?> getDeclaringClass() {
        return member.getDeclaringClass();
    }

    /**
     * The member's modifiers, as {@link java.lang.reflect.Modifier} bits.
     *
     * @return the modifier bits
     */
    public int getModifiers() {
        return member.getModifiers();
    }

    /**
     * The accessor this member belongs to.
     *
     * @return the {@link FastClass} this member belongs to
     */
    public FastClass getFastClass() {
        return fastClass;
    }

    /**
     * The parameter types, in order.
     *
     * @return the parameter types, in declaration order
     */
    public abstract Class<?>[] getParameterTypes();

    /**
     * The declared checked exceptions.
     *
     * @return the declared exception types
     */
    public abstract Class<?>[] getExceptionTypes();

    @Override
    public boolean equals(Object other) {
        return other instanceof FastMember that && member.equals(that.member);
    }

    @Override
    public int hashCode() {
        return member.hashCode();
    }

    @Override
    public String toString() {
        return member.toString();
    }
}
