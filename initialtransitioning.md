# Initial transitioning notes

## Scope

This document records the transitioning of the **old Java finance infrastructure** in
`j_algotrading_infrastructure/Core/JavaCepFinance` (and the parts of `JavaCoreLib` it leans on) into
**pulse-finance**.

The old system is a CEP-style, event-driven trading infrastructure built on `A_Datum`,
`A_RunnableProducerConsumer` and `Topic`. Pulse provides the replacements for that substrate:
**pulse-data** for generated, schema-defined datum types, and **pulse-beacon** for the engine,
topics, actors, gateways, run recording and SQL persistence. What remains to be transitioned is the
*financial* content: position accounting, PnL, portfolio analytics, execution simulation and exchange
simulation.

This is not a lift-and-shift. Each old package is reviewed on its own terms, and the outcome per
package is one of:

- **close port**: the logic is the asset; carry it over with minimal change,
- **port the logic, replace the layer**: behaviour is the spec, the surrounding structure is not,
- **rewrite**: the old structure encodes assumptions that no longer hold,
- **drop**: superseded by pulse-data or pulse-beacon.

Reviews are added here package by package as they are done. The document began as pure analysis;
implementation has since started, and [Implementation status](#implementation-status) is the current
record of what exists versus what is still a decision. Normative specifications live beside it in
`docs/`.

### Conventions assumed

- Java namespace `com.inventzia.pulse.finance`, Python namespace `inventzia.pulse.finance`
  (see `README.md`).
- Datum types are **never** hand-written in this repo, but they **are owned** by it: finance YAML
  lives in `pulse-finance/schemas/`, and pulse-data's generators are invoked against it to emit
  bindings under the finance namespace plus a finance `DatumTypeProvider`. See
  [Decision: datum ownership](#decision-datum-ownership). This was the first decision the package
  review forced, and it was resolved against the initial proposal.
- Event-driven components extend `AbstractActor` (consume and publish) or `AbstractGateway`
  (drive the clock / own an external boundary) from `pulse-beacon-core`.
- Money is `BigDecimal`. There is no float anywhere in a monetary path.

---

## Decision: datum ownership

**Question.** Should finance datum types (`Position`, `PositionState`, and every finance event that
follows) be owned by the **pulse-data** repository as a new core schema group, or owned by
**pulse-finance** and generated with pulse-data's generators?

**Decision: pulse-finance owns its datums.** The initial proposal in the `position` review, a new
`position` schema group inside pulse-data, is withdrawn. It was wrong, and not merely stylistically:
it would bypass the extension mechanism that pulse-data was deliberately built to provide.

### The two decisions, kept separate

The question only looks like one decision. It is two, and conflating them is what made the original
proposal look reasonable:

| | Decision | Answer |
|---|---|---|
| **Meaning** | Who owns the *semantics and evolution* of orders, fills, positions and valuations? | **pulse-finance** |
| **Machinery** | Who provides generation, codecs, discovery and cross-language validation? | **pulse-data** |

Finance owning the meaning does not require finance to own the machinery, and using the machinery
does not require the meaning to move into it. The SPI exists precisely to let those two answers
differ.

**Using the extension mechanism does not push work onto application developers.** The provider is
implemented **once**, here in pulse-finance. An application installs the package and uses the types;
discovery is automatic in both languages (verified below). Packaging the provider (the Python
entry point and the Java `META-INF/services` entry) is pulse-finance's responsibility, discharged
once, not an obligation inherited by every consumer.

### Ownership across the stack

| Package | Owns |
|---|---|
| `pulse-data` | Datum contract, schema tooling, codecs, registry, and broadly shared types |
| `pulse-finance` | Financial schemas, generated bindings, accounting and simulation |
| Broker adapters | Broker-specific types, and their conversion to the shared finance types |

Every financial application and adapter reuses the **same** finance datums, so finance ownership does
not fragment the vocabulary: a broker adapter converts *into* these types rather than defining
rivals to them.

**`CdfBar`.** It currently lives in pulse-data and is a precedent, but it does not determine where
subsequent financial types live. Leave it in pulse-data for compatibility through this transition,
and **do not duplicate it** in pulse-finance. If it is ever re-homed, that is a separate, deliberate
migration, not something this decision triggers.

### Why this path is designed, implemented and already exercised

1. **The SPI exists and is namespace-enforcing.**
   `com.inventzia.pulse.data.datum.DatumTypeProvider` declares `providerId()` as a *"reverse-DNS
   namespace root; every contributed `TYPE_ID` starts with it + `.`"*, alongside `spiVersion()`,
   `packageVersion()`, `bindings()` and a `manifest()` hook. `DatumTypeRegistry` discovers extension
   providers via `ServiceLoader` (core is seeded directly, never discovered), and **validates** the
   namespace rather than trusting it:

   ```java
   if (!b.typeId().startsWith(pid + ".")) {          // DatumTypeRegistry:232
       fail(... "' is not under its namespace '" + pid + ".'");
   }
   ```

   It also rejects an empty/malformed `providerId` and duplicate provider IDs. So a cross-repository
   `TYPE_ID` collision (the one real argument for central ownership) is **detected and rejected at
   startup**, by validation, rather than left to a convention someone must remember. Stated
   precisely: nothing stops two repositories *authoring* conflicting declarations; what the registry
   guarantees is that such a pair cannot start ambiguously: it fails, naming the offender.

2. **The generators are fully parameterised for out-of-repo use.** Both `generate_java.py` and
   `generate_python.py` take `--schemas-dir`, `--output-dir`, `--base-package`, `--provider-id`,
   `--provider-class` and `--package-version`. The defaults are core's values; nothing about them is
   core-only. The provider class itself is generated (`CoreDatumTypeProvider.java` carries
   *"THIS FILE IS GENERATED. DO NOT EDIT MANUALLY."*), so an extension gets its provider for free.

3. **There is a complete working precedent, in both languages, with tests.**
   `pulse-beacon/examples/pulse-ext-example/` owns `schemas/extended_bar.yaml` and generates
   `ExtendedBar` + `ExtDatumTypeProvider` (`providerId() == "com.inventzia.pulse.ext"`) into
   `com.inventzia.pulse.ext.schemas` and `inventzia.pulse.ext.schemas`, registered through
   `META-INF/services/com.inventzia.pulse.data.datum.DatumTypeProvider`, driven by a 25-line
   `regen.sh`, and covered by `tests/test_datum_registry_spi.py` and
   `tests/test_type_universe_gate.py`. pulse-finance is the same shape as that example, one size up.

**What this buys.** Finance schema releases decouple from core pulse-data releases, the point that
matters most, since finance schemas will churn (instrument model, multiplier, fee and cash-flow
events) while core should not. Codecs, schema fingerprints, the tagged `DatumCodec`, cross-language
parity and registry validation are all preserved, because they are properties of the generator and
the SPI, not of the repository the YAML sits in. Nothing finance-specific enters core's type
universe, so a non-finance deployment stays unaware of it.

**What the alternative would have cost.** Putting the types in pulse-data saves the initial
packaging work, a one-off. The standing cost is that every change to a financial contract becomes a
change to the foundational package: its release cadence, its compatibility decisions, and its default
type universe. Non-financial users would inherit that coupling even if they never instantiate a
position. A one-time packaging cost in exchange for permanent decoupling of the layer that will churn
most is the right trade.

**What this decision does cost, stated honestly.**

- **A build-time dependency on pulse-data's generators**, not just its artifacts. The ext example
  resolves them by sibling-checkout path with a `PULSE_DATA_GENERATORS` override; pulse-finance needs
  the same, and `regen.sh` output must be committed so a consumer building from source does not need
  Python.
- **Two separate guarantees must both be pinned.** `SPI_VERSION` compatibility (currently `1`) is a
  *runtime* contract between the finance provider and pulse-data's registry. **Generator
  reproducibility is a different guarantee**, and a sibling checkout plus `SPI_VERSION == 1` does not
  provide it: the same schema run through two generator revisions can emit different bindings while
  both remain SPI-1 compatible. Record the **supported generator version or revision** explicitly
  (pinned in `regen.sh` and stated in the README), and have **CI regenerate and fail on any
  difference** against the committed output. Pinning one and assuming the other is the trap.
- **SQL support is per-field, not automatic.** `StorageMapping` maps scalars and refuses what it
  cannot represent exactly: arrays are rejected outright today. "A SQL table for free" was an
  overstatement in the original review; the correct claim is that *mappable* finance schemas get a
  table for free, and a schema must be checked against `StorageMapping` before it is relied on for
  persistence or recovery.

### Acceptance test for this decision

**A finance application must be able to use these types through ordinary installation and startup,
with no manual registry manipulation.** If that experience is cumbersome, the fix is to improve
extension packaging once, which benefits every future domain package, not to move the schemas into
core.

**Status: MET.** `pytest tests/` passes in release mode (`PULSE_FINANCE_RELEASE=1`), 26 tests,
with the four placeholders replaced by real scenarios. The evidence:

- **Four wheels built and installed offline** (`--no-index`) into a venv **outside the checkout**:
  pulse-data, pulse-beacon, pulse-finance and a second extension. Asserted non-editable, and that
  every distribution resolves from inside the venv rather than the source tree.
- **`start_jvm()` with no arguments** composes three jars, and the Python and Java provider lists
  are asserted **identical**, which is the cross-language gate passing on installed artifacts.
- **Composition is proven, not asserted**: a second extension under a third-party namespace
  (`com.acme.telemetry`, generated by pulse-data's generators with no `inventzia` prefix anywhere)
  is installed alongside finance, and both providers and both type sets are used.
- **Exact decimals across both crossings**: `68.47` and `0.000000000001` survive Python to Java to
  Python unchanged.
- **Mutation-tested.** Removing the second extension's `jvm_classpath` declaration while leaving
  its types declared breaks three scenarios, which is the proof that the suite depends on discovery
  actually working rather than on the environment happening to be right.

What this does **not** prove, and must not be read as proving: that a user can obtain the required
pulse-beacon from a package index. Development and CI build beacon's wheel from a pinned commit.
Publishing finance still requires a published pulse-beacon containing discovery, tested through the
`execution` extra's declared dependency.

The original gap, recorded for the history:

What the ext example does establish:

- **Python discovery** is PEP 621 packaging metadata:
  `[project.entry-points."inventzia.pulse.datum_types"]` in `pyproject.toml`, read by
  `importlib.metadata.entry_points(group=…)` in pulse-data's `registry.py`.
- **Java discovery** is `ServiceLoader` over
  `META-INF/services/com.inventzia.pulse.data.datum.DatumTypeProvider`.
- **Application code touches the registry zero times.** Both ext examples call only
  `registerPublisher` / `registerActor`; `AbstractEngine.initialize()` runs SPI discovery itself, and
  `DatumTypeRegistry.of(…)` is documented as being for tests and custom universes.
- **Cross-language divergence is caught and named.** `pulse-beacon/tests/test_type_universe_gate.py`
  asserts a provider present on one side only is detected and reported by type name (*"only in
  Python"*) rather than failing silently later.

What it does **not** establish, and this is the gap:

```python
# pulse-ext-example/examples/extended_bar_run.py:32,101
_EXT_JAR = Path(__file__).resolve().parents[1] / "java" / "target" / "pulse-ext-example-0.1.0.jar"
...
raise FileNotFoundError(f"build the extension jar first: {_EXT_JAR} (mvn -f java/pom.xml package)")
...
jpype_host.start_jvm(extra_classpath=[_EXT_JAR])
```

The example locates its Java JAR by **hardcoded path into its own source checkout's Maven build
output** and injects it with `extra_classpath`. It therefore proves discovery works *given a
classpath the developer assembled by hand*; it proves nothing about the installed-wheel experience,
which is precisely what the criterion is about. An earlier draft of this section claimed the
criterion was "already satisfied"; that was too strong and is withdrawn.

**Finance must still specify** how its installed wheel ships or locates the Java JAR, and how startup
puts it on the classpath without the application knowing a path. Candidate approaches (to be decided
during provider packaging): ship the JAR inside the wheel as package data and have the finance
package contribute it to the classpath automatically; or publish a classpath-contribution entry point
that `jpype_host` consults, so no caller passes `extra_classpath` at all. The second is the better
outcome and would benefit every future domain package; see the note on fixing packaging once.

**The real acceptance test, to be written and gated in CI:**

1. Build the distributions (wheel + JAR) as a release would.
2. Install them into a **clean environment, outside the source checkout**, with no Maven available
   and no manually located JARs.
3. Run a **Python → Java → Python finance-event round trip**: publish a finance datum from Python,
   have a Java actor observe it, return a derived finance datum to Python, asserting the values
   survive both crossings.
4. Assert the application code contains no `extra_classpath`, no path into a `target/` directory, and
   no registry manipulation.

Until that test exists and passes, the installation criterion is **open**. If it turns out cumbersome,
the fix is to improve extension packaging once, in pulse-data / `jpype_host`, not to move the schemas
into core.

### Proposed design for the installation criterion

**Status: proposed, not implemented. One of the changes lands in pulse-beacon, so it is not
pulse-finance's decision alone.**

#### What already exists

`jpype_host.resolve_classpath()` resolves beacon's classpath three ways, in order: an explicit
`jars_dir`; else a **shaded runtime jar bundled in the installed wheel**
(`inventzia.pulse.beacon._runtime/pulse-beacon-runtime.jar`, located via `importlib.resources`,
staged by `build-runtime-jar.sh`); else staged jars in a source checkout. Beacon has therefore
already solved this for itself. What it has no path for is **anyone else's** jar, which is why
`pulse-ext-example` falls back to `extra_classpath` and why the criterion is still open.

#### The entry-point contract

| | |
|---|---|
| **Group** | `inventzia.pulse.jvm_classpath` |
| **Each entry point references** | a **callable**, not a module attribute, returning a sequence of JAR paths |
| **The callable must not** | start a JVM, import JPype, or touch the datum registry. It runs *before* Java exists, so it may only locate resources (`importlib.resources`, package data) |
| **The callable may** | return an empty sequence, which is not an error: a data-only install legitimately contributes no jar |

A callable rather than a static path lets a package locate its own resources through
`importlib.resources`, which is the only portable way to do it from inside a wheel, including a
zipped one.

**Beacon's obligations**, all discharged **before** the JVM starts:

1. **Deterministic order.** Contributions are collected in a defined order, sorted by entry-point
   name, so a classpath is reproducible across machines and installs. Entry-point iteration order is
   otherwise unspecified, and an unstable classpath makes version-shadowing bugs irreproducible.
2. **Normalise and deduplicate.** Absolute, OS-normalised paths, the way `_normalize_classpath_entry`
   already does, so the same jar contributed twice appears once.
3. **Validate and attribute.** A contributor that raises, or that returns a path which does not
   exist, fails startup with a message **naming the distribution responsible**. A missing jar
   discovered as a `NoClassDefFoundError` deep in a run is the outcome this exists to prevent.
4. **Report the composition.** The source description that `resolve_classpath` already returns names
   the contributing packages, so a wrong classpath is diagnosable from the log alone.

**Interaction with the existing inputs.** Contributions are included in **every** startup mode, so
behaviour does not change depending on which argument a caller happened to pass. Resolution order
becomes: beacon's own jars (explicit `jars_dir`, else bundled, else staged), then discovered
contributions, then `extra_classpath`. The explicit argument stays last so a developer can still
override, and `jars_dir` continues to replace only *beacon's* jars rather than suppressing
contributions.

#### The already-running JVM path needs the same treatment

Adding discovery to `resolve_classpath()` alone is **not sufficient**, and this is the part most
likely to be missed. `start_jvm()` takes a separate branch when a JVM is already running, and its
classpath check is guarded:

```python
if jpype.isJVMStarted():
    if jars_dir is not None or extra_classpath:      # <- only then is anything checked
        ...
```

So a **no-argument** `start_jvm()` against a running JVM resolves no classpath and checks nothing.
Since the recommendation above makes the no-argument call the normal one, that hole becomes the
common case rather than an edge case. It matters for notebooks, for retries after a failed
verification, and for host applications that booted the JVM before calling beacon.

**Required behaviour on reuse:** resolve the discovered contributions, compare them against the
running classpath, and if any are absent raise with a clear instruction to restart the JVM with the
full classpath. Cross-language verification (`verify_type_universe()`) must continue to run on
reuse, as it does today.

#### Why a thin jar, stated correctly

An earlier draft of this section claimed that duplicate classes in two jars would produce two
incompatible `DatumTypeProvider` types and a `ServiceConfigurationError: not a subtype`. **That was
wrong.** Java type identity is the pair of the class and its *defining class loader*, so two copies
of a class on one ordinary flat classpath do not yield two types: the first copy found wins and the
second is simply never loaded.

The real reasons for a thin jar are sufficient on their own:

- **Version shadowing.** Beacon's bundled jar already embeds pulse-data, Jackson and jspecify
  (measured: 2,213 entries, of which pulse-data 22, Jackson 1,185, jspecify 6). A second copy at a
  different version would be shadowed silently by whichever appears first, which is a bug that
  reproduces only on some orderings.
- **Duplicate dependencies.** Shading the same libraries twice inflates the artifact and the
  install for no benefit, since beacon's jar is always on the classpath ahead of the contributions.
- **Unpredictable linkage.** Which copy satisfies a given reference depends on classpath order, and
  that is not a property a release should depend on.

A thin finance jar is also *sufficient*, precisely because beacon's fat jar supplies pulse-data and
Jackson already.

Related detail worth recording: beacon's jar carries **no**
`META-INF/services/com.inventzia.pulse.data.datum.DatumTypeProvider` entry, consistent with core
being seeded directly rather than discovered. Finance's services entry therefore remains the only
one, and the namespace check still applies to it.

#### Why not a bootstrap per package

The alternative is for finance to provide its own bootstrap calling
`start_jvm(extra_classpath=[its own jar])`. It does not compose. **Under beacon's supported startup
contract** the classpath is fixed at `startJVM` and not extended afterwards, which `start_jvm`
enforces by raising rather than silently dropping late additions. (This is beacon's contract, not a
universal claim about the JVM: a program can always load classes later through its own class loader.
Beacon does not, and application code should not have to.)

So with one bootstrap per package, finance and a broker adapter become mutually exclusive: whichever
starts the JVM first wins, and the other package's types cannot be loaded. Broker adapters are named
in the ownership model above, so this is not hypothetical.

There is also a symmetry argument. Type discovery is **already** an entry point
(`inventzia.pulse.datum_types`). Without the matching mechanism for jars, the Python side can
discover a provider whose Java classes the JVM cannot load, and the cross-language type-universe
gate reports that as a mismatch without explaining the cause.

#### Artifact construction and dependency versions

- **The wheel must contain the freshly built jar**, together with its
  `META-INF/services` resource, and not a stale staged artifact. Beacon already guards this: its
  build backend fails a release wheel when the runtime jar is missing. Finance needs the equivalent,
  and the jar's build must be part of producing the wheel rather than a step someone remembers.
- **Maven belongs to the build environment only.** The jar is built with Maven where the wheel is
  produced; installation and execution are then tested in an environment with **no Maven at all**.
- **Finance declares compatibility with the beacon release that provides discovery.** Until that
  release exists, finance cannot depend on it, which is why change 1 below gates the rest.
- **Keep data-only use lightweight.** If using finance's types from Python without running a JVM
  should stay dependency-light, the beacon dependency belongs in an optional extra (an execution
  extra) rather than the base requirements.
- **Thin means finance classes and resources only.** Any future Java dependency of finance that
  beacon's jar does not already supply needs its own declared packaging treatment, decided then, not
  absorbed silently into the jar.

#### The three changes

| # | Repository | Change |
|---|---|---|
| 1 | **pulse-beacon** | The entry-point contract above: discovery in `resolve_classpath()`, deterministic order, normalisation and deduplication, attributed validation before the JVM starts, **and the equivalent check on the already-running path**. Additive and backward compatible, but it needs a release. |
| 2 | **pulse-finance** | Freshly built jar staged into `inventzia/pulse/finance/_runtime/`, declared as `package-data`, with a build-time guard against a missing or stale jar, plus the entry point. Mirrors beacon's `_runtime` pattern without the shading. |
| 3 | **pulse-finance** | The acceptance tests below, replacing the release-gated placeholders in `tests/test_installation.py`. |

#### Acceptance tests

The round trip alone does not test the thing the design exists for, which is **composition**. Each
JVM scenario runs in its **own process**, because a JVM cannot be started twice in one interpreter
and reusing one would make the scenarios interfere.

| Scenario | Asserts |
|---|---|
| **Finance alone** | wheel and jar installed into a venv **outside** the checkout, no Maven present; a Python to Java to Python finance-event round trip preserving exact decimal values; application code contains no `extra_classpath` and no path into a `target/` directory |
| **Finance plus a second extension** | both installed, plain `start_jvm()` with no arguments, **both** providers present in the registry and both type sets usable. This is the justification for the whole design, so it is a test and not a remark |
| **Repeated startup** | `start_jvm()` twice in one process is idempotent and still verifies the type universe on reuse |
| **Already-running JVM missing a contribution** | a JVM started without a contributed jar fails with an attributed, actionable restart instruction rather than a `NoClassDefFoundError` later |
| **Missing packaged jar** | a package whose jar is absent fails at startup, naming the distribution |

#### Build-time guard: existence is not enough

pulse-beacon's `_build_backend.py` is the precedent but only the starting point: it checks
`_RUNTIME_JAR.is_file()` and nothing more. A jar that exists but was built from older sources
passes it. Finance's guard must additionally establish that the jar is **current** and **complete**:

- **Freshness by digest, not timestamp.** Record a digest of the build inputs (the Java sources and
  the generated bindings) alongside the staged jar, and fail the wheel build when it does not match.
  File modification times are unreliable here: a checkout, a copy or a clock skew can make a stale
  jar look new, and WSL and Windows disagree about them.
- **Content verification.** Open the staged jar and assert it actually contains the provider class
  and its `META-INF/services/com.inventzia.pulse.data.datum.DatumTypeProvider` entry. A jar built
  from a broken Maven profile can be present, correctly dated, and still contribute nothing, which
  surfaces as a type-universe mismatch rather than a packaging error.
- **Thin.** Finance classes and resources only. Beacon's shaded jar already supplies pulse-data and
  Jackson, and duplicating them invites version shadowing (see above).

#### Acceptance must run against installed wheels

Not editable installs, and not the Maven-output fallback. The scenarios are only evidence if the
artifact under test is the artifact a user would receive:

| Scenario | Requirements beyond "it runs" |
|---|---|
| Finance alone | wheel + jar installed into a clean environment **outside** the checkout, no Maven present, no editable install. Assert the **provider identity** (`providerId`, package version) and **exact decimal values** across the Python to Java to Python round trip, not merely that startup succeeded |
| Finance plus a second extension | the second extension must also arrive **as a wheel containing its own jar**. Plain `start_jvm()`, then assert **both** provider identities are present and both type sets usable |
| Repeated startup | **two `start_jvm()` calls in the same process**, isolated from the other scenarios, asserting idempotence and that verification still runs on reuse |
| Already-running JVM missing a contribution | an attributed, actionable restart instruction naming the package, not a later `NoClassDefFoundError` |
| Missing packaged jar | **both** halves: the wheel build is **rejected** when the jar is absent, and an installed-but-damaged artifact fails at **startup** with an actionable message |

#### Dependency pinning: a commit for development, a published version for release

`0.4.0-SNAPSHOT` is a Maven development coordinate. It is neither an immutable pin nor a Python
dependency floor, so it cannot serve either purpose:

- **Development and CI pin a pushed pulse-beacon commit** that contains discovery, and build its
  wheel for the acceptance tests. As of this writing `9a89256` on `origin/main` qualifies: the
  discovery commit `1f942ae` is reachable from it.
- **Ordinary installation stays lightweight.** `pulse-finance` requires only `pulse-data`; the
  pulse-beacon requirement belongs in an **execution extra**, so using the finance types from Python
  without running an engine pulls in no JVM bridge.
- **Before publishing**, require a **published** pulse-beacon version containing discovery and test
  installation through that declared dependency.

Worth stating plainly, because it is easy to conflate: passing the five scenarios resolves the four
installation placeholders and the release gate, but it does **not** prove that a user can obtain the
required pulse-beacon from the package index. That is a separate, later criterion.

#### Consequences

Change 1 is cross-repository and needs a pulse-beacon release before pulse-finance can depend on it,
so it is wider in scope than the rest of this work. It is the "improve extension packaging once"
case: every future domain package gets the same behaviour for free.

The fallback, if the blast radius must stay inside pulse-finance, is the bootstrap-per-package
option. It would pass the Finance-alone test today and fail the composition test by construction.

### Generator defects found for out-of-repo ownership

Two small pulse-data bugs, both invisible while core was the only consumer. Each is an
"improve extension packaging once" fix that benefits every future domain package:

1. **The licence/provenance header is hardcoded to pulse-data.** `generate_python.py` and
   `generate_java.py` emit *"This file is part of pulse-data."* into every generated file, with no
   `--project-name` to override it. Confirmed against the ext example's committed output:
   `src/inventzia/pulse/ext/schemas/extended_bar.py` and `ExtendedBar.java` both carry it. So
   pulse-finance's generated bindings would misattribute their own ownership, in the files whose
   ownership this decision is about. Needs a project-name/licence-header argument.
2. **`--package-version` has a silently wrong fallback.** Omitted, `generate_python.py` calls
   `_project_version()`, which reads `pyproject.toml` from `_PROJECT_ROOT`: *pulse-data's* root, not
   the target package's. An extension that forgets the flag gets a provider reporting pulse-data's
   version as its own `packageVersion()`, with no warning. It should resolve the version relative to
   `--output-dir`, or refuse to guess. `regen.sh` here passes it explicitly and says why.

Neither blocks the decision; both should be fixed before the first finance release so the generated
artifacts are correctly attributed.

**Mechanics to implement.**

```
pulse-finance
  schemas/                                  <- finance YAML, owned here
    position.yaml
    position_state.yaml
  regen.sh                                  <- modelled on pulse-ext-example/regen.sh
      --provider-id    com.inventzia.pulse.finance
      --provider-class FinanceDatumTypeProvider
      --base-package   com.inventzia.pulse.finance.schemas   (java)
      --base-package   inventzia.pulse.finance.schemas       (python)
  core/java/src/main/resources/META-INF/services/
      com.inventzia.pulse.data.datum.DatumTypeProvider
      → com.inventzia.pulse.finance.schemas.FinanceDatumTypeProvider
```

Every finance `TYPE_ID` is therefore `com.inventzia.pulse.finance.*`, enforced by the registry.

**Consequence for pulse-data: none required.** No finance schema group, no core change. The one
pulse-data item the review surfaced stands on its own merits: enum constraints are unsupported by
the manifest normaliser and the generators, which is an implementation dependency for
`PositionPoint` regardless of which repository owns the schema.

---

## Decisions governing implementation

**This section takes precedence.** Everything below it (the `position` package review and its
inline `comment:` annotations) is retained as the *analysis record*: how the conclusions were
reached, what was measured, and which initial proposals were wrong. Where a statement in the review
conflicts with this section, **this section governs**. An implementer needs to read only this section
plus [Decision: datum ownership](#decision-datum-ownership) to know what is binding.

### Settled

| # | Decision |
|---|---|
| S1 | **pulse-finance owns its finance schemas**, generated with pulse-data's generators under `com.inventzia.pulse.finance.*`. pulse-data owns the machinery. See the datum-ownership decision. |
| S2 | **No binary floating point in any monetary or quantity path**, in any signature, including constructors, clones and accessors. `Money`'s float round-tripping is the measured reason the old types cannot be ported as-is. |
| S3 | **`PosPnLTracker`'s trade-case decomposition is the asset** (open / add / reduce / close / reversal) and is preserved in structure, but validated against independently calculated fixtures, never against legacy output. |
| S4 | **`MonoStrategyPositionManager` is rewritten**, not ported: typed per-topic handlers on an `AbstractActor`, immutable validated configuration, no `System.exit`. |
| S5 | **Package split** `…finance.position` (accounting) and `…finance.portfolio` (analytics), dependency `portfolio → position` only; one `core/java` module to begin with. |
| S6 | **Essential-actor failure propagation is a prerequisite**, not an existing guarantee: `AbstractEngine.dispatchToActor` currently isolates actor exceptions and continues. |

### Open: must be decided before schemas or recovery are committed

These are decisions, not topics. Each may be **deliberately narrow for the first release**; a
universal accounting model is not a precondition for starting. What is a precondition is that each
has a written answer, because each one reaches into both the schemas and the arithmetic kernel.

| # | Decision | Why it blocks |
|---|---|---|
| D1 | **Instrument and quantity model**, including multiplier placement (instrument metadata vs folded into price vs explicit on the event) and quantity representation (integral, fractional, corporate-action remainders). | Changes field types and the kernel's arithmetic; retrofitting means a schema redesign. |
| D2 | **Numerical policy**: calculation precision, division rounding (decimal division can recur, so deferring presentation rounding does not define it), settlement rounding, and persisted scale against `StorageMapping`'s `DECIMAL(38,12)` default. | Determines whether persistence round trips silently alter or outright reject values. |
| D3 | **Reconciliation equations**: gross trading PnL, fees, external cash flows and net PnL as separate quantities over a shared opening baseline. | `pnl1 == pnl2` is not a valid invariant; without the equations there is nothing to assert. |
| D4 | **Checkpoint contents, replay boundary and duplicate-execution handling**: the accounting identity, execution and fee identities for deduplication, the exact input boundary a checkpoint includes, and where replay resumes. Timestamps alone cannot identify the boundary: several fills can share one. | Recovery correctness; also fixes which fields the state schema must carry. |
| D5 | **Strategy contract**: do strategies read manager state synchronously, or react to published position-update events? | Actor priority (which exists) orders *invocation*, not delivery of derived events; the two contracts need different tests. |

### Superseded statements in the review below

Retained for the record, but **not** to be implemented as written:

- *"Position and PositionState become generated pulse-data schemas"* → superseded by S1.
- *"it earns the SQL gateway for free"* → overstated. `StorageMapping` maps scalars and refuses what
  it cannot represent exactly; mappability is per-schema and must be checked.
- *"Add the `pnl1`/`pnl2` assertion"* → superseded by D3.
- *"pulse-beacon has no actor-priority equivalent"* → false; `MultiClientEngine.registerActor(…,
  highPriority)` exists. Corrected inline.
- *"The port should round only at presentation"* → insufficient, superseded by D2.
- *"routed through pulse-beacon's failure channel"* → that channel covers gateways; see S6.
- *"`TYPE_ID` collision is structurally impossible"* → corrected to *detected and rejected at
  startup*.
- *"installation criterion already satisfied"* → withdrawn; the criterion is open pending the
  clean-environment round-trip test.

### Implementation status

Status refreshed on **2026-10-13** against committed baseline `7e83a58`. Local verification is
recorded below; remote CI and installed-package acceptance have not been verified in this review.

| Item | State |
|---|---|
| Package skeleton | **Done.** `core/java/pom.xml` (`pulse-finance-core` 0.1.0-SNAPSHOT, **release 17** matching pulse-data and pulse-beacon), `position`/`portfolio` packages, Python namespace. |
| Provider packaging | **Machinery done and live, installation criterion open.** `regen.sh` (+ `--check`), one authoritative pin in `generator.pin`, Python entry point and Java `META-INF/services` both enabled. Discovery is proven from a classpath; from an installed wheel + JAR it is not. |
| First schema | **Done.** `Position` v1, confirmed field-by-field with the original author. Generated in both languages, `TYPE_ID` namespace-valid, every field exactly `StorageMapping`-representable. |
| Shared accounting fixtures | **Done.** `tests/fixtures/position_accounting.json`: repository level, consumed by both test suites, **13 cases / 25 checkpoints**, including a recurring VWAP and a multiplied contract (CL at 1000). |
| Pure accounting kernel | **Implemented and green; domain-contract confirmation still open.** `PositionTracker`, `TradeOutcome`, `Side`, `PositionSnapshot`: no Beacon, no persistence, no clock. **31 tests pass.** D2's numerical policy is implemented (scale 12 `HALF_EVEN`), and both realized and unrealized PnL are computed from the signed notional rather than a divided VWAP. |
| CI | **Done.** `contracts`, `generator-pin`, `maven`, and a tag-gated `release` job that sets `PULSE_FINANCE_RELEASE=1`. |
| `PositionState` schema | **Not written.** Specified in `docs/spec-position-state.md`, pending confirmation and D2/D4. |
| Remaining events + actor wrapper | **Blocked on D1–D5**, deliberately. Specified in `docs/spec-accounting-decisions.md`. |
| Portfolio layer | **Not started.** |

**Version relationship.** Maven `0.1.0-SNAPSHOT` ↔ Python `0.1.0.devN` during development;
`0.1.0` ↔ `0.1.0` at release. Enforced by `scripts/check_versions.py` in CI, which also refuses a
tag whose Python version still carries `.dev`.

**The release gate is the mechanism that keeps the installation criterion honest.** The acceptance
tests in `tests/test_installation.py` skip during development but **fail** under
`PULSE_FINANCE_RELEASE=1`, which CI's `release` job sets. Skipping is a development convenience,
never a release outcome; an acceptance test that can only skip is decoration.

**Scope of the classpath guard.** It scans *application-facing* trees (`examples/`, `docs/`) only.
Finance's own bootstrap under `src/` may legitimately locate and contribute its bundled JAR; that is
the work the package exists to do. The requirement is that the work never lands on an application
developer.

### Review of 2026-10-13: findings resolved

| # | Finding | Resolution |
|---|---|---|
| 1 | **High**: D2/D3 specified inconsistent PnL. The realized formula multiplied proceeds but not the removed cost; the unrealized equation read a rounded VWAP back into accounting | Realized is now `( −signedClosedQty × price − costRemoved ) × multiplier`: the multiplier scales the **whole** bracket, because the notional is stored unmultiplied. The earlier form turned a realized 60,000 into **1,558,500** at CL's multiplier of 1000. Unrealized is `( signedPosition × mark − signedNotional ) × multiplier`, exact. The remaining notional is updated by **subtracting the allocated cost**, which is what makes the identity exact when `costRemoved` rounds: the same rounded quantity appears negated in `realized` and in `notionalAfter`, and they cancel. Both implemented, not only specified. Note the original did the unrealized form correctly (`PosPnLTracker:207-208`); the draft was a regression from it. |
| 2 | **Medium**: legacy `PositionState` mappings were wrong: `posVwap` was described as already divided, and `cumulPnl → cumulativeNetPnl` as a rename | `posVwap` holds the **signed cost notional** (`getUnitVwap()` divides on read at `:459-472`; it accumulates additively at `:331`), so it maps to `signedNotional` unchanged, and `positionVwap` is a *conversion*. `cumulPnl` is `cumulRealzdPnl + _unRealizedPnl` (`:211`) with **fees not subtracted**, so it is gross: it maps to `cumulativeGrossPnl`, and a derived `cumulativeNetPnl` is new. Both corrected in `docs/spec-position-state.md`, with conversions distinguished from renames in a table. |
| 3 | **Medium**: integral quantities could still exceed `int64` | `requirePublishable` validates the **resulting** position against `Long.MIN_VALUE`/`MAX_VALUE` before any commit, and on opening and restore. Reproduced: `Long.MAX_VALUE` units plus one more is now refused, while a short reaching `Long.MIN_VALUE` is allowed; the range is asymmetric. |
| 4 | **Medium**: D4 was not a complete protocol | Six unspecified items now named explicitly, each with why it breaks recovery: sequence identity and cross-topic ordering, atomic capture of state with boundary, applied **fee** ids, collection persistence (`StorageMapping` refuses arrays, so neither id set can be a column), instrument-spec versioning, and that a boundary does **not** stop a live adapter redelivering an old execution, so pruning needs a broker guarantee, a durable ledger, or an accepted risk. |
| 5 | **Smaller**: `Set.copyOf` does not preserve insertion order, and the release-ref check accepted `release-next` | The false ordering claim is gone; `canonicalExecutionIds()` imposes a sorted order so two trackers that applied the same ids in a different order produce identical checkpoint bytes. The release gate now requires a **full 40-hex commit SHA**: `release-next` and even `v0.4.0` are rejected, because tags and branches can both be moved. |

### Review of 2026-10-11: findings resolved

| # | Finding | Resolution |
|---|---|---|
| 1 | **High**: a failed trade left partially modified state (cash, fees and the execution id moved before the opposing-trade division could throw) | `applyTrade` now computes the entire next state into locals and commits only on success, recording the execution id **last**. Verified against the reported repro: buy 1@100, buy 2@101, sell 1@102 fee 1: cash, position, fees, notional all unchanged, id not claimed, retry re-throws instead of reporting a duplicate, and the same id applies normally once the trade is viable. |
| 2 | **High**: a flat position required a positive mark, breaking `external-cash-flow-no-trades`; and a universal `price > 0` guard reinstated a restriction this very review had rejected | `unrealizedPnl` / `positionValue` return zero for a flat position **without consulting the mark**. Prices and marks are now any finite decimal: only quantities must be positive. A negative-price case (CL at −37.63) is a test. The `price <= 0` guard was my own regression against a defect I had myself documented. |
| 3 | **Medium**: schema and kernel disagreed, with no conversion contract | The kernel now **rejects a non-integral quantity**, so it cannot reach a state `signedPosition: int64` cannot carry; the failure lands at the trade, not at publication. The full two-way mapping (including flat VWAP → `0`, and that a `Position` is a published view, **not** a recovery record) is documented on `schemas/position.yaml` so it travels with the contract. |
| 4 | **Medium**: the "recovery" constructor was not a recovery contract | Split into three named entry points: `startingFlat`, `openingPeriod(cash, pos, vwap, **mark**, mult)` and `restore(PositionSnapshot)`. Opening a period mid-life records `openingUnrealizedPnl` and nets it out. **This also fixed a latent identity break**: with the baseline at the mark and unrealized at the VWAP, the two sides of the reconciliation disagreed by exactly the pre-existing gain. The identity's unrealized term is now `unrealizedPnlThisPeriod`. `restore` preserves totals, baseline and applied execution ids. |
| 5 | **Medium**: release CI resolved pulse-data and pulse-beacon at finance's own tag | `PULSE_DATA_REF` / `PULSE_BEACON_REF` are pinned independently; the `generator-pin` job checks out pulse-data at the revision `generator.pin` names; and the release job refuses to publish while either ref still tracks a branch. |

### Verification

**Local verification on 2026-10-13:**

- Java: `mvn -o -B -ntp -f core/java/pom.xml verify` using the `pulse` conda environment:
  **31 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**.
- Python: `python -m pytest -q -p no:cacheprovider tests`: **19 passed, 4 skipped**. The four
  installation tests remain placeholders, not evidence of installed-package acceptance.
- `python scripts/check_versions.py`: passed, development versions agree.
- `bash regen.sh --check`: passed, committed bindings match the pinned generators.

- **Accounting**: 13 dynamic fixture cases + 13 unit tests in `PositionTrackerFixtureTest`,
  including one regression test per review finding.
- **SPI discovery**: `FinanceDatumTypeProviderTest` (5 tests) proves the registry resolves
  `Position` by `TYPE_ID` **from the classpath alone**: nothing calls `DatumTypeRegistry.of(...)`;
  discovery goes through `ServiceLoader` reading `META-INF/services`, as it will from the published
  JAR. It also pins `packageVersion() == "0.1.0.dev0"`, which is what proves `regen.sh`'s explicit
  `--package-version` defeated the generator's silent fallback to pulse-data's own version.
- **Shared fixtures reach the Java classpath** via `<testResources>`, confirmed in the build log:
  *"Copying 1 resource from ../../tests/fixtures to target/test-classes/fixtures"*.

**Previously recorded mutation checks** (not repeated in the 2026-10-13 status refresh):

| Mutation | Result |
|---|---|
| Comment out the `META-INF/services` entry (i.e. forget to register the provider) | 2 errors, BUILD FAILURE |
| Point `Position.TYPE_ID` outside the provider namespace (`com.example.rogue.Position`) | 1 failure + 2 errors, with the registry's own message: *"provider 'com.inventzia.pulse.finance' type 'com.example.rogue.Position' is not under its namespace"* |

That second one also turns an earlier *claim* into an observation: the namespace rule is enforced at
startup and names the offender, as the datum-ownership decision asserted.

**What remains unverified** is the installation criterion proper, the Java half of discovery is now
proven from a classpath, but not from an **installed wheel + JAR in a clean environment outside the
checkout**, which is what the criterion asks for and what the release gate still fails on. The
Python shadow transliteration remains useful for reasoning about the algorithm, but it is no longer
the primary evidence; the Java suite is.

### Repository inventory

| Path | Lines | |
|---|---|---|
| `core/java/.../position/PositionTracker.java` | 554 | the accounting kernel |
| `core/java/.../position/{TradeOutcome,PositionSnapshot,Side}.java` | 189 | case decomposition, restore record, side |
| `core/java/.../schemas/{Position,FinanceDatumTypeProvider}.java` | 125 | **generated** |
| `core/java/.../position/PositionTrackerFixtureTest.java` | 457 | 13 fixture cases + 13 unit tests |
| `core/java/.../schemas/FinanceDatumTypeProviderTest.java` | 97 | SPI discovery, mutation-tested |
| `schemas/position.yaml` | 113 | owned here; carries its own conversion contract |
| `src/inventzia/pulse/finance/schemas/*.py` | 93 | **generated** |
| `tests/fixtures/position_accounting.json` | 666 | 13 cases / 25 checkpoints, cross-language |
| `tests/test_{fixtures_consistency,installation}.py` | 329 | identity check + release-gated criterion |
| `regen.sh`, `generator.pin`, `scripts/check_versions.py` | 252 | build metadata, one authoritative pin |
| `.github/workflows/ci.yml` | 189 | 4 jobs, release gated |

### Open

1. **Installation acceptance**: discovery works from a classpath, but the installed wheel + JAR
   experience is not implemented or verified in a clean environment outside the checkout. The four
   acceptance tests skip during development and are configured to fail in release mode. A design is
   proposed in [Proposed design for the installation criterion](#proposed-design-for-the-installation-criterion);
   it needs a decision, because one of its three changes lands in pulse-beacon.
2. **D1–D5 confirmation**: the proposals in `docs/spec-accounting-decisions.md` need explicit
   decisions. Implemented numerical behaviour is evidence for those decisions, not confirmation of
   the entire contract. D2 still needs supported input scales/magnitudes, settlement semantics and
   persistence round-trip verification; D4 still has six protocol questions explicitly open.
3. **PositionState**: the published-view schema is not written. Its spec needs F1–F4 confirmation
   and settled D2/D4 requirements. PositionCheckpoint remains a separate recovery contract; the
   existing in-memory PositionSnapshot does not supply a complete durable replay protocol.
4. **Later integration**: actor wrapper, essential-actor failure propagation in Beacon, portfolio
   layer and the documented generator/packaging improvements. Enum support is optional for the
   initial string-code representation, not a prerequisite for PositionState.

**Repository baseline:** implementation, specs and build scaffolding are now committed. The working
tree was clean at the start of this status refresh; the earlier "nothing committed" note is obsolete.

### Next

Proceed in this order:

1. **Close the installation criterion.** Build and install the distributions in a fresh environment
   outside the checkout. Prove Python and Java provider discovery and an exact finance-event round
   trip without Maven, source-tree paths or application-level classpath/registry manipulation.
   Replace the four placeholders with real tests that pass in release mode. This work can proceed
   independently of D1–D5.
2. **Confirm D1–D5.** Record the accepted scope and policies in the accounting spec, distinguishing
   implemented decisions from deferred capabilities. In particular, finish D2's persistence and
   settlement contract and decide D4's sequence scope/order, atomic checkpoint boundary, fee
   deduplication, collection storage, instrument metadata versioning and ID-retention guarantees.
   Confirm D5 before designing the actor wrapper.
3. **Implement PositionState once D2/D4 are settled and F1–F4 are confirmed.** Write its YAML,
   generate both bindings, define the kernel-to-view conversion and test decimal/SQL round trips.
   Keep it a published view; do not use it as a recovery record or treat its completion as completion
   of PositionCheckpoint persistence.

The corrected notional-based arithmetic, scale-12 HALF_EVEN division, atomic trade application and
int64 range checks are already implemented and tested. The next numerical work is confirmation and
persistence verification, not replacing the former exact-or-throw implementation again.

---

## Package `position`

**Source:** `Old/j_algotrading_infrastructure/Core/JavaCepFinance/src/com/algotrading/cep/finance/position/`
plus `.../finance/data/position/` (the datum types) and `JavaCoreLib/.../money/Money.java`.

**Reviewed:** 2026-10-11. All nine classes read in full, plus `Money`.

### Inventory

| Class | Lines | Role |
|---|---|---|
| `data/position/Position` | 109 | wire datum: signed size + VWAP |
| `data/position/PositionState` | 361 | wire datum: full position + PnL snapshot, `Money`-typed |
| `data/position/PositionPoint` | | enum: why a point exists (`ID_TRD`, `SOD`, `EOD`, `CASHFLOW`, `CACT`, …) |
| `PosPnLTracker` | 680 | the engine, one instrument's position, VWAP, realized/unrealized PnL |
| `MonoStrategyPositionManager` | 1413 | event-driven client: subscribes fills/quotes/bars, owns trackers, publishes states |
| `PosPnLPortfolio` | 494 | aggregation across names, hedge factor, ANV, beta-adjusted delta |
| `MonoStrategyPnLTracker` | 300 | portfolio-level listener + EOD mark/write |
| `PosManagerTopics` | 81 | topic naming convention |

### Strengths, to keep by design, not by inertia

1. **The State / Instantaneous split.** `PosPnLTracker` separates recoverable state from
   `_`-prefixed reporting fields that are derived and need no recovery. That distinction is exactly
   what pulse's recording/recovery model wants, and it is rare to find it drawn correctly in advance.

2. **Dual PnL as a reconciliation design.** `pnl1` accumulates trade-by-trade PnL; `pnl2` derives
   from the cash balance including fees. Two independent routes to the same number is the right way
   to catch a position-accounting bug.

   comment: Keep both calculation paths, but define their reconciliation equation before comparing
   them. In the old implementation, processFeeSum reduces cash without reducing realised trading
   PnL; external cash flows also change equity. An unchanged-price trade followed by a fee of 1 can
   therefore leave gross trading PnL at 0 and equity change at -1. Define gross trading PnL, fees,
   external cash flows and net PnL separately, with a shared opening baseline; direct pnl1 == pnl2
   is not a valid general invariant.

3. **The trade case analysis is genuinely complete.** `processTrade` handles open, add, reduce, close
   and, the case most implementations get wrong: **overshoot**: crossing through zero is split into
   a close of the old position plus a fresh open, each priced correctly. This is the single most
   valuable thing in the package.

   comment: The case decomposition is worth preserving, but completeness of the branches is not
   proof of correctness. Validate open/add/reduce/close/reversal in both directions against
   independently calculated fixtures; do not treat legacy outputs as authoritative where precision
   or accounting defects are already known.

4. **`posVwap` stored as signed notional**, not as a price. Accumulation becomes additive and the
   division is deferred to `getUnitVwap()`. Correct choice.

5. **Corporate actions are a first-class event** (`DTM_INTID_CACT`, `PositionPoint.CACT`), not an
   afterthought. Likewise the fill's receive-time vs exchange-time distinction, and carrying
   `Currency` + decimal scale explicitly rather than assuming USD.

6. **The portfolio layer has real content**: hedge factor, ANV, notional delta as a percentage of
   ANV, beta-adjusted delta. That is a risk surface, not a sum.

### Weaknesses

#### Fatal, the money model cannot be ported as-is

`Money` wraps `BigDecimal`, and its arithmetic (`plus` / `minus` / `multiply` / `divide`) is clean,
with currency checks. But every **entry and exit** routes through `float`: `create(float …)`,
`set(float …)`, `cloneMe()` via `BigDecimal.valueOf(getFloatValue())`, and `getRoundedValue()` via
`new BigDecimal(Float.toString(…))`. Measured:

```
exact            after one cloneMe()      getRoundedValue(2)
123456789.12  →  123456792            →  123456792.00    $2.88 lost
1234.5678     →  1234.5677490234375   →  1234.57
99999999.99   →  1.0E+8               →  100000000.00    $0.01 lost
41.07         →  41.06999969482422    →  41.07
```

All five realistic values lose precision through `float`; none through `double`.
`PosPnLTracker.cloneMe()` clones **17 `Money` fields**, so taking a snapshot corrupts every monetary
figure in it, and snapshots are how state is published and recovered.

At portfolio scale it is worse, because `PosPnLPortfolio.aggregate` computes notionals in bare
`float`:

```
pos=  250,000 × 412.33 = 103,082,500.00  →  float 103,082,496.00   (err −$4.00, ULP ≈ $16)
pos=3,000,000 ×  68.47 = 205,410,000.00  →  ULP ≈ $32
```

A $100M book's notional is quantised to roughly $16.

Separately: **`pnl1` and `pnl2` are never compared anywhere in the tree**: I grepped it; they are
only written side by side into CSV. But the missing piece is *not* a single equality assertion, as an
earlier draft of this review claimed. `processFeeSum` reduces cash without reducing realised trading
PnL, and external cash flows change equity independently, so an unchanged-price trade followed by a
fee of 1 legitimately leaves gross trading PnL at 0 and equity change at −1. `pnl1 == pnl2` is not a
valid general invariant. What is missing is the *defined* reconciliation equation: gross trading
PnL, fees, external cash flows and net PnL as separate quantities over a shared opening baseline:
and only then an assertion over it. See recommendation 5.

#### Serious: correctness rules that were disabled rather than fixed

- `validateTime`'s `throw` is **commented out**. Out-of-order events are silently accepted, which
  corrupts VWAP and realized PnL irrecoverably.
- An invalid price causes a **silent `return`** (that `throw` is commented out too), leaving PnL
  stale rather than failing.
- `price <= 0` is treated as invalid. That is **wrong for crude oil**: and CL is the instrument in
  our own sample data.
- `NaN` is used as the sentinel for an absent bid/ask, with commented-out NaN debug prints left in
  place as evidence of past bugs.
- `Position.adjustValues(float, float)` is **empty**: a split would not adjust the position.
- The corporate-action path does `new BigDecimal(multiplier)` on a `double`
  (`PosPnLTracker:667`). That is the binary-float-into-`BigDecimal` trap: a 1-for-10 reverse split
  factor of `0.1` becomes exactly
  `0.1000000000000000055511151231257827021181583404541015625`, and that is then multiplied into the
  mark price. Use `BigDecimal.valueOf` or, better, carry the split ratio as an exact numerator and
  denominator.
- The same block carries `// TODO a reverse split (merging of odd shares wont work)` and divides the
  signed position with a `(long)` cast (`PosPnLTracker:668`), so a reverse split silently truncates
  the position rather than handling the odd-lot remainder.
- `PosPnLTracker`'s constructor ends `e.printStackTrace(); System.exit(-1);`: a library class
  killing the JVM, and untestable inside an engine.

#### Structural: does not fit the pulse model

- `MonoStrategyPositionManager.onTopicUpdate` is a **single ~350-line `switch` on
  `getDatumIntId()`** across 11 datum types. Pulse's `onEvent(Topic<P>, P)` is typed per topic, so
  this decomposes into one small handler per subscription, a straight improvement, not a cost.
- **File-based state recovery** (`posManagerStateFromFiles`, `setOverrideLoadStateToCustomFile`, EOD
  state files). pulse-beacon now has a SQL sink/source and run recording. Re-homing recovery onto
  those is the largest architectural win available in this package.

  comment: SQL and run recording supply persistence, not a complete checkpoint/recovery protocol.
  Define the accounting identity (account/book/strategy/instrument as applicable), execution and fee
  identities for deduplication, the precise input boundary included in each checkpoint, and where
  replay resumes. Several fills may share a timestamp, so timestamps alone cannot identify that
  boundary. Specify corrections and reconciliation events, and prove that checkpoint plus replay
  produces the same state as uninterrupted processing before replacing the state-file mechanism.

- **~15 boolean setters** (`setAcceptIncomingPositionStatesAsGoodOnes`, `setNeglectForcedFills`,
  `setMarkOnNewBars`, …) with no validation and order dependence. These should be one immutable,
  validated config record, recorded into the run so a replay is reproducible.
- `run()` is **empty** despite extending `A_RunnableProducerConsumer`: a thread that does nothing.
  The concept is dead; drop it.
- `DatumUpdatePriority.HIGH` encodes "the position manager must see a fill before any strategy reads
  positions". **This maps directly onto pulse-beacon as it stands**:
  `MultiClientEngine.registerActor(actor, …, boolean highPriority)` dispatches high-priority actors
  before normal-priority ones (`MultiClientEngine:207`/`:213`), preserving registration order within
  each group. (An earlier draft of this review claimed beacon had no equivalent; that was wrong.)
  What priority does **not** give you is delivery order of a *derived* event: "the manager is updated
  before another fill subscriber runs" and "a published position update reaches that subscriber
  before it runs" are different guarantees, and only the first follows from priority. See open
  question 1.

- Hand-rolled CSV via `split(",")`; `getFullClassName()` returns `null`;
  `internalPopulateFieldNameToJFieldTypeMap()` is empty. The datum types are half-finished
  hand-written `A_Datum` subclasses.

### Recommendation

**Port the logic closely; replace the data and money layers entirely.**

1. **`Position` and `PositionState` become generated schemas owned by pulse-finance**:
   `BigDecimal`-backed, exactly as `CdfBar` already is, but under
   `com.inventzia.pulse.finance.schemas` with a finance `DatumTypeProvider`
   ([decision](#decision-datum-ownership)). That deletes the hand-rolled CSV, the `null`
   `getFullClassName()`, the empty field maps and the `Money`-vs-`double` inconsistency in one move.
   It makes them SQL-persistable *subject to every field being mappable by `StorageMapping`*, to be
   verified per schema, not assumed.
2. **Drop `Money`; use `BigDecimal` + `Currency` directly**, or rewrite `Money` with no `float` in
   any signature. The arithmetic is fine, the conversions are the whole problem. This is
   non-negotiable: there is no port of `cloneMe()` that preserves money.

   comment: Preserve currency compatibility checks while eliminating binary floating-point
   conversions. An immutable decimal money value is a reasonable replacement; bare BigDecimal and
   Currency fields must still enforce the same checks at every arithmetic boundary.

3. **`PosPnLTracker`'s trade analysis ports close to verbatim**: it is the asset. Change only:
   all-`BigDecimal`, no intermediate rounding to scale before a division, and re-enable the time and
   price validation as real failures rather than `System.exit`.

   **Prerequisite, not an existing guarantee.** The failure channel built for the SQL gateway covers
   *gateways*; actors are isolated instead. `AbstractEngine.dispatchToActor` catches `Exception`,
   logs `"actor threw … isolating and continuing"`, and carries on, so a failed accounting update
   would today leave stale state that subsequent trading decisions consume. An
   essential/observational actor policy is therefore a precondition for this item, with a test that
   a failed accounting update fails the run before any decision can read stale state.

   Equally, all-`BigDecimal` is **not** sufficient on its own: preserve the trade-case structure only
   after validating it against independently calculated fixtures, and settle the numerical policy in
   open question 2 rather than assuming the type change carries it.

4. **`MonoStrategyPositionManager` is a rewrite, not a port.** The 350-line switch becomes typed
   handlers on an `AbstractActor`; file recovery becomes SQL + run recording; the boolean flags
   become a config record. Its *behaviour* is the spec; its structure is not worth carrying.
5. **Add the `pnl1`/`pnl2` assertion** the original built toward, as a test, and as a runtime
   reconciliation check.

   comment: Implement the explicitly defined gross/net/cash-flow reconciliation described above,
   rather than a direct equality assertion. Include fees, deposits/withdrawals and nonzero opening
   balances in both unit tests and the runtime reconciliation policy.

6. **`PosManagerTopics` is superseded.** pulse-beacon topics are typed and constructed by the owner;
   a prefix-string convention class is not needed.

### Proposed placement in pulse-finance

The datum types stay in this repo, generated into their own namespace, see
[Decision: datum ownership](#decision-datum-ownership):

```
pulse-finance
  schemas/position.yaml, position_state.yaml        <- owned here
  →  com.inventzia.pulse.finance.schemas.Position
  →  com.inventzia.pulse.finance.schemas.PositionState
  →  inventzia.pulse.finance.schemas.*
     TYPE_ID: com.inventzia.pulse.finance.*         (enforced by DatumTypeRegistry)
```

`PositionPoint` should become an enum field in those schemas rather than a separate Java type:
**blocked**: enum constraints are not currently supported by pulse-data's manifest normaliser, and
its generators do not produce the proposed enum contract. Either schedule enum support across
generation, manifests and codecs (verifying SQL compatibility), or ship initially with string codes
plus explicit application-level validation. This is an implementation dependency on pulse-data and
the only one this package has.

The logic lands in two sibling subpackages, matching the two concerns the README already names
("position accounting" and "portfolio analytics"):

```
com.inventzia.pulse.finance.position           <- position accounting
    PositionTracker          (from PosPnLTracker; close port, all BigDecimal)
    TradeOutcome             (open / add / reduce / close / overshoot, made explicit)
    PositionAccountingConfig (immutable, validated; replaces the ~15 boolean setters)
    PositionManagerActor     (rewrite of MonoStrategyPositionManager, extends AbstractActor)

com.inventzia.pulse.finance.portfolio          <- portfolio analytics
    PortfolioBook            (from PosPnLPortfolio; aggregation, hedge factor, ANV, deltas)
    PortfolioPnLActor        (from MonoStrategyPnLTracker)
```

Reasoning for the split rather than one `position` package: the old code already separates
per-instrument accounting from cross-name aggregation, and the portfolio layer is the one that will
grow (risk, exposure, attribution). Keeping them apart now avoids a later move. `position` depends
on `portfolio` in neither direction, the portfolio layer reads trackers, so the dependency is
`portfolio → position`, one way.

Build layout: a single `core/java` module (`pulse-finance-core`) mirroring `pulse-beacon-core`, so
there is one place for the Maven coordinates and the shared test fixtures until a second module is
actually justified.

comment: The position/portfolio separation, immutable configuration and single Java module are
sound starting choices. Suggested implementation order: accounting contracts and independent
fixtures → pure PositionTracker → generated finance events → essential actor integration →
checkpoint/replay → portfolio aggregation. Acceptance tests should cover long/short reversals,
fees, cash flows, duplicate executions, nonzero opening state and recovery equivalence before
expanding scope.

### Open questions for this package

1. **Actor ordering.** Does pulse-beacon need an actor-priority guarantee so the position manager
   observes a fill before anything that reads positions, or should position reads be routed *through*
   the manager instead? The second removes the need for priority but changes the strategy-facing API.

   comment: Actor priority already exists, as noted above. The remaining decision is the strategy
   contract: synchronous reads of updated manager state, or decisions triggered by published
   position updates. Test the chosen contract explicitly, including multiple events at the same
   timestamp; do not equate actor invocation order with delivery order of derived events.

2. **Decimal scale.** The old code rounds to `decimalScale` (default 2) at every step, including
   notionals and VWAP numerators, before dividing. The port should round only at presentation: worth
   confirming no downstream consumer depends on the intermediate rounding.

   comment: Replace this proposed rule with an explicit numerical policy. Decimal division can have
   a recurring result, so calculation precision and division rounding must be defined even when
   presentation rounding is deferred. Specify settlement rounding and SQL representation as well:
   StorageMapping currently defaults to DECIMAL(38,12). Test persistence round trips against the
   chosen calculation scale so values are neither silently changed nor unexpectedly rejected.

3. **Position-state recovery semantics.** `setAcceptIncomingPositionStatesAsGoodOnes` and
   `setAcceptIncomingPositionAsRefernceAndReplaceIfMistaken` encode a trust policy for externally
   supplied positions. The replacement config record needs those cases named explicitly rather than
   as two booleans.

   comment: Name the trust policies, and specify whether an external position is a recovery
   checkpoint, a reference observation or an authorised adjustment. Make adjustments explicit events
   rather than unexplained state replacement, with recovery and duplicate-handling rules consistent
   with the checkpoint protocol described above.

4. **Instrument multiplier.** Nothing in the reviewed classes applies a contract multiplier; the
   old system appears to fold it into price upstream. For CL (1,000 bbl) this must be settled before
   PnL is trusted.

   comment: Resolve multiplier placement, quantity representation (including fractional quantities
   or corporate-action remainders), currency conversion and the initially supported instrument
   model before fixing the schemas. These affect the accounting kernel and portfolio aggregation;
   leaving them until after implementation risks a schema and arithmetic redesign.
