/**
 * JavaBean utilities: copying, mapping, bulk access, and bean generation.
 *
 * <p>Mirrors {@code net.sf.cglib.beans}. Each of these replaces a loop of reflective calls with a
 * generated method that performs direct ones, which is worth doing where the same shape is
 * processed repeatedly — mapping rows to objects, converting between entity and DTO, serialising.
 *
 * <ul>
 *   <li>{@link com.classwright.beans.BeanCopier} — moves matching properties between two beans.</li>
 *   <li>{@link com.classwright.beans.BulkBean} — moves a chosen set of properties between a bean
 *       and an {@code Object[]}.</li>
 *   <li>{@link com.classwright.beans.BeanMap} — a {@link java.util.Map} view over a bean.</li>
 *   <li>{@link com.classwright.beans.BeanGenerator} — builds a bean class from a shape known only
 *       at runtime.</li>
 *   <li>{@link com.classwright.beans.ImmutableBean} — a read-only view whose setters throw.</li>
 * </ul>
 *
 * <p>Property discovery is implemented here rather than with {@code java.beans.Introspector},
 * which lives in the {@code java.desktop} module. Depending on it would mean requiring a module
 * absent from most server runtimes and headless images, to implement a convention that is a handful
 * of naming rules.
 *
 * <p>The copier, map, and bulk accessor classes are hidden classes: cached per shape, and
 * reclaimed once nothing refers to them. {@link com.classwright.beans.BeanGenerator} is the
 * exception — its product must be an ordinary, resolvable, reflectable class, and such classes
 * are retained for the life of their loader exactly as CGLib's were. Build one class per shape
 * and reuse it; generating a named bean class per request leaks metaspace, and the class's own
 * documentation says so too.
 */
package com.classwright.beans;
