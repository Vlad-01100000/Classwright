package com.classwright.core;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes annotations into a class file, reading their values back through reflection.
 *
 * <p>Reflection is the only source, as everywhere else in this library: an {@link Annotation}
 * instance is asked for its type and then for each element's value, and the result is re-encoded.
 * No class file is read.
 *
 * <h2>Why this matters for a proxy</h2>
 *
 * <p>CGLib did not copy annotations onto generated classes at all, and the consequences leaked into
 * every framework built on it. A proxied bean whose {@code @Transactional} vanished had to be
 * detected some other way, so frameworks grew heuristics — walk to the superclass, unwrap the
 * proxy, look for {@code $$} in the name — and every one of those is a workaround for an absence.
 * Copying them across removes the need.
 *
 * <p>It is still opt-in. Annotations are semantics, and silently duplicating them onto a class the
 * user did not write could change how their existing framework behaves. Making it a choice keeps
 * the surprise out.
 *
 * @see <a href="https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-4.html#jvms-4.7.16">
 *      JVMS 4.7.16, the RuntimeVisibleAnnotations attribute</a>
 */
final class AnnotationEncoder {

    private AnnotationEncoder() {
    }

    /**
     * Splits annotations by retention.
     *
     * <p>Only {@code RUNTIME}-retained annotations are visible to reflection and belong in
     * {@code RuntimeVisibleAnnotations}. {@code CLASS} retention goes in the invisible attribute;
     * {@code SOURCE} retention never reaches a class file at all and is dropped, which is correct
     * rather than lossy — it was already gone from the original.
     *
     * @param annotations the annotations to sort
     * @param visible     whether to select runtime-visible ones
     * @return the matching subset
     */
    static List<Annotation> select(Annotation[] annotations, boolean visible) {
        List<Annotation> selected = new ArrayList<>();
        for (Annotation annotation : annotations) {
            java.lang.annotation.Retention retention =
                    annotation.annotationType().getAnnotation(java.lang.annotation.Retention.class);
            java.lang.annotation.RetentionPolicy policy = retention == null
                    ? java.lang.annotation.RetentionPolicy.CLASS
                    : retention.value();
            if (policy == java.lang.annotation.RetentionPolicy.SOURCE) {
                continue;
            }
            boolean isVisible = policy == java.lang.annotation.RetentionPolicy.RUNTIME;
            if (isVisible == visible) {
                selected.add(annotation);
            }
        }
        return selected;
    }

    /**
     * Writes the body of a {@code RuntimeVisible/InvisibleAnnotations} attribute.
     *
     * @param out         destination
     * @param pool        constant pool
     * @param annotations the annotations to write
     */
    static void writeAnnotations(ByteWriter out, ConstantPool pool, List<Annotation> annotations) {
        out.u2(annotations.size());
        for (Annotation annotation : annotations) {
            writeAnnotation(out, pool, annotation);
        }
    }

    /**
     * Writes the body of a {@code RuntimeVisible/InvisibleParameterAnnotations} attribute.
     *
     * <p>The count here is a {@code u1}, not a {@code u2} — one of the format's small
     * irregularities, and a source of corrupt attributes if assumed otherwise.
     */
    static void writeParameterAnnotations(ByteWriter out, ConstantPool pool,
                                          List<List<Annotation>> byParameter) {
        out.u1(byParameter.size());
        for (List<Annotation> annotations : byParameter) {
            writeAnnotations(out, pool, annotations);
        }
    }

    private static void writeAnnotation(ByteWriter out, ConstantPool pool, Annotation annotation) {
        Class<? extends Annotation> type = annotation.annotationType();
        out.u2(pool.utf8(CwType.of(type).descriptor()));

        List<Method> elements = elementsOf(type);
        out.u2(elements.size());
        for (Method element : elements) {
            out.u2(pool.utf8(element.getName()));
            writeElementValue(out, pool, invoke(annotation, element));
        }
    }

    /**
     * The annotation type's elements.
     *
     * <p>Every element is written, including ones left at their default. Re-emitting a default
     * costs a few bytes and removes a whole class of question about whether the copy behaves
     * identically to the original.
     */
    private static List<Method> elementsOf(Class<? extends Annotation> type) {
        List<Method> elements = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.getParameterCount() == 0 && method.getReturnType() != void.class) {
                elements.add(method);
            }
        }
        elements.sort(java.util.Comparator.comparing(Method::getName));
        return elements;
    }

    private static Object invoke(Annotation annotation, Method element) {
        try {
            element.setAccessible(true);
            return element.invoke(annotation);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new CodeGenerationException("cannot read " + element.getName() + " from "
                    + annotation.annotationType().getName(), e);
        }
    }

    /**
     * Writes one {@code element_value}, choosing the tag from the value's runtime type.
     *
     * <p>Runtime type is enough because an annotation element of type {@code byte} yields a
     * {@link Byte}, one of type {@code int} an {@link Integer}, and so on — the boxing is
     * one-to-one with the tags.
     */
    private static void writeElementValue(ByteWriter out, ConstantPool pool, Object value) {
        if (value instanceof Boolean booleanValue) {
            out.u1('Z').u2(pool.integer(booleanValue ? 1 : 0));
        } else if (value instanceof Byte byteValue) {
            out.u1('B').u2(pool.integer(byteValue));
        } else if (value instanceof Character charValue) {
            out.u1('C').u2(pool.integer(charValue));
        } else if (value instanceof Short shortValue) {
            out.u1('S').u2(pool.integer(shortValue));
        } else if (value instanceof Integer intValue) {
            out.u1('I').u2(pool.integer(intValue));
        } else if (value instanceof Long longValue) {
            out.u1('J').u2(pool.longConstant(longValue));
        } else if (value instanceof Float floatValue) {
            out.u1('F').u2(pool.floatConstant(floatValue));
        } else if (value instanceof Double doubleValue) {
            out.u1('D').u2(pool.doubleConstant(doubleValue));
        } else if (value instanceof String stringValue) {
            out.u1('s').u2(pool.utf8(stringValue));
        } else if (value instanceof Class<?> classValue) {
            // The class_info_index points at a Utf8 holding a *return descriptor*, not a class
            // reference -- so void.class is "V" and int.class is "I".
            out.u1('c').u2(pool.utf8(CwType.of(classValue).descriptor()));
        } else if (value instanceof Enum<?> enumValue) {
            out.u1('e')
                    .u2(pool.utf8(CwType.of(enumValue.getDeclaringClass()).descriptor()))
                    .u2(pool.utf8(enumValue.name()));
        } else if (value instanceof Annotation nested) {
            out.u1('@');
            writeAnnotation(out, pool, nested);
        } else if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            out.u1('[').u2(length);
            for (int i = 0; i < length; i++) {
                writeElementValue(out, pool, java.lang.reflect.Array.get(value, i));
            }
        } else {
            throw new CodeGenerationException("cannot encode annotation element value "
                    + (value == null ? "null" : value.getClass().getName())
                    + "; the class-file format has no representation for it");
        }
    }
}
