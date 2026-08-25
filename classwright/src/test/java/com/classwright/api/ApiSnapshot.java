package com.classwright.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Renders the module's public API as canonical text, so that changes to it show up as a diff.
 *
 * <h2>Why not just japicmp</h2>
 *
 * <p>japicmp answers a different and equally necessary question &mdash; "is this binary compatible
 * with the last <em>release</em>" &mdash; and it runs at release time against a published artifact.
 * It cannot help before the first release, and it does not make an API change visible in the pull
 * request that causes it. A checked-in snapshot does both: adding a public method turns into a
 * one-line diff a reviewer has to approve, and it works from the first commit onward.
 *
 * <p>It is also written here rather than taken from a library, for the same reason the rest of this
 * project is: an API gate that stops working on a new JDK is an API gate that gets switched off.
 * Everything below is core reflection over the compiled output.
 *
 * <h2>What is rendered</h2>
 *
 * <p>Public and protected members of public types in exported packages. Protected members count:
 * a subclass in another package can see them, so removing one breaks callers.
 *
 * <p>Erasure plus generic type names, not descriptors. Generics do not affect binary compatibility
 * but they do affect whether existing source still compiles, and both matter to a consumer.
 *
 * <p>Constant values of {@code static final} primitives and strings are included, because the
 * compiler inlines them into calling code: changing one leaves already-compiled callers using the
 * old value, which is a compatibility break that no signature diff would show.
 *
 * <p>Synthetic and bridge members are excluded &mdash; they are compiler artefacts, and which ones
 * appear can differ between javac versions.
 *
 * <p>Types annotated {@code @com.classwright.Internal} are rendered as a single opaque line.
 * Their <em>existence</em> stays a reviewed fact &mdash; removing one still shows up as a diff
 * &mdash; but their members are not frozen: they are the generated-code runtime ABI, public only
 * because the JVM's accessibility rules demand it, and freezing their shape would freeze the
 * implementation. See {@code com.classwright.Internal} for the three-contract model.
 */
final class ApiSnapshot {

    private ApiSnapshot() {
    }

    /**
     * Renders the API of the compiled module at {@code classesDirectory}.
     *
     * @param classesDirectory an output directory containing {@code module-info.class}
     * @return canonical, sorted, newline-separated text
     */
    static String render(Path classesDirectory) {
        Set<String> exported = exportedPackages(classesDirectory);
        List<Class<?>> types = publicTypesIn(classesDirectory, exported);

        StringBuilder text = new StringBuilder();
        text.append("# Classwright public API snapshot.\n")
                .append("# Regenerate with: mvn -pl classwright verify -Dclasswright.api.update=true\n")
                .append("# Any diff here is an API change and must be a deliberate, reviewed one.\n")
                .append("# Exported packages: ").append(String.join(", ", new TreeSet<>(exported)))
                .append("\n");

        for (Class<?> type : types) {
            if (isInternal(type)) {
                // Existence is API; shape is not. One line, no members.
                text.append('\n').append("@Internal ").append(type.getName())
                        .append(" (generated-code runtime ABI; no compatibility promise)")
                        .append('\n');
                continue;
            }
            text.append('\n').append(describeType(type)).append('\n');
            for (String member : membersOf(type)) {
                text.append("    ").append(member).append('\n');
            }
        }
        return text.toString();
    }

    /**
     * Whether the type (or an enclosing type) opted out of the member-level freeze.
     *
     * <p>By name rather than by class literal so this tool has no compile-time dependency on the
     * module's own annotation — the snapshot must keep rendering even while the API it renders is
     * being rearranged.
     */
    private static boolean isInternal(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
            for (java.lang.annotation.Annotation annotation : current.getAnnotations()) {
                if (annotation.annotationType().getName().equals("com.classwright.Internal")) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==============================================================================================
    // Discovery
    // ==============================================================================================

    /**
     * Reads the exported package list from the compiled module descriptor.
     *
     * <p>Via {@link ModuleFinder}, so the JDK does the reading. Classwright never parses class files
     * itself, and its own tooling should not be the exception.
     */
    private static Set<String> exportedPackages(Path classesDirectory) {
        ModuleDescriptor descriptor = ModuleFinder.of(classesDirectory).findAll().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no module descriptor under " + classesDirectory.toAbsolutePath()
                                + ". module-info.class is compiled in the prepare-package phase, so "
                                + "this must run as an integration test, not a unit test."))
                .descriptor();

        return descriptor.exports().stream()
                .map(ModuleDescriptor.Exports::source)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static List<Class<?>> publicTypesIn(Path classesDirectory, Set<String> exported) {
        try (Stream<Path> files = Files.walk(classesDirectory)) {
            return files
                    .filter(path -> path.toString().endsWith(".class"))
                    .map(path -> binaryName(classesDirectory, path))
                    .filter(name -> !name.equals("module-info"))
                    .filter(name -> !name.endsWith("package-info"))
                    .filter(name -> exported.contains(packageOf(name)))
                    // The type witness and the explicit lambda parameter are load-bearing.
                    // load(...) returns Class<?>, and IDE type resolvers (unlike javac 17 and
                    // 24, which accept the plain chain) capture that wildcard at the map step
                    // into a fresh variable: the stream becomes Stream<Class<CAP>>, toList()
                    // yields List<? extends Class<?>>, and the return is flagged against the
                    // declared List<Class<?>>. Pinning the element type here leaves nothing to
                    // capture, so javac and the IDEs finally tell the same story.
                    .<Class<?>>map(ApiSnapshot::load)
                    .filter(ApiSnapshot::isPartOfTheApi)
                    .sorted(Comparator.comparing((Class<?> type) -> type.getName()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not walk " + classesDirectory, e);
        }
    }

    /** A file name, not a class file: listing names is not parsing, and the no-parser rule holds. */
    private static String binaryName(Path root, Path classFile) {
        String relative = root.relativize(classFile).toString();
        return relative
                .substring(0, relative.length() - ".class".length())
                .replace('\\', '.')
                .replace('/', '.');
    }

    private static String packageOf(String binaryName) {
        int lastDot = binaryName.lastIndexOf('.');
        return lastDot < 0 ? "" : binaryName.substring(0, lastDot);
    }

    private static Class<?> load(String binaryName) {
        try {
            return Class.forName(binaryName, false, ApiSnapshot.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("compiled but not loadable: " + binaryName, e);
        }
    }

    /**
     * Whether consumers can actually reach this type.
     *
     * <p>A public type nested inside a package-private one is not reachable, so it is not API. The
     * whole enclosing chain has to be public.
     */
    private static boolean isPartOfTheApi(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
            if (!Modifier.isPublic(current.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    // ==============================================================================================
    // Rendering
    // ==============================================================================================

    private static String describeType(Class<?> type) {
        String kind = type.isAnnotation() ? "@interface"
                : type.isInterface() ? "interface"
                : type.isEnum() ? "enum"
                : type.isRecord() ? "record"
                : "class";

        String modifiers = Modifier.toString(type.getModifiers() & Modifier.classModifiers());
        if (!kind.equals("class") && !kind.equals("record")) {
            // Implicit on interfaces and enums, and javac has not always set the bits identically.
            modifiers = modifiers.replace("abstract", "").replace("final", "").replaceAll("\\s+", " ");
        }

        StringBuilder line = new StringBuilder(modifiers.trim());
        line.append(' ').append(kind).append(' ').append(type.getName());

        Type superclass = type.getGenericSuperclass();
        if (superclass != null && superclass != Object.class && !type.isEnum() && !type.isRecord()) {
            line.append(" extends ").append(superclass.getTypeName());
        }
        List<String> interfaces = Stream.of(type.getGenericInterfaces())
                .map(Type::getTypeName)
                .sorted()
                .toList();
        if (!interfaces.isEmpty()) {
            line.append(" implements ").append(String.join(", ", interfaces));
        }
        return line.toString();
    }

    private static List<String> membersOf(Class<?> type) {
        List<String> members = new ArrayList<>();

        for (Field field : type.getDeclaredFields()) {
            if (isVisible(field.getModifiers()) && !field.isSynthetic()) {
                members.add(describeField(field));
            }
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (isVisible(constructor.getModifiers()) && !constructor.isSynthetic()) {
                members.add(describeExecutable(constructor, "<init>", null));
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (isVisible(method.getModifiers()) && !method.isSynthetic() && !method.isBridge()) {
                members.add(describeExecutable(
                        method, method.getName(), method.getGenericReturnType()));
            }
        }
        members.sort(Comparator.naturalOrder());
        return members;
    }

    /** Protected counts: a subclass elsewhere can see it, so removing it breaks that subclass. */
    private static boolean isVisible(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static String describeField(Field field) {
        String line = (Modifier.toString(field.getModifiers() & Modifier.fieldModifiers())
                + " " + field.getGenericType().getTypeName() + " " + field.getName()).trim();

        // Constants are inlined into callers at compile time, so a changed value breaks code that
        // was compiled against the old one without any signature having changed.
        if (Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())
                && (field.getType().isPrimitive() || field.getType() == String.class)) {
            try {
                field.setAccessible(true);
                line += " = " + formatConstant(field.get(null));
            } catch (ReflectiveOperationException | RuntimeException notReadable) {
                line += " = <unreadable>";
            }
        }
        return line;
    }

    private static String formatConstant(Object value) {
        return value instanceof String text ? "\"" + text + "\"" : String.valueOf(value);
    }

    private static String describeExecutable(Executable executable, String name, Type returnType) {
        int mask = executable instanceof Method
                ? Modifier.methodModifiers()
                : Modifier.constructorModifiers();

        StringBuilder line = new StringBuilder(
                Modifier.toString(executable.getModifiers() & mask).trim());
        if (line.length() > 0) {
            line.append(' ');
        }
        if (returnType != null) {
            line.append(returnType.getTypeName()).append(' ');
        }
        line.append(name).append('(');

        StringJoiner parameters = new StringJoiner(", ");
        for (Type parameter : executable.getGenericParameterTypes()) {
            parameters.add(parameter.getTypeName());
        }
        line.append(parameters).append(')');

        List<String> thrown = Stream.of(executable.getGenericExceptionTypes())
                .map(Type::getTypeName)
                .sorted()
                .toList();
        if (!thrown.isEmpty()) {
            line.append(" throws ").append(String.join(", ", thrown));
        }
        return line.toString();
    }
}
