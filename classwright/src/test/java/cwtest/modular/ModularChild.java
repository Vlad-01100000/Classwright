package cwtest.modular;

import com.classwright.proxy.Enhancer;
import com.classwright.proxy.MethodInterceptor;

/**
 * Runs with Classwright as a named module on {@code --module-path} and this class — with its
 * proxy targets — on the classpath, which is the topology of every application that consumes
 * the library from a module path: the targets live in the <em>unnamed</em> module.
 *
 * <p>Deliberately in a package the {@code com.classwright} module does not contain. A classpath
 * package that also exists in a named module of the same layer is shadowed by the module, and
 * this class would become unloadable.
 *
 * <p>What it proves: {@code privateLookupIn} against an unnamed-module target requires our named
 * module to read the unnamed module, an edge an explicit module does not have by default. The
 * resolver must establish it; a guard that skips unnamed targets breaks every creation here.
 */
public final class ModularChild {

    /** An ordinary application class, living in the unnamed module. */
    public static class Target {
        public String greet() {
            return "plain";
        }
    }

    /** An application interface, so the interface-proxy neighbour path is exercised too. */
    public interface Echo {
        String echo(String input);
    }

    private ModularChild() {
    }

    /**
     * Exits zero when proxying works, non-zero with a diagnostic line when it does not.
     *
     * @param args unused
     */
    public static void main(String[] args) {
        Target proxy = (Target) Enhancer.create(Target.class,
                (MethodInterceptor) (obj, method, arguments, methodProxy) ->
                        "intercepted:" + methodProxy.invokeSuper(obj, arguments));
        String greeting = proxy.greet();
        if (!"intercepted:plain".equals(greeting)) {
            System.out.println("FAILED: subclass proxy returned " + greeting);
            System.exit(1);
        }
        System.out.println("subclass proxy works on the module path");

        Enhancer enhancer = new Enhancer();
        enhancer.setInterfaces(Echo.class);
        enhancer.setCallback((MethodInterceptor) (obj, method, arguments, methodProxy) ->
                "echo:" + arguments[0]);
        Echo echo = (Echo) enhancer.create();
        if (!"echo:hi".equals(echo.echo("hi"))) {
            System.out.println("FAILED: interface proxy did not dispatch");
            System.exit(1);
        }
        System.out.println("interface proxy works on the module path");

        // Which definition path served the request. Hidden-beside-the-target is impossible
        // here by JPMS design: privateLookupIn across a module boundary drops MODULE, and
        // defineHiddenClass demands full privilege — so the correct behaviour is the child
        // loader, reached through the read edge (without which initialise() has no lookup at
        // all and creation throws). Printed rather than asserted, so the parent test can pin
        // today's truth and notice if a future strategy improves on it.
        System.out.println(proxy.getClass().isHidden()
                ? "definition path: hidden"
                : "definition path: child loader");
    }
}
