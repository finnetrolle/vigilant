# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Vigilant is a guardrails system for AI agent platforms. The product specs live in `spec/` (written in Russian) and are the source of truth for scope decisions:

- `spec/MVP_NON_FUNCTIONAL_REQUIREMENTS.md` - stack, SLOs, and the definition of the first increment (v0 - Bypass Proxy)
- `spec/MVP_FUNCTIONS.md`, `spec/STAGE_1_FUNCTIONS.md`, `spec/OUT_OF_SCOPE_FUNCTIONS.md` - future scope and explicit non-goals
- `spec/WORK_ITEMS.md` - work-item conventions and registry
- `spec/epics/*` - large outcomes decomposed into linked issues
- `spec/issues/**` - standalone and epic-scoped issues I can ask you to implement

The current codebase is **v0: a transparent bypass proxy**. It forwards all HTTP traffic between a client and a single configured upstream (OpenAI/Anthropic-style LLM API) without inspecting bodies. Policies, detectors, plugin workers, and JSON parsing are explicitly out of scope for v0 - do not add them.

## Commands

```bash
./gradlew build                 # compile + tests
./gradlew test                  # tests only
./gradlew test --tests "io.vigilant.gateway.proxy.BypassProxyServiceTest"  # single test class
./gradlew installDist           # build distributable into build/install/vigilant/

# Run env-only (VIGILANT_UPSTREAM_URL is required; VIGILANT_PORT optional, default 8080)
VIGILANT_UPSTREAM_URL=http://127.0.0.1:18081 VIGILANT_PORT=18080 ./build/install/vigilant/bin/vigilant

# Run with a HOCON config file (see vigilant.conf.example); env vars still override file values
VIGILANT_CONFIG=./vigilant.conf.example ./build/install/vigilant/bin/vigilant

# Quality tools (beyond SonarQube + JaCoCo)
./gradlew detekt                 # Kotlin static analysis, wired into build/check; project tweaks in config/detekt/detekt.yml
./gradlew pitest                 # mutation testing against io.vigilant.* classes; on-demand only (analyze-pitest skill), not part of regular checks
./gradlew dependencyCheckAnalyze # OWASP CVE scan of the dependency tree
./gradlew verifyAll              # full local verification: build + dependency check
./gradlew installGitHooks        # one-time after clone: installs pre-push hook from config/git/hooks/
```

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

Request path: `Client -> Armeria Server -> BypassProxyService -> WebClient -> Upstream`.

Key files (under `src/main/kotlin/io/vigilant/gateway/`, split into the subpackages `config`, `proxy`, `health`, and `tracing` plus the composition root):

- `proxy/BypassProxyService.kt` - catch-all `HttpService`. Rewrites headers via `rewriteRequestHeaders` (sets upstream scheme/authority/path, strips hop-by-hop headers including those named in `Connection`) and `rewriteResponseHeaders` (strips hop-by-hop). The body is never aggregated - `HttpRequest`/`HttpResponse` stay streaming publishers end to end, which is what keeps streaming responses and backpressure working (spec PROXY-01).
- `config/AppConfig.kt` - config loading via Hoplite: optional HOCON file (`VIGILANT_CONFIG`, else `./vigilant.conf`, else `/etc/vigilant/vigilant.conf`) with `VIGILANT_*` env overrides on top (env > file > defaults), then post-decode validation (`loadAppConfig`, `validatedUpstreamUri`, `validatedPort`). Unit-tested directly without a running server.
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

## Protocol compatibility principle

For guardrail-enabled stages after v0, the OpenAI-compatible protocol layer must be **schema-tolerant, lossless in forwarding, and strict about inspectability**:

- Preserve the original request body and derive a separate normalized view containing only the data needed by guardrails.
- If a request is allowed without modification, forward its original body rather than rebuilding it from typed DTOs.
- Preserve and forward unknown fields. When a guardrail must modify content, patch only the targeted fields and retain everything else.
- Do not silently allow a request whose LLM-visible content cannot be reliably extracted and inspected. Treat an unknown additional field as forward-compatible, but fail closed with a stable proxy error when the content-bearing structure is ambiguous or unsupported. During the current text-only stage, a schema-recognized non-text content block or provider-opaque continuation block may be forwarded unchanged only when the normalized result explicitly records an inspection gap. This narrow exception does not apply to malformed content, an unknown content discriminator, or an ambiguous content-bearing structure; those cases remain fail-closed.
- Do not silently coerce non-conformant request shapes. Use an explicit, versioned compatibility adapter when Vigilant intentionally accepts a format that the selected upstream does not accept directly.
- Forward only end-to-end headers. Vigilant remains responsible for upstream authentication and for rewriting `Host`, `Content-Length`, and hop-by-hop headers.

This principle applies when body inspection is introduced in a later stage. It does not relax the v0 requirement to forward bodies as uninterpreted byte streams without aggregation or JSON parsing.

## Constraints to preserve when editing

- Follow YAGNI and SOLID principles: implement only currently required functionality and keep designs focused, cohesive, extensible, and dependent on appropriate abstractions.
- Always document added or modified Kotlin/Java methods with the language-standard doc format: KDoc (`/** */`) for Kotlin, Javadoc for Java. Follow the existing style in this codebase (see `BypassProxyService.kt`, `AppConfig.kt`).
- Do not aggregate request/response bodies or parse JSON in the data path (spec: bodies pass as a byte stream).
- Hop-by-hop header handling must follow the HTTP proxy rules, including headers listed in `Connection`.
- Keep Netty event loops free of blocking calls; blocking work belongs on virtual threads or bounded executors (spec CONC-01..03).
- Upstream errors must surface as stable proxy errors, not raw connection exceptions (spec PROXY-03).
- Logs must not contain bodies or auth headers by default.
