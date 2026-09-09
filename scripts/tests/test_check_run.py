"""Exercise the durable runner through its public CLI in isolated repositories."""

import fcntl
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest


RUNNER = Path(__file__).resolve().parents[1] / 'check-run'


class CheckRunFixture(unittest.TestCase):
    """Own isolated repositories and the canonical CLI/process observation helpers."""

    def setUp(self):
        """Create a real Git fixture without relying on the user's configuration."""
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.runner = RUNNER
        self.runs = []
        for args in [('init', '-q'), ('config', 'user.name', 'Runner test'), ('config', 'user.email', 'test@example.invalid')]:
            subprocess.run(['git', *args], cwd=self.root, check=True, capture_output=True)
        (self.root / '.gitignore').write_text('build/\n')
        (self.root / 'src').mkdir()
        (self.root / 'build').mkdir()
        (self.root / 'src/value').write_text('before')
        (self.root / 'guide.md').write_text('guide')
        subprocess.run(['git', 'add', '.'], cwd=self.root, check=True, capture_output=True)
        subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', 'commit', '-qm', 'fixture'], cwd=self.root, check=True, capture_output=True)

    def tearDown(self):
        """Request and observe cleanup for every started run before deleting files."""
        for identifier in self.runs:
            self.cli('cancel', identifier)
            self.cli('wait', identifier, '--seconds', '10')
        self.temp.cleanup()

    def cli(self, *args, env=None):
        """Read scalar TOON without interpreting human help or raw command output."""
        result = subprocess.run([sys.executable, str(self.runner), *args], cwd=self.root, capture_output=True, text=True, timeout=15,
                                env=env if env is not None else getattr(self, 'environment', None))
        fields = {}
        for line in result.stdout.splitlines():
            key, separator, value = line.partition(': ')
            if separator:
                fields[key] = json.loads(value)
        return result.returncode, fields

    def start(self, code, *options):
        """Start a real detached command; the launching CLI must already have exited."""
        status, fields = self.cli('start', '--label', 'fixture', *options, '--', sys.executable, '-c', code)
        self.assertEqual(status, 1 if fields.get('state') == 'infrastructure_failure' else 0, fields)
        self.runs.append(fields['run'])
        return fields['run']

    def record(self, identifier):
        """Read the exact final evidence produced by the supervisor."""
        return json.loads((self.root / '.git/check-runs' / identifier / 'result.json').read_text())

    def await_file(self, name):
        """Synchronize on a child-published observation, never a timing assumption."""
        deadline = time.monotonic() + 5
        path = self.root / name
        while not path.exists() and time.monotonic() < deadline:
            time.sleep(0.02)
        self.assertTrue(path.exists(), name)
        return path


class CheckRunTest(CheckRunFixture):
    """Test result integrity, selected inputs and actual process lifecycles."""

    def test_detached_success_failure_and_private_logs(self):
        """A dead launching terminal does not lose exact exit or command output."""
        ok = self.start("print('literal evidence')")
        status, result = self.cli('wait', ok, '--seconds', '10')
        self.assertEqual((status, result['state'], result['exit_code']), (0, 'passed', 0))
        path = self.root / '.git/check-runs' / ok
        self.assertEqual((path / 'command.log').read_text(), 'literal evidence\n')
        self.assertEqual(path.stat().st_mode & 0o077, 0)
        bad = self.start("import sys; print('failed evidence'); sys.exit(7)")
        status, result = self.cli('wait', bad, '--seconds', '10')
        self.assertEqual((status, result['state'], result['exit_code']), (1, 'failed', 7))

    def test_selected_inputs_new_files_and_environment_invalidate(self):
        """Evidence survives unrelated docs but not selected files or changed environment."""
        identifier = self.start('pass', '--input', 'src', '--env-key', 'CHECK_TEST_ENV')
        self.cli('wait', identifier, '--seconds', '10')
        (self.root / 'guide.md').write_text('changed unrelated docs')
        status, result = self.cli('status', identifier, '--check-inputs')
        self.assertEqual((status, result['inputs_current']), (0, True))
        status, result = self.cli('status', identifier, '--check-inputs', env={**os.environ, 'CHECK_TEST_ENV': 'changed'})
        self.assertEqual((status, result['state'], result['inputs_current']), (1, 'stale', False))
        (self.root / 'src/new').write_text('new untracked input')
        status, result = self.cli('status', identifier, '--check-inputs')
        self.assertEqual((status, result['state']), (1, 'stale'))

    def test_input_changes_during_check_cannot_pass(self):
        """A successful command against changing inputs is explicitly stale evidence."""
        identifier = self.start("from pathlib import Path; Path('src/value').write_text('during')")
        status, result = self.cli('wait', identifier, '--seconds', '10')
        self.assertEqual((status, result['state'], result['exit_code']), (1, 'stale', 0))
        self.assertFalse(self.record(identifier)['inputs_stable'])
        self.assertEqual(result['reason'], 'inputs_changed_during_run')
        self.assertIn('start a new check', result['error'])

    def test_artifacts_have_independent_digest_and_fail_closed(self):
        """The real CLI binds literal bytes and rejects each artifact mutation."""
        for mutation in ('missing', 'modified', 'symlink', 'parent_symlink', 'added'):
            with self.subTest(mutation=mutation):
                code = "from pathlib import Path; p=Path('build/reports'); p.mkdir(exist_ok=True); (p/'proof').write_text('abc')"
                identifier = self.start(code, '--artifact', 'directory:build/reports')
                self.assertEqual(self.cli('wait', identifier, '--seconds', '10')[1]['state'], 'passed')
                status, result = self.cli('status', identifier, '--check-inputs')
                self.assertEqual((status, result['applicability']), (0, 'current'))
                evidence = json.loads((self.root / '.git/check-runs' / identifier / 'artifacts.json').read_text())
                self.assertEqual(evidence[0]['digest']['proof']['sha256'],
                                 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
                proof = self.root / 'build/reports/proof'
                if mutation == 'missing':
                    proof.unlink()
                elif mutation == 'modified':
                    proof.write_text('changed')
                elif mutation == 'symlink':
                    proof.rename(self.root / 'build/original')
                    proof.symlink_to('../original')
                elif mutation == 'parent_symlink':
                    proof.parent.rename(self.root / 'build/old-reports')
                    proof.parent.symlink_to('old-reports', target_is_directory=True)
                else:
                    proof.with_name('extra').write_text('extra artifact')
                status, result = self.cli('status', identifier, '--check-inputs')
                self.assertEqual((status, result['state']), (1, 'corrupt'))
                self.assertIn('start a new check', result['error'])
                import shutil
                if proof.parent.is_symlink():
                    proof.parent.unlink()
                else:
                    shutil.rmtree(proof.parent)

    def test_partial_and_modified_records_never_pass(self):
        """Every sealed document and the terminal publication are required."""
        identifier = self.start('pass')
        self.cli('wait', identifier, '--seconds', '10')
        path = self.root / '.git/check-runs' / identifier
        for name in ('request.json', 'result.json', 'inputs-before.json', 'inputs-after.json', 'artifacts.json', 'seal.json'):
            original = (path / name).read_bytes()
            for corruption in (b'{}', b'{', None):
                with self.subTest(name=name, corruption=corruption):
                    if corruption is None:
                        (path / name).unlink()
                    else:
                        (path / name).write_bytes(corruption)
                    status, result = self.cli('status', identifier, '--check-inputs')
                    self.assertEqual((status, result['state']), (1, 'corrupt'))
                    self.assertNotIn('Traceback', str(result))
                    (path / name).write_bytes(original)

    def test_special_record_files_are_rejected_without_blocking(self):
        """A FIFO at any evidence document fails closed instead of blocking status."""
        identifier = self.start('pass', '--snapshot', 'fifo')
        self.cli('wait', identifier, '--seconds', '10')
        path = self.root / '.git/check-runs' / identifier
        for name in ('request.json', 'result.json', 'inputs-before.json', 'inputs-after.json', 'artifacts.json', 'seal.json'):
            with self.subTest(name=name):
                original = (path / name).read_bytes()
                (path / name).unlink()
                os.mkfifo(path / name)
                try:
                    self.assertEqual(self.cli('status', identifier)[1]['state'], 'corrupt')
                    self.assertEqual(self.cli('lookup', '--label', 'fixture', '--snapshot', 'fifo',
                                              '--', sys.executable, '-c', 'pass')[0], 1)
                finally:
                    (path / name).unlink()
                    (path / name).write_bytes(original)

    def test_exact_snapshot_reuse_and_cycle_metrics(self):
        """Repeated starts reuse one real execution; read-only lookup records no reuse."""
        code = "from pathlib import Path; p=Path('build/count'); p.write_text(p.read_text()+'x' if p.exists() else 'x')"
        options = ('--snapshot', 'issue-fixture', '--input', 'src', '--gate', 'full')
        identifier = self.start(code, *options)
        self.cli('wait', identifier, '--seconds', '10')
        for action in ('lookup', 'start', 'start'):
            status, result = self.cli(action, '--label', 'fixture', *options, '--', sys.executable, '-c', code)
            self.assertEqual((status, result['run'], result['applicability']), (0, identifier, 'current'))
        self.assertEqual((self.root / 'build/count').read_text(), 'x')
        _, metrics = self.cli('metrics', '--snapshot', 'issue-fixture')
        self.assertEqual((metrics['full_gates'], metrics['reused_evidence'], metrics['remediation_waves']), (1, 2, 0))
        self.assertGreater(metrics['tool_duration_seconds'], 0)
        self.assertEqual(metrics['wall_clock_savings'], 'unavailable')
        changed = self.start(code, '--snapshot', 'another-issue', '--input', 'src', '--gate', 'full')
        self.assertNotEqual(changed, identifier)
        self.cli('wait', changed, '--seconds', '10')
        self.assertEqual((self.root / 'build/count').read_text(), 'xx')

    def test_lookup_matches_command_environment_toolchain_and_artifact_contract(self):
        """An exact label alone cannot authorize a different consumer's contract."""
        (self.root / 'build/tool').write_text('tool-v1')
        options = ('--snapshot', 'contract', '--input', 'src', '--tool', 'build/tool', '--env-key', 'CHECK_TEST_ENV')
        identifier = self.start('pass', *options)
        self.cli('wait', identifier, '--seconds', '10')
        for variation in ('command', 'environment', 'toolchain', 'artifacts'):
            with self.subTest(variation=variation):
                command = 'print(1)' if variation == 'command' else 'pass'
                extra = ('--artifact', 'file:build/proof') if variation == 'artifacts' else ()
                env = {**os.environ, 'CHECK_TEST_ENV': 'never-store-this-value'} if variation == 'environment' else None
                if variation == 'toolchain':
                    (self.root / 'build/tool').write_text('tool-v2')
                status, result = self.cli('lookup', '--label', 'fixture', *options, *extra, '--', sys.executable, '-c', command, env=env)
                self.assertEqual((status, result['evidence']), (1, 'NOT RUN'))
                self.assertEqual(result['reason'], {'command': 'contract_changed', 'environment': 'environment',
                                                   'toolchain': 'toolchain', 'artifacts': 'contract_changed'}[variation])
                (self.root / 'build/tool').write_text('tool-v1')
        for path in (self.root / '.git/check-runs' / identifier).glob('*.json'):
            self.assertNotIn('never-store-this-value', path.read_text())

    def test_each_selected_input_mutation_and_docs_scope(self):
        """Changed/added/deleted inputs stale only the run whose declared scope includes them."""
        runtime = self.start('pass', '--snapshot', 'scope', '--input', 'src')
        self.cli('wait', runtime, '--seconds', '10')
        docs = self.start('pass', '--snapshot', 'scope-docs', '--input', 'guide.md', '--gate', 'review')
        self.cli('wait', docs, '--seconds', '10')
        (self.root / 'guide.md').write_text('remediation')
        self.assertEqual(self.cli('status', docs, '--check-inputs')[1]['state'], 'stale')
        self.assertEqual(self.cli('status', runtime, '--check-inputs')[1]['applicability'], 'current')
        for mutation in ('changed', 'added', 'deleted'):
            with self.subTest(mutation=mutation):
                target = self.root / ('src/new' if mutation == 'added' else 'src/value')
                if mutation == 'deleted':
                    target.unlink()
                else:
                    target.write_text('changed')
                self.assertEqual(self.cli('status', runtime, '--check-inputs')[1]['state'], 'stale')
                if mutation == 'added':
                    target.unlink()
                else:
                    target.write_text('before')
        (self.root / 'src/value').write_text('shared contract change')
        remediated = self.start('pass', '--snapshot', 'scope', '--input', 'src', '--wave', '1')
        self.cli('wait', remediated, '--seconds', '10')
        self.assertNotEqual(runtime, remediated)
        _, metrics = self.cli('metrics', '--snapshot', 'scope')
        self.assertEqual(metrics['remediation_waves'], 1)
        self.assertEqual(json.loads(metrics['invalidation_reasons']), {'files': 1, 'no_matching_record': 1})

    def test_running_failed_and_infrastructure_are_never_reusable(self):
        """Lookup is bounded, does not launch commands and preserves failure outcomes."""
        code = "from pathlib import Path; import time; Path('build/ready').touch(); time.sleep(20)"
        options = ('--snapshot', 'terminal', '--input', 'src')
        identifier = self.start(code, *options)
        self.await_file('build/ready')
        status, result = self.cli('lookup', '--label', 'fixture', *options, '--', sys.executable, '-c', code)
        self.assertEqual((status, result['reason']), (1, 'running'))
        self.cli('cancel', identifier)
        self.cli('wait', identifier, '--seconds', '10')
        self.assertEqual(self.cli('status', identifier, '--check-inputs')[1]['applicability'], 'cancelled')
        self.assertEqual(self.cli('lookup', '--label', 'fixture', *options, '--', sys.executable, '-c', code)[0], 1)
        for code in ('import sys; sys.exit(9)',):
            identifier = self.start(code, *options)
            self.cli('wait', identifier, '--seconds', '10')
            self.assertEqual(self.cli('status', identifier, '--check-inputs')[1]['applicability'], 'failed')
            status, result = self.cli('lookup', '--label', 'fixture', *options, '--', sys.executable, '-c', code)
            self.assertEqual((status, result['evidence']), (1, 'NOT RUN'))
            self.assertIn('failed', result['reason'])
        status, result = self.cli('start', '--label', 'missing', '--snapshot', 'terminal', '--', '/nonexistent-vig40-command')
        self.assertEqual(status, 0)
        self.runs.append(result['run'])
        self.cli('wait', result['run'], '--seconds', '10')
        self.assertEqual(self.cli('status', result['run'], '--check-inputs')[1]['applicability'], 'infrastructure_failure')
        status, result = self.cli('lookup', '--label', 'missing', '--snapshot', 'terminal', '--', '/nonexistent-vig40-command')
        self.assertEqual((status, result['evidence'], result['reason']), (1, 'NOT RUN', 'infrastructure_failure'))

    def test_single_repository_lease_prevents_command_overlap(self):
        """A second command cannot execute while the first holds the actual lease."""
        first = self.start("from pathlib import Path; import time; Path('build/ready').touch(); time.sleep(20)")
        self.await_file('build/ready')
        second = self.start("from pathlib import Path; Path('build/forbidden').touch()")
        status, result = self.cli('wait', second, '--seconds', '10')
        self.assertEqual((status, result['state']), (1, 'infrastructure_failure'))
        self.assertFalse((self.root / 'build/forbidden').exists())
        self.cli('cancel', first)
        self.assertEqual(self.cli('wait', first, '--seconds', '10')[1]['state'], 'cancelled')

    def test_clean_preserves_evidence_and_execution_lease(self):
        """Deleting build outputs cannot erase a live run or admit an overlapping one."""
        first = self.start("from pathlib import Path; import shutil,time; shutil.rmtree('build'); Path('build').mkdir(); Path('build/cleaned').touch(); time.sleep(20)")
        self.await_file('build/cleaned')
        self.assertTrue((self.root / '.git/check-runs' / first / 'result.json').is_file())
        second = self.start("from pathlib import Path; Path('build/forbidden').touch()")
        self.assertEqual(self.cli('wait', second, '--seconds', '10')[1]['state'], 'infrastructure_failure')
        self.assertFalse((self.root / 'build/forbidden').exists())
        self.cli('cancel', first)
        self.assertEqual(self.cli('wait', first, '--seconds', '10')[1]['state'], 'cancelled')
        third = self.start("print('after clean')")
        self.assertEqual(self.cli('wait', third, '--seconds', '10')[1]['state'], 'passed')

    def test_status_probe_cannot_prevent_supervisor_startup(self):
        """A transient reader lease delays a supervisor without losing the command."""
        identifier = self.start("from pathlib import Path; Path('build/executed').touch()")
        self.assertEqual(self.cli('wait', identifier, '--seconds', '10')[1]['state'], 'passed')
        path = self.root / '.git/check-runs' / identifier
        # Recreate the starting record with the same validated request, then hold
        # the exact reader lease at worker startup. A wrapper signals entry to
        # flock so this regression does not rely on a sleep/race winning.
        record = self.record(identifier)
        record.update(state='starting', created_at=time.time(), exit_code=None)
        (path / 'result.json').write_text(json.dumps(record))
        (self.root / 'build/executed').unlink()
        wrapper = "import fcntl,runpy,sys; from pathlib import Path; real=fcntl.flock; fcntl.flock=lambda fd,op: (Path('build/probe').touch(),real(fd,op))[1]; sys.path.insert(0,str(Path(sys.argv[1]).parent)); sys.argv=[sys.argv[1], '_supervise', sys.argv[2]]; runpy.run_path(sys.argv[0],run_name='__main__')"
        with (path / 'worker.lock').open('a') as lease:
            fcntl.flock(lease, fcntl.LOCK_EX)
            worker = subprocess.Popen([sys.executable, '-c', wrapper, str(RUNNER), identifier], cwd=self.root, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                self.await_file('build/probe')
            finally:
                fcntl.flock(lease, fcntl.LOCK_UN)
        self.assertEqual(worker.wait(timeout=10), 0)
        self.assertTrue((self.root / 'build/executed').exists())
        self.assertEqual(self.cli('status', identifier)[1]['state'], 'passed')

    def test_terminal_publication_wins_over_an_older_running_read(self):
        """A reader paused before lock probing must observe the published cancellation."""
        identifier = self.start("from pathlib import Path; import time; Path('build/ready').touch(); time.sleep(20)")
        self.await_file('build/ready')
        wrapper = '''import fcntl,runpy,sys,time
from pathlib import Path
real = fcntl.flock
clock = time.time
time.time = lambda: clock() + 10
def probe(fd, operation):
    Path('build/reader-ready').touch()
    deadline = time.monotonic() + 10
    while not Path('build/release-reader').exists() and time.monotonic() < deadline:
        time.sleep(0.02)
    return real(fd, operation)
fcntl.flock = probe
sys.path.insert(0, str(Path(sys.argv[1]).parent))
sys.argv = [sys.argv[1], 'status', sys.argv[2]]
runpy.run_path(sys.argv[0], run_name='__main__')
'''
        reader = subprocess.Popen([sys.executable, '-c', wrapper, str(self.runner), identifier],
                                  cwd=self.root, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self.await_file('build/reader-ready')
            self.cli('cancel', identifier)
            self.assertEqual(self.cli('wait', identifier, '--seconds', '10')[1]['state'], 'cancelled')
        finally:
            (self.root / 'build/release-reader').touch()
            stdout, stderr = reader.communicate(timeout=15)
        self.assertEqual(reader.returncode, 1, stdout + stderr)
        self.assertIn('applicability: "cancelled"', stdout)

    def test_special_worker_lock_is_bounded_corruption(self):
        """The status probe rejects unsafe lock files before trying to acquire a lease."""
        identifier = self.start("from pathlib import Path; import time; Path('build/ready').touch(); time.sleep(30)")
        self.await_file('build/ready')
        path = self.root / '.git/check-runs' / identifier
        lease = path / 'worker.lock'
        saved = path / 'worker.original'
        for kind in ('fifo', 'symlink'):
            with self.subTest(kind=kind):
                lease.rename(saved)
                if kind == 'fifo':
                    os.mkfifo(lease)
                else:
                    lease.symlink_to('worker.original')
                try:
                    self.assertEqual(self.cli('status', identifier)[1]['state'], 'corrupt')
                finally:
                    lease.unlink()
                    saved.rename(lease)

    def test_timeout_and_cancel_kill_owned_descendants_only(self):
        """TERM-resistant children are reclaimed while an unrelated process survives."""
        foreign = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'], start_new_session=True)
        self.addCleanup(lambda: self.stop_foreign(foreign))
        code = "import os, signal, subprocess, sys, time; from pathlib import Path; child=subprocess.Popen([sys.executable, '-c', 'import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(30)']); Path('build/child').write_text(str(child.pid)); signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(30)"
        for terminal in ('timeout', 'cancelled'):
            with self.subTest(terminal=terminal):
                child_file = self.root / 'build/child'
                child_file.unlink(missing_ok=True)
                timeout = '0.5' if terminal == 'timeout' else '30'
                identifier = self.start(code, '--timeout', timeout)
                child = int(self.await_file('build/child').read_text())
                if terminal == 'cancelled':
                    self.cli('cancel', identifier)
                status, result = self.cli('wait', identifier, '--seconds', '10')
                self.assertEqual((status, result['state']), (1, terminal))
                self.assertEqual(self.cli('status', identifier)[1]['applicability'], terminal)
                status, result = self.cli('lookup', '--label', 'fixture', '--snapshot', identifier,
                                          '--timeout', timeout, '--', sys.executable, '-c', code)
                self.assertEqual((status, result['evidence'], result['reason']), (1, 'NOT RUN', terminal))
                self.assertIsNone(foreign.poll())
                # An exited orphan may briefly be a zombie, but cannot execute.
                process = subprocess.run(['ps', '-o', 'stat=', '-p', str(child)], capture_output=True, text=True)
                self.assertTrue(not process.stdout.strip() or process.stdout.strip().startswith('Z'), process.stdout)

    def stop_foreign(self, process):
        """Clean the separately owned control process without touching runner groups."""
        if process.poll() is None:
            process.terminate()
        process.wait(timeout=5)

    def test_invalid_arguments_are_rejected_before_launch(self):
        """Typos, unbounded deadlines and nonexistent input scopes cannot start work."""
        for args in [
            ('start', '--label', 'x', '--timeot', '3', '--', 'true'),
            ('start', '--label', 'x', '--timeout', 'nan', '--', 'true'),
            ('start', '--label', 'x', '--input', 'missing', '--', 'true'),
            ('start', '--label', 'x', '--input', '../outside', '--', 'true'),
            ('start', '--label', 'x', 'true'),
        ]:
            status, result = self.cli(*args)
            self.assertEqual(status, 2, result)
            self.assertIn('error', result)
        self.assertFalse((self.root / '.git/check-runs').exists())


if __name__ == '__main__':
    unittest.main()
