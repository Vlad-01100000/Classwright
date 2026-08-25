package com.classwright.proxy.fixtures;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;

/**
 * A target carrying one annotation of every shape the encoder has to handle, plus generic methods.
 *
 * <p>The annotation elements below are chosen to cover each {@code element_value} tag: a primitive,
 * a {@code long} (which occupies two constant-pool slots), a {@code String}, a {@code Class}, an
 * enum constant, an array, and a nested annotation.
 */
@Annotated.Details(
        name = "the class",
        count = 7,
        size = 1234567890123L,
        precision = 2.5,
        enabled = true,
        letter = 'x',
        type = String.class,
        flavour = Annotated.Flavour.RICH,
        tags = {"alpha", "beta"},
        nested = @Annotated.Marker("inner"))
@Annotated.ClassRetained
public class Annotated {

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Marker {
        String value() default "";
    }

    /** Every element value kind in one annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Details {
        String name();

        int count() default 1;

        long size() default 0L;

        double precision() default 0.0;

        boolean enabled() default false;

        char letter() default 'a';

        Class<?> type() default Object.class;

        Flavour flavour() default Flavour.PLAIN;

        String[] tags() default {};

        Marker nested() default @Marker;
    }

    /** CLASS retention: belongs in the invisible attribute, and is invisible to reflection. */
    @Retention(RetentionPolicy.CLASS)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface ClassRetained {
    }

    /** SOURCE retention: never reaches a class file at all. */
    @Retention(RetentionPolicy.SOURCE)
    @Target(ElementType.METHOD)
    public @interface SourceOnly {
    }

    public enum Flavour {
        PLAIN, RICH
    }

    @Marker("on the method")
    @Details(name = "the method", count = 3)
    @SourceOnly
    public String describe(@Marker("on the parameter") String prefix, int count) {
        return prefix + count;
    }

    /** Generic in several ways at once, to exercise the signature renderer. */
    public <T extends Number> List<T> collect(Map<String, ? extends T> input, T[] extra)
            throws IOException {
        return List.of();
    }

    /** A wildcard-bounded parameter and a plain return. */
    public int count(List<? super Integer> values) {
        return values.size();
    }

    public String plain() {
        return "plain";
    }
}
