package net.sf.cglib.core;

/**
 * CGLib's default naming policy, reproduced for code that subclasses or references it.
 *
 * <p>Like every {@link NamingPolicy}, it is accepted and ignored by the compat
 * {@code Enhancer}; see there for why.
 */
public class DefaultNamingPolicy implements NamingPolicy {

    /** Creates the policy; {@link #INSTANCE} serves the common case, as in CGLib. */
    public DefaultNamingPolicy() {
    }

    /** The shared instance, as CGLib exposed it. */
    public static final DefaultNamingPolicy INSTANCE = new DefaultNamingPolicy();

    @Override
    public String getClassName(String prefix, String source, Object key, Predicate names) {
        if (prefix == null) {
            prefix = "net.sf.cglib.empty.Object";
        } else if (prefix.startsWith("java")) {
            prefix = "$" + prefix;
        }
        String base = prefix + "$$"
                + source.substring(source.lastIndexOf('.') + 1)
                + getTag() + "$$"
                + Integer.toHexString(key == null ? 0 : key.hashCode());
        String attempt = base;
        int index = 2;
        while (names.evaluate(attempt)) {
            attempt = base + "_" + index++;
        }
        return attempt;
    }

    /**
     * The infix identifying the generator, historically {@code "ByCGLIB"}.
     *
     * @return the tag placed between the source name and the key hash
     */
    protected String getTag() {
        return "ByCGLIB";
    }

    /**
     * Tag-based, as CGLib defined it: two policies are interchangeable when they produce the
     * same names. Code that keys configuration on a naming policy relies on this.
     */
    @Override
    public int hashCode() {
        return getTag().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof DefaultNamingPolicy other && getTag().equals(other.getTag());
    }
}
