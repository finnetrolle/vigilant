# Project workflow procedures

Read the applicable sections through the mandatory routing in
[CLAUDE.md](../CLAUDE.md#mandatory-routing). These are project-specific procedures,
not copies of installed global skills. The configured `tdd`, `verify-changes`,
`two-axis-review`, `judge-changes` and `no-mistakes` remain their own owners.

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

Before implementation completion, obtain one current full gate for production
changes: `./gradlew build`, or `verifyAll` when OWASP is required. `verifyAll`
already owns build; do not run a separate full build immediately before it. Do not run an overlapping broad subset immediately before
it unless diagnosing a failure or the task explicitly requires that evidence.
Run required process/OCI/load checks when the issue calls for them. Preserve
their applicability to the final inputs; an unchanged valid result may be reused.

Use the [durable runner procedure](development.md#устойчивый-запуск-проверок)
for long checks, including direct-Gradle overlap preflight and bounded waits.

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
  snapshot: <verification-session-id>
  mechanical evidence: <gate -> new/reused run ID + current status command/result; or NOT RUN + reason>
  criteria: <covered>/<total>; unsupported: <count>
  quantified cases: <named cases>; label-only cases: 0
  lifecycle: <owners and terminal paths>; missing: 0
  canonical reuse: <searches and decisions>
  contract sweep: <old/new terms and searched sources>; stale claims: 0
  scope: <base and owned files>; unrelated files: 0 or <approved list>
```

Implementation, `verify-changes` and delivery consumers pass these exact run IDs
forward under the [reusable evidence contract](development.md#контракт-reusable-evidence).
Each consumer checks current applicability before reuse and records whether it
requested a new run, reused an existing one, or has NOT RUN evidence. A launch
acknowledgement, missing report, failed/cancelled/timeout/infrastructure result,
changed argv or stale fingerprint is never PASS. External-data reports remain
inside the same explicit verification session. Do not create a second supervisor,
remote cache or heuristic affected-test selector. Semantic applicability, mandatory
process/OCI/load/lifecycle evidence, OWASP/Sonar and independent review axes remain
reviewer obligations; hashes cannot approve scope or fixtures. Pitest stays on demand.

Transfer per-gate selected inputs, tool/environment declarations and artifacts with
the ID, not only a source hash. A docs-only delta may reuse unaffected runtime
checks while refreshing Spec/docs evidence. A full `verifyAll` that reads docs
must be invalidated when those inputs change. Standards and Spec retain their
initial full snapshot and inspect remediation delta/affected contracts with the
same reviewers. Use the [cycle ledger](development.md#метрики-verification-cycle)
for observed tool time, actual full gates, reuse and invalidation reasons.

## Work-item completion

`spec/WORK_ITEMS.md` is the catalog of open work. Permanent product requirements
belong to the [requirements owners](../spec/requirements/README.md), runtime details
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
   [development](development.md#завершение-work-item). Do not weaken validation
   to accommodate an already removed source. TDD, KDoc/Javadoc, privacy and all
   task-specific verification obligations still apply.
6. Never reuse IDs. Before allocating a new ID, inspect both the active catalog
   and Git history, including removed paths and title/ID metadata.
