# Migrating from CGLib

Two routes. Pick based on how much code you want to touch.

| | `classwright-cglib-compat` | `classwright` |
|---|---|---|
| Code changes | **none for the reproduced surface** — every deliberate gap is machine-checked and listed, see [the differences](#reproduced-with-documented-differences) | package rename |
| Imports | stay `net.sf.cglib.*` | become `com.classwright.*` |
| Best for | large or unfamiliar codebases, first move | new code, and once the shim has proved itself |

What "reproduced surface" means is not a judgement call: the shim's build diffs real CGLib
3.3.0's public and protected API against the shim's — by complete JVM descriptors, visibility,
checked exceptions and constant values (`CglibApiParityTest`) — and anything not exact must
appear, with a reason, in
[`classwright-cglib-compat/api/cglib-parity-allowlist.txt`](../classwright-cglib-compat/api/cglib-parity-allowlist.txt).
That file *is* the authoritative list of what a migration can hit; the grep below finds the
practically relevant subset.

**Source compatibility versus binary compatibility.** For everything not on the allowlist, the
swap is binary: already-compiled jars link and run unchanged. The allowlist's
`SOURCE-USAGE-DEPENDENT` category is the deliberate exception — a handful of factory methods
whose *return descriptor* differs (CGLib's factories returned its own abstract bases because
generated classes extended them; here they return `Object` or the native type). Precisely what
that means:

- **Precompiled bytecode** that links one of those exact CGLib descriptors does not link and
  needs rebuilding.
- **Recompiled source** using the near-universal factory-cast pattern —
  `MyKey factory = (MyKey) KeyFactory.create(MyKey.class);` — compiles and runs unchanged.
- **Recompiled source** that assigns the result to CGLib's own facade type —
  `KeyFactory f = KeyFactory.create(...)`, `Mixin m = Mixin.create(...)` — no longer compiles
  and needs a one-line source change (assign to `Object` or the business interface).

The list is short and precise, so the check is a grep, not an audit. Validation against
real-world CGLib consumers' own test suites is still outstanding, so treat the first migration
of a large codebase as a verification exercise, not a formality.

Both routes give the same proxy and interception semantics and the same cache/unloading
architecture and memory characteristics; the shim adds one thin adapter object per callback and
nothing on the per-call path. One deliberate difference: **generated naming.** The shim (Route 1)
uses CGLib-compatible names automatically; the native API (Route 2) defaults to Classwright's own
convention (`$$CW`, `CW$` member prefixes) — code that matches CGLib-specific names must opt in,
as described under "What changes at runtime".

---

## Route 1: change the dependency, usually nothing else

```xml
<dependency>
  <groupId>com.classwright</groupId>
  <artifactId>classwright-cglib-compat</artifactId>
  <version>0.1.0</version>
</dependency>
```

Then **remove CGLib**, including transitively:

```bash
mvn dependency:tree -Dincludes=cglib:cglib
```

```xml
<exclusions>
  <exclusion><groupId>cglib</groupId><artifactId>cglib</artifactId></exclusion>
</exclusions>
```

Gradle:

```groovy
configurations.all {
    exclude group: 'cglib', module: 'cglib'
    exclude group: 'cglib', module: 'cglib-nodep'
}
```

### Why removing it is not optional

This artifact **defines the `net.sf.cglib` packages itself**. With CGLib also present, every class in
those packages exists twice and which one loads depends on class path order — which build tools do
not guarantee and which can differ between a laptop and production. The failure mode is not an error
but a silent mixture of two implementations.

The shim detects this and refuses to start, with the exclusion snippets in the message. The bundled
Maven Enforcer rule catches it at build time. `-Dclasswright.cglib.allowCoexistence=true` downgrades
the check to a warning, and you should not need it.

You can also drop `--add-opens java.base/java.lang=ALL-UNNAMED` from your launch scripts. CGLib
needed it on JDK 16 and later; Classwright does not.

---

## Route 2: rename the packages

```
net.sf.cglib.proxy.*    ->  com.classwright.proxy.*
net.sf.cglib.reflect.*  ->  com.classwright.reflect.*
net.sf.cglib.beans.*    ->  com.classwright.beans.*
net.sf.cglib.core.*     ->  com.classwright.core.*  (see the table below)
```

A find-and-replace across imports covers nearly all of it. What it will not cover is listed under
*Not reproduced*.

---

## Coming from Spring's `org.springframework.cglib`

Spring vendored CGLib into `org.springframework.cglib` years ago, so a great deal of code now
imports that instead. Two things are worth being clear about.

**If it is Spring's own internal use, leave it alone.** `spring-core` ships that fork for its own
AOP and configuration-class proxying, it is maintained as part of Spring, and it is not broken.
Replacing it under Spring's feet is not something this project asks anyone to do, and Classwright
deliberately ships no `org.springframework.cglib` shim — shadowing a maintained library would be
hostile, and the coexistence problem below would apply to it just as much.

**If it is your own code importing it**, that is a different situation. Plenty of projects moved
their *application* code from `net.sf.cglib` to `org.springframework.cglib` purely as a way to get a
JDK-17-capable CGLib, without wanting a Spring dependency at all. For that, the rename is the same
shape as Route 2:

```
org.springframework.cglib.proxy.*    ->  com.classwright.proxy.*
org.springframework.cglib.reflect.*  ->  com.classwright.reflect.*
org.springframework.cglib.beans.*    ->  com.classwright.beans.*
```

The API is CGLib's, so everything in this document applies unchanged. Two differences from the
`net.sf.cglib` case:

- **Route 1 does not exist for it.** The `classwright-cglib-compat` shim implements `net.sf.cglib`
  only. Renaming the imports is the whole migration.
- **There is no coexistence problem.** Classwright defines nothing in `org.springframework.*`, so
  Spring's fork can stay on the class path for Spring's own use while your code moves off it.

To find out how much is yours rather than Spring's:

```bash
grep -rl "org\.springframework\.cglib" src/
```

---

## What changes at runtime

### Generated classes can be unloaded

The point of the exercise. Over 20 000 generated classes CGLib reclaims **0%** and retains 24 MB
permanently; Classwright reclaims **100%** and returns metaspace to baseline. Full measurements in
[BASELINE.md](BASELINE.md).

This works because proxies are *hidden classes*, and two consequences follow:

- **`Class.forName(proxy.getClass().getName())` fails.** The name carries a `/0x...` suffix and is
  not a valid binary name — that is what makes the class collectable.
- **Naming depends on the route.** Route 1 (the shim) keeps `$$EnhancerByCGLIB$$` in the name and
  the `CGLIB$` member prefix automatically, so Spring's `ClassUtils.getUserClass` and similar
  heuristics keep working unchanged. Route 2 (the native API) defaults to Classwright's own
  convention — `Target$$CW/0x...`, members prefixed `CW$`. The broad `$$`-marker heuristic still
  matches either way, but code that looks specifically for `EnhancerByCGLIB` or `CGLIB$` must
  request the legacy names explicitly:

  ```java
  enhancer.setNamingConvention(NamingConvention.CGLIB_COMPATIBLE);
  ```

If something genuinely needs a resolvable name:

```java
enhancer.setUseHiddenClasses(false);              // compat shim
enhancer.setDefinitionStrategy(DefinitionStrategy.named());   // native API
```

Those classes are never reclaimed — exactly CGLib's behaviour, and exactly the problem you came here
to solve. Use it for the one class that needs it, not globally.

### Interception (HISTORICAL / UNVERIFIED figures)

Historical corrected-harness measurements favoured Classwright — 3.97 ns per intercepted call
against CGLib's 11.18 ns, doing identical work — and nothing about the migration changes for it
either way. These figures await reproduction on the reference Java 17 environment; until then
they are the record of what was measured, not a claim.

The structural reason is not a measurement: CGLib's `invokeSuper` goes through a separately
generated `FastClass` — an index lookup and a `tableswitch` in another class. Classwright puts
the same switch inside the proxy itself, which removes both the extra class and a dispatch hop.

### Generation and cache hits (HISTORICAL / UNVERIFIED figures)

| | Classwright | CGLib |
|---|---:|---:|
| Fresh proxy, warm | **238 µs** | 627 µs |
| Cache hit, warm | 155 ns | **77 ns** |

Historically, generating a proxy measured about 2.6× faster while a cache hit measured about
twice CGLib's (an absolute difference of 78 ns). Both figures predate later cache-path work and
the harness corrections; the reference Java 17 campaign supersedes them.

> An earlier version of this page reported generation as *slower* than CGLib. That was measured
> single-shot on a barely warmed JVM, with all arms sharing one process so later arms inherited
> earlier arms' JIT work. Measured warm and in isolated JVMs the result inverts. The methodology and
> both sets of figures are in [BASELINE.md](BASELINE.md).
>
> **Harness note (2026-08-11).** The JMH harness was itself corrected on 2026-08-11 — see the
> harness-correction note at the top of [BASELINE.md](BASELINE.md). The nanosecond and microsecond
> figures on this page are therefore **historical**: they are kept for the record, and current
> numbers await re-measurement on the reference JDK 17 machine.

If startup cost matters more than the numbers above — thousands of proxies built during boot, or a
GraalVM native image, where runtime generation is impossible — proxies can be generated at build
time instead. See [AOT.md](AOT.md).

### Behaviour that is deliberately identical

Verified by a differential test suite that runs the same scenario through both libraries:

- an interceptor returning `null` for a primitive method yields the zero value, not an exception;
- an interceptor returning a `Long` for an `int` method narrows it;
- `final`, `static`, and `private` methods are not intercepted;
- exceptions from an interceptor propagate unwrapped;
- `equals`, `hashCode`, and `toString` are intercepted.

Two of those were divergences found by writing that suite. Reading CGLib's documentation would not
have revealed either.

---

## Reproduced with documented differences

The rest of the reproduced surface behaves as CGLib's did. The entries below are present and
working, but differ in a way a call site can notice. Every difference is deliberate, and each row
names the change — if any — that migrated code needs.

Two entries an earlier version of this page listed as missing are now present:
`Enhancer.registerCallbacks` and `registerStaticCallbacks` — the deferred-binding flow Spring and
Hibernate use — are reproduced in full, and `core.KeyFactory` appears below.

| CGLib | Difference | What a call site does |
|---|---|---|
| `proxy.Mixin` | The factories return `Object`, not `Mixin`: the generated class extends Classwright's `Mixin` and cannot also extend the shim's. | Cast to your business interfaces, as nearly all code already does. A variable declared as `Mixin` becomes `Object` or an interface. |
| `proxy.Proxy` | `getProxyClass` returns a class whose constructors mirror `Object`'s — there is no generated `(InvocationHandler)` constructor to look up reflectively. | Use `newProxyInstance`, or `Enhancer.registerCallbacks` for deferred binding. |
| `proxy.InterfaceMaker` | The ASM-typed `add(Signature, Type[])` overload cannot exist here, and generated interfaces are ordinary named classes that never unload. | Add methods by `java.lang.reflect.Method` or by copying a type; build one interface per shape and reuse it. |
| `core.KeyFactory` | Only `create(Class)`; the factory is a `java.lang.reflect.Proxy`, so key *creation* pays a reflective dispatch and an argument copy that CGLib's generated factory did not (a generated typed factory is planned). Key *use* follows CGLib's exact semantics: a declared array component compares by one level of typed content; everything else — including a declared `Object` holding an array — by `equals()`, so nested arrays compare by identity, as CGLib's keys did. | Nothing, unless it used the `Customizer` overloads, which do not exist here, or created keys on a per-request hot path. |
| `core.NamingPolicy`, `core.DefaultNamingPolicy`, `Enhancer.setNamingPolicy` | Accepted and **ignored**: hidden classes are named by the JVM, not by a policy. Names keep the `$$EnhancerByCGLIB$$` marker frameworks look for. | Nothing compiles differently; a custom policy simply has no effect. On the native API, `Enhancer.setNamingConvention(NamingConvention)`. |
| `Enhancer.setSerialVersionUID` | Accepted and **ignored**: hidden proxies are not serialisable, so there is no stream to stamp. | Nothing. |
| `Enhancer.setAttemptLoad` | Accepted and **ignored**: CGLib used it to try `Class.forName` before generating, and hidden classes are not resolvable by name. The generation cache serves the same purpose. | Nothing. |
| `proxy.MethodProxy` statics | `MethodProxy.find(Class, Signature)` and `MethodProxy.create(...)` do not exist: both existed for CGLib's *generated* bytecode to call, not for applications, and the wrapper here is created by the interception path itself. | Application code that called `find` reflectively must hold the `MethodProxy` the interceptor receives instead. |
| `Enhancer.setClassLoader` | Accepted and validated when the proxy is created — the loader must be able to see the superclass — but never used for placement, which always follows the superclass. | Nothing, or drop the call. |
| `Enhancer` method set | `finalize()` and `Object.clone()` are never intercepted, and the `CallbackFilter` is not consulted for them. | Delete any route-`finalize`-to-`NoOp` filter workaround. Code that relied on intercepting `clone` has no equivalent. |
| `reflect.MethodDelegate` | `create`/`createStatic` return `Object`, for the same inheritance reason as `Mixin`. `getTarget()` and `newInstance(Object)` live on `com.classwright.reflect.MethodDelegate`. | Cast to the requested interface, as nearly all code already does. |
| `reflect.ConstructorDelegate` | `create` returns `Object`, same reason. | Cast to the factory interface. |
| `reflect.MulticastDelegate` | `create` returns `com.classwright.reflect.MulticastDelegate`: returning `Object` would make `add`/`remove` unreachable, and a shim-typed wrapper could not be cast to the fanned-out interface. Also `getTargets()` returns `Object[]` rather than a `List`, and the no-argument `newInstance()` does not exist. | Declare the variable `var` (or the returned type); `add`, `remove`, and the cast to your interface are unchanged. For a fresh empty delegate call `create` again — it is a cache hit. |

---

## Not reproduced

Most of what is below exposes ASM types or requires reading class files. Reproducing those would
mean taking the dependency Classwright exists to remove — the same dependency that fragmented CGLib
into `cglib`, `cglib-nodep`, and Spring's fork, and that left it unable to read Java 17 class files.

| CGLib | Status | Instead |
|---|---|---|
| `core.Signature` | **partial** — name and descriptor only | The ASM-typed `getReturnType()` / `getArgumentTypes()` are gone. Parse the descriptor, or use `java.lang.reflect`. |
| `core.GeneratorStrategy`, `Enhancer.setStrategy` | not reproduced | `Enhancer.setDefinitionStrategy(DefinitionStrategy)`. |
| `core.ClassEmitter`, `CodeEmitter`, `EmitUtils` | not reproduced | `com.classwright.core.CwClassWriter` and `CodeBuilder`, which are a different and better-checked API. |
| **all of `net.sf.cglib.transform.*`** | **not reproduced, ever** | It rewrites existing class files, which needs a class-file *parser*. That is the single thing Classwright will not have. For load-time transformation use a `java.lang.instrument` agent. |
| `util.ParallelSorter` | not reproduced | `java.util.Arrays.parallelSort`, which did not exist when CGLib was written. |
| `util.StringSwitcher` | not yet | Planned; a `switch` on strings, or a `Map`, covers the common cases meanwhile. |

To find out whether any of this affects you:

```bash
grep -rE "net\.sf\.cglib\.(transform|util\.(ParallelSorter|StringSwitcher)|core\.(ClassEmitter|CodeEmitter|EmitUtils|GeneratorStrategy))" src/
```

No hits means none of the *practically common* omissions affect you. The complete, enforced
list of everything the shim does not reproduce — including generator-SPI internals and
protected plumbing that this grep does not look for — is
`classwright-cglib-compat/api/cglib-parity-allowlist.txt`; scan it once before committing to
the swap.

---

## Verifying the move

The shim's own test suite is written the way an application is written — importing only
`net.sf.cglib.*`, with no reference to Classwright anywhere in the file. That constraint *is* the
assertion, and it is worth reproducing against your own code: run your existing test suite
unmodified against the new dependency. If it passes, you are done. But still, treat the shim as tested rather than proven.
