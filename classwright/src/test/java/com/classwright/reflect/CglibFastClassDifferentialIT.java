package com.classwright.reflect;

import com.classwright.reflect.FastClassBridgeTest.BothValued;
import com.classwright.reflect.FastClassBridgeTest.Narrow;
import com.classwright.reflect.FastClassBridgeTest.StringBox;
import net.sf.cglib.core.Signature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asks real CGLib 3.3.0 the same {@code FastClass} questions and requires the same answers.
 *
 * <p>The numeric indexes are each library's own business; what must agree is <em>which
 * signatures exist</em> and <em>what invoking them does</em>. The covariant cases are the ones
 * that regressed: CGLib indexed bridge descriptors, so migrated code that looked one up got a
 * valid index there and -1 here.
 *
 * <p>An integration test because CGLib needs {@code --add-opens java.base/java.lang}, which the
 * Failsafe configuration grants and the unit-test JVM deliberately does not.
 */
class CglibFastClassDifferentialIT {

    private static void assertBothResolveAndAgree(Class<?> target, Object receiver,
                                                  String name, String descriptor,
                                                  Object[] arguments) throws Exception {
        net.sf.cglib.reflect.FastClass cglib = net.sf.cglib.reflect.FastClass.create(target);
        FastClass classwright = FastClass.create(target);

        int cglibIndex = cglib.getIndex(new Signature(name, descriptor));
        int classwrightIndex = classwright.getIndex(name, descriptor);

        assertTrue(cglibIndex >= 0, "CGLib resolves " + name + descriptor + " on " + target);
        assertTrue(classwrightIndex >= 0,
                "Classwright must resolve " + name + descriptor + " exactly as CGLib does");
        assertEquals(cglib.invoke(cglibIndex, receiver, arguments),
                classwright.invoke(classwrightIndex, receiver, arguments),
                "both indexes must dispatch to the same implementation");
    }

    @Test
    @DisplayName("covariant class override: both descriptors resolve in both libraries")
    void covariantOverrideParity() throws Exception {
        assertBothResolveAndAgree(Narrow.class, new Narrow(),
                "item", "()Ljava/lang/String;", new Object[0]);
        assertBothResolveAndAgree(Narrow.class, new Narrow(),
                "item", "()Ljava/lang/Object;", new Object[0]);
    }

    @Test
    @DisplayName("covariant independent interfaces: both descriptors resolve in both libraries")
    void independentInterfacesParity() throws Exception {
        assertBothResolveAndAgree(BothValued.class, new BothValued(),
                "value", "()Ljava/lang/String;", new Object[0]);
        assertBothResolveAndAgree(BothValued.class, new BothValued(),
                "value", "()Ljava/lang/Object;", new Object[0]);
    }

    @Test
    @DisplayName("generic erasure bridge: both descriptors resolve in both libraries")
    void erasureBridgeParity() throws Exception {
        assertBothResolveAndAgree(StringBox.class, new StringBox(),
                "unwrap", "(Ljava/lang/String;)Ljava/lang/String;", new Object[]{"a"});
        assertBothResolveAndAgree(StringBox.class, new StringBox(),
                "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", new Object[]{"a"});
    }

    @Test
    @DisplayName("Method-object lookups keep bridge and real method distinct in both libraries")
    void methodObjectLookupParity() throws Exception {
        java.lang.reflect.Method real =
                FastClassBridgeTest.declared(Narrow.class, "item", false);
        java.lang.reflect.Method bridge =
                FastClassBridgeTest.declared(Narrow.class, "item", true);

        net.sf.cglib.reflect.FastClass cglib = net.sf.cglib.reflect.FastClass.create(Narrow.class);
        FastClass classwright = FastClass.create(Narrow.class);

        // Each library's index values are its own business; the property that must agree is
        // that the two Method objects are two JVM methods with two slots, and that invoking
        // either slot lands on the real implementation.
        assertTrue(cglib.getMethod(real).getIndex() != cglib.getMethod(bridge).getIndex(),
                "CGLib keeps the pair distinct");
        assertTrue(classwright.getMethod(real).getIndex()
                        != classwright.getMethod(bridge).getIndex(),
                "Classwright must keep the pair distinct exactly as CGLib does");
        assertEquals(cglib.getMethod(bridge).invoke(new Narrow(), new Object[0]),
                classwright.getMethod(bridge).invoke(new Narrow(), new Object[0]));
        assertEquals(cglib.getMethod(real).invoke(new Narrow(), new Object[0]),
                classwright.getMethod(real).invoke(new Narrow(), new Object[0]));
    }
}
