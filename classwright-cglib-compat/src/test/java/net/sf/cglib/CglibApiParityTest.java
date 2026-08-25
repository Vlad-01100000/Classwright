package net.sf.cglib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Diffs real CGLib 3.3.0's public and protected API against this module's, mechanically.
 *
 * <p>The migration promise — "a change of dependency coordinate and nothing else" — is only as
 * good as the API overlap, and an overlap maintained by hand drifts. So the reference CGLib jar
 * (copied to {@code target/parity} by the build, never onto a classpath) is loaded in an
 * isolated class loader, every public type and every public/protected member is enumerated, and
 * every one this module does not reproduce must be listed — deliberately, with a reason — in
 * {@code api/cglib-parity-allowlist.txt}. An unlisted gap fails the build.
 *
 * <p>The comparison is by names and complete erased JVM descriptors — parameter <em>and
 * return</em> types — since the two sides live in different loaders and precompiled clients
 * link against descriptors, not source signatures. Visibility must not narrow and checked
 * exceptions must not be added. Additions on our side are ignored: they cannot break migrating
 * code.
 */
class CglibApiParityTest {

    private static final Path REFERENCE_JAR = Path.of("target", "parity", "cglib-reference.jar");
    private static final Path ASM_JAR = Path.of("target", "parity", "asm-reference.jar");
    private static final Path ALLOWLIST = Path.of("api", "cglib-parity-allowlist.txt");

    @Test
    @DisplayName("every CGLib API element is reproduced or deliberately allowlisted")
    void parityGapsAreAllExplicit() throws Exception {
        assumeTrue(Files.exists(REFERENCE_JAR) && Files.exists(ASM_JAR),
                "reference jars not copied; run through Maven so dependency:copy provides them");

        List<String> findings = new ArrayList<>();
        try (URLClassLoader cglib = referenceLoader()) {
            for (String name : publicClassNames()) {
                compareType(name, cglib, findings);
            }
        }

        Set<String> allowed = readAllowlist();
        List<String> unlisted = findings.stream()
                .filter(finding -> !isAllowed(finding, allowed))
                .sorted()
                .toList();

        if (!unlisted.isEmpty()) {
            StringBuilder message = new StringBuilder("CGLib 3.3.0 API elements this module does "
                    + "not reproduce and the allowlist does not name (")
                    .append(unlisted.size()).append("):\n");
            unlisted.forEach(line -> message.append("  ").append(line).append('\n'));
            message.append("Each is either a gap to close or a deliberate difference to add to ")
                    .append(ALLOWLIST).append(" with a comment saying why.");
            fail(message.toString());
        }
    }

    // ==========================================================================================
    // Reference side
    // ==========================================================================================

    private static URLClassLoader referenceLoader() throws IOException {
        // Platform parent: java.* resolves, this module's own net.sf.cglib classes do not, so
        // the reference cannot accidentally be compared against itself.
        return new URLClassLoader(
                new URL[]{REFERENCE_JAR.toUri().toURL(), ASM_JAR.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
    }

    private static List<String> publicClassNames() throws IOException {
        List<String> names = new ArrayList<>();
        try (JarFile jar = new JarFile(REFERENCE_JAR.toFile())) {
            for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
                String entry = entries.nextElement().getName();
                if (entry.startsWith("net/sf/cglib/") && entry.endsWith(".class")) {
                    names.add(entry.substring(0, entry.length() - ".class".length())
                            .replace('/', '.'));
                }
            }
        }
        return names;
    }

    private void compareType(String name, ClassLoader cglibLoader, List<String> findings)
            throws Exception {
        Class<?> reference;
        try {
            reference = Class.forName(name, false, cglibLoader);
        } catch (ClassNotFoundException | LinkageError unresolvable) {
            return;         // needs something beyond cglib+asm; not comparable, not API here
        }
        if (!isReachable(reference)) {
            return;         // package-private (or nested in package-private): not CGLib API
        }

        Class<?> ours;
        try {
            ours = Class.forName(name, false, getClass().getClassLoader());
        } catch (ClassNotFoundException missing) {
            findings.add("type " + name);
            return;
        }

        compareShape(name, reference, ours, findings);
        compareMembers(name, reference, ours, findings);
    }

    private static boolean isReachable(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
            if (!Modifier.isPublic(current.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    // ==========================================================================================
    // Shape: what subclassing, instanceof and `new` rely on
    // ==========================================================================================

    private static void compareShape(String name, Class<?> reference, Class<?> ours,
                                     List<String> findings) {
        if (!Modifier.isFinal(reference.getModifiers()) && Modifier.isFinal(ours.getModifiers())) {
            findings.add("shape " + name + " is final here but subclassable in CGLib");
        }
        if (!reference.isInterface() && !Modifier.isAbstract(reference.getModifiers())
                && Modifier.isAbstract(ours.getModifiers())) {
            findings.add("shape " + name + " is abstract here but instantiable in CGLib");
        }
        if (reference.isInterface() != ours.isInterface()) {
            findings.add("shape " + name + " is a "
                    + (ours.isInterface() ? "interface" : "class")
                    + " here but a " + (reference.isInterface() ? "interface" : "class")
                    + " in CGLib");
        }
        Set<String> ourInterfaces = interfaceNamesOf(ours);
        for (String implemented : interfaceNamesOf(reference)) {
            if (!ourInterfaces.contains(implemented)) {
                findings.add("shape " + name + " implements " + implemented);
            }
        }
        // Compared by name whatever package the superclass lives in — an earlier revision
        // checked only net.sf.cglib.* supers, which would have let an accidental change of a
        // RuntimeException/AbstractMap/Object parent slip through unlisted.
        Class<?> superclass = reference.getSuperclass();
        if (superclass != null && !superclass.getName().equals(nameOfSuperclass(ours))) {
            findings.add("shape " + name + " extends " + superclass.getName());
        }
        compareSerialVersion(name, reference, ours, findings);
    }

    /**
     * Serialized instances interoperate only when the stream identities agree — the UID
     * <em>and</em> the field schema. A matching UID with a missing field is the quiet failure
     * mode: deserialization succeeds and the unmatched field's value is silently dropped, which
     * is exactly how a real CGLib exception's cause vanished here before the field layouts were
     * reproduced. Compared only when both sides are serializable and both can be looked up —
     * the lookup can initialise the class, which some CGLib classes cannot survive in the
     * isolated loader; those are skipped, not failed, exactly like unreadable constants.
     */
    private static void compareSerialVersion(String name, Class<?> reference, Class<?> ours,
                                             List<String> findings) {
        java.io.ObjectStreamClass referenceStream = streamDescriptorOf(reference);
        java.io.ObjectStreamClass ourStream = streamDescriptorOf(ours);
        if (referenceStream == null || ourStream == null) {
            return;
        }
        if (referenceStream.getSerialVersionUID() != ourStream.getSerialVersionUID()) {
            findings.add("serial " + name + " has serialVersionUID "
                    + ourStream.getSerialVersionUID() + " here but "
                    + referenceStream.getSerialVersionUID() + " in CGLib");
        }
        Set<String> ourFields = new LinkedHashSet<>();
        for (java.io.ObjectStreamField field : ourStream.getFields()) {
            ourFields.add(serialSchemaOf(field));
        }
        for (java.io.ObjectStreamField field : referenceStream.getFields()) {
            String schema = serialSchemaOf(field);
            if (!ourFields.contains(schema)) {
                findings.add("serial-field " + name + " lacks CGLib's serialized field "
                        + schema);
            }
        }
    }

    /** Name plus JVM type signature; getTypeString() is null for primitives, the code is not. */
    private static String serialSchemaOf(java.io.ObjectStreamField field) {
        String type = field.getTypeString();
        return field.getName() + ":" + (type == null ? String.valueOf(field.getTypeCode()) : type);
    }

    private static java.io.ObjectStreamClass streamDescriptorOf(Class<?> type) {
        try {
            return java.io.ObjectStreamClass.lookup(type);
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    private static String nameOfSuperclass(Class<?> type) {
        return type.getSuperclass() == null ? "" : type.getSuperclass().getName();
    }

    private static Set<String> interfaceNamesOf(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            collectInterfaceNames(current.getInterfaces(), names);
        }
        return names;
    }

    private static void collectInterfaceNames(Class<?>[] interfaces, Set<String> into) {
        for (Class<?> each : interfaces) {
            if (into.add(each.getName())) {
                collectInterfaceNames(each.getInterfaces(), into);
            }
        }
    }

    // ==========================================================================================
    // Members: what calling code links against
    //
    // Identity is the complete JVM descriptor — name, parameters AND return type — because that
    // is what already-compiled client bytecode links against. Matching by name and parameters
    // alone declared `createClass()Ljava/lang/Class;` a reproduction of
    // `createClass()Ljava/lang/Object;`, and a precompiled client met a NoSuchMethodError that
    // the gate had signed off on. Visibility must not narrow, and checked exceptions must not
    // be added, for the same reason: both break callers that compiled against CGLib.
    // ==========================================================================================

    private void compareMembers(String name, Class<?> reference, Class<?> ours,
                                List<String> findings) {
        for (Field field : reference.getDeclaredFields()) {
            if (isApiMember(field.getModifiers()) && !field.isSynthetic()) {
                compareField(name, field, ours, findings);
            }
        }
        for (Constructor<?> constructor : reference.getDeclaredConstructors()) {
            if (isApiMember(constructor.getModifiers()) && !constructor.isSynthetic()) {
                compareConstructor(name, constructor, ours, findings);
            }
        }
        for (Method method : reference.getDeclaredMethods()) {
            if (isApiMember(method.getModifiers()) && !method.isSynthetic()
                    && !method.isBridge()) {
                compareMethod(name, method, ours, findings);
            }
        }
    }

    private static boolean isApiMember(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static int visibilityRank(int modifiers) {
        return Modifier.isPublic(modifiers) ? 2 : Modifier.isProtected(modifiers) ? 1 : 0;
    }

    private static String describeMethod(String owner, Method method) {
        return "method " + owner + "#" + (Modifier.isStatic(method.getModifiers())
                ? "static " : "") + method.getName() + "(" + parameterNames(method) + "):"
                + method.getReturnType().getTypeName();
    }

    private void compareMethod(String owner, Method wanted, Class<?> ours,
                               List<String> findings) {
        Method match = findMethod(ours, wanted);
        if (match == null) {
            findings.add(describeMethod(owner, wanted));
            return;
        }
        if (visibilityRank(match.getModifiers()) < visibilityRank(wanted.getModifiers())) {
            findings.add("visibility " + describeMethod(owner, wanted)
                    + " is narrower here than in CGLib");
        }
        if (!Modifier.isFinal(wanted.getModifiers()) && Modifier.isFinal(match.getModifiers())) {
            findings.add("shape " + describeMethod(owner, wanted)
                    + " is final here but overridable in CGLib");
        }
        Set<String> allowedExceptions = new LinkedHashSet<>();
        for (Class<?> declared : wanted.getExceptionTypes()) {
            allowedExceptions.add(declared.getName());
        }
        Set<String> ourExceptions = new LinkedHashSet<>();
        for (Class<?> declared : match.getExceptionTypes()) {
            ourExceptions.add(declared.getName());
            boolean checked = !RuntimeException.class.isAssignableFrom(declared)
                    && !Error.class.isAssignableFrom(declared);
            if (checked && !allowedExceptions.contains(declared.getName())) {
                findings.add("throws " + describeMethod(owner, wanted) + " adds "
                        + declared.getName());
            }
        }
        // Removal breaks source too, the quieter way round: a catch block for a checked
        // exception that a method no longer declares becomes unreachable and fails to compile.
        for (Class<?> declared : wanted.getExceptionTypes()) {
            boolean checked = !RuntimeException.class.isAssignableFrom(declared)
                    && !Error.class.isAssignableFrom(declared);
            if (checked && !ourExceptions.contains(declared.getName())) {
                findings.add("throws " + describeMethod(owner, wanted) + " removes "
                        + declared.getName());
            }
        }
    }

    /**
     * The matching method anywhere in our hierarchy — an inherited equivalent serves callers
     * just as well, since invocation resolves through the receiver type.
     */
    private static Method findMethod(Class<?> ours, Method wanted) {
        String parameters = parameterNames(wanted);
        String returnType = wanted.getReturnType().getTypeName();
        boolean wantedStatic = Modifier.isStatic(wanted.getModifiers());
        for (Class<?> current = ours; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (isApiMember(method.getModifiers()) && !method.isSynthetic()
                        && method.getName().equals(wanted.getName())
                        && Modifier.isStatic(method.getModifiers()) == wantedStatic
                        && parameterNames(method).equals(parameters)
                        && method.getReturnType().getTypeName().equals(returnType)) {
                    return method;
                }
            }
        }
        // Interface defaults (AbstractMap implements Map's shape, say) live off the superclass
        // chain; getMethods covers every public inherited member in one sweep.
        for (Method method : ours.getMethods()) {
            if (method.getName().equals(wanted.getName())
                    && Modifier.isStatic(method.getModifiers()) == wantedStatic
                    && parameterNames(method).equals(parameters)
                    && method.getReturnType().getTypeName().equals(returnType)) {
                return method;
            }
        }
        return null;
    }

    private static void compareConstructor(String owner, Constructor<?> wanted, Class<?> ours,
                                           List<String> findings) {
        String parameters = parameterNames(wanted);
        for (Constructor<?> constructor : ours.getDeclaredConstructors()) {
            if (parameterNames(constructor).equals(parameters)) {
                if (visibilityRank(constructor.getModifiers())
                        < visibilityRank(wanted.getModifiers())) {
                    findings.add("visibility constructor " + owner + "#(" + parameters
                            + ") is narrower here than in CGLib");
                }
                return;
            }
        }
        findings.add("constructor " + owner + "#(" + parameters + ")");
    }

    private static void compareField(String owner, Field wanted, Class<?> ours,
                                     List<String> findings) {
        String description = "field " + owner + "#" + wanted.getName()
                + ":" + wanted.getType().getTypeName();
        Field match = findField(ours, wanted);
        if (match == null) {
            findings.add(description);
            return;
        }
        if (visibilityRank(match.getModifiers()) < visibilityRank(wanted.getModifiers())) {
            findings.add("visibility " + description + " is narrower here than in CGLib");
        }
        if (Modifier.isStatic(match.getModifiers()) != Modifier.isStatic(wanted.getModifiers())) {
            findings.add("shape " + description + " differs in staticness");
        }
        // Public static final primitives and Strings are inlined into client constant pools at
        // compile time; a changed value silently diverges without any linkage failure at all.
        if (Modifier.isStatic(wanted.getModifiers()) && Modifier.isFinal(wanted.getModifiers())
                && (wanted.getType().isPrimitive() || wanted.getType() == String.class)) {
            Object reference = constantValue(wanted);
            Object mine = constantValue(match);
            if (reference != null && !reference.equals(mine)) {
                findings.add("constant " + description + " is " + mine + " here but "
                        + reference + " in CGLib");
            }
        }
    }

    private static Field findField(Class<?> ours, Field wanted) {
        for (Class<?> current = ours; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (isApiMember(field.getModifiers()) && field.getName().equals(wanted.getName())
                        && field.getType().getTypeName().equals(wanted.getType().getTypeName())) {
                    return field;
                }
            }
        }
        return null;
    }

    private static Object constantValue(Field field) {
        // Reading a static field initialises its class, and some CGLib classes cannot initialise
        // in the isolated reference loader (KeyFactory's constants run its generator, say). A
        // null here just skips the value comparison for that constant — the field's existence,
        // type and modifiers were already checked without initialising anything.
        try {
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    private static String parameterNames(Executable executable) {
        StringBuilder names = new StringBuilder();
        for (Class<?> parameter : executable.getParameterTypes()) {
            if (names.length() > 0) {
                names.append(',');
            }
            names.append(parameter.getTypeName());
        }
        return names.toString();
    }

    // ==========================================================================================
    // Allowlist
    // ==========================================================================================

    private static Set<String> readAllowlist() throws IOException {
        Set<String> allowed = new TreeSet<>();
        if (!Files.exists(ALLOWLIST)) {
            return allowed;
        }
        for (String line : Files.readAllLines(ALLOWLIST, StandardCharsets.UTF_8)) {
            String entry = line.strip();
            if (!entry.isEmpty() && !entry.startsWith("#")) {
                allowed.add(entry);
            }
        }
        return allowed;
    }

    /** Exact match, or prefix match for entries ending in {@code *}. */
    private static boolean isAllowed(String finding, Set<String> allowed) {
        if (allowed.contains(finding)) {
            return true;
        }
        for (String entry : allowed) {
            if (entry.endsWith("*")
                    && finding.startsWith(entry.substring(0, entry.length() - 1))) {
                return true;
            }
        }
        return false;
    }
}
