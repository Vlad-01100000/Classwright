package com.classwright.runtime;

import com.classwright.ClasswrightException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Defines generated classes next to a target class, choosing how automatically.
 *
 * <pre>{@code
 * ClassDefiner definer = ClassDefiner.alongside(Service.class);
 * if (definer.canOverridePackagePrivate()) { ... generate accordingly ... }
 * DefinedClass proxy = definer.define(bytes);
 * Object instance = proxy.constructor().invoke();
 * }</pre>
 *
 * <h2>How the strategy is chosen</h2>
 *
 * <p>{@link DefinitionStrategy#hidden()} whenever the target's package can be reached, because it
 * is the only option that lets generated classes be collected. When the package is closed &mdash;
 * a named module that does not {@code opens} it &mdash; it falls back to
 * {@link DefinitionStrategy#childLoader()} rather than failing, since a working proxy with reduced
 * override ability beats no proxy at all.
 *
 * <p>Callers who need a specific strategy can say so with {@link #using}. Anyone doing that to get
 * {@link DefinitionStrategy#named()} should read what that costs first.
 */
public final class ClassDefiner {

    /** Set {@code -Dclasswright.dumpDir=/some/path} to write every generated class to disk. */
    public static final String DUMP_DIRECTORY_PROPERTY = "classwright.dumpDir";

    private static final AtomicLong DUMP_SEQUENCE = new AtomicLong();

    private final DefinitionSite site;
    private final DefinitionStrategy strategy;

    private ClassDefiner(DefinitionSite site, DefinitionStrategy strategy) {
        this.site = site;
        this.strategy = strategy;
    }

    /**
     * A definer that places classes beside {@code neighbour}, picking the best available strategy.
     *
     * @param neighbour the class being extended or proxied
     * @return a definer ready to use
     */
    public static ClassDefiner alongside(Class<?> neighbour) {
        DefinitionSite site = DefinitionSite.of(Objects.requireNonNull(neighbour, "neighbour"));
        DefinitionStrategy preferred = DefinitionStrategy.hidden();
        DefinitionStrategy chosen = preferred.isUsableAt(site) && Capabilities.hiddenClasses()
                ? preferred
                : DefinitionStrategy.childLoader();
        return new ClassDefiner(site, chosen);
    }

    /**
     * A definer using a specific strategy.
     *
     * @param neighbour the class being extended or proxied
     * @param strategy  the strategy to use
     * @return a definer ready to use
     * @throws ClasswrightException if the strategy cannot work at this site, with the reason
     */
    public static ClassDefiner using(Class<?> neighbour, DefinitionStrategy strategy) {
        DefinitionSite site = DefinitionSite.of(Objects.requireNonNull(neighbour, "neighbour"));
        if (!strategy.isUsableAt(site)) {
            throw new ClasswrightException("the '" + strategy.name() + "' definition strategy "
                    + "cannot be used here.\n" + site.explainMissingLookup());
        }
        return new ClassDefiner(site, strategy);
    }

    /**
     * Where classes defined here will live.
     *
     * @return the package and loader being targeted
     */
    public DefinitionSite site() {
        return site;
    }

    /**
     * How classes defined here become loaded classes.
     *
     * @return the strategy in use
     */
    public DefinitionStrategy strategy() {
        return strategy;
    }

    /**
     * The package name generated classes will carry.
     *
     * <p>Note that a matching name does not imply a matching <em>runtime</em> package: under
     * {@link DefinitionStrategy.ChildLoader} the class has the same package name but a different
     * loader, which makes it a stranger as far as access control is concerned. Use
     * {@link #canOverridePackagePrivate()} for that question.
     *
     * @return the package generated classes will be placed in
     */
    public String packageName() {
        return site.packageName();
    }

    /**
     * Whether a generated subclass here could override the target's package-private methods.
     *
     * <p>Worth asking <em>before</em> generating. Two classes share a runtime package only if they
     * share both a package name and a class loader, so a child loader never qualifies however its
     * classes are named. A generator that ignores this emits an override that compiles, verifies,
     * loads, and is never called &mdash; the worst kind of failure.
     *
     * @return whether a class defined here can override package-private methods of its superclass
     */
    public boolean canOverridePackagePrivate() {
        return !(strategy instanceof DefinitionStrategy.ChildLoader)
                && site.canOverridePackagePrivate();
    }

    /** Distinguishes generated class names where the strategy requires them to be unique. */
    private static final AtomicLong NAME_SEQUENCE = new AtomicLong();

    /**
     * A legal internal name for a class generated beside {@code target}.
     *
     * <p>Normally {@code target}'s own name with the suffix appended, which keeps the generated
     * class in the target's package and preserves the {@code $$} marker that framework heuristics
     * look for.
     *
     * <p>A target in {@code java.*} is the exception when the bytes will go to a <em>child
     * loader</em>: a class loader is forbidden from defining anything under {@code java.*} at
     * all, whatever the name. Such classes are relocated into Classwright's own generated-code
     * package, with the target's name mangled into the simple name so it stays identifiable.
     * Note the question is which mechanism defines the bytes, not whether a lookup exists — a
     * teleported lookup can carry package access while strategy selection has already fallen
     * back to the child loader, and naming for the lookup then produces a
     * {@code SecurityException} from the loader.
     *
     * <p>Hidden classes are not registered under their name, so two may share one. Every other
     * strategy registers the class with a loader, and a loader rejects a duplicate — which the
     * shared child loader would otherwise guarantee for the second of two <em>different</em>
     * configurations against one target. Resolvable names therefore get a unique suffix here,
     * for every caller, rather than in whichever generators happen to remember.
     *
     * @param target the class being generated beside
     * @param suffix e.g. {@code "$$CW"}; should contain {@code $$}
     * @return an internal name that this definer can actually define
     */
    public String generatedNameFor(Class<?> target, String suffix) {
        String name = stripHiddenSuffix(target.getName());
        String base = !target.getPackageName().startsWith("java.") || definesThroughSiteLookup()
                ? name.replace('.', '/') + suffix
                : "com/classwright/generated/" + name.replace('.', '$') + suffix;
        return strategy.producesResolvableNames()
                ? base + "$" + NAME_SEQUENCE.incrementAndGet()
                : base;
    }

    /**
     * Whether this definer hands bytes to the site's lookup, as opposed to a child loader.
     *
     * <p>This, not the lookup's own capabilities, is what the naming methods must ask: only a
     * lookup may define a {@code java.*} name, and the strategy in use decides whether the
     * lookup is the thing defining.
     */
    private boolean definesThroughSiteLookup() {
        return !(strategy instanceof DefinitionStrategy.ChildLoader)
                && site.hasFullPrivilegeLookup();
    }

    /**
     * Removes the {@code /0x...} a hidden class carries in its name.
     *
     * <p>A hidden class reports a name like {@code com.example.Foo$$CW/0x00007f...}, which is not a
     * valid binary name — that is the point, since it cannot be resolved. Generating a class
     * <em>beside</em> one (a {@code BeanMap} over a generated bean, say) has to derive a name from
     * it, and carrying the suffix through would produce an internal name with a stray slash in it.
     */
    private static String stripHiddenSuffix(String className) {
        int suffix = className.indexOf('/');
        return suffix < 0 ? className : className.substring(0, suffix);
    }

    /**
     * A legal internal name for a generated class with a chosen simple name.
     *
     * <p>Like {@link #generatedNameFor}, but for classes whose name is not derived from a target
     * — a generated interface, for instance. Falls back to Classwright's own generated-code
     * package when the bytes will go to a child loader and the site's package is {@code java.*}.
     *
     * <p>No unique suffix is appended here, deliberately: the callers of this method either
     * sequence their names themselves or are relaying a name the user chose explicitly, and an
     * explicitly chosen name must come out exactly as chosen.
     *
     * @param simpleName the class's simple name
     * @return an internal name this definer can define
     */
    public String generatedNameInPackage(String simpleName) {
        String packageName = site.packageName();
        if (packageName.startsWith("java.") && !definesThroughSiteLookup()) {
            return "com/classwright/generated/" + simpleName;
        }
        return packageName.isEmpty()
                ? simpleName
                : packageName.replace('.', '/') + "/" + simpleName;
    }

    /**
     * Defines a class from the given bytes.
     *
     * @param classBytes a complete class file
     * @return the loaded class
     */
    public DefinedClass define(byte[] classBytes) {
        dumpIfRequested(classBytes);
        return strategy.define(site, classBytes);
    }

    /**
     * Writes the bytes to disk when {@code -Dclasswright.dumpDir} is set.
     *
     * <p>The equivalent of CGLib's {@code DebuggingClassWriter}, and just as necessary. When a
     * generated class misbehaves, the first thing anyone wants is the actual class file to run
     * {@code javap} against. Failures here are reported but never propagated: a debugging aid must
     * not be able to break the thing it is helping debug.
     */
    /**
     * Read once at class load, not per definition. {@code System.getProperty} synchronises on the
     * global properties table, and paying that on every definition — during exactly the parallel
     * warm-up storms where definitions cluster — bought nothing: a dump directory is set on the
     * command line, not toggled mid-run. Volatile so the test seam below is sound.
     */
    private static volatile String dumpDirectory = readDumpDirectory();

    private static String readDumpDirectory() {
        String directory = System.getProperty(DUMP_DIRECTORY_PROPERTY);
        return directory == null || directory.isBlank() ? null : directory;
    }

    /** Re-reads the dump property. For tests, which set it after this class has loaded. */
    static void refreshDumpDirectory() {
        dumpDirectory = readDumpDirectory();
    }

    private void dumpIfRequested(byte[] classBytes) {
        String directory = dumpDirectory;
        if (directory == null) {
            return;
        }
        try {
            Path target = Path.of(directory);
            Files.createDirectories(target);
            // Hidden classes have no usable name at this point, and two proxies of one target
            // would collide, so a sequence number keeps dumps distinct.
            String fileName = site.neighbour().getSimpleName()
                    + "$$CW-" + DUMP_SEQUENCE.incrementAndGet() + ".class";
            Files.write(target.resolve(fileName), classBytes);
        } catch (IOException | RuntimeException e) {
            System.err.println("[classwright] could not dump generated class to '" + directory
                    + "': " + e);
        }
    }

    @Override
    public String toString() {
        return "ClassDefiner[" + site.neighbour().getName() + " via " + strategy.name() + "]";
    }
}
