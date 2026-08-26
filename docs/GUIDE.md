# Classwright — The Guide

Classwright is a runtime code-generation library for the JVM: proxies, interceptors, fast
reflection, and bean tooling, built from scratch for Java 17+ as a replacement for CGLib. It has
**zero runtime dependencies**, defines its classes as **JVM hidden classes** so they unload like
any other garbage, and ships with a `net.sf.cglib` compatibility layer that makes most CGLib
code run unchanged.

This guide describes what Classwright is, how to use it for each thing it
does, how to migrate from CGLib, and the operational knowledge. Reference documents with deeper detail are linked throughout.

---

## 1. Why Classwright exists

CGLib powered a decade of Java infrastructure — Spring AOP, Hibernate lazy loading, Mockito —
and then died of three specific wounds, each of which is a design input here:

1. **The JDK moved and CGLib could not.** It reached into `java.lang` internals that JDK 16
   sealed off. CGLib 3.3.0 (2019, the final release) runs on modern JDKs only with
   `--add-opens java.base/java.lang=ALL-UNNAMED` — a permanent tax on every application, with
   no guarantee of continued mercy. Classwright uses only supported APIs
   (`MethodHandles.Lookup::defineHiddenClass` first among them) and needs no flags of any kind.
2. **Generated classes never unloaded.** CGLib defined classes into the target's own loader and
   pinned them from static caches: metaspace an application never got back, and the classic
   Tomcat-redeploy leak where a retained class pinned the whole application class loader.
   Classwright's proxies are hidden classes — individually collectible the moment nothing
   references them, while their loader stays alive. Measured: **100% of 5,000 dropped proxies
   reclaimed, against CGLib's 0%** ([BASELINE.md](BASELINE.md) §3).
3. **Classpath hell.** `cglib` vs `cglib-nodep` vs Spring's repackaged copy, shaded ASM
   colliding with the application's ASM. Classwright depends on nothing, so there is nothing to
   shade and nothing to collide.

And because a replacement that is slower would be no replacement at all, performance is a
first-class, continuously measured property: an intercepted call costs **4.1 ns against CGLib's
11.5 ns**, warm proxy creation **70 ns against 74 ns with 44% less allocation**, cold start
**4.7× faster** ([§9](#9-performance) has the whole table).

## 2. Requirements and installation

- **Java 17 or newer.** Java 17 is the supported baseline; 21 and 25 are compatibility-tested
  in CI. Classwright compiles against the Java 17 API signature set, so nothing newer leaks in.
- **Zero runtime dependencies**, enforced by the build.
- A proper JPMS module, `com.classwright`, requiring only `java.base` — and a
  classpath-friendly jar with `Automatic-Module-Name` for everyone else. No `--add-opens`, no
  agent, no flags.

```xml
<dependency>
    <groupId>com.classwright</groupId>
    <artifactId>classwright</artifactId>
    <version>${classwright.version}</version>
</dependency>
```

For CGLib source compatibility (see [§8](#8-migrating-from-cglib)), add the shim as well:

```xml
<dependency>
    <groupId>com.classwright</groupId>
    <artifactId>classwright-cglib-compat</artifactId>
    <version>${classwright.version}</version>
</dependency>
```

The shim must not coexist with real CGLib on one classpath — both define `net.sf.cglib.*`
classes, and Classwright refuses loudly rather than letting the JVM pick one at random.

## 3. Five-minute tour

```java
import com.classwright.proxy.Enhancer;
import com.classwright.proxy.MethodInterceptor;

OrderService proxy = (OrderService) Enhancer.create(OrderService.class,
        (MethodInterceptor) (obj, method, args, methodProxy) -> {
            long start = System.nanoTime();
            try {
                return methodProxy.invokeSuper(obj, args);   // call the real method
            } finally {
                metrics.record(method.getName(), System.nanoTime() - start);
            }
        });

proxy.placeOrder(order);   // intercepted
```

That is the whole idea: a generated subclass of `OrderService` routes every overridable method
through your interceptor, and `invokeSuper` reaches the original implementation without
reflection. The generated class is created once per configuration and cached; every further
`create()` for the same shape is a ~70 ns constructor call. When nothing references the proxy
class any more, the JVM reclaims it.

If a method you expected to intercept is not intercepted, ask the library instead of guessing:

```java
Enhancer enhancer = new Enhancer();
enhancer.setSuperclass(OrderService.class);
enhancer.setCallback(interceptor);
enhancer.create();
System.out.println(enhancer.describeSkippedMethods());
// e.g. "OrderService.finalizeTotals — final methods cannot be overridden"
```

## 4. Core concepts

**One generated class per proxy shape.** A proxy family (superclass + interfaces + callback
types + filter + options) produces exactly one class, holding the dispatch table, the
`Factory` implementation, and the super-call bridges. CGLib generated about three. Fewer
classes means faster startup and fewer definition events.

**Hidden classes by default.** The generated class is defined with
`Lookup::defineHiddenClass` beside its target: same package, full member access (it is even a
*nestmate*, so private members are reachable), no entry in any loader's class table. Two
consequences you must know:

- `proxy.getClass().getName()` contains a `/0x...` suffix and **`Class.forName` on it fails**.
  That irresolvability is precisely what makes the class collectible. Framework heuristics that
  look for a `$$` marker in the name keep working; code that round-trips class names through
  strings does not (see `DefinitionStrategy.named()` below when that genuinely matters).
- Hidden proxies are **not serializable**. A proxy is a behavior wrapper, not data; serialize
  the underlying state instead.

**The generation cache.** Keyed per target class ("anchor"), holding generated classes weakly:
the cache never keeps a class alive, entries clean up after collection, and the whole per-anchor
state dies with the anchor — so caching can never reintroduce the leak the library exists to
end. Retention is tunable ([§7.3](#73-cache-retention-and-observability)).

**Definition sites and strategies.** Where a class can be placed and how depends on the
target's package (open or closed) and loader. `DefinitionStrategy.hidden()` is the default;
`named()` and `childLoader()` are deliberate trade-offs ([§7.2](#72-definition-strategies)).

## 5. Proxies and interception

### 5.1 The Enhancer

```java
Enhancer enhancer = new Enhancer();
enhancer.setSuperclass(OrderService.class);          // the class to extend
enhancer.setInterfaces(new Class<?>[]{Audited.class}); // extra interfaces to implement
enhancer.setCallback(interceptor);                   // one callback for every method
Object proxy = enhancer.create();                    // instance via the no-arg constructor
Object proxy2 = enhancer.create(                     // or any superclass constructor
        new Class<?>[]{String.class}, new Object[]{"tenant-42"});
```

Configuration worth knowing:

| Setter | Meaning | Default |
|---|---|---|
| `setCallbacks` / `setCallbackFilter` | several callbacks, routed per method ([§5.3](#53-routing-several-callbacks)) | — |
| `setUseFactory(boolean)` | proxy implements [`Factory`](#54-factory-createclass-and-framework-flows) | `true` |
| `setInterceptDuringConstruction(boolean)` | callbacks fire for virtual calls made from the superclass constructor | `true` |
| `setCopyAnnotations(boolean)` | reproduce the target's annotations and generic signatures on the proxy | `false` |
| `setNamingConvention(...)` | `DEFAULT` (`$$CW`, `CW$`) or `CGLIB_COMPATIBLE` (`$$EnhancerByCGLIB$$`, `CGLIB$`) | `DEFAULT` |
| `setUseCache(boolean)` | `false` forces a fresh class per call — survivable only because hidden classes unload | `true` |
| `setDefinitionStrategy(...)` | override placement ([§7.2](#72-definition-strategies)) | automatic |

An `Enhancer` instance is a configuration object: configure it on one thread, call `create()`
as often as you like. The generated classes and the cache behind them are fully thread-safe,
with single-flight generation — a thundering herd of first requests generates once.

**Interface-only proxies:** pass the interfaces via `setInterfaces` and leave the superclass
unset (or use the JDK-shaped facade `com.classwright.proxy.Proxy.newProxyInstance(loader,
interfaces, handler)`). Do not pass an interface to `setSuperclass` — the error message will
redirect you.

### 5.2 The callback types

All of CGLib's callback vocabulary, in `com.classwright.proxy`:

- **`MethodInterceptor`** — the general one: sees the method, the arguments, and a
  `MethodProxy`; may call `invokeSuper` (the original code), `invoke` (the same method on
  another instance — a delegation pattern), both, or neither.
- **`NoOp.INSTANCE`** — no behavior change. A `NoOp`-only proxy costs the same as a direct
  call once the JIT has seen it (0.85 vs 0.73 ns measured).
- **`FixedValue`** — every intercepted method returns the same computed value.
- **`LazyLoader`** — `loadObject()` runs once on first use; every call thereafter forwards to
  that instance. Classic expensive-to-build delegate.
- **`Dispatcher`** — `loadObject()` runs on **every** call: routing to a per-request or
  per-transaction delegate. `ProxyRefDispatcher` additionally receives the proxy itself.
- **`InvocationHandler`** — JDK-proxy-style, for porting handler code. Prefer
  `MethodInterceptor` for new code: it can reach the original method, and it is the fast path.

`MethodProxy` details that matter: `invokeSuper` is the non-reflective super-call
(4 ns, allocation-free warm); `hasSuperImplementation()` tells you whether an abstract method
has anything to reach; `attachment()` / `setAttachment(Object)` give the interceptor a per-method
slot to cache derived state (a resolved endpoint, a compiled expression) without a map lookup.

### 5.3 Routing several callbacks

```java
enhancer.setCallbacks(new Callback[]{auditInterceptor, NoOp.INSTANCE});
enhancer.setCallbackFilter(method ->
        method.isAnnotationPresent(Audited.class) ? 0 : 1);   // index into the array
```

The filter runs **once per method at generation time**, and its identity is part of the cache
key — implement `equals`/`hashCode` on filters you construct repeatedly, or equal
configurations will generate needlessly. With more than one callback a filter is mandatory;
Classwright refuses the ambiguous configuration instead of guessing.

### 5.4 Factory, createClass, and framework flows

Every proxy implements `Factory` by default: `setCallback(s)` rebinds behavior on a live
instance, `newInstance(...)` clones the shape with different callbacks — no regeneration.

For frameworks that separate class creation from instantiation (Spring's pattern, or
Objenesis-style constructor-skipping):

```java
enhancer.setCallbackTypes(new Class<?>[]{MethodInterceptor.class});
Class<?> proxyClass = enhancer.createClass();          // class only, no instance

Enhancer.registerCallbacks(proxyClass, new Callback[]{interceptor});
Object proxy = instantiateHowYouLike(proxyClass);      // constructor, Factory, or Objenesis
```

`registerCallbacks` binds instances created afterwards on any thread (`null` clears);
`registerStaticCallbacks` sets loader-wide defaults that per-thread registration temporarily
overrides. Instances created without a constructor pick their callbacks up on first use.

### 5.5 What cannot be proxied

Final classes; records, enums, sealed and hidden classes; primitive and array types — each
refused with a message naming the reason. Final *methods* are simply not intercepted (that is
JVM law, same as CGLib), and `describeSkippedMethods()` names each one. `equals`, `hashCode`
and `toString` **are** intercepted; `getClass`, `wait`, `notify` are final in `Object` and are
not. The superclass needs at least one constructor a subclass can call.

## 6. Fast reflection and bean tooling

Everything CGLib-era persistence and mapping layers leaned on, at native speed.
All figures: [BASELINE.md](BASELINE.md) §5.

### 6.1 FastClass — invocation by index

```java
FastClass fast = FastClass.create(OrderService.class);
int index = fast.getIndex("total", new Class<?>[]{});
Object result = fast.invoke(index, service, new Object[0]);

FastMethod method = fast.getMethod("total", new Class<?>[]{});   // index resolved once
Object same = method.invoke(service, new Object[0]);

Object fresh = fast.newInstance();                                // constructors too
```

Resolve the index once, invoke forever: 3.2 ns and allocation-free per call, faster than warmed
`Method.invoke` and `MethodHandle.invoke`, with none of reflection's per-call boxing garbage on
small-int returns. Bridge methods are handled the way reflection users expect:
`getIndex(Method)` is descriptor-exact, name+parameter lookup prefers the non-bridge method.

### 6.2 Delegates

```java
interface Notify { void call(String message); }

MethodDelegate d = MethodDelegate.create(listener, "onMessage", Notify.class);
((Notify) d).call("hello");                        // direct call, no reflection

MulticastDelegate all = MulticastDelegate.create(Notify.class)
        .add(listenerA).add(listenerB);
((Notify) all).call("to everyone");                // fans out in add order

ConstructorDelegate maker = ConstructorDelegate.create(Order.class, OrderFactory.class);
```

### 6.3 Bean utilities

**`BeanCopier`** — property-by-property copy between bean types, matched by name and type:

```java
BeanCopier copier = BeanCopier.create(OrderDto.class, Order.class, false);
copier.copy(dto, order, null);
// with `true`, a Converter bridges mismatched types:
//   (value, targetType, setterContext) -> converted
```

`describeMapping()` prints exactly which properties will move and why the others will not —
run it once in a test instead of debugging silent skips.

**`BeanMap`** — a live `Map<String, Object>` view over a bean (changes write through):

```java
BeanMap map = BeanMap.create(order);
map.get("total");  map.put("total", 99);
map.get(otherOrder, "total");              // same shape, different bean, no new view
BeanMap other = map.newInstance(otherOrder);
```

Get/put run through generated per-key dispatch — 1.4–1.7 ns, allocation-free, faster than
CGLib's on primitives. Unknown keys read `null` and write nothing (the key set is fixed by the
bean's shape); read-only properties ignore writes and answer `null`.

**`BulkBean`** — array-at-once property access for serialization layers:

```java
BulkBean bulk = BulkBean.create(Order.class,
        new String[]{"getId", "getQuantity"},
        new String[]{"setId", "setQuantity"},
        new Class<?>[]{String.class, int.class});
Object[] values = bulk.getPropertyValues(order);
bulk.setPropertyValues(order, values);
```

Strict on purpose: a `null` for a primitive property fails with the failing index rather than
silently writing zero.

**`ImmutableBean.create(bean)`** — a read-through view whose setters throw.
**`BeanGenerator`** — a bean class from a runtime description
(`addProperty("name", String.class)…create()`).
**`Mixin.create(delegates)`** — several interface implementations presented as one object.
**`InterfaceMaker`** — build an interface type at runtime from methods or another type.

Property discovery follows the JavaBeans rules precisely, including the corners: `isX` getters
are recognized for primitive `boolean` only (boxed `Boolean` uses `getX`), and setter overloads
resolve to the most specific type compatible with the getter.

## 7. Controlling generation

### 7.1 Scopes — deterministic cleanup

When generated classes should die with a unit of work (a plugin, a tenant, a test):

```java
try (ClasswrightScope scope = ClasswrightScope.open("plugin-x")) {
    DefinedClass defined = scope.define(neighbourClass, classBytes);
    // scope.size(), scope.isFullyReclaimable() — false if anything named is in it
}   // close() releases everything the scope defined
```

### 7.2 Definition strategies

| Strategy | Placement | `Class.forName` | Unloads | Use when |
|---|---|---|---|---|
| `DefinitionStrategy.hidden()` *(default)* | target's package and loader, nestmate | no — by design | **per class** | almost always |
| `DefinitionStrategy.named()` | target's package and loader, ordinary class | **yes** | **never** | tooling genuinely resolves proxies by name; this is CGLib's leak, opt-in and documented |
| `DefinitionStrategy.childLoader()` | fresh child loader delegating to the target's | yes | with its loader (rotating cohorts of 64, `-Dclasswright.childLoaderCohort`) | the target's package is closed (a named module without `opens`) — also the automatic fallback |
| `new DefinitionStrategy.ChildLoader(parent)` | one dedicated loader per strategy instance | yes | when you drop the strategy | a code-generation unit torn down as a whole |

The child-loader placement loses package-private access (different loader = different runtime
package); everything else keeps it. `Capabilities.describe()` — or
`java -cp classwright.jar com.classwright.runtime.Capabilities` — prints what the current JVM
supports.

### 7.3 Cache retention and observability

| System property | Values | Effect |
|---|---|---|
| `classwright.cacheRetention` | `weak` *(default)* / `soft` / `mru` | weak: classes live exactly as long as something uses them. soft: survive until memory pressure. mru: a per-target ring strongly retains the hottest shapes |
| `classwright.cacheMruSize` | int, default 16 | ring size for `mru` |
| `classwright.cacheStats` | `true` | enables counters; disabled they cost the hit path nothing |
| `classwright.dumpDir` | a directory | writes every generated class file to disk — the debugging tool when generated bytecode must be inspected with `javap` |

```java
GenerationCache.statistics();          // hits / misses / regeneratedAfterCollection, hitRate()
GenerationCache.invalidate(target);    // drop everything generated for one target
```

Cache hits stay flat (8–18 ns) out to 100,000 cached shapes per anchor; housekeeping is
incremental and reference-queue-driven, never a stop-the-world sweep.

## 8. Migrating from CGLib

Two routes. Both give the same interception semantics, the same cache-and-unloading
architecture, and the same memory behavior; they differ in source form and generated naming.

### Route 1 — the shim: change a dependency, not code

Swap `cglib:cglib` for `com.classwright:classwright-cglib-compat` (plus the core artifact).
Your `net.sf.cglib.*` imports, and Spring-era idioms above them, keep compiling and running:
`Enhancer`, all callback types, `MethodProxy`, `FastClass`, `BeanCopier`/`BeanMap`/`BulkBean`/
`BeanGenerator`/`ImmutableBean`, `Mixin`, `KeyFactory`, `Proxy`, `CallbackHelper`,
`InterfaceMaker`. Remove `--add-opens java.base/java.lang` from your JVM flags — nothing needs
it any more.

Fidelity is machine-checked, not hoped for: a build gate diffs the shim's API against real
CGLib 3.3.0 member by member (descriptors, visibility, checked exceptions,
`serialVersionUID`s, serialized field layouts), and behavioral parity — down to CGLib's exact
exception types, messages like `Cannot find specified property`, null-key semantics, and
`KeyFactory`'s equality quirks — is pinned by differential tests that run both libraries side
by side. Every deliberate gap is in a categorized allowlist rather than silently absent.

What the shim deliberately does not reproduce:

- **ASM types and generator internals** (`net.sf.cglib.core.ClassEmitter`, custom
  `GeneratorStrategy`, the `transform`/`util` subtrees): Classwright has no ASM. Code that
  *emitted bytecode through CGLib* is porting to `com.classwright.core`, not migrating.
- **Accepted-and-ignored settings**, because hidden classes make them meaningless:
  `setNamingPolicy` (names come from the JVM; the `$$EnhancerByCGLIB$$` marker is kept),
  `setSerialVersionUID` (hidden proxies do not serialize), `setAttemptLoad`.
- **Factory-method return types** on `KeyFactory`, `Mixin` and the delegates are `Object`
  rather than ASM-typed variants — source-compatible with a cast, which is how virtually all
  code already used them.
- `MethodProxy.find`/`create` statics (they existed for CGLib's generated bytecode, not for
  applications), and `Proxy.getProxyClass` returns a class instantiated via
  `newProxyInstance`/`registerCallbacks` rather than a reflective `(InvocationHandler)`
  constructor.

**The one behavioral difference to audit for:** `Class.forName(proxyClass.getName())` fails,
because proxies are hidden. Spring's `ClassUtils.getUserClass` and marker-based heuristics keep
working (the name still contains `$$EnhancerByCGLIB$$`, members still `CGLIB$`-prefixed); code
that persists proxy class names and resolves them later needs `DefinitionStrategy.named()` and
acceptance of its non-unloading cost.

### Route 2 — the native API

Mostly an import rename — the API was shaped for it:

| CGLib | Classwright |
|---|---|
| `net.sf.cglib.proxy.Enhancer` / callbacks / `MethodProxy` | `com.classwright.proxy.*`, same names |
| `net.sf.cglib.proxy.CallbackHelper` | a `CallbackFilter` lambda ([§5.3](#53-routing-several-callbacks)) |
| `net.sf.cglib.reflect.FastClass` / `FastMethod` | `com.classwright.reflect.*`, same names |
| `net.sf.cglib.beans.*` | `com.classwright.beans.*` |
| `net.sf.cglib.proxy.KeyFactory` | shim-only; a `record` is the modern answer |
| `net.sf.cglib.core.Signature` | `String` name + `Class<?>[]`, or a descriptor string |

Differences beyond imports: the native default naming is `$$CW`/`CW$` — call
`enhancer.setNamingConvention(NamingConvention.CGLIB_COMPATIBLE)` if anything matches
CGLib-specific names; checked-exception behavior is CGLib's (`MethodInterceptor` may throw
anything; only `InvocationHandler` wraps undeclared checked exceptions in
`UndeclaredThrowableException`); and the native API adds what CGLib never had —
`describeSkippedMethods()`, `MethodProxy.attachment()`, scopes, strategies, statistics.

### Migration checklist

1. Swap dependencies; ensure real CGLib is fully evicted (`mvn dependency:tree`), including
   transitively.
2. Delete `--add-opens java.base/java.lang=ALL-UNNAMED` from JVM args and surefire configs.
3. Grep for `Class.forName` fed by `getClass().getName()` of proxied objects — decide
   per site: usually a bug to fix, rarely `DefinitionStrategy.named()`.
4. Grep for `setNamingPolicy`, `setAttemptLoad`, `setSerialVersionUID` — now inert; confirm
   nothing depended on their effects.
5. If proxies were serialized: stop — hidden proxies do not serialize; serialize state instead.
6. Run your suite. Then delete the shim dependency where you can and finish on the native API
   (Route 2), which is where new features land.

## 9. Performance

Measured on the pinned reference machine (Ryzen 7 3800X, Corretto 17.0.20, JMH 1.37, GC
profiler; CGLib 3.3.0 with its required `--add-opens`, Byte Buddy 1.15.11). Full data,
methodology and honest caveats: [BASELINE.md](BASELINE.md); raw evidence `release-jmh*.json`.

| Metric | Classwright | CGLib | Byte Buddy |
|---|---|---|---|
| Intercepted call (steady state) | **4.12 ns · 32 B** | 11.46 ns · 72 B | — |
| No-op proxy call | 0.85 ns · 0 B | 0.86 ns · 0 B | 0.86 ns · 0 B |
| Warm cached `create()` | **70.0 ns · 112 B** | 74.4 ns · 200 B | no cache to measure |
| Cold: first generated class | **10.1 ms** | 47.5 ms | 175.5 ms |
| `MethodProxy.invokeSuper` | **3.93 ns · 0 B** | 5.91 ns · 16 B | — |
| `FastClass` indexed invoke | **3.21 ns · 0 B** | 3.77 ns · 16 B | — |
| `BeanMap` get / put (primitive) | **1.43 / 1.68 ns · 0 B** | 2.18 / 2.38 ns · 16 B | — |
| Classes reclaimed after drop | **100%** | 0% (23.5 MB kept per 5,000) | 100% (via a loader per class) |
| Generation under churn | **221 µs/class** | 696 µs/class | 260 µs/class |

Read honestly: the 0.85 ns no-op arms are the perfectly-inlined best case, not "proxying is
free"; CGLib keeps a ~0.1–0.2 ns edge on trivial-return `FastClass` shapes and reference-typed
`BeanMap.get`; and dispatch tables beyond 512 methods have a known position-dependent
degradation being tracked ([BASELINE.md](BASELINE.md) §5).

## 10. Ahead-of-time generation and GraalVM native image

A native image cannot define classes at runtime, which kills generate-on-demand outright. The
Maven plugin generates proxy classes at build time — ordinary compiled classes on the
classpath — and writes the `reflect-config.json` a native image needs. On a normal JVM the same
setup just moves generation cost out of startup; at runtime, `Enhancer` transparently adopts a
pre-generated class when the configuration matches (verified by a routing fingerprint, so a
stale index can never smuggle in wrong dispatch).

```xml
<plugin>
    <groupId>com.classwright</groupId>
    <artifactId>classwright-maven-plugin</artifactId>
    <version>${classwright.version}</version>
    <executions>
        <execution><goals><goal>generate-proxies</goal></goals></execution>
    </executions>
    <configuration>
        <proxies>
            <proxy>
                <superclass>com.example.OrderService</superclass>
                <callbacks>
                    <callback>com.classwright.proxy.MethodInterceptor</callback>
                </callbacks>
                <!-- <interfaces>, <filter>, <useFactory>, <interceptDuringConstruction>,
                     <copyAnnotations>, <naming> — same meaning as the Enhancer setters -->
            </proxy>
        </proxies>
    </configuration>
</plugin>
```

The project must depend on `com.classwright:classwright` **at the plugin's exact version** —
the build fails on a mismatch (the failure it prevents is silent regeneration on a JVM and an
outright failure in a native image) and on a missing dependency (the generated classes could
never load). Skip with `-Dclasswright.skip=true`. Details, including the programmatic
`AheadOfTime`/`ProxyBlueprint` API for non-Maven builds: [AOT.md](AOT.md).

## 11. API stability

Three explicit contracts ([COMPATIBILITY.md](COMPATIBILITY.md)):

1. **The supported API** — everything the module exports, frozen in a reviewed snapshot
   (`api/classwright.api`, also embedded in the jar) and gated by SemVer machinery at release:
   removals or narrowing are MAJOR, additions MINOR, fixes PATCH (before 1.0.0, MINOR carries
   the breaking changes).
2. **`@Internal` types** (`ProxySupport`, `CallbackRegistry`, `SuperDispatcher`, …) — public
   only because generated classes must reach them from their target's package. Not for
   application code; versioned by `AheadOfTime.RUNTIME_ABI`, not by SemVer.
3. **The shape of generated classes** — member names, field layout, dispatch ordering — is an
   implementation detail, except the `$$` name marker frameworks rely on.

The Maven plugin's contract is its **XML configuration**, frozen and release-gated exactly like
the API.

## 12. Troubleshooting

| Symptom | Meaning |
|---|---|
| `ClassNotFoundException` for a name with `/0x` | Something did `Class.forName` on a hidden proxy's name — [§4](#4-core-concepts), [§8](#8-migrating-from-cglib) checklist item 3 |
| "no callbacks were set" / "there are N callbacks but no CallbackFilter" | Configuration order: set callbacks (or `setCallbackTypes` before `createClass()`); several callbacks need a filter |
| A method is not intercepted | `describeSkippedMethods()` names it and the reason (final, or a signature collision with a generated member) |
| "it is final / sealed / a record / an interface…" from `setSuperclass` | [§5.5](#55-what-cannot-be-proxied); for interfaces use `setInterfaces` |
| Proxy creation works but package-private methods are not overridden | The target's package is closed; the child-loader fallback engaged. Open the package (`opens` / `--add-opens your.module/your.pkg`) to regain package-private access, then `DefinitionSite.forget(target)` if the JVM already probed it |
| Slow first call, fast after | Expected: first-shape generation is milliseconds, then cached; pre-generate with the AOT plugin if startup matters |
| Need to see the generated bytecode | `-Dclasswright.dumpDir=/tmp/cw` and `javap -c` the dump |
| `IllegalStateException` from the build's own `PublicApiIT` in an IDE | Run through Maven (`mvn verify`); the API gate needs the packaged module descriptor — the message says exactly that |

## 13. The wider project

| Document | What it holds |
|---|---|
| [MIGRATION.md](MIGRATION.md) | the full migration contract, item by item, with the allowlist categories |
| [COMPATIBILITY.md](COMPATIBILITY.md) | versioning policy, JDK support policy, what counts as API |
| [BASELINE.md](BASELINE.md) | the measured performance record and its methodology |
| [AOT.md](AOT.md) | ahead-of-time generation and native image, in depth |

CI runs the full suite plus an executable conformance TCK on JDK 17, 21 and 25, and a nightly
canary against the next JDK's early-access builds — the alarm CGLib never had, so the next
platform shift arrives as a tracked issue instead of an emergency.

Classwright is licensed under the Apache License 2.0.
