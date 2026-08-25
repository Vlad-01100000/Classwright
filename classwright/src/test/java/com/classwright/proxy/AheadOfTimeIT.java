package com.classwright.proxy;

import com.classwright.proxy.fixtures.Service;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An ahead-of-time proxy is adopted by an ordinary {@link Enhancer} call.
 *
 * <p>Runs in a child JVM, for the same reason the TCK does: {@link AotProxies} reads its index once
 * per process. Testing it in-process would mean depending on which test touched the class first,
 * which is not a test at all.
 *
 * <p>This is as close as the build can get to the native-image case without GraalVM. It proves the
 * two halves line up — that a key computed at build time is recomputed identically at runtime, and
 * that the generated class is complete enough to be used without ever being defined at runtime.
 * What it cannot prove is that {@code native-image} accepts the result; that needs GraalVM in CI.
 */
class AheadOfTimeIT {

    @Test
    @DisplayName("a pre-generated proxy is used instead of generating one at runtime")
    void preGeneratedProxyIsAdopted(@TempDir Path aotOutput) throws Exception {
        AheadOfTime.writeTo(aotOutput, List.of(
                ProxyBlueprint.of(Service.class)
                        .callbacks(MethodInterceptor.class)
                        .build(),
                // For the routing-fingerprint scenarios: built with a fresh DriftingFilter (flag
                // down), which the child JVM matches with one instance and drifts from with
                // another. See AotChild.checkRoutingFingerprint.
                ProxyBlueprint.of(Service.class)
                        .callbacks(MethodInterceptor.class, NoOp.class)
                        .filteredBy(AotChild.DriftingFilter.class)
                        .build()));

        String classpath = String.join(File.pathSeparator,
                Path.of("target", "classes").toAbsolutePath().toString(),
                Path.of("target", "test-classes").toAbsolutePath().toString(),
                aotOutput.toAbsolutePath().toString());

        Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath,
                AotChild.class.getName())
                .redirectErrorStream(true)
                .start();

        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(child.waitFor(2, TimeUnit.MINUTES), "the child JVM did not finish:\n" + output);

        assertEquals(0, child.exitValue(),
                "the ahead-of-time path did not work in a fresh JVM:\n" + output);
        assertTrue(output.contains("2 ahead-of-time proxies registered"),
                "the index was not picked up:\n" + output);
        assertTrue(output.contains("refused a filter whose routing drifted"),
                "the fingerprint mismatch was not exercised:\n" + output);
        assertTrue(output.contains("adopted the same entry once the routing matched"),
                "the fingerprint acceptance was not exercised:\n" + output);
    }
}
