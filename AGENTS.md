# Project agent instructions

Read and follow `CLAUDE.md` completely before doing project work. It is the
canonical project guide for every coding agent, despite the provider-specific
filename.

Before planning or editing any task that adds or changes production code, load
the installed TDD skill (`$tdd` in Codex, `/tdd` in Claude Code), even when the
user did not invoke it explicitly. Follow the `Mandatory test-driven
development` section in `CLAUDE.md`.

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
- update issue, epic, dependent issues, and `spec/WORK_ITEMS.md` as one
  consistency change, and do not mark work `Done` before its required dynamic
  evidence exists and `./gradlew validateWorkItems` passes.

When Codex uses RTK, use `rtk proxy` for machine-consumed output and for raw
verification evidence. Do not pipe presentation-filtered paths or JSON into
another command, and do not start a second Gradle command until the first
yielded process has definitely exited.
