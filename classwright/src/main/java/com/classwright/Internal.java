package com.classwright;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public type that is <em>not</em> part of Classwright's supported API.
 *
 * <p>Classwright has three compatibility contracts, not one:
 *
 * <ul>
 * <li><strong>Supported user API</strong> — what applications compile against. Covered by the API
 * snapshot; changes are deliberate and reviewed.</li>
 * <li><strong>Generated-code runtime ABI</strong> — helpers that generated classes call. These
 * must be {@code public} and exported, because generated classes live in the <em>target's</em>
 * package (and possibly module), not Classwright's; the JVM's accessibility rules leave no
 * choice. Visibility is not a promise: these types exist for bytecode Classwright itself emits,
 * their shape is versioned with the runtime, and they may change in any release that also
 * changes the generator.</li>
 * <li><strong>Internal implementation</strong> — everything else, kept package-private where the
 * language allows.</li>
 * </ul>
 *
 * <p>This annotation marks the second and, where package-private is impossible, third kind. The
 * API snapshot lists an annotated type as a single opaque line — so its <em>existence</em> is
 * still a reviewed fact — but none of its members are frozen. Application code that calls an
 * {@code @Internal} type is on its own: nothing about it is guaranteed between releases, and the
 * freedom to reshape these helpers is precisely what lets the library chase future JDKs without
 * breaking anyone.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Internal {
}
