# Task context

`rtk proxy ./scripts/task-context <active-issue-ID>` reads an exact active issue from the
current Git repository, including when invoked from a subdirectory. Python 3
and Git are required. With declared papercut tags it also uses the existing
pinned `scripts/papercuts` 0.2.0 wrapper. `--help` is the only flag; no setup,
network lookup, generated source or persistent process is involved.

## Contract

Success is a complete UTF-8 TOON packet on stdout, exit 0. The output is built
before publication and is byte-identical for unchanged source files and tooling.
Quoted strings preserve full source text, including newlines and Unicode.
The small scalar/tabular encoding follows the [TOON specification](https://toonformat.dev/reference/spec).

| Field | Content and ownership |
|---|---|
| `issue.id/status/path/dependencies/blockers` | Exact owning metadata; wrapped dependency values retain their line breaks |
| `issue.contract` | Complete issue source, without summarization or truncation: goal/result, risk reason or scope lock, criteria, validation commands, non-goals and any remaining own sections |
| `context` | Only the explicitly declared repository sections, in declaration order, as source/content pairs |
| `papercuts` | At most five exact-tag matches with ID, symptom, status and resolution note; null means no note |
| `sources` | Every included source: registry membership section, issue, parent when applicable, declared sections and journal only when used |

Active membership means a standalone issue occurs once in the registry, or a
child occurs once with current status in a registered parent checklist and has
an Epic backlink. Heading IDs and the catalog's backtick ID metadata are
supported; conflicting identities and duplicate files/entries are errors.
Dependencies/blockers remain explicit links; the command does not infer Done
from absence, choose work or authorize implementation. Draft/Blocked/Done
metadata is reported as such. Readiness remains the catalog's contract.

The [issue template](../spec/ISSUE_TEMPLATE.md#context-sources) owns declarations.
Legacy issues retain their full agreed source without inventing a risk reason
or approval. Adding context declarations does not rewrite their frozen scope.
A scope lock owned by an epic must be explicitly included by its exact anchor;
an ordinary navigation link is not an instruction to expand a document.

Sources are regular UTF-8 files inside the repository. Each selection includes
its heading and descendant subsections through the next heading of the same or
higher level, excluding neighbors and their explicit anchor markers. ATX and
Setext headings use the catalog validator's Unicode slug spelling and duplicate
suffixes; fenced examples create no anchors. A standalone `<a id="name"></a>`
immediately before a heading, with optional blank lines, is also an exact
selection anchor. Other inline HTML IDs are not section selectors. Explicit IDs
must be unique. H1/full-document and overlapping selections are rejected;
authors select a narrower owning section instead. No source is silently cut.

For papercuts, the pinned CLI owns journal folding and stable ordering: severity
descending, timestamp descending, ID ascending. The packet preserves that
order, filters by the exact union of declared tags across open and resolved
entries, then limits to five. It does not duplicate the comparator. Skipped
malformed/torn/duplicate/unknown/orphan records invalidate the entire packet,
even when their tags do not match. Without tags no journal operation occurs.
The complete folded journal is internal tool data, never default agent output.

## Errors and validation

Failures emit only two structured fields, `error` and actionable `help`, with
no partial issue/context packet, stack trace or raw source/tool error. Exit 1
means source/catalog/tooling failure; exit 2 means invalid CLI usage. Neither
channel logs input bodies. Error codes distinguish `missing_id`, `duplicate_id`,
`catalog`, `metadata`, `source_file`, `context_declaration`, `missing_anchor`,
`ambiguous_anchor`, `broad_source`, `papercuts_malformed`, `papercuts_unreadable`
and `repository`. Fix the named declaration/source and retry; do not guess a
similar ID, path, tag or anchor. Errors are on stdout as structured TOON.

```bash
rtk proxy python3 -m unittest discover -s scripts/tests -p 'test_*.py'
rtk proxy ./gradlew workItemValidatorTest --rerun validateWorkItems
rtk proxy git diff --check
```

Tests execute the real CLI in isolated Git repositories. Independent literals
own expected issue and external-section text, papercut order/status/notes and
error codes. No production extractor computes expected results. Runtime behavior,
Gradle test topology, global skills, security gates, affected-test selection and
verification verdicts are outside this tool. Mandatory obligations are routed
from [CLAUDE.md](../CLAUDE.md#mandatory-routing) independently of packet success.

The [context baseline](agent-context-baseline.md) separates input text, internal
tool data, emitted packet and bytes/words saved; it does not claim token or
wall-clock savings or impose an unmeasured release threshold.
