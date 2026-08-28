# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Vigilant is a guardrails system for AI agent platforms. The product specs live in `spec/` (written in Russian) and are the source of truth for scope decisions:

- `spec/MVP_NON_FUNCTIONAL_REQUIREMENTS.md` - stack, SLOs, and the definition of the first increment (v0 - Bypass Proxy)
- `spec/MVP_FUNCTIONS.md`, `spec/STAGE_1_FUNCTIONS.md`, `spec/OUT_OF_SCOPE_FUNCTIONS.md` - future scope and explicit non-goals
- `spec/WORK_ITEMS.md` - work-item conventions and registry
- `spec/epics/*` - large outcomes decomposed into linked issues
- `spec/issues/**` - standalone and epic-scoped issues I can ask you to implement

The project has moved beyond bypass-only v0 and completed its first production
guardrail increment: bounded request-side PII inspection for OpenAI Chat
Completions in shadow mode. The v0 proxy remains the transport foundation, but
the production request path now retains a bounded complete request source,
derives a lossless normalized inspection view, evaluates the immutable startup
policy snapshot with `fast-pii`, emits a safe aggregate decision, and replays
the original body upstream byte-for-byte. Response bodies remain streaming.

The current startup contract enforces shadow-only `ALLOW` reactions without
transformations. Do not add enforcement (`BLOCK`, `MASK`, `REMOVE`), response
inspection, new protocol routes, external identity lookup or authentication,
disk spill, plugin workers, or other runtime behavior unless a dedicated
implementation-ready issue explicitly requires it.

## Commands

```bash
./gradlew build                 # compile + tests
./gradlew test                  # tests only
./gradlew test --tests "io.vigilant.gateway.proxy.BypassProxyServiceTest"  # single test class
./gradlew run                   # run MainKt directly; same config requirements as the distribution
./gradlew installDist           # build distributable into build/install/vigilant/
./gradlew ociArtifact           # reproducible versioned tar consumed by the Dockerfile

# Run env-only (VIGILANT_UPSTREAM_URL is required; VIGILANT_PORT optional, default 8080)
VIGILANT_UPSTREAM_URL=http://127.0.0.1:18081 VIGILANT_PORT=18080 ./build/install/vigilant/bin/vigilant

# Run with a HOCON config file (see vigilant.conf.example); env vars still override file values
VIGILANT_CONFIG=./vigilant.conf.example ./build/install/vigilant/bin/vigilant

# Quality tools (beyond SonarQube + JaCoCo)
./gradlew detekt                 # Kotlin static analysis, wired into build/check; project tweaks in config/detekt/detekt.yml
./gradlew pitest                 # mutation testing against io.vigilant.* classes; on-demand only (analyze-pitest skill), not part of regular checks
./gradlew dependencyCheckAnalyze # OWASP CVE scan of the dependency tree
./gradlew validateWorkItems       # work-item graph consistency; also wired into check
./gradlew piiQualityReport        # canonical synthetic PII quality JSON/Markdown report
./gradlew verifyAll              # full local verification: build + dependency check
./gradlew installGitHooks        # one-time after clone: installs pre-push hook from config/git/hooks/
```

Copy `politics.conf.example` to `politics.conf` before local application runs.
The policy file is mandatory; `VIGILANT_POLITICS_CONFIG` overrides the default
`./politics.conf` path.

Invalid or missing config prints a message to stderr and exits with code 2.

## Agent papercuts

`.papercuts.jsonl` is the tracked, append-only journal of recurring friction in
this repository. It keeps both problem reports and resolution notes so future
agents can reuse a verified approach instead of rediscovering it.

Before diagnosing unexpected build, test, tooling, configuration, or
documentation friction, search both open and resolved entries:

```bash
./scripts/papercuts --pretty list --status all
```

When new actionable friction appears, record it before continuing the primary
task:

```bash
./scripts/papercuts add \
  "<what happened; context; what would have prevented it>" \
  --tag <area> --severity <minor|major|blocker>
```

When the problem is solved, preserve the reusable approach in the resolution:

```bash
./scripts/papercuts resolve <id> \
  --note "<root cause; durable fix or workaround; verification command>"
```

- Keep working after filing unless the papercut is a real blocker.
- Use papercuts for repository, tooling, and documentation friction. Product
  defects and planned work still belong in `spec/issues/`.
- Prefer fixing the underlying script, configuration, or documentation. A
  resolution note records the approach; it does not replace the durable fix.
- Reuse a resolved approach only after confirming its context still applies.
- Never record secrets, request or response bodies, authentication headers, raw
  environment dumps, or unredacted stderr that may contain them.
- Run `./scripts/papercuts doctor` after manual conflict resolution or when the
  journal looks malformed.

## Tech debt registry: sonar_problems.md

`sonar_problems.md` (project root) is the registry of technical debt found by static analysis (SonarQube via the `analyze-via-sonar` skill). For each problem it records: what it is, where it lives (file:line), why it is a problem, and the recommended fix. Treat every entry as debt to be repaid as soon as possible: when a task touches an area listed there, proactively fix the corresponding findings in the same change rather than leaving them for later. After fixing, remove the resolved entries from the file (or regenerate it by re-running the analysis).

## Architecture

Stack: Kotlin 2.4.10, JVM toolchain 25, Armeria (HTTP server + client), Metro DI (compile-time, via Kotlin compiler plugin), Gradle Kotlin DSL. No Spring.

Request path:

`Client -> TrafficAdmissionService -> MetricsService -> TracingService -> PiiShadowProxyService -> BypassProxyService -> WebClient -> Upstream`.

The maintained architectural overview is `docs/architecture.md`; use it with
`docs/runtime-contract.md`, `docs/configuration.md`, and
`docs/observability.md` when changing runtime behavior.

Key gateway and policy files under `src/main/kotlin/io/vigilant/`:

- `proxy/PiiShadowProxyService.kt` - thin production HTTP inspection boundary. It validates the supported Chat Completions descriptor, extracts configured identity before body demand, ingests the body into a quota-controlled source, schedules complete-source workflow execution, maps typed rejects to stable responses, and performs the one-shot handoff to `BypassProxyService` after consumed identity headers are stripped.
- `proxy/ShadowInspectionWorkflow.kt` / `proxy/ReplayReadyRequest.kt` - gateway-specific complete-source application workflow and one-shot transport ownership boundary. The workflow parses one normalized view, assembles and stores context, evaluates each independent text fragment, emits one safe aggregate audit, and returns only typed `Forward` or expected `Reject`. Unexpected failures and cancellation escape to the HTTP adapter. `ReplayReadyRequest` retains owner responsibility until transport accepts the exact replay publisher, then terminal replay owns cleanup.
- `identity/IdentityExtractor.kt` / `context/PolicyContextHandoff.kt` - mutually exclusive anonymous, trusted-header and Basic identity extraction plus the typed Armeria request-scoped bridge used to derive a response context by changing only the phase. Trusted headers use only the immediate socket peer CIDR; credentials and raw identity values are never retained in policy context or logs.
- `source/BoundedRequestSource.kt` - process-wide owner/byte/segment quota plus one-request lifecycle. It receives the request with backpressure, exposes one sequential parser view and one demand-driven exact replay lease, and releases every reservation on completion or cancellation.
- `protocol/openai/ChatCompletionsRequestParser.kt` - schema-tolerant parser for model-visible Chat Completions content. It preserves unknown fields by never rebuilding the original body, records recognized non-text inspection gaps, and fails closed for malformed or ambiguous content-bearing shapes.
- `policy/engine/PolicyEngine.kt` / `policy/selection/PolicySelector.kt` / `policy/execution/DetectorExecutionCoordinator.kt` - deterministic policy matching, simultaneous overrides, deduplicated detector execution, per-policy deadlines, fail-fast blocking semantics in the domain layer, and complete decision explanations. Runtime startup currently restricts all configured reactions to shadow-only `ALLOW`.
- `detectors/pii/fast/FastPiiDetector.kt` / `windowing/WindowedFastPiiExecutor.kt` - built-in deterministic detector and UTF-8-safe window execution for large logical fragments. CPU work runs on the bounded pool owned by `InspectionResources`.
- `proxy/BypassProxyService.kt` - transport stage after inspection. Rewrites request headers (upstream scheme/authority/path and hop-by-hop stripping), strips hop-by-hop response headers, preserves exact request replay and streaming responses, and maps upstream failures to stable proxy errors.
- `config/AppConfig.kt` - config loading via Hoplite: optional HOCON file (`VIGILANT_CONFIG`, else `./vigilant.conf`, else `/etc/vigilant/vigilant.conf`) with `VIGILANT_*` env overrides on top (env > file > defaults), then post-decode validation (`loadAppConfig`, `validatedUpstreamUri`, `validatedPort`). Unit-tested directly without a running server.
- `policy/config/PolicyConfiguration.kt` - resolves mandatory `politics.conf` (`VIGILANT_POLITICS_CONFIG`, else `./politics.conf`), reads it once, and composes the strict parser with semantic validation into an immutable startup snapshot.
- `policy/provider/PolicyProvider.kt` - suspend provider contract and `DummyPolicyProvider`, which retains one complete immutable startup snapshot without I/O, filtering, or hot reload.
- `policy/selection/PolicySelector.kt` - pure context matcher and simultaneous override resolver; returns immutable policy lists sorted by policy ID without provider I/O or detector execution.
- `AppComponent.kt` - Metro `@DependencyGraph(AppScope::class)`. Providers live in the companion object; the graph also assembles the Armeria `Server`. New injectable classes use `dev.zacsweers.metro.Inject` / `@SingleIn(AppScope::class)` (not `javax.inject` - Metro does not ship it, and `dev.zacsweers.metro.Singleton` does not exist).
- `health/LivenessService.kt` / `health/ReadinessService.kt` - gateway-owned probes registered before the catch-all and never proxied upstream: `/healthz` answers `200` while the server accepts connections; `/readyz` answers `200` when ready and `503` once the shutdown hook has called `ReadinessService.markNotReady()`, before the server actually closes (enabled by a graceful shutdown timeout on the `Server`).
- `Main.kt` - builds the graph, registers a shutdown hook that marks readiness as draining and then stops the server gracefully, blocks until the server closes.

Tests spin up real Armeria servers on ephemeral ports (`http(0)`) and proxy through them - keep this E2E style for proxy behavior changes.

## Mandatory test-driven development

Load and follow the installed `tdd` skill for every task that adds or changes production code, even when the user did not invoke it explicitly. The project-specific rules below define when a seam is already pre-agreed and override conflicting confirmation wording in the skill; the rest of the skill remains authoritative.

For every behavior change or bug fix, work in vertical slices:

1. Identify one observable behavior and the seam through which it will be tested, and state the proposed seam in the conversation before writing the test. A seam explicitly documented in a `Ready for implementation` issue or in this guide is already pre-agreed, so proceed without a live confirmation round-trip. Obtain explicit user confirmation only when the seam is undocumented, introduces or materially changes an architectural boundary, or conflicts with the normative specification.
2. Add one focused behavior test before changing production code. For bugs, start with a regression test that reproduces the problem as an end user would encounter it, using an E2E test whenever practical.
3. Run the narrowest relevant Gradle test and observe it fail for the expected behavioral reason. A compilation error, broken fixture, or unrelated failure does not count as RED.
4. Write only the minimum production code needed to satisfy that test.
5. Run the same test again and observe it pass before starting another slice.
6. Finish the RED -> GREEN cycle before refactoring. Refactoring is a separate review-stage activity, not part of the implementation loop. Keep the affected tests GREEN throughout that stage and run them after each refactoring step.
7. Repeat with the next behavior. Do not write a batch of tests followed by a batch of implementation.

When a focused test cannot compile solely because its new public contract does not yet exist, add the smallest behaviorless contract or no-op scaffold after writing the test. The compilation failure still does not count as RED: rerun the test and observe a behavioral failure before implementing the capability. If a later acceptance example already passes because the preceding minimal implementation naturally covers it, keep the test and do not manufacture a production change merely to force another RED.

Proxy behavior must continue to be tested E2E through real Armeria servers. Focused unit tests are appropriate for pure deterministic logic or edge cases that are impractical to exercise through an E2E seam, but they do not replace required E2E coverage of proxy behavior.

Armeria `RequestLog.whenComplete()` and similar completion callbacks may publish observations after the client has received or aggregated the response. Client completion is not a synchronization barrier for those observations. Assert them with deadline-bounded polling through `GatewayTestFixture.awaitUntil`, using the shortest practical timeout and a failure message that reports the last observed state.

Pure refactoring is the exception to the RED-first requirement because it must not change observable behavior. Before refactoring, run the narrowest relevant existing tests and confirm they are GREEN; add characterization coverage first if the behavior is not adequately protected. Keep the tests GREEN throughout the refactoring.

Changes limited to documentation, comments, formatting, build metadata, or test infrastructure are exempt when they do not add or change production behavior.

After the final slice, run the narrowest affected test suite once, then run `./gradlew build` before declaring implementation complete. Do not run an overlapping broad regression subset immediately before the full build unless diagnosing an earlier failure, the affected scope cannot be selected reliably, or the full build will not be run. Report the command and expected failure that established RED, plus the commands that established local and final GREEN.

## Pre-verification defect prevention

The final verification pipeline is a backstop, not the first time the change
should be compared with its contract. Apply the following rules while coding.
They supplement the TDD loop above and do not replace its RED -> GREEN order.

### Build criterion-level evidence

- Before the first slice, read the implementation-ready issue, its parent epic,
  linked normative specs, dependencies, and explicit non-goals. Keep a working
  matrix that maps every criterion to the production behavior and exact test or
  dynamic evidence that will prove it. Update the matrix as the implementation
  changes.
- Treat `all`, `each`, `every`, `exact`, `complete`, `exhaustive`, and
  `deterministic` as quantifiers. Cover every named state, position, ordering,
  content class, boundary, and lifecycle outcome, preferably with a
  table-driven test when the issue asks for a matrix. A representative happy
  path does not satisfy a quantified criterion.
- Verify that fixtures actually have the property named by the case. For
  example, an ASCII case must contain only ASCII, a suffix or delimiter case
  must also occur before trailing content, and a preserved 4xx/5xx response
  must assert its body as well as its status.
- When a criterion requires runtime, packaging, performance, or lifecycle
  evidence, run that evidence. A passing build or static inspection is not a
  substitute for an OCI smoke test, load run, streaming observation, or process
  shutdown scenario.

### Preserve scope and failure semantics

- Implement only behavior required by the current implementation-ready issue.
  Do not add future enforcement reactions, schema containers, configuration
  switches, compatibility modes, or generic extension points for anticipated
  work. Record a follow-up issue instead when the need is real but out of scope.
- Check library defaults and convenience APIs for behavior that crosses the
  intended boundary, such as external file includes, implicit I/O, permissive
  coercion, or raw exceptions. User-controlled and upstream-controlled invalid
  input must follow the issue's typed, stable, and safe failure contract.
- For stateful, concurrent, and resource-owning code, enumerate ownership and
  every terminal path before implementation: success, rejection, failure,
  timeout, cancellation, caller close, peer close, and shutdown. Release quota,
  leases, buffers, executors, and connections only at the lifecycle point that
  actually ends their use. Test illegal interleavings, not only sequential use.

### Keep documentation and sources of truth current

- KDoc/Javadoc is part of the slice, not verification cleanup. Document every
  added or modified Kotlin/Java method, including test methods, named callbacks,
  fixtures, and lifecycle helpers, plus any broader declarations required by
  the issue. After a refactor, compare wording about ordering, early return,
  waiting, cancellation, errors, and ownership with the final code.
- Before introducing a comparator, invariant, report calculation, serializer,
  process launcher, polling helper, or raw HTTP fixture, search for the existing
  canonical implementation. Reuse it when semantics are identical. When two
  outputs must agree, derive them from one immutable snapshot or one shared
  rule rather than duplicating the calculation.
- Do not create abstractions merely to remove superficial test similarity.
  Extract shared code when duplicated domain semantics or setup obligations
  could drift; keep independent scenario mechanics local when they differ.

### Make asynchronous and process tests deterministic

- Synchronize on the observation asserted by the test. A client response, a
  callback entry, or a latch inside a worker is not proof that a later metric,
  log, published result, or cleanup action is visible. Signal after publication
  or use deadline-bounded polling that reports the last observed state.
- Do not prove streaming or ordering with wall-clock timestamp races or sleeps.
  Use explicit handshakes: hold the final upstream chunk or state transition
  until the downstream observation has occurred, then release it.
- Use Armeria `http(0)` for in-process servers. Cross-process tests must use the
  shared port-reservation fixture or a validated non-ephemeral reservation;
  never close `ServerSocket(0)` and later ask another process to bind the
  released port.
- Keep process launch configuration centralized. Whenever startup gains a
  mandatory file, environment variable, or resource, audit normal process
  tests, packaged-process tests, performance fixtures, OCI smoke tests, and
  distribution launchers in the same change.

### Finish the consistency pass before verification

- Inspect `git status` and the complete diff against the chosen base. Keep
  unrelated papercuts, Sonar cleanup, generated reports, and other issues out of
  the current change set unless the user explicitly includes them.
- Update the issue checklist/status, parent epic membership/progress, dependent
  issue contracts, and `spec/WORK_ITEMS.md` together. Parent epics describe
  outcomes and boundaries; they must not copy detailed acceptance rules owned
  by leaf issues.
- Do not mark an issue or epic `Done` while required dynamic evidence is
  missing, a dependency is incomplete, or its decomposition still says
  otherwise. Run `./gradlew validateWorkItems` before the final build whenever
  work-item files changed.
- Before handing the change to `verify-changes`, confirm that the criterion
  matrix has no unsupported row, required KDoc/Javadoc is current, specialized
  dynamic evidence has run, the diff contains no unapproved behavior, and all
  asynchronous tests use deterministic barriers.

## Protocol compatibility principle

For guardrail-enabled work after bypass-only v0, the OpenAI-compatible protocol layer must be **schema-tolerant, lossless in forwarding, and strict about inspectability**:

- Preserve the original request body and derive a separate normalized view containing only the data needed by guardrails.
- If a request is allowed without modification, forward its original body rather than rebuilding it from typed DTOs.
- Preserve and forward unknown fields. When a guardrail must modify content, patch only the targeted fields and retain everything else.
- Do not silently allow a request whose LLM-visible content cannot be reliably extracted and inspected. Treat an unknown additional field as forward-compatible, but fail closed with a stable proxy error when the content-bearing structure is ambiguous or unsupported. During the current text-only stage, a schema-recognized non-text content block or provider-opaque continuation block may be forwarded unchanged only when the normalized result explicitly records an inspection gap. This narrow exception does not apply to malformed content, an unknown content discriminator, or an ambiguous content-bearing structure; those cases remain fail-closed.
- Do not silently coerce non-conformant request shapes. Use an explicit, versioned compatibility adapter when Vigilant intentionally accepts a format that the selected upstream does not accept directly.
- Forward only end-to-end headers. Vigilant remains responsible for upstream authentication and for rewriting `Host`, `Content-Length`, and hop-by-hop headers.

This principle is implemented by the current request-side shadow inspection
path. Keep the normalized inspection view separate from the quota-controlled
original source, and always replay the original bytes for an allowed request.

## Constraints to preserve when editing

- Follow YAGNI and SOLID principles: implement only currently required functionality and keep designs focused, cohesive, extensible, and dependent on appropriate abstractions.
- Always document added or modified Kotlin/Java methods with the language-standard doc format: KDoc (`/** */`) for Kotlin, Javadoc for Java. Follow the existing style in this codebase (see `BypassProxyService.kt`, `AppConfig.kt`).
- Do not aggregate response bodies. Request-side inspection may retain only the bounded complete source owned by `RequestSourceQuota`; parse a separate view and replay the original bytes without DTO reserialization.
- Hop-by-hop header handling must follow the HTTP proxy rules, including headers listed in `Connection`.
- Keep Netty event loops free of blocking calls; blocking work belongs on virtual threads or bounded executors (spec CONC-01..03).
- Upstream errors must surface as stable proxy errors, not raw connection exceptions (spec PROXY-03).
- Logs must not contain bodies or auth headers by default.
