package net.sf.cglib.core;

import java.util.Objects;

/**
 * A method's name and descriptor.
 *
 * <p>Reproduces {@code net.sf.cglib.core.Signature}, <strong>partially</strong>. CGLib's version
 * also exposed {@code getReturnType()} and {@code getArgumentTypes()} returning
 * {@code org.objectweb.asm.Type}, and those are absent here: providing them would mean depending on
 * ASM, which is the dependency Classwright exists to remove. Fragmenting the ecosystem around
 * shaded ASM copies is what made CGLib unusable, and reintroducing it to reproduce two accessors
 * would be a poor trade.
 *
 * <p>Code that only reads {@link #getName()} and {@link #getDescriptor()} — which is most code —
 * compiles and runs unchanged. Code that reads the ASM-typed accessors does not, and there is no
 * way to make it, which the migration guide says plainly.
 */
public class Signature {

    private final String name;
    private final String descriptor;

    /**
     * Creates a signature from a name and a JVM method descriptor.
     *
     * @param name       the method name
     * @param descriptor the JVM method descriptor, e.g. {@code (IJ)Ljava/lang/String;}
     */
    public Signature(String name, String descriptor) {
        this.name = Objects.requireNonNull(name, "name");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }

    /**
     * The method name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * The method descriptor.
     *
     * @return the descriptor, in JVM form
     */
    public String getDescriptor() {
        return descriptor;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Signature that
                && name.equals(that.name) && descriptor.equals(that.descriptor);
    }

    @Override
    public int hashCode() {
        return name.hashCode() * 31 + descriptor.hashCode();
    }

    @Override
    public String toString() {
        return name + descriptor;
    }
}
