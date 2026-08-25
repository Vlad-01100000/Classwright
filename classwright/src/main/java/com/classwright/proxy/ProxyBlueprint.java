package com.classwright.proxy;

import com.classwright.ClasswrightException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * A proxy configuration described well enough to be built before the program runs.
 *
 * <p>The same thing an {@link Enhancer} is configured with, minus everything that only exists at
 * runtime. An {@code Enhancer} holds <em>callback instances</em>; a blueprint holds only their
 * <em>types</em>, because at build time there are no instances yet. Callbacks are still supplied
 * per instance at runtime, exactly as with {@link Enhancer#createClass()}.
 *
 * <h2>What this is for</h2>
 *
 * <p>GraalVM native images are compiled under a closed-world assumption: every class must exist at
 * build time, and defining one at runtime is not possible at all. That rules out the whole normal
 * path, which is to generate bytes and define them on demand. Pre-generating the classes into the
 * build output makes them ordinary compiled classes that native-image treats like any other, and
 * {@link AotProxies} lets the runtime find them instead of trying to generate.
 *
 * <p>It is also useful outside native images, as a way to move proxy generation off application
 * startup. See {@link AheadOfTime}.
 *
 * <h2>The key</h2>
 *
 * <p>{@link #key()} is what connects the two halves: the build writes it into an index beside the
 * generated class, and the runtime recomputes it from the {@code Enhancer}'s configuration to find
 * that class again. It is deliberately plain text — an index that cannot be read by eye is an index
 * nobody can debug.
 */
public final class ProxyBlueprint {

    private final Class<?> superclass;
    private final List<Class<?>> interfaces;
    private final List<Class<?>> callbackTypes;
    private final Class<? extends CallbackFilter> filter;
    private final boolean useFactory;
    private final boolean interceptDuringConstruction;
    private final boolean copyAnnotations;
    private final NamingConvention naming;

    private ProxyBlueprint(Builder builder) {
        this.superclass = builder.superclass;
        this.interfaces = List.copyOf(builder.interfaces);
        this.callbackTypes = List.copyOf(builder.callbackTypes);
        this.filter = builder.filter;
        this.useFactory = builder.useFactory;
        this.interceptDuringConstruction = builder.interceptDuringConstruction;
        this.copyAnnotations = builder.copyAnnotations;
        this.naming = builder.naming;
    }

    /**
     * Starts describing a proxy that extends {@code superclass}.
     *
     * @param superclass the class to extend, or {@code Object.class} for a pure interface proxy
     * @return a builder
     */
    public static Builder of(Class<?> superclass) {
        return new Builder(superclass);
    }

    /**
     * The identity used to match this configuration at runtime.
     *
     * <p>Stable across builds and JVMs: it is built from names and flags only, so it does not
     * depend on reflection ordering or on anything the JIT or the class loader might vary.
     *
     * <p>Interfaces appear <em>in configured order</em>, deliberately unsorted. Order is part of
     * the configuration's meaning: the first interface decides where a pure interface proxy is
     * placed (see {@link #placementNeighbour()}), and the order unrelated interfaces are listed in
     * is the order method discovery walks them. Sorting would let two configurations that place
     * and dispatch differently share one key — and one pre-generated class.
     *
     * <p>Every free-form component is delimiter-escaped, so that no two distinct configurations
     * can render as the same text. Without that, the boundaries are ambiguous: a naming convention
     * whose suffix ends where another's prefix begins, or a component containing the {@code |}
     * and {@code ,} separators (naming conventions are user-supplied strings), would concatenate
     * into an identical key. Tabs and newlines are escaped too, because the key is written into a
     * tab-separated, line-oriented index and must not be able to corrupt it.
     *
     * @return a plain-text key
     */
    public String key() {
        return String.join("|",
                escape(superclass.getName()),
                interfaces.stream().map(Class::getName).map(ProxyBlueprint::escape)
                        .collect(java.util.stream.Collectors.joining(",")),
                callbackTypes.stream().map(Class::getName).map(ProxyBlueprint::escape)
                        .collect(java.util.stream.Collectors.joining(",")),
                // Empty for "no filter": no class has an empty name, so this cannot collide with
                // a real one, where a printable sentinel like "-" in principle could.
                filter == null ? "" : escape(filter.getName()),
                Boolean.toString(useFactory),
                Boolean.toString(interceptDuringConstruction),
                Boolean.toString(copyAnnotations),
                // Two components, not a concatenation: ("$$CW", "X$") and ("$$CWX", "$") must not
                // meet in the middle and produce one key for two naming conventions.
                escape(naming.classNameSuffix()),
                escape(naming.memberPrefix()));
    }

    /**
     * Makes a component unable to imitate the key's structure.
     *
     * <p>Backslash-escapes the separators ({@code |} between components, {@code ,} within lists)
     * and the index format's own structure characters (tab, newline, carriage return). Escaped
     * text contains none of those characters raw, so the join above is unambiguous and the result
     * always stays a single field of a single index line.
     */
    private static String escape(String component) {
        StringBuilder escaped = new StringBuilder(component.length());
        for (int i = 0; i < component.length(); i++) {
            char c = component.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '|' -> escaped.append("\\|");
                case ',' -> escaped.append("\\,");
                case '\t' -> escaped.append("\\t");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /**
     * The binary name the generated class will have.
     *
     * <p>Placed in the neighbour's package so that package-private methods can be overridden, and
     * suffixed with a digest of {@link #key()} so that two configurations of the same target do not
     * collide. The digest makes the name a pure function of the configuration, which keeps builds
     * reproducible.
     *
     * @return a binary class name
     */
    public String generatedClassName() {
        Class<?> neighbour = placementNeighbour();
        String packageName = neighbour.getPackageName();
        String simple = neighbour.getName().substring(neighbour.getName().lastIndexOf('.') + 1);
        return (packageName.isEmpty() ? "" : packageName + ".")
                + simple + naming.classNameSuffix() + "$" + digest();
    }

    /** Where the proxy must live; see {@code Enhancer.placementNeighbour}. */
    Class<?> placementNeighbour() {
        if (superclass != Object.class || interfaces.isEmpty()) {
            return superclass;
        }
        return interfaces.get(0);
    }

    private String digest() {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(key().getBytes(StandardCharsets.UTF_8));
            // Eight bytes, not four. Class names collide at the birthday bound, and with a 32-bit
            // digest that is even odds at around 2^16 blueprints — a size a large application can
            // actually reach, and a collision here is two configurations silently overwriting each
            // other's class file. Sixteen hex characters push the bound out to ~2^32 blueprints
            // while keeping the name readable.
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new ClasswrightException("SHA-256 is required of every JVM", impossible);
        }
    }

    Class<?> superclass() {
        return superclass;
    }

    List<Class<?>> interfaces() {
        return interfaces;
    }

    List<Class<?>> callbackTypes() {
        return callbackTypes;
    }

    Class<? extends CallbackFilter> filter() {
        return filter;
    }

    boolean useFactory() {
        return useFactory;
    }

    boolean interceptDuringConstruction() {
        return interceptDuringConstruction;
    }

    boolean copyAnnotations() {
        return copyAnnotations;
    }

    NamingConvention naming() {
        return naming;
    }

    @Override
    public String toString() {
        return "ProxyBlueprint[" + key() + " -> " + generatedClassName() + "]";
    }

    /** Assembles a {@link ProxyBlueprint}. */
    public static final class Builder {

        private final Class<?> superclass;
        private final List<Class<?>> interfaces = new ArrayList<>();
        private final List<Class<?>> callbackTypes = new ArrayList<>();
        private Class<? extends CallbackFilter> filter;
        private boolean useFactory = true;
        private boolean interceptDuringConstruction = true;
        private boolean copyAnnotations;
        private NamingConvention naming = NamingConvention.DEFAULT;

        private Builder(Class<?> superclass) {
            this.superclass = Objects.requireNonNull(superclass, "superclass");
        }

        /**
         * Adds interfaces for the proxy to implement.
         *
         * @param types the interfaces
         * @return this builder
         */
        public Builder implementing(Class<?>... types) {
            interfaces.addAll(List.of(types));
            return this;
        }

        /**
         * Declares the callback types, in slot order.
         *
         * <p>Types, not instances: the instances are supplied at runtime. With more than one, a
         * {@link #filteredBy(Class) filter} decides which handles which method.
         *
         * @param types one per callback slot
         * @return this builder
         */
        public Builder callbacks(Class<?>... types) {
            callbackTypes.addAll(List.of(types));
            return this;
        }

        /**
         * Sets the filter that routes methods to callbacks.
         *
         * <p>A class rather than an instance, and it must have a no-argument constructor, because
         * the build has to run it to decide which callback handles which method.
         *
         * @param filterType the filter
         * @return this builder
         */
        public Builder filteredBy(Class<? extends CallbackFilter> filterType) {
            this.filter = filterType;
            return this;
        }

        /**
         * Whether the proxy implements {@link Factory}. Defaults to {@code true}.
         *
         * @param value whether to implement it
         * @return this builder
         */
        public Builder useFactory(boolean value) {
            this.useFactory = value;
            return this;
        }

        /**
         * Whether callbacks fire for calls made from within a constructor. Defaults to {@code true}.
         *
         * @param value whether they fire
         * @return this builder
         */
        public Builder interceptDuringConstruction(boolean value) {
            this.interceptDuringConstruction = value;
            return this;
        }

        /**
         * Whether to reproduce the target's annotations and generic signatures.
         *
         * @param value whether to copy them
         * @return this builder
         */
        public Builder copyAnnotations(boolean value) {
            this.copyAnnotations = value;
            return this;
        }

        /**
         * The member and class naming convention. Defaults to {@link NamingConvention#DEFAULT}.
         *
         * @param convention the convention
         * @return this builder
         */
        public Builder naming(NamingConvention convention) {
            this.naming = Objects.requireNonNull(convention, "convention");
            return this;
        }

        /**
         * Finishes the blueprint, validating that the configuration could actually generate.
         *
         * @return the finished blueprint
         */
        public ProxyBlueprint build() {
            if (callbackTypes.isEmpty()) {
                throw new ClasswrightException("a blueprint needs at least one callback type; "
                        + "these are the types of the callbacks that will be supplied at runtime");
            }
            if (callbackTypes.size() > 1 && filter == null) {
                throw new ClasswrightException("there are " + callbackTypes.size()
                        + " callback types but no CallbackFilter, so there is no way to decide "
                        + "which handles which method");
            }
            return new ProxyBlueprint(this);
        }
    }
}
