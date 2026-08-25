package net.sf.cglib.reflect;

import com.classwright.cglib.Coexistence;

/**
 * Fans one call out to several implementations of an interface.
 *
 * <p>Reproduces {@code net.sf.cglib.reflect.MulticastDelegate}'s factory, delegating to
 * {@link com.classwright.reflect.MulticastDelegate}. As in CGLib, {@code add} and {@code remove}
 * return a new delegate and leave the receiver untouched, and where the interface method returns
 * a value the last target's result wins.
 *
 * <p><strong>One deliberate difference, and it is bigger than the other wrappers':</strong>
 * {@link #create(Class)} returns {@link com.classwright.reflect.MulticastDelegate}, not this type.
 * CGLib's generated class extended its {@code MulticastDelegate}; Classwright's extends its own,
 * and a class cannot extend both. Returning {@code Object} — the {@code Mixin} answer — would
 * leave {@code add} and {@code remove} unreachable, and a wrapper of this type would not be
 * castable to the fanned-out interface, which is the whole point of the class. So the underlying
 * delegate is returned as its own type: declare the variable {@code var} (or the returned type),
 * and everything else — {@code add}, {@code remove}, the cast to your interface — runs unchanged.
 *
 * <p>Two smaller differences on that instance surface: {@code getTargets()} returns
 * {@code Object[]} where CGLib returned a {@code List}, and there is no public no-argument
 * {@code newInstance()} — it was CGLib's internal hook for {@code add}; call {@link #create(Class)}
 * again instead, which is a cache hit.
 *
 * @see com.classwright.reflect.MulticastDelegate
 */
public final class MulticastDelegate {

    static {
        Coexistence.check();
    }

    private MulticastDelegate() {
    }

    /**
     * Creates an empty delegate for a single-method interface.
     *
     * @param interfaceType the interface to fan out
     * @return an empty delegate; add targets with {@code add}, then cast to {@code interfaceType}
     *         to invoke the fan-out
     */
    public static com.classwright.reflect.MulticastDelegate create(Class interfaceType) {
        return com.classwright.reflect.MulticastDelegate.create(interfaceType);
    }
}
