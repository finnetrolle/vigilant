"""Prove the real shell pipeline's argv and artifacts using an external Gradle fixture."""

import json
import os
import shutil
import subprocess
import unittest

import test_check_run as runner_tests


class PipelineVerifyTest(runner_tests.CheckRunFixture):
    """Use the canonical Git/runner fixture and observe actual shell child invocations."""

    def setUp(self):
        """Install real scripts and a deterministic executable at the Gradle boundary."""
        super().setUp()
        self.environment = {**os.environ, 'GRADLE_USER_HOME': str(self.root / 'build/gradle-home')}
        for directory in ('scripts', 'config', 'gradle', 'buildSrc', 'docs', 'spec'):
            (self.root / directory).mkdir()
            (self.root / directory / 'input').write_text('fixture')
        for name in ('check-run', 'check_run_evidence.py', 'pipeline-verify'):
            shutil.copy2(runner_tests.RUNNER.parent / name, self.root / 'scripts' / name)
        self.runner = self.root / 'scripts/check-run'
        for name in ('settings.gradle.kts', 'gradlew.bat', 'README.md', 'CLAUDE.md', 'AGENTS.md'):
            (self.root / name).write_text('fixture')
        (self.root / 'build.gradle.kts').write_text('failBuildOnCVSS = 9.0\n')
        (self.root / 'build/tool').write_text('installed-toolchain')
        (self.root / 'gradlew').write_text('''#!/usr/bin/env python3
from pathlib import Path
import sys
p = Path('build/invocations')
with p.open('a') as log:
    log.write(' '.join(sys.argv[1:]) + '\\n')
if sys.argv[1:] == ['verifyAll', 'dependencyCheckAnalyze', '--rerun']:
    for directory in ('test-results/test', 'test-results/processTest', 'test-results/workItemValidatorTest',
                      'reports/tests/test', 'reports/tests/processTest'):
        target = Path('build') / directory
        target.mkdir(parents=True, exist_ok=True)
        (target / 'proof').write_text('abc')
    for name in ('reports/detekt/detekt.xml', 'reports/dependency-check/dependency-check-report.html'):
        target = Path('build') / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text('abc')
''')
        (self.root / 'gradlew').chmod(0o755)
        # Python imports are build-independent outputs in the real repository too.
        (self.root / '.gitignore').write_text('build/\n__pycache__/\n')

    def pipeline(self, *extra):
        """Dispatch the real wrapper, returning its single durable evidence ID."""
        result = subprocess.run(['./scripts/pipeline-verify', '--snapshot', 'pipeline-fixture',
                                 '--tool', 'build/tool', *extra], cwd=self.root,
                                capture_output=True, text=True, timeout=15, env=self.environment)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        fields = {key: json.loads(value) for key, _, value in
                  (line.partition(': ') for line in result.stdout.splitlines())}
        self.runs.append(fields['run'])
        return fields

    def test_one_final_gate_artifacts_and_reuse(self):
        """Exactly detekt then verifyAll execute; replay dispatch leaves invocation bytes unchanged."""
        fields = self.pipeline()
        identifier = fields['run']
        self.assertEqual(fields['evidence'], 'new')
        self.assertEqual(self.cli('wait', identifier, '--seconds', '10')[1]['state'], 'passed')
        self.assertEqual((self.root / 'build/invocations').read_text(), 'detekt\nverifyAll dependencyCheckAnalyze --rerun\n')
        self.assertEqual(self.cli('status', identifier)[1]['applicability'], 'current')
        again = self.pipeline()
        self.assertEqual((again['run'], again['evidence']), (identifier, 'reused'))
        self.assertEqual((self.root / 'build/invocations').read_text(), 'detekt\nverifyAll dependencyCheckAnalyze --rerun\n')
        (self.root / 'build/reports/dependency-check/dependency-check-report.html').unlink()
        self.assertEqual(self.cli('status', identifier)[1]['state'], 'corrupt')

    def test_shared_build_input_invalidates_the_full_gate(self):
        """The full pipeline loses reuse when its actual shared build file changes."""
        identifier = self.pipeline()['run']
        self.cli('wait', identifier, '--seconds', '10')
        (self.root / 'build.gradle.kts').write_text('failBuildOnCVSS = 8.0\n')
        self.assertEqual(self.cli('status', identifier)[1]['state'], 'stale')
        second = self.pipeline('--wave', '1')
        self.assertEqual(second['evidence'], 'new')
        self.assertNotEqual(second['run'], identifier)
        self.cli('wait', second['run'], '--seconds', '10')
        self.assertEqual((self.root / 'build/invocations').read_text(),
                         'detekt\nverifyAll dependencyCheckAnalyze --rerun\ndetekt\nverifyAll dependencyCheckAnalyze --rerun\n')

    def test_external_data_gate_forces_task_execution(self):
        """A new verification session cannot inherit OWASP UP-TO-DATE from another task."""
        identifier = self.pipeline()['run']
        self.cli('wait', identifier, '--seconds', '10')
        self.assertEqual((self.root / 'build/invocations').read_text(),
                         'detekt\nverifyAll dependencyCheckAnalyze --rerun\n')

    def test_new_optional_gradle_configuration_invalidates_id_status(self):
        """Absence of every optional configuration file/tree remains a tracked input."""
        identifier = self.pipeline()['run']
        self.cli('wait', identifier, '--seconds', '10')
        for relative in ('gradle.properties', 'build/gradle-home/gradle.properties',
                         'build/gradle-home/init.d/new.gradle', 'build/gradle-home/init.gradle',
                         'build/gradle-home/init.gradle.kts'):
            with self.subTest(relative=relative):
                target = self.root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text('new configuration')
                try:
                    self.assertEqual(self.cli('status', identifier)[1]['state'], 'stale')
                finally:
                    target.unlink()
                    if 'init.d/' in relative:
                        target.parent.rmdir()

    def test_threshold_preflight_fails_without_gradle_execution(self):
        """An unconfigured OWASP gate keeps its real nonzero terminal exit."""
        (self.root / 'build.gradle.kts').write_text('report-only\n')
        identifier = self.pipeline()['run']
        _, result = self.cli('wait', identifier, '--seconds', '10')
        self.assertEqual((result['state'], result['exit_code']), ('failed', 2))
        self.assertFalse((self.root / 'build/invocations').exists())


if __name__ == '__main__':
    unittest.main()
