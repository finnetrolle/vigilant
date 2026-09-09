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


class CheckRunTest(unittest.TestCase):
    """Test result integrity, selected inputs and actual process lifecycles."""

    def setUp(self):
        """Create a real Git fixture without relying on the user's configuration."""
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
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
        result = subprocess.run([sys.executable, str(RUNNER), *args], cwd=self.root, capture_output=True, text=True, timeout=15, env=env)
        fields = {}
        for line in result.stdout.splitlines():
            key, separator, value = line.partition(': ')
            if separator:
                fields[key] = json.loads(value)
        return result.returncode, fields

    def start(self, code, *options):
        """Start a real detached command; the launching CLI must already have exited."""
        status, fields = self.cli('start', '--label', 'fixture', *options, '--', sys.executable, '-c', code)
        self.assertEqual(status, 0, fields)
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
        wrapper = "import fcntl,runpy,sys; from pathlib import Path; real=fcntl.flock; fcntl.flock=lambda fd,op: (Path('build/probe').touch(),real(fd,op))[1]; sys.argv=[sys.argv[1], '_supervise', sys.argv[2]]; runpy.run_path(sys.argv[0],run_name='__main__')"
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
