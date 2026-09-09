# Project agent instructions

Read and follow `CLAUDE.md` completely before doing project work. It is the
canonical project guide for every coding agent, despite the provider-specific
filename.

Before planning or editing any task that adds or changes production code, load
the installed TDD skill (`$tdd` in Codex, `/tdd` in Claude Code), even when the
user did not invoke it explicitly. Follow the `Behavior-first development and selective TDD` section in `CLAUDE.md`.

Before coding, and again before requesting verification, follow the
`Pre-verification defect prevention` section in `CLAUDE.md`. In particular:

- map every issue/epic acceptance criterion and non-goal to concrete code and
  test evidence; expand words such as `all`, `each`, `exact`, `complete`, and
  `deterministic` into explicit cases rather than representative examples;
- add or update required KDoc/Javadoc in the same TDD slice as the declaration,
  including test methods and lifecycle helpers, and re-read it after behavior
  or ownership semantics change;
- keep the change set inside the implementation-ready issue, reuse canonical
  helpers and ordering rules where semantics are identical, and do not add
  future reactions, schema variants, configuration, or extension points;
- make concurrent and process E2E fixtures deterministic: synchronize on the
  observation being asserted, use bounded waits, never reuse a released
  ephemeral port, and propagate every mandatory startup setting through the
  shared launch fixtures;
- close issue, epic, dependent prerequisite references, and `spec/WORK_ITEMS.md`
  as one consistency change under `Work-item completion` in `CLAUDE.md`; obtain
  required evidence, transfer current requirements to permanent owners, remove
  completed planning records, then run `./gradlew validateWorkItems` again.
  Never infer completion from a missing file or reuse an ID from Git history.

Before handing work to `verify-changes`, publish the exact
`Pre-verification closure` summary required by `CLAUDE.md`. A green build does
not permit handoff while any criterion, quantified case, lifecycle path,
contract sweep, canonical-reuse decision, or scope item remains unresolved.

When Codex uses RTK, use `rtk proxy` for machine-consumed output and for raw
verification evidence. Do not pipe presentation-filtered paths or JSON into
another command, and do not start a second Gradle command until the first
yielded process has definitely exited.

Use the existing `ast-index` first for semantic code navigation: symbols,
outlines, references, usages, implementations, hierarchies, callers and module
dependencies. Invoke it through `rtk proxy ast-index`. Keep `rg` first for
literal text and file-name searches, documentation, configuration, comments,
string literals and final completeness sweeps. Refresh the index after source
edits or branch switches; treat index results as navigation, not verification.

For one issue, implement inline unless a real model switch or explicit user
delegation requires a worker. Preserve separate independent verification.
Use the canonical testing mode, early lint/contract checks and durable runner
described in CLAUDE.md. No automatic snapshot approvals or skipped required
lifecycle/E2E evidence. Revalidate only changed inputs between remediation waves,
and retain a complete current set of final verification results.
