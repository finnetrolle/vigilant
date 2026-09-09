"""Observe task-context stdout/exit through real isolated repository fixtures."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'task-context'
ISSUE = '''# VIG-1: Literal contract

- **ID:** `VIG-1`
- **Статус:** Ready for implementation
- **Зависит от:** нет
- **Блокирует:** нет
- **Архитектурный риск:** Low - local tooling

## Результат

Keep every word: «цель», quotes "yes", backslash \\.

## Context sources

- `docs/owner.md#selected`

## Критерии готовности

- [ ] Observe the exact result.

## Проверки

`./check literal`

## Не входит

Do not change runtime.
'''
OWNER = '''# Owner

Preamble excluded.

## Before

Excluded before.

<a id="selected"></a>

## Chosen

Full text.

### Nested

Preserve this too.

```md
## Fake boundary
```

## After

Excluded after.
'''
EXPECTED_SECTION = '<a id="selected"></a>\n\n## Chosen\n\nFull text.\n\n### Nested\n\nPreserve this too.\n\n```md\n## Fake boundary\n```\n\n'
REGISTRY = '''# Catalog

## Реестр

| Work item | Статус |
|---|---|
| [VIG-1: Literal contract](issues/one.md) | `Ready for implementation` |

## Next

[VIG-1](issues/one.md) is a navigation link, not another entry.
'''


def quote(value):
    """Serialize independent expected literals, without importing the extractor."""
    return json.dumps(value, ensure_ascii=False)


class TaskContextTest(unittest.TestCase):
    """Validate deterministic complete packets and each rejected input boundary."""

    def setUp(self):
        """Create a repository with one hand-authored issue and exact section oracle."""
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True, capture_output=True)
        self.write('spec/WORK_ITEMS.md', REGISTRY)
        self.write('spec/issues/one.md', ISSUE)
        self.write('docs/owner.md', OWNER)

    def write(self, name, value):
        """Install explicit fixture content, including malformed source variants."""
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value, encoding='utf-8')

    def cli(self, *args, cwd=None):
        """Run the actual read-only CLI and retain both public output channels."""
        return subprocess.run([sys.executable, str(SCRIPT), *args], cwd=cwd or self.root, capture_output=True, text=True, timeout=20)

    def assert_error(self, code, *args):
        """Errors are complete structured diagnostics, with no success packet or input."""
        result = self.cli(*(args or ('VIG-1',)))
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertEqual(result.stderr, '')
        self.assertEqual(result.stdout.splitlines()[0], 'error: ' + quote(code))
        self.assertEqual(len(result.stdout.splitlines()), 2)
        self.assertNotIn('PRIVATE_SENTINEL', result.stdout)
        return result

    def test_exact_packet_and_repeat_are_literal_and_complete(self):
        """All own clauses survive, nested content stays, and both neighbors are absent."""
        expected = '\n'.join([
            'issue:', '  id: "VIG-1"', '  status: "Ready for implementation"',
            '  path: "spec/issues/one.md"', '  dependencies: "нет"', '  blockers: "нет"',
            '  contract: ' + quote(ISSUE),
            'context[1]{source,content}:', '  "docs/owner.md#selected",' + quote(EXPECTED_SECTION),
            'papercuts[0]{id,symptom,status,resolution}:',
            'sources[3]: "spec/WORK_ITEMS.md#реестр","spec/issues/one.md","docs/owner.md#selected"', '',
        ])
        first = self.cli('VIG-1')
        self.assertEqual((first.returncode, first.stdout, first.stderr), (0, expected, ''))
        self.assertEqual(self.cli('VIG-1').stdout, expected)
        self.assertEqual(self.cli('VIG-1', cwd=self.root / 'docs').stdout, expected)

    def test_unknown_id_never_guesses_prefix(self):
        """A similar known ID cannot satisfy an unknown exact ID."""
        self.assert_error('missing_id', 'VIG-10')

    def test_duplicate_files_and_duplicate_registry_entries(self):
        """Both file-level ambiguity and duplicate active memberships reject."""
        self.write('spec/issues/two.md', ISSUE)
        self.assert_error('duplicate_id')
        (self.root / 'spec/issues/two.md').unlink()
        self.write('spec/WORK_ITEMS.md', REGISTRY.replace('## Next', '| [VIG-1](issues/one.md) | `Ready for implementation` |\n\n## Next'))
        self.assert_error('catalog')

    def test_absent_and_stale_membership(self):
        """An orphan or mismatched registry path/status is not an active issue."""
        for value in [REGISTRY.replace('[VIG-1: Literal contract]', '[VIG-11]'), REGISTRY.replace('(issues/one.md)', '(issues/missing.md)'), REGISTRY.replace('`Ready for implementation`', '`Draft`')]:
            with self.subTest(registry=value):
                self.write('spec/WORK_ITEMS.md', value)
                self.assert_error('catalog')

    def test_catalog_notes_cannot_supply_the_current_status(self):
        """Only the status cell establishes registry state, never a historical note."""
        self.write('spec/WORK_ITEMS.md', REGISTRY.replace('| `Ready for implementation` |', '| `Draft` | previous: `Ready for implementation` |'))
        self.assert_error('catalog')

    def test_malformed_metadata_is_safe(self):
        """Conflicting IDs, absent/duplicate status and malformed edges cannot succeed."""
        for value in [
            ISSUE.replace('`VIG-1`', '`VIG-2`'),
            ISSUE.replace('- **Статус:** Ready for implementation\n', ''),
            ISSUE.replace('Ready for implementation', 'PRIVATE_SENTINEL'),
            ISSUE.replace('- **Статус:**', '- **Статус:** Draft\n- **Статус:**'),
            ISSUE.replace('**Зависит от:** нет', '**Зависит от:** PRIVATE_SENTINEL'),
            ISSUE.replace('- **Блокирует:** нет\n', ''),
        ]:
            with self.subTest(issue=value):
                self.write('spec/issues/one.md', value)
                self.assert_error('metadata')

    def test_both_catalog_id_spellings_are_supported(self):
        """A heading-only or metadata-only ID is sufficient in the documented format."""
        for value in [ISSUE.replace('- **ID:** `VIG-1`\n', ''), ISSUE.replace('# VIG-1: Literal contract', '# Literal contract')]:
            self.write('spec/issues/one.md', value)
            self.assertEqual(self.cli('VIG-1').returncode, 0)

    def test_missing_file_anchor_and_fenced_anchor(self):
        """Unreadable paths and non-existent or example-only anchors fail separately."""
        for source, error in [('docs/missing.md#selected', 'source_file'), ('docs/owner.md#absent', 'missing_anchor'), ('docs/owner.md#fake-boundary', 'missing_anchor')]:
            with self.subTest(source=source):
                self.write('spec/issues/one.md', ISSUE.replace('docs/owner.md#selected', source))
                self.assert_error(error)

    def test_malformed_context_declarations(self):
        """Missing sections, approximate paths, duplicate/overlapping sources reject."""
        for declaration in [
            '- docs/owner.md#selected', '- `docs/owner.md`', '- `docs/*.md#selected`',
            '- `../outside.md#selected`', '- `/tmp/outside.md#selected`', '- `docs/owner.md#`',
            '- `docs/owner.md#selected`\n- `docs/owner.md#selected`',
            '- `docs/owner.md#selected`\n- `docs/owner.md#nested`',
            '- `spec/issues/one.md#результат`', '', 'нет\n- `docs/owner.md#selected`',
            '- `docs/owner\x00.md#selected`',
        ]:
            with self.subTest(declaration=declaration):
                self.write('spec/issues/one.md', ISSUE.replace('- `docs/owner.md#selected`', declaration))
                self.assert_error('context_declaration')
        self.write('spec/issues/one.md', ISSUE.replace('## Context sources', '## Other'))
        self.assert_error('context_declaration')

    def test_whole_document_and_duplicate_explicit_anchors_reject(self):
        """Broad context and ambiguous explicit ownership are repaired by the author."""
        self.write('spec/issues/one.md', ISSUE.replace('#selected`', '#owner`'))
        self.assert_error('broad_source')
        self.write('spec/issues/one.md', ISSUE)
        self.write('docs/owner.md', OWNER + '\n<a id="selected"></a>\n\n## Duplicate\n')
        self.assert_error('ambiguous_anchor')

    def test_unicode_repeated_and_setext_headings(self):
        """Literal Unicode slugs and suffixes select distinct complete source sections."""
        self.write('docs/owner.md', '# Owner\n\n## Проверка *точно*\n\nfirst\n\n## Проверка *точно*\n\nsecond\n\nSetext\n------\nthird\n\n## End\nexcluded\n')
        for anchor, expected in [('проверка-точно', '## Проверка *точно*\n\nfirst\n\n'), ('проверка-точно-1', '## Проверка *точно*\n\nsecond\n\n'), ('setext', 'Setext\n------\nthird\n\n')]:
            with self.subTest(anchor=anchor):
                self.write('spec/issues/one.md', ISSUE.replace('#selected`', '#' + anchor + '`'))
                result = self.cli('VIG-1')
                self.assertEqual(result.returncode, 0, result.stdout)
                self.assertIn('  "docs/owner.md#' + anchor + '",' + quote(expected) + '\n', result.stdout)

    def test_thematic_break_after_atx_does_not_empty_the_section(self):
        """An ATX heading followed by a rule owns the full text until its next peer."""
        self.write('docs/owner.md', '# Owner\n\n## Selected\n---\n\nRequired exact text.\n\n## Next\nExcluded.\n')
        result = self.cli('VIG-1')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('  "docs/owner.md#selected",' + quote('## Selected\n---\n\nRequired exact text.\n\n') + '\n', result.stdout)

    def test_long_heading_preserves_content_without_parser_stall(self):
        """A legal heading with a long internal gap completes at the real CLI boundary."""
        section = '<a id="selected"></a>\n\n## Start' + ' ' * 6000 + 'End\n\nRequired literal.\n\n'
        self.write('docs/owner.md', '# Owner\n\n' + section + '## Next\nExcluded.\n')
        result = subprocess.run([sys.executable, str(SCRIPT), 'VIG-1'], cwd=self.root,
                                capture_output=True, text=True, timeout=3)
        self.assertEqual((result.returncode, result.stderr), (0, ''))
        self.assertIn('  "docs/owner.md#selected",' + quote(section) + '\n', result.stdout)

    def test_heading_and_fence_delimiters_preserve_exact_selection(self):
        """Whitespace, closing hashes and fence lengths keep the existing section boundary."""
        for heading in ['## Chosen ###   ', '  ##\tChosen##', '##   Chosen\t']:
            with self.subTest(heading=heading):
                section = heading + '\n\n~~~language\n## Fake\n~~~~\n\nRequired literal.\n\n'
                self.write('spec/issues/one.md', ISSUE.replace('#selected`', '#chosen`'))
                self.write('docs/owner.md', '# Owner\n\n' + section + '## Next\nExcluded.\n')
                result = self.cli('VIG-1')
                self.assertEqual(result.returncode, 0, result.stdout)
                self.assertIn('  "docs/owner.md#chosen",' + quote(section) + '\n', result.stdout)

    def test_explicit_anchor_cannot_skip_fenced_content(self):
        """Only actual blank lines may separate a selector ID from its heading."""
        self.write('docs/owner.md', '# Owner\n\n<a id="selected"></a>\n\n```text\nUnrelated code.\n```\n\n## Next\nUnrelated section.\n')
        self.assert_error('missing_anchor')

    def test_explicit_anchor_before_setext_preserves_complete_section(self):
        """An adjacent explicit ID selects a Setext heading without losing its title."""
        self.write('docs/owner.md', '# Owner\n\n<a id="selected"></a>\n\nChosen\n------\n\nExact text.\n\n## Next\nExcluded.\n')
        result = self.cli('VIG-1')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('  "docs/owner.md#selected",' + quote('<a id="selected"></a>\n\nChosen\n------\n\nExact text.\n\n') + '\n', result.stdout)

    def test_empty_context_does_not_read_any_journal(self):
        """Missing, malformed and blocking FIFO journals are untouched without tags."""
        self.write('spec/issues/one.md', ISSUE.replace('- `docs/owner.md#selected`', 'нет'))
        self.assertEqual(self.cli('VIG-1').returncode, 0)
        self.write('.papercuts.jsonl', 'PRIVATE_SENTINEL')
        self.assertEqual(self.cli('VIG-1').returncode, 0)
        import os
        (self.root / '.papercuts.jsonl').unlink()
        os.mkfifo(self.root / '.papercuts.jsonl')
        result = self.cli('VIG-1')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertNotIn('.papercuts.jsonl', result.stdout)

    def cut(self, identifier, text, tags, severity='minor', timestamp='2026-09-01T00:00:00.000Z'):
        """Create explicit journal events accepted by the existing pinned CLI."""
        return {'kind': 'cut', 'id': identifier, 'ts': timestamp, 'agent': 'fixture', 'text': text, 'tags': tags, 'severity': severity, 'cwd': '/tmp', 'repo': None}

    def test_exact_tags_use_canonical_order_before_five_limit(self):
        """Real folding includes open/resolved matches, union tags and ordering ties."""
        self.write('spec/issues/one.md', ISSUE + '\n## Papercut tags\n\n- `tooling`\n- `docs`\n')
        events = [
            self.cut('pc_000000000007', 'excluded-sixth', ['tooling']),
            self.cut('pc_000000000006', 'fifth', ['docs']),
            self.cut('pc_000000000005', 'fourth', ['tooling', 'docs']),
            self.cut('pc_000000000004', 'third', ['tooling']),
            self.cut('pc_000000000003', 'second', ['docs'], timestamp='2026-09-02T00:00:00.000Z'),
            self.cut('pc_000000000002', 'first', ['tooling'], severity='major'),
            self.cut('pc_000000000001', 'substring-excluded', ['tooling-extra'], severity='blocker'),
            {'kind': 'resolve', 'id': 'pc_000000000002', 'ts': '2026-09-03T00:00:00.000Z', 'agent': 'fixture', 'note': 'literal repair'},
        ]
        expected = '''papercuts[5]{id,symptom,status,resolution}:
  "pc_000000000002","first","resolved","literal repair"
  "pc_000000000003","second","open",null
  "pc_000000000004","third","open",null
  "pc_000000000005","fourth","open",null
  "pc_000000000006","fifth","open",null
'''
        for order in [events, list(reversed(events))]:
            self.write('.papercuts.jsonl', ''.join(json.dumps(event) + '\n' for event in order))
            result = self.cli('VIG-1')
            self.assertEqual(result.returncode, 0, result.stdout)
            self.assertIn(expected, result.stdout)
            self.assertNotIn('substring-excluded', result.stdout)
            self.assertNotIn('excluded-sixth', result.stdout)
            self.assertTrue(result.stdout.endswith(',".papercuts.jsonl"\n'))

    def test_malformed_journal_cases_never_yield_partial_packet(self):
        """Skipped/torn/duplicate/orphan/invalid events are errors even off-tag."""
        self.write('spec/issues/one.md', ISSUE + '\n## Papercut tags\n\n- `tooling`\n')
        good = json.dumps(self.cut('pc_000000000001', 'literal', ['unrelated'])) + '\n'
        for suffix in ['PRIVATE_SENTINEL\n', '{"kind":', '{"kind":"future"}\n', good, '{"kind":"cut","text":"PRIVATE_SENTINEL"}\n', '{"kind":"resolve","id":"pc_111111111111","ts":"2026-09-01T00:00:00Z","agent":"fixture","note":null}\n']:
            with self.subTest(suffix=suffix):
                self.write('.papercuts.jsonl', good + suffix)
                self.assert_error('papercuts_malformed')

    def test_no_tag_matches_is_an_explicit_success(self):
        """An intact journal without exact matches produces an empty result."""
        self.write('spec/issues/one.md', ISSUE + '\n## Papercut tags\n\n- `tooling`\n')
        self.write('.papercuts.jsonl', json.dumps(self.cut('pc_000000000001', 'excluded', ['Tooling'])) + '\n')
        result = self.cli('VIG-1')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('papercuts[0]{id,symptom,status,resolution}:', result.stdout)

    def test_unreadable_journal_is_distinct_from_malformed_events(self):
        """A declared journal must exist and be a readable UTF-8 regular file."""
        self.write('spec/issues/one.md', ISSUE + '\n## Papercut tags\n\n- `tooling`\n')
        self.assert_error('papercuts_unreadable')
        self.write('.papercuts.jsonl', '')
        (self.root / '.papercuts.jsonl').write_bytes(b'\xffPRIVATE_SENTINEL')
        self.assert_error('papercuts_unreadable')

    def test_child_membership_and_wrapped_edges(self):
        """A child is active through its registered epic; links retain exact values."""
        epic = '# EPIC-1: Parent\n\n**ID:** `EPIC-1`\n**Статус:** In progress\n\n## Дочерние issues\n\n- [ ] [VIG-1: Child](../issues/epic_1/one.md) - `Ready for implementation`\n'
        self.write('spec/epics/parent.md', epic)
        child = ISSUE.replace('- **ID:**', '- **Epic:** [EPIC-1](../../epics/parent.md)\n- **ID:**').replace('**Зависит от:** нет', '**Зависит от:**\n  [VIG-2](../two.md)')
        self.write('spec/issues/epic_1/one.md', child)
        self.write('spec/issues/two.md', ISSUE.replace('VIG-1', 'VIG-2').replace('Ready for implementation', 'Done'))
        (self.root / 'spec/issues/one.md').unlink()
        self.write('spec/WORK_ITEMS.md', REGISTRY.replace('[VIG-1: Literal contract](issues/one.md)', '[EPIC-1: Parent](epics/parent.md)').replace('`Ready for implementation`', '`In progress`'))
        result = self.cli('VIG-1')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('  dependencies: "\\n  [VIG-2](../two.md)"', result.stdout)
        self.assertIn('"spec/epics/parent.md"', result.stdout)
        for invalid in [
            epic.replace('Ready for implementation', 'Draft'),
            epic.replace(' - `Ready for implementation`', ' - `Draft` previous: `Ready for implementation`'),
            epic.replace('- [ ]', '- [x]'),
            epic.replace('VIG-1: Child', 'VIG-2: Child'),
            epic.replace('## Дочерние issues', '## Unrelated checklist'),
        ]:
            self.write('spec/epics/parent.md', invalid)
            self.assert_error('catalog')

    def test_symlink_outside_and_invalid_utf8_fail_safely(self):
        """Repository declarations cannot read external files or invalid text."""
        path = self.root / 'docs/owner.md'
        path.write_bytes(b'\xffPRIVATE_SENTINEL')
        self.assert_error('source_file')
        path.unlink()
        path.symlink_to('/etc/hosts')
        self.assert_error('source_file')

    def test_usage_validates_before_repository_access(self):
        """Unknown flags, multiple IDs and no ID have bounded actionable usage errors."""
        for args in [(), ('--wat',), ('VIG-1', '--full'), ('VIG-1', 'VIG-2'), ('EPIC-1',), ('vig-1',)]:
            result = self.cli(*args)
            self.assertEqual(result.returncode, 2, result.stdout)
            self.assertTrue(result.stdout.startswith('error: "usage"\nhelp: '))
        self.assertEqual(self.cli('--help').returncode, 0)


if __name__ == '__main__':
    unittest.main()
