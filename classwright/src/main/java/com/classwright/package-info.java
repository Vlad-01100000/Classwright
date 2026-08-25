/**
 * Public entry points and types shared across the whole library.
 *
 * <p>Classwright generates classes at runtime: proxies, fast-reflection accessors, and bean
 * utilities. It is a clean-room replacement for CGLib with the same programming model, built so
 * that a new JDK release cannot break it.
 *
 * <h2>The two rules that shape everything else</h2>
 *
 * <p><strong>1. Bytecode is only ever written, never read.</strong> Everything Classwright needs to
 * know about a class it learns through core reflection ({@link java.lang.Class},
 * {@link java.lang.reflect.Method}, and friends). There is no class-file parser anywhere in this
 * library. That matters because a parser must be taught every new class-file format version, and a
 * parser that falls behind is what turned CGLib from "the standard" into "unusable on Java 17".
 * Reflection, by contrast, is a public API that every future JDK keeps working by definition.
 *
 * <p><strong>2. Classes are defined only through {@link java.lang.invoke.MethodHandles.Lookup}.</strong>
 * No {@code sun.misc.Unsafe}, no {@code setAccessible} against JDK internals, no reflective calls
 * into {@code ClassLoader.defineClass}. Those were the tricks that JDK 16's strong encapsulation
 * turned off, taking CGLib with them. Optional features that genuinely cannot be implemented
 * without an unstable API are quarantined behind a capability interface, so that when such an API
 * is eventually removed, one feature degrades instead of the library dying.
 *
 * <h2>Footprint</h2>
 *
 * <p>Generated classes are, by default, <em>hidden classes</em>. They can be garbage collected
 * independently of the class loader that defined them, so a long-running application that
 * continually creates proxies does not accumulate metaspace forever. Measurements are in
 * {@code docs/RESEARCH.md}; the short version is that the classic approach reclaims 0% of generated
 * classes and this one reclaims 100%.
 *
 * @see <a href="https://github.com/classwright/classwright">Project home</a>
 */
package com.classwright;
