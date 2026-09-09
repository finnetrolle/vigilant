# Project agent guide

Read this file completely before project work. It is the canonical startup
owner for every coding agent. [Product docs](docs/README.md) describe the
current Kotlin/JVM Armeria guardrails system; [requirements](spec/requirements/README.md)
are normative and [coverage](docs/requirements-coverage.md) distinguishes runtime
behavior, targets and evidence gaps. Planning records live in the
[active catalog](spec/WORK_ITEMS.md); never infer completion from a missing file.

## Mandatory routing

These obligations apply even without an issue ID or a working task-context
command. Read each applicable owner before its phase, once per unchanged source.
A packet is source selection, never readiness approval or a verification verdict.

| Before / when | Required owner or action |
|---|---|
| Any production-code planning/edit | Load installed `tdd` (`$tdd` in Codex, `/tdd` in Claude Code), even without explicit invocation; follow [project testing mode](docs/agent-workflow.md#behavior-first-development-and-selective-tdd) |
| Any implementation, then before verification | Read [defect prevention](docs/agent-workflow.md#pre-verification-defect-prevention); keep the criterion/evidence map complete |
| Exact tracked issue | Run `rtk proxy ./scripts/task-context <ID>`; follow [task packet contract](docs/task-context.md#contract). A failed packet requires repairing declarations or reading the exact owners directly; it never waives rules |
| Work-item authoring/readiness | Read [risk-based readiness](spec/WORK_ITEMS.md#risk-based-readiness) and [issue template](spec/ISSUE_TEMPLATE.md) |
| Verification handoff | Publish the exact [pre-verification closure](docs/agent-workflow.md#finish-the-consistency-pass-before-verification); unsupported criteria, lifecycle, contracts or scope stop handoff |
| Completion/removal or ID allocation | Follow [completion protocol](docs/agent-workflow.md#work-item-completion) and [reproducible completion checks](docs/development.md#завершение-work-item) |
| Build/test/run or tooling friction | Read the relevant [development procedure](docs/development.md); use [exact-tag papercut retrieval](docs/development.md#agent-papercuts) before diagnosis |
| Runtime behavior change | Read owning requirements and [architecture](docs/architecture.md), [runtime](docs/runtime-contract.md), [configuration](docs/configuration.md), [observability](docs/observability.md) sections |

For one issue, implement inline unless a real model switch or explicit user
delegation requires a worker. Preserve separate independent Standards and Spec
verification. Installed global skills are used in place, not copied into this
repository. Explicit session instructions select testing mode and authorized scope.

## Stable engineering invariants

- Keep current behavior in `docs/` and normative scope/status in `spec/`.
  Never describe future behavior as available. Update requirements coverage when
  a requirement's status or documented surface changes.
- Apply YAGNI and SOLID: implement only agreed scope, with focused, cohesive
  responsibilities and appropriate abstractions. Future reactions, routes,
  identity modes, disk spill and plugin workers need an implementation-ready issue.
- Added/modified Kotlin and Java methods require KDoc/Javadoc, including tests,
  callbacks, fixtures and lifecycle helpers, in the same slice. Re-read wording
  after behavior or ownership changes; [defect prevention](docs/agent-workflow.md#keep-documentation-and-sources-of-truth-current) owns the detailed sweep.
  Follow the existing style in `BypassProxyService.kt` and `AppConfig.kt`.
- New injectable classes use Metro `dev.zacsweers.metro.Inject` and
  `@SingleIn(AppScope::class)`, not `javax.inject` or the nonexistent
  `dev.zacsweers.metro.Singleton`. Composition stays in `AppComponent`.
- Keep Netty event loops free of blocking calls; use virtual threads or bounded
  executors (CONC-01..03). Use real Armeria-server E2E for proxy behavior changes.
- Low-level bypass responses stream. The guardrail route retains complete
  responses only through `RetainedResponseSource`; request inspection retains
  only the bounded complete source owned by `RequestSourceQuota`.
- Preserve the [protocol compatibility principle](#protocol-compatibility-principle),
  hop-by-hop rules including Connection-named fields, and stable safe proxy errors.
  Logs must not contain bodies or auth headers by default.
- Architecture schemes use UML 2.0 with reviewable PlantUML in `docs/diagrams/`.
  Update the owning text and corresponding diagrams when component ownership,
  request sequence, audit state or tracing lineage changes.
- `sonar_problems.md` owns known static-analysis debt. When touching a listed
  area, fix its corresponding findings and remove resolved entries; unrelated
  cleanup stays outside the issue under the scope check.

## Agent code navigation

Use the local `ast-index` first for symbols, outlines, references, usages,
implementations, hierarchy, callers, changed symbols and module dependencies:
`rtk proxy ast-index stats`, `explore`, `symbol`, `refs`, `outline`, `changed`.
Refresh with `rtk proxy ast-index update` after source edits or branch switches.
Do not start persistent `watch`. If absent, stale or unrefreshable, use `rg`
and direct reads. Index hits are navigation, not verification.

Use `rg` first for literal text, paths, docs, config, comments, KDoc and string
literals, and for final completeness sweeps. For machine output use
`rtk proxy ast-index --format json`; never pipe presentation-filtered paths or
JSON into another command. All raw verification evidence uses `rtk proxy`.
Never start another Gradle invocation until the yielded process definitely exits.

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
