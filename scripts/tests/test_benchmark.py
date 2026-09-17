"""Observe the public benchmark command against real supervisor/process boundaries."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import unittest

import test_check_run as runner_tests


GRADLE_FIXTURE = r'''#!/usr/bin/env python3
import itertools, json, os, pathlib, sys, time
args = sys.argv[1:]
task = next(x for x in args if not x.startswith('-'))
with pathlib.Path('build/invocations').open('a') as log:
    log.write(task + '\n')
mode = os.environ.get('BENCH_FIXTURE_MODE', '')
if task == 'testClasses' or task == 'installDist':
    pathlib.Path('build/gateway-environment.json').write_text(json.dumps({k:v for k,v in os.environ.items() if k.startswith('VIGILANT_')}))
    sys.exit(3 if mode == 'prepare-failure' else 0)
out = pathlib.Path(next(x.split('=', 1)[1] for x in args if x.startswith('-PbenchmarkOutputRoot=')))
if mode == 'hold' and task == 'piiQualityReport':
    pathlib.Path('build/child.pid').write_text(str(os.getpid()))
    while True: time.sleep(.1)
if task == 'piiQualityReport' and mode == 'failure': sys.exit(7)
if task == 'piiQualityReport' and mode == 'mutate-input': pathlib.Path('src/value').write_text('changed during run')
score = {'exact': {'truePositives': 2, 'falsePositives': 1, 'falseNegatives': 0,
                   'precision': 2/3, 'recall': 1, 'f1': .8},
         'relaxed': {'truePositives': 2, 'falsePositives': 0, 'falseNegatives': 0,
                     'precision': 1, 'recall': 1, 'f1': 1}}
paths = {
 'piiQualityReport': ('pii/canonical/pii-quality-report', {'releaseGate': True, 'corpus': {'version': 'fixture'}, 'metrics': {'aggregate': score}}),
 'redMadRobotPiiBenchmark': ('pii/redmadrobot/redmadrobot-pii-benchmark', {k: {'metrics': {'aggregate': score}} for k in ('sourceAligned','productAligned','nestedIpAligned')}),
 'hiveTracePiiBenchmark': ('pii/hivetrace/hivetrace-pii-benchmark', {'partitions': {'Full': {'sourceAligned': {'aggregate': score}, 'productAligned': {'aggregate': score}, 'cleanFpr': {'numerator':0,'denominator':4,'ratio':0}}}}),
 'advPiiBenchmark': ('pii/advpii/advpii-benchmark', {'documentFalsePositiveRates': {'negative': {'numerator':0, 'denominator':4, 'ratio':0}}, 'subsets': {k: {'suppressed': False, 'data': {'micro': {'sourceAligned': score}, 'fewShotPrecisionCaveat': k == 'combined'}} for k in ('baseline','pii_only','combined','negative','hard_negative')}}),
 'piiQualityQualification': ('pii/qualification/pii-quality-qualification', {'passed': mode != 'qualification-failure'}),
 'perfTest': ('perf-01/summary', {'passed': mode != 'deviation', 'fullProfile': mode != 'smoke', 'verdict': 'DEVIATION' if mode == 'deviation' else 'PASS'}),
 'inspectionLoadTest': ('inspection/load/summary', {'passed': True, 'fullProfile': True, 'verdict': 'PASS'}),
 'inspectionResourceQualification': ('inspection/resource-qualification/summary', {'passed': True, 'fullProfile': True, 'verdict': 'PASS'})}
if task in ('piiJmhBaseline', 'inspectionPhaseBenchmark'):
    if task == 'piiJmhBaseline':
        cases = itertools.product(('ASCII','RUSSIAN','MIXED_UNICODE'),('1024','65536','1048576'),('NO_MATCH_STOP_ON_FIRST','EARLY_EMAIL','PHONE_NUMBER','PAYMENT_CARD','IP_ADDRESS','IBAN','RU_INN','RU_SNILS','RU_PASSPORT','RU_OMS','NO_MATCH_FULL_SCAN','FULL_SCAN'))
        rows = [('io.vigilant.detectors.pii.fast.FastPiiDetectorBenchmark.detect', dict(zip(('dataset','sizeBytes','scenario'), case))) for case in cases]
        name = 'pii/jmh/baseline'
    else:
        rows = [('io.vigilant.perf.InspectionPipelineBenchmark.'+phase, {'sizeBytes': size}) for phase,size in itertools.product(('parsing','windowing','policyEvaluation','totalInspection'),('1024','65536'))]
        name = 'inspection/phase/results'
    data = [{'benchmark': b, 'params': p, 'mode': 'sample', 'forks': 2, 'warmupIterations': 3, 'measurementIterations': 5, 'primaryMetric': {'scoreUnit': 'us/op', 'scorePercentiles': {'50.0':1,'95.0':2,'99.0':3}}} for b,p in rows]
    if mode == 'partial-jmh': data.pop()
else: name,data = paths[task]
def write(name, value):
    path = out/name; path.parent.mkdir(parents=True,exist_ok=True); path.write_text(value)
if not (mode == 'missing' and task == 'hiveTracePiiBenchmark'):
    write('reports/'+name+'.json', json.dumps(data))
    write('reports/'+name+'.md', 'fixture human report')
if task == 'piiJmhBaseline':
    write('reports/pii/jmh/baseline.txt','fixture'); write('reports/pii/jmh/environment.properties','fixture')
if task == 'inspectionPhaseBenchmark': write('reports/inspection/phase/summary.md','fixture')
if task == 'perfTest':
    write('reports/perf-01/latest-summary.md','fixture'); write('perf-processes/gateway.jfr','fixture'); write('perf-processes/slow-sink-gateway.jfr','fixture')
if task in ('perfTest','inspectionLoadTest'): write('reports/gatling/fixture/index.html','fixture')
'''


class BenchmarkTest(runner_tests.CheckRunFixture):
    """Exercise ordering, verdicts, isolation and cancellation via the shipped CLI."""

    def setUp(self):
        super().setUp()
        (self.root / 'scripts').mkdir()
        for name in ('check-run', 'check_run_evidence.py', 'benchmark', 'benchmark_cycle.py'):
            shutil.copy2(runner_tests.RUNNER.parent / name, self.root / 'scripts' / name)
        self.runner = self.root / 'scripts/check-run'
        wrapper = self.root / 'gradle/wrapper/gradle-wrapper.properties'
        wrapper.parent.mkdir(parents=True)
        wrapper.write_text('distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n')
        gradle_home = self.root / 'build/gradle-home'
        distribution = gradle_home / 'wrapper/dists/gradle-9.7.1-bin/fixture/gradle-9.7.1'
        distribution.mkdir(parents=True)
        (distribution / 'tool').write_text('fixture')
        jdk = self.root / 'jdk'
        (jdk / 'bin').mkdir(parents=True)
        java = jdk / 'bin/java'
        java.write_text(f'#!/usr/bin/env python3\nimport sys\nprint("    java.home = {jdk}\\n    java.version = 25.0.2",file=sys.stderr)\n')
        java.chmod(0o755)
        (self.root / 'gradlew').write_text(GRADLE_FIXTURE)
        (self.root / 'gradlew').chmod(0o755)
        (self.root / '.gitignore').write_text('build/\n__pycache__/\n')
        self.environment = {**os.environ, 'JAVA_HOME': str(jdk), 'GRADLE_USER_HOME': str(gradle_home)}

    def benchmark(self, *args, success=True):
        """Run the public command and retain its supervisor ID for guaranteed cleanup."""
        result = subprocess.run(['./scripts/benchmark', *args], cwd=self.root, env=self.environment,
                                capture_output=True, text=True, timeout=30)
        fields = {k: json.loads(v) for k, sep, v in (line.partition(': ') for line in result.stdout.splitlines()) if sep}
        if 'run' in fields:
            self.runs.append(fields['run'])
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result, fields

    def summary(self, fields):
        """Read the persisted cycle result independently from CLI claims."""
        return json.loads(Path(fields['summary']).with_suffix('.json').read_text())

    def test_quality_complete_fresh_and_non_gating(self):
        """All four corpora run in order; imperfect external scores never become release gates."""
        _, first = self.benchmark('pii-quality')
        summary = self.summary(first)
        self.assertEqual(summary['state'], 'passed')
        self.assertEqual((self.root / 'build/invocations').read_text().splitlines(),
                         ['testClasses', 'piiQualityReport', 'redMadRobotPiiBenchmark', 'hiveTracePiiBenchmark', 'advPiiBenchmark'])
        self.assertEqual(summary['stages'][2]['verdict'], 'MEASURED')
        self.assertEqual(summary['stages'][2]['metrics']['sourceAligned']['exact']['falsePositives'], 1)
        before = Path(first['summary']).read_bytes()
        _, second = self.benchmark('pii-quality')
        self.assertNotEqual(first['run'], second['run'])
        self.assertNotEqual(first['cycle'], second['cycle'])
        self.assertEqual(Path(first['summary']).read_bytes(), before)
        self.assertEqual(self.cli('status', first['run'])[1]['applicability'], 'current')

    def test_failed_or_missing_stage_keeps_independent_evidence(self):
        """Stage exit failures and absent reports both fail the cycle without dropping later corpora."""
        for mode in ('failure', 'missing'):
            with self.subTest(mode=mode):
                self.environment['BENCH_FIXTURE_MODE'] = mode
                result, fields = self.benchmark('pii-quality', success=False)
                self.assertEqual(result.returncode, 1)
                summary = self.summary(fields)
                self.assertEqual(summary['state'], 'failed')
                self.assertEqual(summary['stages'][-1]['state'], 'passed')
                self.assertTrue(any(s['state'] == 'failed' for s in summary['stages']))

    def test_report_deviation_smoke_and_partial_matrix_fail(self):
        """Exit-zero children cannot hide failed gates, smoke profiles or incomplete JMH matrices."""
        for mode in ('deviation', 'smoke', 'partial-jmh'):
            with self.subTest(mode=mode):
                self.environment['BENCH_FIXTURE_MODE'] = mode
                result, fields = self.benchmark('load', success=False)
                self.assertEqual(result.returncode, 1, result.stdout)
                summary = self.summary(fields)
                self.assertEqual(summary['state'], 'failed')
                self.assertEqual(summary['stages'][-1]['state'], 'passed')

    def test_full_load_order_and_measurement_rerun(self):
        """The complete five-stage load matrix executes serially with an explicit fresh JMH producer."""
        _, fields = self.benchmark('load')
        summary = self.summary(fields)
        self.assertEqual([s['id'] for s in summary['stages']],
                         ['prepare', 'pii-jmh', 'inspection-phase', 'perf-01', 'inspection-load', 'inspection-resources'])
        self.assertEqual(summary['stages'][1]['command'][-2:], ['jmh', '--rerun'])
        self.assertEqual(len(summary['stages'][1]['metrics']), 108)

    def test_cancel_kills_child_and_records_unrun_stages(self):
        """Detached cancellation crosses the real supervisor process boundary and releases its child."""
        self.environment['BENCH_FIXTURE_MODE'] = 'hold'
        _, fields = self.benchmark('pii-quality', '--detach')
        pid = int(self.await_file('build/child.pid').read_text())
        self.cli('cancel', fields['run'])
        self.assertEqual(self.cli('wait', fields['run'], '--seconds', '10')[1]['state'], 'cancelled')
        self.assertEqual(self.summary(fields)['state'], 'cancelled')
        self.assertEqual(self.summary(fields)['stages'][-1]['state'], 'not_run')
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)

    def test_active_cycle_rejects_second_launch(self):
        """Concurrent invocation cannot enter Gradle or reuse a running cycle."""
        self.environment['BENCH_FIXTURE_MODE'] = 'hold'
        _, first = self.benchmark('pii-quality', '--detach')
        self.await_file('build/child.pid')
        result, second = self.benchmark('pii-quality', '--detach', success=False)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(second['state'], 'infrastructure_failure')
        self.assertNotEqual(first['run'], second['run'])

    def test_timeout_reclaims_the_child(self):
        """The one supervisor deadline bounds the entire cycle and retains partial progress."""
        self.environment['BENCH_FIXTURE_MODE'] = 'hold'
        _, fields = self.benchmark('pii-quality', '--detach', '--timeout', '3')
        pid = int(self.await_file('build/child.pid').read_text())
        self.assertEqual(self.cli('wait', fields['run'], '--seconds', '10')[1]['state'], 'timeout')
        self.assertEqual(self.summary(fields)['stages'][-1]['state'], 'not_run')
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)

    def test_input_change_cannot_return_success(self):
        """A complete measurement against changing code is stale, never reusable evidence."""
        self.environment['BENCH_FIXTURE_MODE'] = 'mutate-input'
        result, fields = self.benchmark('pii-quality', success=False)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(fields['state'], 'stale')
        _, home = self.benchmark()
        self.assertNotIn('state', home)
        self.assertEqual(home['measurement_state'], 'passed')
        self.assertEqual(home['applicability'], 'unverified')

    def test_ambient_gateway_configuration_is_excluded(self):
        """Local config and unknown future VIGILANT overrides cannot change a fixed benchmark profile."""
        self.environment.update(VIGILANT_CONFIG='/missing/custom.conf', VIGILANT_UPSTREAM_CONNECT_TIMEOUT='1ms',
                                VIGILANT_FUTURE_OVERRIDE='unexpected')
        (self.root / 'vigilant.conf').write_text('invalid ambient config')
        _, fields = self.benchmark('load')
        observed = json.loads((self.root / 'build/gateway-environment.json').read_text())
        expected = Path(fields['summary']).parent / 'vigilant.conf'
        self.assertEqual(observed, {'VIGILANT_CONFIG': str(expected)})
        self.assertTrue(expected.read_text().startswith('# Benchmark'))
        expected.write_text('modified runtime config')
        self.assertNotEqual(self.cli('status', fields['run'])[1]['state'], 'passed')

    def test_bootstrap_checks_external_gradle_before_invocation(self):
        """A first-run distribution bootstrap obeys the same overlap boundary as measurements."""
        shutil.rmtree(self.root / 'build/gradle-home/wrapper')
        child = subprocess.Popen([sys.executable, '-c',
                                  "from pathlib import Path; import time; Path('build/ready').touch(); time.sleep(30)",
                                  'org.gradle.wrapper.GradleWrapperMain'], cwd=self.root)
        try:
            self.await_file('build/ready')
            result, fields = self.benchmark('load', success=False)
            self.assertEqual(result.returncode, 2)
            self.assertIn('another Gradle invocation', fields['error'])
            self.assertFalse((self.root / 'build/invocations').exists())
        finally:
            child.terminate()
            child.wait(timeout=5)

    def test_prepare_failure_skips_measurement(self):
        """A broken build stops the dependent matrix and publishes every skipped stage."""
        self.environment['BENCH_FIXTURE_MODE'] = 'prepare-failure'
        result, fields = self.benchmark('pii-quality', success=False)
        self.assertEqual(result.returncode, 1)
        self.assertEqual([s['state'] for s in self.summary(fields)['stages']], ['failed'] + ['not_run'] * 4)

    def test_usage_validation_and_offline_paths(self):
        """Unknown/mode-specific arguments fail before launch; offline supplies all dataset inputs."""
        for args in [('load', '--typo'), ('load', '--off'), ('load', '--baseline', 'x'), ('pii-quality', '--timeout', '0')]:
            result, _ = self.benchmark(*args, success=False)
            self.assertEqual(result.returncode, 2)
            self.assertFalse((self.root / 'build/invocations').exists())
        result, fields = self.benchmark('load', '--help')
        self.assertEqual(list(fields), ['help'])
        self.assertEqual(len(result.stdout.splitlines()), 1)
        self.assertIn('--offline', fields['help'])
        _, fields = self.benchmark('pii-quality', '--offline')
        command = self.summary(fields)['stages'][1]['command']
        self.assertIn('--offline', command)
        self.assertIn(f'-PredMadRobotPiiDataset={self.root.resolve()}/build/redmadrobot-pii/test.csv', command)
        self.assertIn(f'-PhiveTracePiiCorpusDirectory={self.root.resolve()}/build/hivetrace-pii', command)
        self.assertIn(f'-PadvPiiCorpusDirectory={self.root.resolve()}/build/advpii', command)

    def test_optional_qualification_and_artifact_integrity(self):
        """A baseline adds qualification after all corpora; modified results cannot remain current."""
        baseline = self.root / 'baseline'
        baseline.mkdir()
        for name in ('redmadrobot-pii-benchmark.json', 'jmh.json', 'environment.properties', 'revision.txt'):
            (baseline / name).write_text('reviewed fixture')
        _, fields = self.benchmark('pii-quality', '--baseline', str(baseline))
        self.assertEqual(self.summary(fields)['stages'][-1]['id'], 'qualification')
        Path(fields['summary']).write_text('modified')
        self.assertEqual(self.cli('status', fields['run'])[1]['state'], 'corrupt')


if __name__ == '__main__':
    unittest.main()
