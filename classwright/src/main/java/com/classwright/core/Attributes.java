package com.classwright.core;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the annotation attributes, which are shaped identically on classes, fields, and methods.
 *
 * <p>Each method appends zero, one, or two attributes and returns how many, so callers can keep
 * their attribute counts right without tracking it themselves — a count that disagrees with the
 * bytes that follow produces a {@code ClassFormatError} with no useful detail.
 */
final class Attributes {

    private Attributes() {
    }

    /**
     * Appends {@code RuntimeVisibleAnnotations} and {@code RuntimeInvisibleAnnotations} as needed.
     *
     * @return how many attributes were written
     */
    static int writeAnnotations(ByteWriter out, ConstantPool pool, Annotation[] annotations) {
        if (annotations == null || annotations.length == 0) {
            return 0;
        }
        int written = 0;
        written += writeOne(out, pool, "RuntimeVisibleAnnotations",
                AnnotationEncoder.select(annotations, true));
        written += writeOne(out, pool, "RuntimeInvisibleAnnotations",
                AnnotationEncoder.select(annotations, false));
        return written;
    }

    private static int writeOne(ByteWriter out, ConstantPool pool, String attributeName,
                                List<Annotation> selected) {
        if (selected.isEmpty()) {
            return 0;
        }
        ByteWriter body = new ByteWriter(64);
        AnnotationEncoder.writeAnnotations(body, pool, selected);
        out.u2(pool.utf8(attributeName));
        out.u4(body.length());
        out.bytes(body);
        return 1;
    }

    /**
     * Appends the parameter-annotation attributes as needed.
     *
     * <p>Skipped entirely when no parameter carries one, which is the common case; the attribute
     * would otherwise be a length-prefixed list of empty lists.
     *
     * @return how many attributes were written
     */
    static int writeParameterAnnotations(ByteWriter out, ConstantPool pool,
                                         Annotation[][] parameterAnnotations) {
        if (parameterAnnotations == null || parameterAnnotations.length == 0) {
            return 0;
        }
        int written = 0;
        written += writeParameterSet(out, pool, "RuntimeVisibleParameterAnnotations",
                parameterAnnotations, true);
        written += writeParameterSet(out, pool, "RuntimeInvisibleParameterAnnotations",
                parameterAnnotations, false);
        return written;
    }

    private static int writeParameterSet(ByteWriter out, ConstantPool pool, String attributeName,
                                         Annotation[][] parameterAnnotations, boolean visible) {
        List<List<Annotation>> byParameter = new ArrayList<>(parameterAnnotations.length);
        boolean anyPresent = false;
        for (Annotation[] onParameter : parameterAnnotations) {
            List<Annotation> selected = AnnotationEncoder.select(
                    onParameter == null ? new Annotation[0] : onParameter, visible);
            anyPresent |= !selected.isEmpty();
            byParameter.add(selected);
        }
        if (!anyPresent) {
            return 0;
        }

        ByteWriter body = new ByteWriter(64);
        AnnotationEncoder.writeParameterAnnotations(body, pool, byParameter);
        out.u2(pool.utf8(attributeName));
        out.u4(body.length());
        out.bytes(body);
        return 1;
    }
}
