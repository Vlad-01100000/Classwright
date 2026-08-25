package com.classwright.runtime;

import java.lang.invoke.MethodHandles.Lookup;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a generated class is going to live: which package, which class loader, and with what
 * access.
 *
 * <p>Resolved once per target and then handed to a {@link DefinitionStrategy}. Holding the lookup
 * here rather than re-deriving it in each strategy means {@link LookupResolver} runs once, and
 * means a caller can ask {@link #canOverridePackagePrivate()} <em>before</em> generating anything
 * &mdash; which matters, because the answer changes what bytecode should be generated.
 *
 * @param neighbour the class the generated class will sit beside, normally the one being subclassed
 * @param lookup    a full-privilege lookup in the neighbour's package, or {@code null} if the
 *                  package is closed to Classwright
 */
public record DefinitionSite(Class<?> neighbour, Lookup lookup) {

    /**
     * Validates the components.
     *
     * @param neighbour the class whose package and loader are being targeted
     * @param lookup    a lookup on it, or {@code null} if none could be obtained
     */
    public DefinitionSite {
        Objects.requireNonNull(neighbour, "neighbour");
    }

    /**
     * Sites, resolved once per class.
     *
     * <p>{@code privateLookupIn} is not free, and the answer for a given class never changes: a
     * package that is open now cannot close later. Every proxy creation went through it before
     * this cache existed, including the ones that go on to hit the generation cache and produce no
     * class at all.
     *
     * <p>A {@link ClassValue}, so the entry lives on the neighbour class and nothing global holds a
     * class loader alive. The cached site holds a {@link Lookup} on the class it is keyed by — a
     * cycle, which the collector handles as a unit.
     */
    private static final ClassValue<DefinitionSite> SITES = new ClassValue<>() {
        @Override
        protected DefinitionSite computeValue(Class<?> neighbour) {
            return new DefinitionSite(neighbour,
                    LookupResolver.tryResolve(neighbour).orElse(null));
        }
    };

    /**
     * Resolves the site for a class, obtaining a lookup if the package allows it.
     *
     * <p>Does not fail when the package is closed. A closed package is a normal, recoverable
     * situation: it means the proxy goes in Classwright's own package with reduced override
     * ability, which is worth doing rather than refusing outright.
     *
     * @param neighbour the class to sit beside
     * @return the resolved site, cached per class
     */
    public static DefinitionSite of(Class<?> neighbour) {
        return SITES.get(neighbour);
    }

    /**
     * Drops the cached site for a class, so the next {@link #of} probes again.
     *
     * <p>The cached verdict includes "no lookup here", and while an open package can never
     * close, a closed one can open <em>later</em>: an instrumentation agent, or the target
     * module itself, calling {@code Module.addOpens} after the first probe. Nothing re-probes
     * automatically — the failure path would pay an exception per creation to notice an event
     * that almost never happens — so code that has just opened a package calls this and the
     * next creation sees the lookup.
     *
     * @param neighbour the class whose cached site should be re-resolved on next use
     */
    public static void forget(Class<?> neighbour) {
        SITES.remove(neighbour);
    }

    /**
     * The package the generated class will be defined into.
     *
     * @return the package a class defined here would join
     */
    public String packageName() {
        return neighbour.getPackageName();
    }

    /**
     * The loader the generated class will belong to.
     *
     * @return the loader a class defined here would belong to
     */
    public ClassLoader classLoader() {
        return neighbour.getClassLoader();
    }

    /**
     * The lookup on the neighbour, if one could be obtained.
     *
     * @return the lookup, or empty
     */
    public Optional<Lookup> maybeLookup() {
        return Optional.ofNullable(lookup);
    }

    /**
     * Whether a class can be defined into the neighbour's own package at all.
     *
     * @return whether the lookup carries full privilege access
     */
    public boolean hasFullPrivilegeLookup() {
        return lookup != null && (lookup.lookupModes() & Lookup.PACKAGE) != 0;
    }

    /**
     * Whether a <em>hidden</em> class can be defined here.
     *
     * <p>Stricter than {@link #hasFullPrivilegeLookup()}: {@code defineHiddenClass} demands a
     * lookup with full privilege access, which {@code defineClass} does not.
     *
     * <p>The distinction is not academic. {@code privateLookupIn} across a module boundary returns
     * a <em>teleported</em> lookup that has package access but has lost full privilege, and that
     * includes the case of a target loaded by a custom class loader — every class loader gets its
     * own unnamed module. Treating such a lookup as usable produced an
     * {@code IllegalAccessException} from deep inside definition instead of a clean fallback to the
     * child-loader strategy.
     *
     * @return whether {@code defineHiddenClass} would be permitted here
     */
    public boolean canDefineHiddenClass() {
        return lookup != null && lookup.hasFullPrivilegeAccess();
    }

    /**
     * Whether a generated subclass could override the neighbour's package-private methods.
     *
     * <p>True only when the generated class ends up in the same runtime package, which means the
     * same package name <em>and</em> the same class loader. A lookup gives us both. Without one the
     * class goes elsewhere and package-private methods are simply invisible to it &mdash; not an
     * error, but something the generator must know so it can leave those methods alone rather than
     * emitting an override that silently never runs.
     *
     * @return whether a class defined here would share a runtime package with the neighbour
     */
    public boolean canOverridePackagePrivate() {
        return hasFullPrivilegeLookup();
    }

    /**
     * Explains why a lookup could not be obtained.
     *
     * @return remediation instructions
     * @throws IllegalStateException if a lookup was in fact obtained
     */
    public String explainMissingLookup() {
        if (lookup != null && !lookup.hasFullPrivilegeAccess()) {
            return "Cannot define a hidden class beside " + neighbour.getName()
                    + ": the lookup obtained for it is teleported and lacks full privilege access.\n"
                    + "That happens when the class lives in a different module from Classwright, "
                    + "including the case of a custom class loader — every loader has its own "
                    + "unnamed module.\n"
                    + "Classwright falls back to a child class loader here, which works but cannot "
                    + "override package-private methods.";
        }
        if (hasFullPrivilegeLookup()) {
            throw new IllegalStateException("a lookup was obtained; there is nothing to explain");
        }
        return LookupResolver.explainInaccessible(neighbour);
    }
}
