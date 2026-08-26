# Performance Baseline

The measured performance record: every claim in this document is a number from a recorded run,
and every future run is judged as a diff against it. The current figures come from the
**2026-08-20 reference campaign** — the complete execution of the JMH harness on
the pinned Corretto 17 environment.

Reproduce with:

```bash
./mvnw -pl classwright-bench -am package -DskipTests
java -jar classwright-bench/target/benchmarks.jar \
  "GenerationBenchmark|DispatchBenchmark|CacheScalingBenchmark|FastClassBenchmark|FastClassScalingBenchmark|MethodProxyBenchmark|BeansBenchmark" \
  -prof gc -rf json -rff release-jmh.json
java --add-opens java.base/java.lang=ALL-UNNAMED -cp classwright-bench/target/benchmarks.jar \
  com.classwright.bench.FootprintHarness 5000 <arm>
```

> **Provenance.** Earlier revisions of this document carried figures from a harness that was
> later found to have four validity defects (warm-contaminated "cold" samples, unequal work
> between arms, single forks, a pre-resolved definition site), and every number here replaced
> them. The corrected harness is the one described in each benchmark's own javadoc: cold arms
> are single-shot across 25 fresh JVMs with zero warm-up; steady-state arms run 5 forks ×
> (5 warm-up + 5 measured) × 1 s; the footprint harness runs one arm per JVM. Errors are JMH's
> 99.9% confidence interval across forks. `B/op` is `gc.alloc.rate.norm`.

| | |
|---|---|
| CPU | AMD Ryzen 7 3800X, 8C/16T |
| Memory | 32 GB |
| OS | Windows 10 Home, build 19045 |
| JVM | Amazon Corretto 17.0.20+8-LTS, 64-Bit Server VM |
| Power plan | Balanced — not a fixed high-performance profile; treat cold single-shot error bars accordingly |
| Reference libraries | CGLib 3.3.0 (with `--add-opens`), Byte Buddy 1.15.11, JDK dynamic proxy |
| JMH | 1.37, compiler blackholes |
| Campaign | 119 forked runs, 1 h 14 m, 2026-08-20 |

---

## 1. Per-invocation dispatch — goal 2

`int compute(int, int)`, average time per call, lower is better.

| Arm | ns/op | B/op | What it measures |
|---|---:|---:|---|
| `direct` | 0.730 ± 0.006 | 0 | Unproxied call. The floor. |
| **`classwrightNoOp`** | **0.851 ± 0.008** | **0** | **Classwright proxy with `NoOp`** |
| `cglibNoOp` | 0.859 ± 0.007 | 0 | CGLib proxy with a no-op callback |
| `byteBuddySuperCall` | 0.863 ± 0.014 | 0 | Byte Buddy subclass delegating to super |
| `jdkProxy` | 3.781 ± 0.168 | 32 | JDK proxy, handler computing inline (a floor, not a proxy) |
| **`classwrightIntercept`** | **4.119 ± 0.073** | **32** | **Classwright `MethodInterceptor` calling `invokeSuper`** |
| `cglibIntercept` | 11.460 ± 0.115 | 72 | CGLib `MethodInterceptor` calling `invokeSuper` |
| `jdkProxyDelegate` | 12.296 ± 0.091 | 72 | JDK proxy, handler reflectively invoking a real target |

**The interceptor path is 2.8× faster than CGLib's with 2.25× less allocation** — the number an
application pays on every call forever, and the one this project exists for. The structural
explanation: CGLib's `invokeSuper` routes through a separately generated `FastClass` (a lookup
plus a `tableswitch` in another class), where Classwright's goes through `SuperDispatcher`, an
interface method on the proxy itself whose `tableswitch` cases perform a direct
`invokespecial`. One fewer hop and one fewer class. It also lands *below* the JDK proxy's
inline-handler floor while doing strictly more work.

**Reading the ~0.85 ns arms honestly.** Each benchmark has one receiver type at its call site,
so C2 inlines the whole thing to an add. Real applications with megamorphic call sites will not
see proxying collapse to zero; treat those three arms as "indistinguishable from direct when
perfectly inlined", not as "free".

> **Benchmark note.** An earlier revision used literal arguments `compute(17, 25)`. Both are
> inside the `Integer` cache, so boxing allocated nothing, and being compile-time constants let
> C2 fold the calls entirely — `jdkProxy` reported 3.3× faster than it is. The operands are now
> non-final fields holding values outside the cache range. Recorded because the flawed version
> looked completely plausible.

## 2. Generation — goal 1

### Cold: the first operation of a fresh JVM (single-shot × 25 forks)

| Arm | µs/op | Alloc | What it measures |
|---|---:|---:|---|
| `jdkProxyNewInstance` | 1,308 ± 119 | 0.33 MB | JDK proxy: interface-only, a smaller job, for scale |
| `classwrightEmitBytesOnly` | 8,683 ± 358 | 0.81 MB | class-file construction alone, no definition |
| **`classwrightGenerateClass`** | **10,069 ± 370** | **0.90 MB** | **bare subclass, generated and defined** |
| **`classwrightProxyGenerate`** | **37,758 ± 1,143** | 2.37 MB | **full proxy instance, cache off** |
| `cglibGenerateClass` | 47,475 ± 934 | 2.54 MB | proxy class only, cache off |
| `cglibCached` | 47,772 ± 897 | 2.56 MB | first proxy through CGLib's cache |
| `cglibCreateProxy` | 47,875 ± 1,771 | 2.55 MB | full proxy instance, cache off |
| `classwrightProxyCached` | 54,030 ± 1,030 | 3.18 MB | first proxy through Classwright's cache |
| `byteBuddyGenerateClass` | 175,476 ± 38,994 | 10.34 MB | fresh subclass, default loading strategy |

Cold class generation is **4.7× faster than CGLib and 17× faster than Byte Buddy**; a cold full
proxy is 1.27× faster than CGLib's. The one cold arm CGLib wins is first-proxy-through-cache
(47.8 vs 54.0 ms): Classwright's first pass pays cache installation, lookup resolution, and
hidden-class definition together. Every proxy after the first takes the warm path below.

### Warm: creation with the class already cached — the number that matters

| Arm | ns/op | B/op |
|---|---:|---:|
| **`classwrightProxyCachedWarm`** | **70.0 ± 2.0** | **112** |
| `cglibCachedWarm` (same run) | 74.4 ± 0.3 | 200 |

**Both axes now at or below CGLib's.** The campaign initially measured this arm at
98.5 ± 3.0 ns / 136 B against CGLib's 78.7 ± 1.3 ns / 200 B — a real ~20 ns deficit, flagged by
the release decision rules. Profiling the hit path found two per-create costs that existed only
to build the cache key: a fresh `ClassDefiner` (site probe, capability checks, an allocation)
and superclass re-validation that a cache hit had already proven. Deferring the definer behind a
site-freshness-checked per-class memo and moving validation onto the miss path took the arm to
**70.0 ns / 112 B**, measured the same day against CGLib's 74.4 ns in the same run. The delta
between CGLib's two same-day readings (78.7 vs 74.4) is the run-to-run variance floor on this
machine — the 28 ns improvement is 7× that.

### Warm: steady-state generation (only possible for Classwright)

| Arm | µs/op | |
|---|---:|---|
| `classwrightEmitBytesWarm` | 1.90 ± 0.18 | class-file construction alone |
| `classwrightGenerateAndDefineWarm` | 22.1 ± 1.8 | construction plus JVM class definition |
| `classwrightProxyGenerateWarm` | 170.9 ± 2.3 | full proxy, generated fresh, cache off |

Warm generation splits as roughly **2 µs of Classwright and 20 µs of JVM** — the engine is a
rounding error against the cost of defining a class at all, which is why the design generates
*one* class per proxy where CGLib generates about three. There is deliberately no warm CGLib
arm: a hot loop generating permanent classes exhausts metaspace before it converges. The
Classwright arm survives hundreds of thousands of iterations precisely because hidden classes
unload as it runs — the measurement and the design claim are the same fact from two angles.

## 3. Footprint and unloading — goal 3

5,000 generated classes per arm, all references dropped, forced collection; one arm per JVM.

| Arm | reclaimed | retained | peak | µs/class |
|---|---:|---:|---:|---:|
| CGLib (`Enhancer`, cache disabled) | **0.0%** | **23.52 MB** | 23.52 MB | 696 |
| Byte Buddy (subclass, default loading) | 100.0% | 0.03 MB | 5.29 MB | 260 |
| **Classwright (`Enhancer` proxy, cache disabled)** | **100.0%** | **0.00 MB** | 27.62 MB | **221** |
| Classwright (bare subclass, hidden) | 100.0% | 0.00 MB | 5.84 MB | 31.6 |
| Classwright (hidden, define only, bytes reused) | 100.0% | 0.01 MB | 5.84 MB | 17.6 |
| Classwright (bare subclass, named — opt-in) | 0.0% | 5.81 MB | 5.81 MB | 29.0 |

**CGLib reclaims nothing** — 23.52 MB for 5,000 proxies is metaspace an application never gets
back, the failure that made it untenable in long-running and redeploying applications.
**Classwright reclaims everything** and is also the fastest full-proxy generator under churn
(221 vs 260 vs 696 µs/class).

**A correction, kept.** An early revision claimed a quarter of CGLib's metaspace *peak*; that
compared unequal work. Like for like, the peak is slightly **higher** (27.6 vs 23.5 MB), because
one Classwright proxy class carries more than any one of CGLib's three. "One class instead of
three" buys generation speed and fewer definition events; the memory win is entirely
reclamation. The `named()` row is the honest cost of a `Class.forName`-resolvable name: 0%
reclaimed, same behaviour as CGLib, for the same reason — opt-in and documented. Byte Buddy's
100% rests on a new class loader per generated class; Classwright's hidden classes are
individually collectible in the target's *existing* loader.

## 4. Does CGLib even run on JDK 17?

Yes — but only with `--add-opens java.base/java.lang=ALL-UNNAMED`. Without it, CGLib 3.3.0
fails on JDK 16+ because it reaches into `java.lang` internals that strong encapsulation
closed. Every CGLib figure in this document was measured with that flag granted, so the
comparison is about performance rather than about CGLib failing to start. The flag is a
permanent tax on every application still depending on CGLib, and it is part of this project's
motivation.

## 5. The CGLib-parity surfaces

The utilities a migration actually exercises, measured against real CGLib in the same campaign.

| Surface | Classwright | CGLib | Verdict |
|---|---|---|---|
| `FastClass.invoke`, int method | **3.21 ns · 0 B** | 3.77 ns · 16 B | faster, allocation-free |
| `FastClass`, reference/void returns | 0.97–0.98 ns · 0 B | **0.85 ns · 0 B** | CGLib by ~0.1 ns |
| `FastClass.newInstance` | 2.11 ns · 16 B | 2.08 ns · 16 B | tie |
| `MethodProxy.invokeSuper` | **3.93 ns · 0 B** | 5.91 ns · 16 B | 1.5× faster, allocation-free |
| `MethodProxy.invoke` | **3.71 ns · 0 B** | 4.31 ns · 16 B | faster, allocation-free |
| `BeanCopier.copy` | 1.45 ns · 0 B | 1.45 ns · 0 B | exact tie |
| `BulkBean` get / set | **3.91 / 2.18 ns · 0 B** | 5.34 / 2.33 ns · 16/0 B | faster, allocation-free |
| `BeanMap` get / put, primitive | **1.43 / 1.68 ns · 0 B** | 2.18 / 2.38 ns · 16 B | faster, allocation-free |
| `BeanMap` get / put, reference | 1.34 / **1.47 ns** · 0 B | **1.16** / 1.67 ns · 0 B | split by ~0.2 ns |

**The BeanMap rework this table records.** The campaign first measured BeanMap 4–5× *slower*
than CGLib (get 8.76 vs 2.23 ns): reads went through a name→index map probe, an `Integer`
unbox, and a second dispatch. That measurement decided a deliberately deferred design question,
and the same-day rework generates what CGLib generates — a `lookupswitch` on the key's hash
jumping straight to the accessor (`getByKey`/`putByKey`, with per-key writability decided at
generation time). Re-measured in the same session: every arm at or below CGLib except reference
`get`, where CGLib keeps a 0.18 ns edge, and all Classwright arms allocation-free where CGLib
boxes 16 B on primitive paths.

**Known issue — wide dispatch tables.** `FastClassScalingBenchmark` (5 sizes × 5 positions):
below 512 methods the two libraries are equivalent (~4 ns, flat). At 600 and 1,000 methods
CGLib's single giant switch degrades uniformly to ~66 ns — the classic huge-method JIT bailout —
while Classwright's chunked dispatch turns *bimodal*: some positions hold ~4.3 ns (15× better),
others degrade to ~70 ns (parity), and which chunk stays fast flips between the 600- and
1,000-method shapes. Worst case matches CGLib, best case is 15× ahead; the position-dependence
is reproducible (tight error bars across 5 forks) and tracked as a dispatch-generator follow-up.

**Cache machinery under load** (`CacheScalingBenchmark`): hits stay at 7.6–17.9 ns from 1 to
100,000 populated shapes under one anchor — no linear drift, 0 B/op on the preallocated-key
path. Misses run 7–23 µs single-shot, dominated by generation, not bookkeeping.

---

## Targets for Classwright

Verdicts rendered by the 2026-08-20 campaign and its same-day optimization re-measurements.

| Goal | Target | CGLib | Status |
|---|---|---|---|
| Dispatch, interceptor path | ≤ CGLib | 11.46 ns · 72 B | ✅ **4.12 ns · 32 B — 2.8× better** |
| Dispatch, no-op path | ≈ direct | 0.86 ns | ✅ **0.85 ns**, direct is 0.73 |
| Classes generated per proxy | 1 | ~3 | ✅ 1 |
| Classes reclaimed | 100%, no loader per class | 0% | ✅ **100%**, no extra loader |
| Generation, fresh proxy, cold | ≤ CGLib | 47.9 ms | ✅ **37.8 ms — 1.27× better** |
| Generation, fresh class, cold | ≤ CGLib | 47.5 ms | ✅ **10.1 ms — 4.7× better** |
| Generation, cache hit, warm | ≤ CGLib, both axes | 74.4 ns · 200 B | ✅ **70.0 ns · 112 B** (post-rework; was ❌ 98.5 ns) |
| Bean utilities | ≤ CGLib | see §5 | ✅ at/below on every arm but reference `BeanMap.get` (−0.18 ns) |
| Metaspace peak | ~~below CGLib~~ | 23.5 MB | ➖ 27.6 MB — target withdrawn; see the §3 correction |
| Wide-table dispatch ≥ 600 methods | flat | ~66 ns uniform | ⚠ bimodal 4.3–71 ns — open follow-up |

Every steady-state goal is met on current evidence. The two honest asterisks: the first
cached proxy of a shape costs ~6 ms more than CGLib's cold (§2), and dispatch tables beyond 512
methods carry the bimodality above. Future release campaigns compare against
`release-jmh.json` plus the re-measurement figures recorded here, run-to-run, on this machine.
