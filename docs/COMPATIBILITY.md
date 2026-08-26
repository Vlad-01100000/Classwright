# Compatibility policy

What Classwright promises about not breaking your code, and what enforces each promise.
Each promise below names the mechanism that makes it true; a promise with no
mechanism is a wish.

---

## 1. Versioning

[Semantic versioning](https://semver.org). Given `MAJOR.MINOR.PATCH`:

| Change | Version |
|---|---|
| Removing or narrowing anything public, changing behaviour incompatibly | MAJOR |
| Adding API, adding a JDK to the supported set, deprecating something | MINOR |
| Fixes that keep the same API and the same behaviour | PATCH |

Before `1.0.0`, MINOR carries the breaking changes and the guarantees below apply from `1.0.0`
onward. That is the one period where the API is still allowed to be wrong.

## 2. What counts as public API

Everything exported by the `com.classwright` module — the packages listed at the top of
[`classwright/api/classwright.api`](../classwright/api/classwright.api) — including **protected**
members of public types, because a subclass in another package can reach them, **except types
annotated `@com.classwright.Internal`**. Those are the generated-code runtime ABI: public and
exported only because generated classes live in their target's package and the JVM's
accessibility rules leave no choice (`ProxySupport`, `CallbackRegistry`, `SuperDispatcher`,
`RuntimeConstants`, `BulkAccessException`). Their *existence* is reviewed — the API snapshot
lists each as one opaque line, and removing one is a visible diff — but their members are not
frozen, and both the snapshot and the release `japicmp` gate exclude them. Their compatibility
story is `AheadOfTime.RUNTIME_ABI`, versioned with the generator, not this document. Application
code that calls an `@Internal` type is on its own between releases.

Explicitly **not** API, and changeable in any release:

- anything not exported by `module-info.java`;
- the **shape of generated classes**: member names such as `CW$super$…`, field layout, the dispatch
  table's ordering. Generated classes are an implementation detail. Code that reflects over them by
  name is depending on something that was never promised. The one exception is the `$$` marker in
  generated class names, which frameworks detect proxies with, and which is covered by §5;
- the exact wording of exception messages, though the *type* thrown is API;
- the `net.sf.cglib` shim's fidelity to CGLib beyond what
  [MIGRATION.md](MIGRATION.md) documents as supported.

## 3. What enforces it

Three gates, deliberately answering different questions:

| Gate | Question | When |
|---|---|---|
| [`PublicApiIT`](../classwright/src/test/java/com/classwright/api/PublicApiIT.java) | did the API change at all? | every build |
| `japicmp` (release profile) | is there a binary/source incompatibility, and may this version carry one? | release workflows |
| contract policy ([`.github/scripts`](../.github/scripts)) | is the accumulated API/contract change legal for the version being released? | release workflows |
| [`classwright-tck`](../classwright-tck) | does the behaviour still hold on this JVM? | every build, and nightly against early-access JDKs |

The first is a checked-in snapshot of the API as text. Any change to the public surface turns into a
diff that a reviewer has to approve in the pull request that causes it, so nothing becomes API by
accident. It needs no baseline artifact and no network, so it works from the first commit and on
every JDK.

The second and third together answer the question the snapshot cannot: not "did this change" but
"is this change allowed **for this version**". The release stage derives the gate configuration
from the (baseline, version) pair with `compatibility-policy.sh`, implementing §1 mechanically:

- **japicmp** detects binary/source incompatibility against the previously released jar. Whether
  that fails the build depends on the version: always at a PATCH or a post-1.0 MINOR, never at a
  MAJOR or a pre-1.0 MINOR (where breaking is legal by policy, and japicmp runs report-only).
  japicmp's own semantic-versioning switch is deliberately not used — a fixture proved it
  classifies additive public API as PATCH-level, which contradicts §1.
- **The contract policy** judges additions and removals. The core jar embeds its reviewed API
  snapshot (`META-INF/classwright/classwright.api`) and the Maven plugin embeds its
  configuration contract (`META-INF/classwright/plugin-contract.txt`), so the release stage
  diffs the baseline's *actual published* contract against the current one: identical passes
  anywhere, additions need at least a MINOR, removals or changes need a MAJOR (a MINOR before
  1.0.0). This is the release-level gate that the per-build snapshots cannot provide once a
  developer has deliberately updated them.

The whole legality matrix — every version-pair class, every contract-diff class, and the japicmp
flag behaviour against real fixture jars — is executable and runs in CI
(`compatibility-policy-test.sh`, `compatibility-fixtures.sh`), so the policy above is a tested
property, not prose. One honest boundary: the `net.sf.cglib` shim has no additive gate of its
own — its surface is structurally pinned to real CGLib by the parity gate and allowlist, so any
surface change already forces a reviewed allowlist diff, and incompatibilities are covered by
japicmp like the core.

The fourth is behavioural rather than structural, and is the one that matters for JDK transitions.

**The Maven plugin's contract is its XML configuration**, not its Java classes — a consuming POM
names the goal, the parameters, the nested `<proxy>` fields and relies on their defaults, and
never links against the mojo. So the plugin skips `japicmp` (which would freeze the wrong thing)
and is gated instead by
[`PluginContractTest`](../classwright-maven-plugin/src/test/java/com/classwright/maven/PluginContractTest.java),
which diffs the goal name, lifecycle binding, every user-facing parameter, property, nested field,
and default against the reviewed snapshot
[`classwright-maven-plugin/api/plugin-contract.txt`](../classwright-maven-plugin/api/plugin-contract.txt)
on every build. The version rules of §1 apply to that contract exactly as they apply to the
library API: removing or renaming a goal, parameter, or nested field, changing a default, or
moving the default phase is MAJOR (breaking before 1.0.0 per the pre-1.0 rule); adding an
optional parameter or nested field is MINOR; behavioural fixes that leave the contract untouched
are PATCH.

> **Accidental API is permanent API.** Once a release goes out, removing something is a breaking
> change whether or not anyone meant to publish it. That is why the cheap gate runs on every build.

## 4. Deprecation

Nothing is removed without warning. To retire something:

1. Mark it `@Deprecated(since = "x.y", forRemoval = true)` and say what to use instead, in Javadoc.
2. Leave it working for **at least two MINOR releases and at least six months**, whichever is longer.
3. Remove it only in a MAJOR release.

Deprecating is a MINOR change. Removing is a MAJOR one.

## 5. JDK support

The baseline is **Java 17** and the emitted bytecode is **class file version 52 (Java 8)** — the
lowest that supports what generated classes need. JVMs accept old class files essentially forever,
so emitting low is what makes output forward-compatible with JDKs that do not exist yet. See
[RESEARCH.md §5](RESEARCH.md#5-measured-how-low-the-emitted-class-file-version-can-go).

- **Supported**: JDK 17, this release's stated scope. The support promise is deliberately no
  wider than the release: promising a JDK is a compatibility-policy commitment, and retracting
  one later is a compatibility-policy event, so each LTS is *promised* only when its release
  work is actually done.
- **Compatibility-tested**: JDK 21 and JDK 25. CI runs the full suite and TCK on each, and a
  failure there blocks merges like any other — but until their release milestones land, that is
  evidence gathering ahead of the promise, not the promise itself.
- **Watched**: the current early-access build, nightly. Allowed to fail; a failure opens an issue.
  This is the habit CGLib did not have, and the reason its JDK 9 and JDK 16 breakages each arrived
  as an emergency rather than as a known issue with months of lead time.
- Raising the baseline is a MAJOR change. Promoting a compatibility-tested JDK to supported is
  MINOR.

Support for a new JDK means one thing: **the TCK passes on it.**

```bash
java -jar classwright-tck.jar
```

## 6. Behavioural compatibility

Where Classwright reproduces a CGLib API, it reproduces CGLib's *behaviour*, including choices that
look like mistakes — `ImmutableBean` throws `IllegalStateException` rather than
`UnsupportedOperationException`, because that is the type existing code catches. Differential tests
against real CGLib pin this.

Where Classwright deliberately differs, the difference is documented and the shim hides it. The one
case today: `Enhancer.setSuperclass(anInterface)` is refused by the native API with a message
pointing at `setInterfaces`, where CGLib silently reinterpreted it. The `net.sf.cglib` shim keeps
the old behaviour, so migrating code is unaffected.

## 7. Dependencies

Classwright has **no runtime dependencies and will never have any**, and does not shade anything.
This is enforced by `ArchitectureRules`, by the `bannedDependencies` enforcer rule, and by a CI job that
inspects the built jar for foreign packages and stray `requires` entries.

It is a compatibility promise, not just a design preference: a shipped dependency is what fragmented
CGLib into `cglib`, `cglib-nodep`, and Spring's repackaged fork, and what left it pinned to an ASM
version too old to read newer class files.
