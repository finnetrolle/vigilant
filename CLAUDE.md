# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Vigilant is a guardrails system for AI agent platforms. The product specs live in `spec/` (written in Russian) and are the source of truth for scope decisions:

- `spec/MVP_NON_FUNCTIONAL_REQUIREMENTS.md` - stack, SLOs, and the definition of the first increment (v0 - Bypass Proxy)
- `spec/MVP_FUNCTIONS.md`, `spec/STAGE_1_FUNCTIONS.md`, `spec/OUT_OF_SCOPE_FUNCTIONS.md` - future scope and explicit non-goals
- `spec/WORK_ITEMS.md` - work-item conventions and registry
- `spec/epics/*` - large outcomes decomposed into linked issues
- `spec/issues/**` - standalone and epic-scoped issues I can ask you to implement

Current product documentation starts at `docs/README.md`.
`docs/requirements-coverage.md` maps every MVP, non-functional, Stage 1 and
out-of-scope requirement to the actual runtime status and owning documents.

The project has moved beyond bypass-only v0 and completed its first production
guardrail increment: bounded request-side PII inspection for OpenAI Chat
Completions with configured ALLOW, MASK and BLOCK. The v0 proxy remains the transport foundation, but
the production request path now retains a bounded complete request source,
derives a lossless normalized inspection view, evaluates the immutable startup
policy snapshot with `fast-pii`, emits a safe aggregate decision, and either replays original bytes, applies validated
non-expanding free-text source patches, or rejects before upstream handoff. The low-level bypass transport keeps
responses streaming, while the guardrail-enabled Chat Completions path retains
the complete upstream response in memory, validates its protocol terminal
state, and only then either replays the exact original response, replays an
exact source-patched masked representation, or rejects without upstream
disclosure.

The startup policy file remains mandatory, but an explicit empty snapshot is
valid and no global coverage policy is required. REQUEST detected reactions
allow ALLOW, MASK or BLOCK; clean requires ALLOW and error requires BLOCK,
both without transformations, including disabled policies. A structural MASK
blocks the whole request. Technical failure or deadline takes priority over
policy BLOCK and returns safe 503. Ordinary JSON and SSE response semantics
remain unchanged. REMOVE, new routes, identity modes, disk spill and plugin
workers require a dedicated implementation-ready issue.

## Commands

```bash
./gradlew build                 # compile + tests
./gradlew test                  # full serial processTest lane, then four-worker non-process tests
./gradlew test -x processTest --tests "io.vigilant.gateway.proxy.BypassProxyServiceTest"  # single non-process test class
./gradlew processTest --tests "io.vigilant.gateway.MainTest"  # focused child-process suite
./gradlew run                   # run MainKt directly; same config requirements as the distribution
./gradlew installDist           # build distributable into build/install/vigilant/
./gradlew ociArtifact           # reproducible versioned tar consumed by the Dockerfile

# Run env-only (upstream, identity and policy configuration are required)
VIGILANT_UPSTREAM_URL=http://127.0.0.1:18081 VIGILANT_ENVIRONMENT=development VIGILANT_IDENTITY_MODE=DUMMY VIGILANT_IDENTITY_DUMMY_USER=local-user VIGILANT_PORT=18080 ./build/install/vigilant/bin/vigilant

# Run with a HOCON config file (see vigilant.conf.example); env vars still override file values
VIGILANT_CONFIG=./vigilant.conf.example ./build/install/vigilant/bin/vigilant

# Quality tools (beyond SonarQube + JaCoCo)
./gradlew detekt                 # Kotlin static analysis, wired into build/check; project tweaks in config/detekt/detekt.yml
./gradlew pitest                 # mutation testing against io.vigilant.* classes; on-demand only (analyze-pitest skill), not part of regular checks
./gradlew dependencyCheckAnalyze # OWASP CVE scan of the dependency tree
./gradlew validateWorkItems       # work-item graph consistency; also wired into check
./gradlew piiQualityReport        # canonical synthetic PII quality JSON/Markdown report
./gradlew testThroughputQualification # exact local 3 + 3 + 10 four-worker qualification
./gradlew verifyAll              # full local verification: build + dependency check
./gradlew installGitHooks        # one-time after clone: installs pre-push hook from config/git/hooks/
```

Copy `politics.conf.example` to `politics.conf` before local application runs;
the policy snapshot is mandatory and
`VIGILANT_POLITICS_CONFIG` overrides the default `./politics.conf` path.

Invalid or missing config prints a message to stderr and exits with code 2.

## Agent code navigation

The repository has a local `ast-index` for compact semantic navigation. Prefer
it before broad source reads when locating a symbol, outline, references,
usages, implementations, hierarchy, callers, changed symbols or module
dependencies:

```bash
rtk proxy ast-index stats
rtk proxy ast-index explore "request inspection"
rtk proxy ast-index symbol PiiShadowProxyService
rtk proxy ast-index refs PiiShadowProxyService
rtk proxy ast-index outline src/main/kotlin/io/vigilant/gateway/proxy/PiiShadowProxyService.kt
rtk proxy ast-index changed
```

Run `rtk proxy ast-index update` after source edits or a branch switch before
relying on the index again. Do not start the persistent `watch` command during
an agent task. If the index is absent, stale or cannot be refreshed, fall back
to `rg` and direct file reads without blocking the task.

Use `rg` first for literal text, paths, Markdown/specs, configuration, KDoc,
comments and string literals, and as the final completeness sweep when a change
may affect non-symbol references. For machine parsing request
`ast-index --format json` through `rtk proxy`; do not pipe presentation-formatted
output into another command. Index hits identify code to inspect and do not
replace reading the owning contract, reviewing the exact diff or running tests.

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

- `gateway/proxy/PiiShadowProxyService.kt` - thin production HTTP inspection boundary. It validates the supported Chat Completions descriptor, ingests into a quota-controlled request source, schedules complete-source workflow execution, maps typed rejects to stable responses, and performs the one-shot handoff to `BypassProxyService` and `RetainedResponseHandler` after best-effort terminal request audit submission without durable reservation or acknowledgement.
- `gateway/proxy/RetainedResponseHandler.kt` / `source/RetainedResponseSource.kt` - response-retention boundary for the guardrail route. It holds upstream status, headers, trailers and body until complete protocol validation and final response-policy enforcement, then transfers one exact or masked replay or returns a stable error from the [HTTP contract](spec/requirements/http-gateway.md#inspection-error-matrix); the source owns in-memory segments and clears them on every terminal path without response quota or disk spill.
- `gateway/proxy/ResponseInspectionWorkflow.kt` / `protocol/openai/JsonResponseRewriter.kt` / `protocol/openai/SseResponseRewriter.kt` - shared ordinary JSON/SSE response-policy orchestration and transport-specific exact-source rewriting. The response parser creates immutable source coordinates in its single parse pass; SSE spans may cross delta events without reserializing event objects.
- `gateway/proxy/ShadowInspectionWorkflow.kt` / `gateway/proxy/ReplayReadyRequest.kt` - gateway-specific complete-source application workflow and one-shot transport ownership boundary. The workflow parses one normalized view, assembles context, evaluates fragments, publishes one safe started/completed pair through the existing non-blocking logger, and returns typed `Forward` or `Reject`. `ReplayReadyRequest` retains owner responsibility until transport accepts original or patched replay, then terminal replay owns cleanup.
- `gateway/proxy/ShadowAuditLogger.kt` - safe request and response analysis lifecycle events published best-effort through the existing non-blocking Logback stdout pipeline. The application owns no audit persistence or delivery subsystem.
- `gateway/identity/DummyIdentityExtractor.kt` / `gateway/identity/OfflineJwtIdentityExtractor.kt` / `gateway/identity/ExternalIdentityExtractor.kt` / `gateway/identity/BridgeIdentityClient.kt` / `context/PolicyContextHandoff.kt` - common async single-Bearer boundary with startup-selected Dummy, offline RS256 JWT, or trusted Bridge External lookup. External uses `CachingExternalIdentityLookup` with Caffeine write TTL/maximumSize and a separate process-local HMAC `ExternalIdentityCacheKeyHasher`. Completed hits bypass Bridge; bounded shared misses preserve its exact one-attempt HTTP, original whole-exchange timeout, immediate admission, safe metrics and initiating CLIENT span. Each caller has independent cancellation; the last cancellation aborts shared work. Identity extraction and each continuation run on the existing blocking-safe request executor under the caller context. Raw tokens are never retained, and accepted Authorization is preserved upstream.
- `gateway/proxy/OutboundClientResources.kt` - application owner of the sole Armeria `ClientFactory`, the upstream client and, only in External mode, a distinct Bridge client, cache decorator and hasher. Shutdown attempts decorator, Bridge and shared factory cleanup in that order, preserving failures through `runAllCleanupActions`.
- `source/BoundedRequestSource.kt` - process-wide owner/byte/segment quota plus one-request lifecycle. It receives the request with backpressure, exposes one sequential parser view and one demand-driven original or patched replay lease, and releases every reservation on completion or cancellation.
- `protocol/openai/ChatCompletionsRequestParser.kt` - schema-tolerant parser for model-visible Chat Completions content. It preserves unknown fields by never rebuilding the original body, records recognized non-text inspection gaps, and fails closed for malformed or ambiguous content-bearing shapes.
- `policy/engine/PolicyEngine.kt` / `policy/selection/PolicySelector.kt` / `policy/execution/DetectorExecutionCoordinator.kt` - deterministic policy matching, simultaneous overrides, deduplicated detector execution, per-policy deadlines, fail-fast blocking semantics in the domain layer, and complete decision explanations. Executable REQUEST validation requires clean ALLOW/error BLOCK and accepts detected ALLOW/MASK/BLOCK; response validation remains unchanged.
- `detectors/pii/fast/FastPiiDetector.kt` / `windowing/WindowedInspectionExecutor.kt` / `windowing/WindowedFastPiiExecutor.kt` - built-in deterministic detector, PII-free UTF-8-safe generic windowing core, and the thin Fast PII contract adapter. CPU work runs on the bounded pool owned by `InspectionResources`.
- `gateway/proxy/BypassProxyService.kt` - transport stage after inspection. Rewrites request headers (upstream scheme/authority/path and hop-by-hop stripping), strips hop-by-hop response headers, preserves exact request replay and streaming responses, and maps upstream failures to stable proxy errors.
- `gateway/config/AppConfig.kt` - config loading via Hoplite: optional HOCON file (`VIGILANT_CONFIG`, else `./vigilant.conf`, else `/etc/vigilant/vigilant.conf`) with `VIGILANT_*` env overrides on top (env > file > defaults), then strict post-decode validation. Unit-tested directly without a running server.
- `policy/config/PolicyConfiguration.kt` - resolves mandatory `politics.conf` (`VIGILANT_POLITICS_CONFIG`, else `./politics.conf`), reads it once, and composes the strict parser with semantic validation into an immutable startup snapshot.
- `policy/provider/PolicyProvider.kt` - suspend provider contract and `DummyPolicyProvider`, which retains one complete immutable startup snapshot without I/O, filtering, or hot reload.
- `policy/selection/PolicySelector.kt` - pure context matcher and simultaneous override resolver; returns immutable policy lists sorted by policy ID without provider I/O or detector execution.
- `gateway/AppComponent.kt` - Metro `@DependencyGraph(AppScope::class)`. Providers live in the companion object; the graph also assembles the Armeria `Server`. New injectable classes use `dev.zacsweers.metro.Inject` / `@SingleIn(AppScope::class)` (not `javax.inject` - Metro does not ship it, and `dev.zacsweers.metro.Singleton` does not exist).
- `gateway/health/LivenessService.kt` / `gateway/health/ReadinessService.kt` - gateway-owned probes never proxied upstream: `/healthz` answers `200` while the server accepts connections; `/readyz` follows lifecycle readiness and becomes `503` during shutdown.
- `gateway/Main.kt` - builds the graph and shuts down in order: not-ready, server drain, inspection, Bridge/outbound factory, telemetry.

Tests spin up real Armeria servers on ephemeral ports (`http(0)`) and proxy through them - keep this E2E style for proxy behavior changes.

## Documentation maintenance

- Keep current behavior in `docs/` and normative scope/status in `spec/`; never
  describe future behavior as available runtime functionality.
- Update `docs/requirements-coverage.md` whenever implementation changes the
  status or documented surface of an existing requirement.
- Create architecture schemes only in UML 2.0 notation. Store reviewable
  PlantUML sources in `docs/diagrams/` and update the owning text document in
  the same change.
- When component ownership, request sequence, audit state or tracing lineage
  changes, update the corresponding UML diagram as contract evidence.

## Work-item completion

`spec/WORK_ITEMS.md` is the catalog of open work. Permanent product requirements
belong to the [requirements owners](spec/requirements/README.md), runtime details
to `docs/`, and implementation history to Git. One behavioral clause has one
normative owner; coverage distinguishes targets, runtime and evidence gaps.

Complete an issue and its parent scope as one consistency change:

1. Obtain every required implementation/verification observation from the agreed
   issue and applicable epic criteria before removal. Ensure the exact agreed
   source is recoverable from Git first; never delete the only uncommitted
   specification. Do not create archives, historical wrappers or retirement
   registries in the working tree, or rewrite Git history.
2. Transfer current requirements from both issue and epic to permanent owners,
   relevant implementation details to runtime docs, and applicable observations
   to evidence. Update requirements coverage. Do not transfer superseded rules,
   planning/history or duplicate clauses, lower product targets to match code,
   or describe an old measurement as evidence for a new target.
3. Keep `Зависит от` hard edges to unfinished work. Replace completed prerequisites
   with explicit requirement/capability links under `Выполненные предпосылки`.
   Missing files never imply completion; dangling, self and cyclic dependencies
   are errors. Check every incoming reference, including wrapped metadata.
4. Delete the completed issue and its registry/checklist row, and remove or
   redirect incoming references. Retain an unfinished parent with its remaining
   children/scope; delete a fully completed parent after transferring its clauses.
   An epic with future scope but no executable children is `Draft` with a reason.
   Registry progress counts only current checklist files: removed children no
   longer contribute to either side of `done/total`. Empty catalogs and absent
   empty directories are valid.
5. Validate the resulting catalog and local references after terminal removal,
   not only before it. Follow the reproducible checks in
   [development](docs/development.md#завершение-work-item). Do not weaken validation
   to accommodate an already removed source. TDD, KDoc/Javadoc, privacy and all
   task-specific verification obligations still apply.
6. Never reuse IDs. Before allocating a new ID, inspect both the active catalog
   and Git history, including removed paths and title/ID metadata.

## Behavior-first development and selective TDD

Load the installed `tdd` skill before planning or changing production behavior.
This section selects the project default and overrides conflicting workflow or
confirmation wording in that skill. Explicit user requests for TDD/test-first
select strict RED -> GREEN; otherwise use small behavior-first slices.

1. Before coding, map acceptance criteria to independent input/output examples,
   existing contract consumers and exact observations. Think through data types,
   errors and lifecycle ownership together. Include old unit, HTTP, process,
   packaged/OCI and performance fixtures affected by a changed contract.
2. State the seam briefly. A boundary documented in the agreed issue, guide or
   session is pre-agreed. Ask only when it is undocumented, materially changes
   architecture or conflicts with normative requirements.
3. Implement one small coherent behavior with its related examples. Code/test
   writing order is flexible; run the group before taking another slice. Do not
   batch the entire issue without feedback. Quantified cases remain mandatory.
4. For a bug, first obtain a failing regression test at the public boundary,
   using real E2E when practical. Confirm the behavioral reason, then fix and
   run the same test. Compilation, fixture and environment failures are not RED.
   Keep naturally GREEN additional examples; never manufacture a defect.
5. Run detekt and the narrow affected old/new tests after a coherent code change.
   Resolve lint and contract-migration failures before a broad build. A stable
   slice needs no repeated checks unless relevant inputs change or it fails.
6. Refactor under existing GREEN tests; add characterization coverage only where
   needed. Documentation, formatting and test/build tooling use relevant focused
   checks without artificial RED when production behavior is unchanged.

Proxy behavior still requires E2E through real Armeria servers. Keep causal
barriers and every required terminal-path check for cancellation, replay, quota,
ownership, backpressure, privacy and shutdown. Client completion does not prove
that `RequestLog.whenComplete()`, spans, metrics or cleanup have been published;
observe the owning boundary with `GatewayTestFixture.awaitUntil` and a bounded
diagnostic timeout.

For deterministic parser/masking/header/error contracts, use input/expected-output
fixtures with independent literals. Human-approved baselines must not be updated
automatically to match actual output. Approved scenarios supplement lifecycle
tests. Mutation testing remains on demand, outside regular development gates.

Before implementation completion, obtain one current full `./gradlew build` for
production changes. Do not run an overlapping broad subset immediately before
it unless diagnosing a failure or the task explicitly requires that evidence.
Run required process/OCI/load checks when the issue calls for them. Preserve
their applicability to the final inputs; an unchanged valid result may be reused.

Use `scripts/check-run` for long checks. It records a durable command/result/log
and selected input hashes. Prefer completion events; otherwise use a single
bounded wait at useful checkpoints, aligned with the outer tool yield. Do not
repeatedly read unchanged stdout, request heartbeat messages or run overlapping
Gradle invocations. The runner's lock protects runner invocations; check for an
already running direct Gradle command before starting it.

## Pre-verification defect prevention

The final verification pipeline is a backstop, not the first time the change
should be compared with its contract. Apply the following rules while coding.
They apply in every testing mode; regression fixes and explicit TDD still require behavioral RED.

### Build criterion-level evidence

- Before the first slice, read the implementation-ready issue, its parent epic,
  linked normative specs, dependencies, and explicit non-goals. Keep one compact
  map of criteria, changed contracts, old consumers, independent examples and
  validation commands. Execute its cheap checks during coding; do not defer
  contract migration or lint until the final full build. Update the same map as
  the implementation changes.
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

### Require causal and independent evidence

- A test or generated report is evidence only when its setup reaches the named
  state, it observes the requirement at the boundary that owns it, and its oracle
  is independent from the production calculation. Case labels, comments,
  hard-coded booleans or counts, and duplicated calculations cannot establish a
  criterion.
- A parameterized row must vary a required input, state, terminal event, or
  transition and assert the corresponding consequence. Do not count future or
  unimplemented reactions as covered by passing their names through the same
  generic code path.
- For cross-component state, synchronize on an observation published by the
  owning server or process. Client transport consumption, callback entry, or an
  earlier latch is not proof that the asserted state has been published.
- Cleanup must attempt every owned resource even when one close operation fails;
  preserve the first failure and retain later failures as suppressed evidence.

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
- Use the canonical Armeria loopback-address helper for in-process servers: it
  binds `127.0.0.1` with port `0`, matching the client URI family while retaining
  kernel-owned ephemeral allocation. Cross-process tests must use the shared
  port-reservation fixture or a validated non-ephemeral reservation; never close
  `ServerSocket(0)` and later ask another process to bind the released port.
- Keep process launch configuration centralized. Whenever startup gains a
  mandatory file, environment variable, or resource, audit normal process
  tests, packaged-process tests, performance fixtures, OCI smoke tests, and
  distribution launchers in the same change.

### Finish the consistency pass before verification

- Inspect `git status` and the complete diff against the chosen base. Keep
  unrelated papercuts, Sonar cleanup, generated reports, and other issues out of
  the current change set unless the user explicitly includes them.
- Update the issue completion/removal, parent epic membership/progress, dependent
  prerequisite references, and `spec/WORK_ITEMS.md` together under the completion
  protocol. Parent epics describe outcomes and boundaries; they must not copy detailed acceptance rules owned
  by leaf issues.
- Do not mark an issue or epic `Done` while required dynamic evidence is
  missing, a dependency is incomplete, or its decomposition still says
  otherwise. Run `./gradlew validateWorkItems` before the final build whenever
  work-item files changed.
- Before handing the change to `verify-changes`, confirm that the criterion
  matrix has no unsupported row, required KDoc/Javadoc is current, specialized
  dynamic evidence has run, the diff contains no unapproved behavior, and all
  asynchronous tests use deterministic barriers.

Verification keeps independent Standards and Spec axes. Run an initial complete
review; after an authorized remediation wave, review its delta and affected
contracts with the same reviewers. Expand to a full review when context or the
trusted baseline is missing, or a shared boundary changes broadly. Collect
available Sonar/static findings before semantic review when feasible. Reuse a
check only while all its relevant inputs, configuration and toolchain remain
valid; input hashes alone do not prove artifact integrity or report freshness.
The final state must have every required gate covered.

Before requesting `verify-changes`, publish this closure summary and stop when
any required field is unresolved:

```text
Pre-verification closure:
  criteria: <covered>/<total>; unsupported: <count>
  quantified cases: <named cases>; label-only cases: 0
  lifecycle: <owners and terminal paths>; missing: 0
  canonical reuse: <searches and decisions>
  contract sweep: <old/new terms and searched sources>; stale claims: 0
  scope: <base and owned files>; unrelated files: 0 or <approved list>
```

## Protocol compatibility principle

For guardrail-enabled work after bypass-only v0, the OpenAI-compatible protocol layer must be **schema-tolerant, lossless in forwarding, and strict about inspectability**:

- Preserve the original request body and derive a separate normalized view containing only the data needed by guardrails.
- If a request is allowed without modification, forward its original body rather than rebuilding it from typed DTOs.
- Preserve and forward unknown fields. When a guardrail must modify content, patch only the targeted fields and retain everything else.
- Do not silently allow a request whose LLM-visible content cannot be reliably extracted and inspected. Treat an unknown additional field as forward-compatible, but fail closed with a stable proxy error when the content-bearing structure is ambiguous or unsupported. During the current text-only stage, a schema-recognized non-text content block or provider-opaque continuation block may be forwarded unchanged only when the normalized result explicitly records an inspection gap. This narrow exception does not apply to malformed content, an unknown content discriminator, or an ambiguous content-bearing structure; those cases remain fail-closed.
- Do not silently coerce non-conformant request shapes. Use an explicit, versioned compatibility adapter when Vigilant intentionally accepts a format that the selected upstream does not accept directly.
- Forward only end-to-end headers. Vigilant remains responsible for upstream authentication and for rewriting `Host`, `Content-Length`, and hop-by-hop headers.

This principle is implemented by the current request-side enforcement
path. Keep the normalized inspection view separate from the quota-controlled
original source, and replay original bytes for ALLOW and validated exact source patches for MASK.

## Constraints to preserve when editing

- Follow YAGNI and SOLID principles: implement only currently required functionality and keep designs focused, cohesive, extensible, and dependent on appropriate abstractions.
- Always document added or modified Kotlin/Java methods with the language-standard doc format: KDoc (`/** */`) for Kotlin, Javadoc for Java. Follow the existing style in this codebase (see `BypassProxyService.kt`, `AppConfig.kt`).
- Keep low-level bypass responses streaming. The guardrail route may retain a
  complete upstream response only through `RetainedResponseSource`; parse a
  separate view and replay the original bytes without DTO reserialization.
  Request-side inspection may retain only the bounded complete source owned by
  `RequestSourceQuota`.
- Hop-by-hop header handling must follow the HTTP proxy rules, including headers listed in `Connection`.
- Keep Netty event loops free of blocking calls; blocking work belongs on virtual threads or bounded executors (spec CONC-01..03).
- Upstream errors must surface as stable proxy errors, not raw connection exceptions (spec PROXY-03).
- Logs must not contain bodies or auth headers by default.
