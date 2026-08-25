/**
 * The quarantine: the only package permitted to touch APIs that are not part of the supported JDK.
 *
 * <h2>Why this package exists at all</h2>
 *
 * <p>Classwright's central promise is that a new JDK release cannot break it, and the mechanism is
 * that everything runs on public, supported API. One capability resists that: constructing an
 * object <em>without running its constructor</em>. Deserialization frameworks need it, CGLib users
 * expect it, and the JDK has said plainly that a supported replacement is a long-term project.
 * {@code sun.misc.Unsafe::allocateInstance} is being retained for the medium term precisely because
 * no alternative exists yet.
 *
 * <p>Pretending otherwise would be dishonest, and using it casually would repeat CGLib's mistake.
 * So it is confined here, under three rules:
 *
 * <ol>
 *   <li><strong>Reached only through an interface.</strong> Nothing outside this package names an
 *       unstable type. Callers depend on
 *       {@link com.classwright.runtime.unsafe.ConstructorSkippingAllocator}, which is ordinary
 *       Java.</li>
 *   <li><strong>Accessed reflectively, never by import.</strong> There is no compile-time
 *       dependency on any JDK-internal class, so the library compiles and links on a JDK that has
 *       removed them entirely.</li>
 *   <li><strong>Allowed to be absent.</strong> Every implementation reports whether it works on the
 *       running JVM. When the day comes that none of them do, one optional feature stops working
 *       and everything else carries on.</li>
 * </ol>
 *
 * <p>The architecture rules enforce rule 1 by making this the single package exempt from the ban on
 * JDK-internal references; see {@code ArchitectureRules.UNSTABLE_API_QUARANTINE}.
 *
 * @see <a href="https://openjdk.org/jeps/471">JEP 471: Deprecate the Memory-Access Methods in
 *      sun.misc.Unsafe for Removal</a>
 * @see <a href="https://openjdk.org/jeps/498">JEP 498: Warn upon Use of Memory-Access Methods in
 *      sun.misc.Unsafe</a>
 */
package com.classwright.runtime.unsafe;
