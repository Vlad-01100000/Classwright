# Ahead-of-time proxies and GraalVM native image

How to generate proxies at build time instead of at runtime, and why you would.

---

## Why this exists

A GraalVM native image is compiled under a **closed-world assumption**: every class must exist when
the image is built, and defining a class at runtime is not supported at all. That rules out the
normal path — generate bytes, define them on demand — completely. It is not a limitation of
Classwright but of the execution model, and it is a large part of why Spring moved away from CGLib
for its native story.

The fix is to do the same work earlier. Classwright can generate the proxy classes at build time as
ordinary `.class` files, which native-image then compiles in like any other class. At runtime,
`Enhancer` finds the pre-generated class and uses it instead of generating one.

It is worth using outside native images too. An application that builds thousands of proxies at
startup can build them at compile time instead.

## The shape of it

```
build time                                    runtime
──────────                                    ───────
ProxyBlueprint ──► AheadOfTime.writeTo()      Enhancer.create(...)
                     │                                 │
                     ├─ Service$$CW$1a2b3c4d.class     ├─ AotProxies.find(key)
                     ├─ META-INF/classwright/          │     │
                     │    proxies.index   ─────────────┼─────┘  key ──► class name
                     └─ META-INF/native-image/.../     │
                          reflect-config.json          └─ found: use it, generate nothing
```

The connection between the halves is `ProxyBlueprint.key()` — plain text, built from class names and
flags, written into the index at build time and recomputed from the `Enhancer`'s configuration at
runtime. Both sides construct it through the same code, so they cannot drift apart.

Two more things travel with the index, both guards against the halves being *different builds*:

- **A routing fingerprint per entry** — a digest of each method's assigned callback index and the
  generation flags. The runtime recomputes it from its own discovery and its own `CallbackFilter`
  instance before adopting; a mismatch (a stateful filter answering differently, a recompiled
  target) is refused loudly instead of dispatching to the wrong callback.
- **A runtime ABI version per index** (`%abi` directive, `AheadOfTime.RUNTIME_ABI`). Generated
  bytecode calls Classwright's runtime helpers, whose shape is versioned with the generator, not
  with the public API. An index generated for a different ABI is refused up front — with a
  message saying to regenerate — rather than surfacing as a `NoSuchMethodError` at first
  dispatch. On an ordinary JVM the refusal just means proxies are generated at runtime; in a
  native image it is an early, explained failure instead of a late mystery.

## With Maven

```xml
<plugin>
  <groupId>com.classwright</groupId>
  <artifactId>classwright-maven-plugin</artifactId>
  <version>${classwright.version}</version>
  <executions>
    <execution>
      <goals><goal>generate-proxies</goal></goals>
    </execution>
  </executions>
  <configuration>
    <proxies>
      <proxy>
        <superclass>com.example.OrderService</superclass>
        <callbacks>
          <callback>com.classwright.proxy.MethodInterceptor</callback>
        </callbacks>
      </proxy>
    </proxies>
  </configuration>
</plugin>
```

Runs at `process-classes`, so the project's own classes are compiled and proxyable, and the
generated classes are in place before the jar is built.

| Element | Meaning | Default |
|---|---|---|
| `<superclass>` | the class to extend | required |
| `<callbacks><callback>` | callback **types**, in slot order | required |
| `<interfaces><interface>` | extra interfaces to implement | none |
| `<filter>` | a `CallbackFilter` with a no-argument constructor | none |
| `<useFactory>` | implement `Factory` | `true` |
| `<interceptDuringConstruction>` | callbacks fire during construction | `true` |
| `<copyAnnotations>` | reproduce the target's annotations | `false` |
| `<naming>` | `DEFAULT` or `CGLIB_COMPATIBLE` | `DEFAULT` |

Skip with `-Dclasswright.skip=true`.

**The project must depend on `com.classwright:classwright`, at the plugin's version.** The
generated classes link against the library's runtime ABI, so the goal fails the build when the
dependency is missing (they could never load) or at a different version (the dispatch tables can
disagree, and the failure would be quiet: silent runtime regeneration on a JVM, an outright
failure in a native image). For the rare assembly where a different layer of the build supplies
classwright at runtime, opt out explicitly with
`<allowMissingRuntimeDependency>true</allowMissingRuntimeDependency>` — the version-match
obligation then transfers to whatever does the supplying.

**Callback types, not instances.** At build time there are no callback instances yet, so a blueprint
names the *types*. The instances are supplied at runtime exactly as with `Enhancer.createClass()` —
by passing them to `create(...)`, or through `Factory`.

## Without Maven

The plugin is a thin driver over a public API, so any build tool — or a plain `main` — can do it:

```java
List<ProxyBlueprint> blueprints = List.of(
        ProxyBlueprint.of(OrderService.class)
                .callbacks(MethodInterceptor.class)
                .build());

AheadOfTime.writeTo(Path.of("build/classes/java/main"), blueprints);
```

## Checking it worked

`AotProxies` reports what it found. The indexes are per class loader, so name a class from the
loader you care about — the proxy's target is the natural choice:

```java
System.out.println(AotProxies.describe(OrderService.class));
```

```
1 ahead-of-time proxies registered:
  com.example.OrderService$$CW$1a2b3c4d5e6f7081
    for com.example.OrderService||com.classwright.proxy.MethodInterceptor||true|true|false|$$CW|CW$
```

(The key's fields are the superclass, interfaces, callback types, the filter class — empty when
there is none — the three generation flags, and the two naming components.)

If a configuration is registered, `enhancer.createClass().getName()` equals the pre-generated name
and `isHidden()` is `false`. If it is not registered, Classwright generates at runtime as usual — on
an ordinary JVM that is invisible, and in a native image it fails, which is the correct outcome
because a proxy that was never generated cannot be conjured.

A configuration that differs **in any way** — a different callback type, `useFactory`, the naming
convention — is a different key and therefore not a match. That is deliberate: a near-miss silently
resolving to the wrong class would be far worse than not resolving at all.

## What you give up

| | Runtime generation | Ahead of time |
|---|---|---|
| Unloadable | yes, hidden classes | **no** |
| `Class.forName` | no | yes |
| Package-private override | yes | yes |
| Works in a native image | **no** | yes |
| Cost at startup | generate + define | a name lookup |

Pre-generated classes are ordinary named classes, so they are **never unloaded**. That is inherent:
a class compiled into a native image is part of the image. Everything in
[RESEARCH.md §2](RESEARCH.md#2-measured-hidden-classes-solve-the-footprintunloading-problem-outright)
about reclamation applies to the runtime path, not this one. Use it where the closed world requires
it, or where startup cost matters more than reclamation — not by default.

Two further limits, stated plainly:

- The proxy is placed in **its target's package**, so the build output contains a package that also
  exists in another jar. Fine on the class path; a split package under JPMS.
- A `CallbackFilter` must be instantiable at build time, with a no-argument constructor, because
  deciding which callback handles which method means running it.

## Native image

The plugin writes reachability metadata to
`META-INF/native-image/com.classwright/classwright/`, which `native-image` picks up automatically:

- `reflect-config.json` — the target hierarchy and interfaces as *query-only* entries (method
  discovery still runs reflectively on the pre-generated path; it is what fills in the dispatch
  table), the proxied methods as invocable on their declaring classes, and the generated class's
  `CW$init`, fields, and constructors. Callback types and the `CallbackFilter` are deliberately
  absent: nothing reflects on them at runtime, and every registered member is one the image
  shrinker must keep.
- `resource-config.json` — the index, so it can be read from inside the image.

> **Not yet verified against a real native image.** Everything above is tested — including that a
> pre-generated proxy is adopted by an ordinary `Enhancer` call in a fresh JVM, which is what
> `AheadOfTimeIT` checks by launching a child process. What the build cannot currently check is that
> `native-image` accepts the result, because CI has no GraalVM. Treat the metadata as
> well-formed-and-plausible rather than proven, and please report anything it gets wrong. Adding a
> GraalVM job to CI is the obvious next step.
