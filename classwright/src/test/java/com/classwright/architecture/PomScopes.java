package com.classwright.architecture;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Checks that a POM declares no dependency that would reach a consumer's runtime classpath.
 *
 * <p>Zero runtime dependencies is the countermeasure to the third thing that went wrong with CGLib:
 * it shipped a shaded copy of ASM, which meant that {@code cglib}, {@code cglib-nodep}, and Spring's
 * repackaged fork could not coexist, and that an application's own ASM version could collide with
 * the one hidden inside. Nothing to ship means nothing to collide.
 *
 * <p>The build already enforces this through the Maven Enforcer plugin, which is the authoritative
 * gate. This class exists alongside it for two reasons: it runs in the IDE without a full Maven
 * build, and it states the rule in the same place as the other architecture rules, where someone
 * reading the test tree will actually encounter it.
 */
public final class PomScopes {

    /**
     * Scopes that never reach a consumer.
     *
     * <p>{@code test} is invisible outside the build. {@code provided} is present at compile time
     * but expected to come from the environment, so it is not transitively imposed on anyone.
     */
    public static final Set<String> PERMITTED_SCOPES = Set.of("test", "provided");

    private PomScopes() {
    }

    /**
     * Finds dependencies whose scope would make them part of the published artifact's runtime.
     *
     * <p>Only {@code /project/dependencies} is examined. {@code dependencyManagement} declares
     * versions without introducing dependencies, and {@code build/plugins} runs at build time only;
     * neither reaches a consumer, so neither is a violation.
     *
     * @param pomXml the full text of a {@code pom.xml}
     * @return a description of each offending dependency, empty if the POM is clean
     */
    public static List<String> runtimeDependencies(String pomXml) {
        Document document = parse(pomXml);
        Element project = document.getDocumentElement();
        Element dependencies = directChild(project, "dependencies");
        if (dependencies == null) {
            return List.of();
        }

        List<String> offenders = new ArrayList<>();
        for (Element dependency : directChildren(dependencies, "dependency")) {
            String scope = textOf(directChild(dependency, "scope"));
            if (!PERMITTED_SCOPES.contains(scope)) {
                offenders.add(coordinates(dependency)
                        + " has scope '" + (scope.isEmpty() ? "compile (defaulted)" : scope)
                        + "'; only " + PERMITTED_SCOPES + " are permitted");
            }
        }
        return List.copyOf(offenders);
    }

    private static String coordinates(Element dependency) {
        return textOf(directChild(dependency, "groupId")) + ":"
                + textOf(directChild(dependency, "artifactId"));
    }

    /**
     * Parses XML with external entity resolution disabled.
     *
     * <p>Standard hygiene rather than a response to a real threat here &mdash; the input is our own
     * POM. Left in because test utilities get copied, and a parser configured this way stays safe
     * wherever it lands.
     */
    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(false);   // POM namespaces are noise for this purpose
            DocumentBuilder builder = factory.newDocumentBuilder();
            // Without a handler the default one prints to stderr before the exception propagates,
            // which makes a passing negative test look like a failing build.
            builder.setErrorHandler(new SilentErrorHandler());
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML parser cannot be configured safely", e);
        } catch (Exception e) {
            throw new IllegalArgumentException("not parseable as XML: " + e.getMessage(), e);
        }
    }

    /** Swallows parser diagnostics; the thrown exception already carries everything we need. */
    private static final class SilentErrorHandler implements org.xml.sax.ErrorHandler {
        @Override
        public void warning(org.xml.sax.SAXParseException e) {
            // ignored
        }

        @Override
        public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXParseException {
            throw e;
        }

        @Override
        public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXParseException {
            throw e;
        }
    }

    private static Element directChild(Element parent, String name) {
        List<Element> found = directChildren(parent, name);
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<Element> directChildren(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && child.getNodeName().equals(name)) {
                found.add((Element) child);
            }
        }
        return found;
    }

    private static String textOf(Element element) {
        return element == null ? "" : element.getTextContent().trim();
    }
}
