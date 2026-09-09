"""Reproduce the three-sample context baseline; never calculate a semantic verdict."""

import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[2]
AGENT_GUIDES = ('AGENTS.md', 'CLAUDE.md')
JOURNAL_PATH = '.papercuts.jsonl'
SAMPLES = {
    'VIG-33': 'spec/issues/issue_33_availability_slo_and_operations.md',
    'VIG-39': 'spec/issues/issue_39_compact_task_context.md',
    'VIG-40': 'spec/issues/issue_40_reusable_verification_evidence.md',
}


def git_source(revision, name):
    """Read exact versioned input with no checkout, archive or worktree mutation."""
    return subprocess.check_output(['git', 'show', f'{revision}:{name}'], cwd=ROOT).decode('utf-8')


def measure(parts):
    """Count raw UTF-8 bytes and whitespace-separated words in decoded input text."""
    return (sum(len(part.encode('utf-8')) for part in parts), sum(len(part.split()) for part in parts))


def inspect_packet(output, expected_issue):
    """Decode the small public TOON schema, independently of production extraction."""
    decoded, context, internal = [], [], False
    for line in output.splitlines():
        if line.startswith('  contract: '):
            assert json.loads(line.partition(': ')[2]) == expected_issue
        if line.startswith('  ') and ': ' in line and not line.startswith('  "'):
            decoded.append(json.loads(line.partition(': ')[2]))
        elif line.startswith('context['):
            internal = True
        elif line.startswith('papercuts['):
            internal = False
        elif line.startswith('  "'):
            # A JSON decoder handles escaped commas, quotes and newline scalars.
            remainder = line.strip()
            values = []
            while remainder:
                value, end = json.JSONDecoder().raw_decode(remainder)
                values.append(value)
                remainder = remainder[end:].lstrip(',')
            decoded.extend(str(value) for value in values if value is not None)
            if internal:
                context.append(values[1])
    return context, len(output.split())


def main():
    """Measure a versioned before guide and current routed inputs in temporary repos."""
    if len(sys.argv) != 3:
        raise SystemExit('Usage: python3 scripts/tests/measure_task_context.py <before-revision> <issue-source-revision>')
    before, source = sys.argv[1:]
    old_guides = [git_source(before, name) for name in [*AGENT_GUIDES, 'spec/WORK_ITEMS.md']]
    routed = [(ROOT / name).read_text() for name in [*AGENT_GUIDES, 'docs/agent-workflow.md']]
    columns = 'id,before_bytes,before_words,routed_bytes,routed_words,packet_bytes,packet_words,after_bytes,after_words,saved_bytes,saved_words,internal_tool_bytes,internal_tool_words'
    rows = []
    with tempfile.TemporaryDirectory() as temporary:
        fixture = Path(temporary)
        subprocess.run(['git', 'init', '-q', str(fixture)], check=True)
        for directory in ['spec', 'docs']:
            shutil.copytree(ROOT / directory, fixture / directory)
        for name in [*AGENT_GUIDES, JOURNAL_PATH]:
            shutil.copyfile(ROOT / name, fixture / name)
        # Restore every removed sample from the explicit source revision only.
        for name in SAMPLES.values():
            target = fixture / name
            if not target.exists():
                target.write_text(git_source(source, name))
        registry = '# Measurement catalog\n\n## Реестр\n\n| Work item | Статус |\n|---|---|\n'
        for identifier, name in SAMPLES.items():
            status = 'Draft' if identifier == 'VIG-33' else 'Ready for implementation'
            registry += f'| [{identifier}]({name.removeprefix("spec/")}) | `{status}` |\n'
        (fixture / 'spec/WORK_ITEMS.md').write_text(registry)
        for identifier, name in SAMPLES.items():
            output = subprocess.check_output([sys.executable, str(ROOT / 'scripts/task-context'), identifier], cwd=fixture).decode('utf-8')
            selected, packet_words = inspect_packet(output, (fixture / name).read_text())
            old_inputs = old_guides + [git_source(before, name)] + selected
            tool_output = ''
            if identifier != 'VIG-33':
                old_inputs.append(git_source(before, JOURNAL_PATH))
                tool_output = subprocess.check_output([str(ROOT / 'scripts/papercuts'), '--file', str(fixture / JOURNAL_PATH), 'list', '--status', 'all', '--limit', '2147483647']).decode('utf-8')
                # Normalize only the temporary-root metadata, not the journal data.
                tool_output = tool_output.replace(str(fixture), '<fixture>')
            old_bytes, old_words = measure(old_inputs)
            routed_bytes, routed_words = measure(routed)
            packet_bytes = len(output.encode('utf-8'))
            after_bytes, after_words = routed_bytes + packet_bytes, routed_words + packet_words
            internal_bytes, internal_words = measure([tool_output])
            rows.append([identifier, old_bytes, old_words, routed_bytes, routed_words, packet_bytes, packet_words, after_bytes, after_words, old_bytes-after_bytes, old_words-after_words, internal_bytes, internal_words])
    print(f'samples[{len(rows)}]{{{columns}}}:')
    for row in rows:
        print('  ' + ','.join(map(str, row)))


if __name__ == '__main__':
    main()
