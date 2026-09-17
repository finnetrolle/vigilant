"""Sequential benchmark composition; check-run alone owns locks, deadlines and processes."""

from datetime import datetime, timezone
import itertools
import json
import math
import os
from pathlib import Path
import platform
import signal
import socket
import subprocess
import time


def publish_json(path, value):
    """Atomically publish complete progress and result records."""
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + '\n')
    temporary.replace(path)


def now():
    """Return a timezone-qualified UTC timestamp for cycle provenance."""
    return datetime.now(timezone.utc).isoformat()


def stages(mode, baseline):
    """Define the complete existing matrix and each task's required result contract."""
    if mode == 'load':
        return [
            ('pii-jmh', 'piiJmhBaseline', 'reports/pii/jmh/baseline.json', 'jmh',
             ['reports/pii/jmh/baseline.txt', 'reports/pii/jmh/environment.properties']),
            ('inspection-phase', 'inspectionPhaseBenchmark', 'reports/inspection/phase/results.json', 'jmh',
             ['reports/inspection/phase/summary.md']),
            ('perf-01', 'perfTest', 'reports/perf-01/summary.json', 'gate',
             ['reports/perf-01/latest-summary.md', 'perf-processes/gateway.jfr', 'perf-processes/slow-sink-gateway.jfr']),
            ('inspection-load', 'inspectionLoadTest', 'reports/inspection/load/summary.json', 'gate',
             ['reports/inspection/load/summary.md']),
            ('inspection-resources', 'inspectionResourceQualification',
             'reports/inspection/resource-qualification/summary.json', 'gate',
             ['reports/inspection/resource-qualification/summary.md']),
        ]
    result = [
        ('canonical', 'piiQualityReport', 'reports/pii/canonical/pii-quality-report.json', 'canonical',
         ['reports/pii/canonical/pii-quality-report.md']),
        ('redmadrobot', 'redMadRobotPiiBenchmark', 'reports/pii/redmadrobot/redmadrobot-pii-benchmark.json', 'external',
         ['reports/pii/redmadrobot/redmadrobot-pii-benchmark.md']),
        ('hivetrace', 'hiveTracePiiBenchmark', 'reports/pii/hivetrace/hivetrace-pii-benchmark.json', 'external',
         ['reports/pii/hivetrace/hivetrace-pii-benchmark.md']),
        ('advpii', 'advPiiBenchmark', 'reports/pii/advpii/advpii-benchmark.json', 'external',
         ['reports/pii/advpii/advpii-benchmark.md']),
    ]
    if baseline:
        result.append(('qualification', 'piiQualityQualification',
                       'reports/pii/qualification/pii-quality-qualification.json', 'qualification',
                       ['reports/pii/qualification/pii-quality-qualification.md']))
    return result


def ensure_idle():
    """Reject active Gradle clients outside the supervisor before touching measurement resources."""
    result = subprocess.run(['ps', '-axo', 'args='], capture_output=True, text=True, timeout=10, check=True)
    markers = ('org.gradle.wrapper.GradleWrapperMain', 'org.gradle.launcher.GradleMain')
    if any(any(marker in line for marker in markers) for line in result.stdout.splitlines()):
        raise ValueError('another Gradle invocation is active; wait for its completion')


def ensure_ports():
    """Check fixed non-ephemeral fixture ports; the owning launchers recheck before binding."""
    for port in (18080, 18081, 18082, 19080, 19081, 19082, 19083, 19084, 19085):
        with socket.socket() as connection:
            connection.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                connection.bind(('127.0.0.1', port))
            except OSError as error:
                raise ValueError(f'benchmark port {port} is occupied; stop its owner before running load') from error


def gradle_command(root, config):
    """Pin installed Java inputs and expose only reviewed dataset/baseline properties."""
    home = config['java_home']
    command = ['./gradlew', '--no-daemon', '--console=plain', '--no-parallel',
               f'-Dorg.gradle.java.home={home}', f'-Dorg.gradle.java.installations.paths={home}',
               '-Dorg.gradle.java.installations.auto-detect=false',
               '-Dorg.gradle.java.installations.auto-download=false', '-Dorg.gradle.java.installations.fromEnv=']
    if config['offline']:
        command.append('--offline')
    properties = {'redmadrobot': ('redMadRobotPiiDataset', 'build/redmadrobot-pii/test.csv'),
                  'hivetrace': ('hiveTracePiiCorpusDirectory', 'build/hivetrace-pii'),
                  'advpii': ('advPiiCorpusDirectory', 'build/advpii'),
                  'baseline': ('piiQualificationBaselineDirectory', None)}
    for name, (prop, cache) in properties.items():
        value = config.get(name)
        if not value and config['offline'] and cache and config['mode'] == 'pii-quality':
            value = str(root / cache)
        if value:
            command.append(f'-P{prop}={value}')
    return command


def jmh_metrics(identifier, data):
    """Reject partial/duplicate JMH matrices and expose finite measured percentiles only."""
    if identifier == 'pii-jmh':
        scenarios = ('NO_MATCH_STOP_ON_FIRST', 'EARLY_EMAIL', 'PHONE_NUMBER', 'PAYMENT_CARD', 'IP_ADDRESS',
                     'IBAN', 'RU_INN', 'RU_SNILS', 'RU_PASSPORT', 'RU_OMS', 'NO_MATCH_FULL_SCAN', 'FULL_SCAN')
        expected = set(itertools.product(('ASCII', 'RUSSIAN', 'MIXED_UNICODE'),
                                        ('1024', '65536', '1048576'), scenarios))
        identity = lambda row: tuple(row['params'][key] for key in ('dataset', 'sizeBytes', 'scenario'))
    else:
        expected = set(itertools.product(('parsing', 'windowing', 'policyEvaluation', 'totalInspection'),
                                        ('1024', '65536')))
        identity = lambda row: (row['benchmark'].rsplit('.', 1)[1], row['params']['sizeBytes'])
    if not isinstance(data, list) or len(data) != len(expected) or {identity(row) for row in data} != expected:
        raise ValueError('incomplete or duplicate JMH scenario matrix')
    rows = []
    for row in data:
        metric = row['primaryMetric']
        percentiles = {name: metric['scorePercentiles'][percentile]
                       for name, percentile in [('p50', '50.0'), ('p95', '95.0'), ('p99', '99.0')]}
        if (row['mode'] != 'sample' or row['forks'] != 2 or row['warmupIterations'] != 3
                or row['measurementIterations'] != 5 or metric['scoreUnit'] != 'us/op'
                or any(not isinstance(v, (int, float)) or not math.isfinite(v) or v < 0 for v in percentiles.values())):
            raise ValueError('JMH profile or measured percentiles do not match the full cycle contract')
        rows.append({'case': list(identity(row)), 'unit': 'us/op', **percentiles})
    return rows


def quality_metrics(identifier, data):
    """Select existing corpus aggregates without recomputing scores or joining denominators."""
    if identifier == 'canonical':
        if data['releaseGate'] is not True:
            raise ValueError('canonical release gate report is invalid')
        return {'corpus': data['corpus'], 'mixed': data['metrics']['aggregate']}
    if identifier == 'redmadrobot':
        return {view: data[view]['metrics']['aggregate']
                for view in ('sourceAligned', 'productAligned', 'nestedIpAligned')}
    if identifier == 'hivetrace':
        full = data['partitions']['Full']
        return {'sourceAligned': full['sourceAligned']['aggregate'],
                'productAligned': full['productAligned']['aggregate'], 'cleanFpr': full['cleanFpr']}
    result = {}
    for subset in ('baseline', 'pii_only', 'combined', 'negative', 'hard_negative'):
        group = data['subsets'][subset]
        if group['suppressed']:
            result[subset] = {'suppressed': True}
        else:
            values = group['data']
            result[subset] = {k: values[k] for k in ('micro', 'documentFpr', 'fewShotPrecisionCaveat') if k in values}
    result['documentFalsePositiveRates'] = data['documentFalsePositiveRates']
    return result


def inspect_result(stage, artifact_root):
    """Require this stage's newly isolated artifacts and read its owning verdict."""
    identifier, _, primary, kind, required = stage
    for name in [primary, *required]:
        path = artifact_root / name
        if not path.is_file() or path.is_symlink() or path.stat().st_size == 0:
            raise ValueError(f'missing or empty report artifact: {name}')
    if identifier in ('perf-01', 'inspection-load') and not list((artifact_root / 'reports/gatling').glob('*/index.html')):
        raise ValueError('missing Gatling HTML report')
    data = json.loads((artifact_root / primary).read_text())
    if kind == 'jmh':
        return 'MEASURED', jmh_metrics(identifier, data)
    if kind in ('gate', 'qualification'):
        if not isinstance(data.get('passed'), bool):
            raise ValueError('missing explicit benchmark verdict')
        if kind == 'gate' and data.get('fullProfile') is not True:
            raise ValueError('SMOKE ONLY cannot satisfy a full cycle')
        return ('PASS' if data['passed'] else 'DEVIATION'), data
    return ('PASS' if kind == 'canonical' else 'MEASURED'), quality_metrics(identifier, data)


def render_metrics(metrics):
    """Render compact human tables from the very same aggregates stored in JSON."""
    if isinstance(metrics, list):
        return ['| Case | p50 | p95 | p99 | Unit |', '|---|---:|---:|---:|---|'] + [
            f'| {" / ".join(row["case"])} | {row["p50"]:.3f} | {row["p95"]:.3f} | '
            f'{row["p99"]:.3f} | {row["unit"]} |' for row in metrics]
    scores = []
    other = []

    def visit(value, path):
        """Traverse safe aggregate objects, preserving corpus/view/matching identities."""
        if isinstance(value, dict) and {'precision', 'recall', 'f1'} <= value.keys():
            counts = value.get('counts', value)
            scores.append([path, *(counts.get(key) for key in ('truePositives', 'falsePositives', 'falseNegatives')),
                           *(value[key] for key in ('precision', 'recall', 'f1'))])
        elif isinstance(value, dict):
            for key, child in value.items():
                visit(child, f'{path}/{key}' if path else key)
        elif not isinstance(value, list):
            other.append((path, value))

    def display(value):
        """Keep undefined ratios explicit and finite numeric summaries readable."""
        if value is None:
            return 'n/a'
        return f'{value:.6g}' if isinstance(value, float) else str(value)

    visit(metrics, '')
    result = []
    if scores:
        result += ['| View / matching | TP | FP | FN | Precision | Recall | F1 |',
                   '|---|---:|---:|---:|---:|---:|---:|']
        result += ['| ' + ' | '.join(display(value) for value in row) + ' |' for row in scores]
    if other:
        result += ['', '| Observation | Value |', '|---|---|']
        result += [f'| {key} | {display(value)} |' for key, value in other]
    return result


def write_summary(output, summary):
    """Publish progress and final reports with direct links and explicit evidence boundaries."""
    lines = [f'# Benchmark cycle: {summary["mode"]}', '', f'- Cycle: `{summary["cycle"]}`',
             f'- State: **{summary["state"]}**', f'- Started: {summary["started_at"]}',
             f'- Git revision: `{summary["revision"]}`; dirty: `{summary["dirty"]}`',
             f'- Environment: {summary["environment"]}', '',
             'Execution completeness and benchmark gates are separate from current product SLO qualification.',
             'Load covers the detector, request-policy and resource matrix; dedicated request/response PERF-01/02 remains unqualified.',
             'External PII scores are non-gating and corpus-specific; no combined F1 or production-quality claim.',
             'AdvPIIBench combined precision retains the caveat for incompletely labelled auxiliary examples.',
             'Before reusing results, check the owning check-run status for current inputs and intact artifacts.', '',
             '| Stage | State | Verdict | Seconds | Reports |', '|---|---|---|---:|---|']
    if summary.get('error'):
        lines += [f'\nCycle error: {summary["error"]}\n']
    for stage in summary['stages']:
        links = ' '.join(f'[{Path(p).name}]({p})' for p in stage.get('reports', []))
        lines.append(f'| {stage["id"]} | {stage["state"]} | {stage.get("verdict", "")} | '
                     f'{stage.get("duration_seconds", "")} | {links} |')
    for stage in summary['stages']:
        if stage.get('error'):
            lines += ['', f'{stage["id"]}: {stage["error"]}']
        if 'metrics' in stage:
            lines += ['', f'## {stage["id"]}', '', *render_metrics(stage['metrics'])]
    publish_json(output / 'summary.json', summary)
    temporary = output / 'summary.md.tmp'
    temporary.write_text('\n'.join(lines) + '\n')
    temporary.replace(output / 'summary.md')


def execute(root, cycle):
    """Run independent stages sequentially under check-run's process-group lifecycle."""
    output = root / 'build/reports/benchmark' / cycle
    config = json.loads((output / 'config.json').read_text())
    plan = stages(config['mode'], config.get('baseline'))
    summary = {'cycle': cycle, 'mode': config['mode'], 'state': 'running', 'started_at': now(),
               'revision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
               'dirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=root)),
               'environment': f'{platform.platform()}; CPUs={os.cpu_count()}; JDK={config["java_version"]}',
               'stages': [{'id': 'prepare', 'state': 'pending'}] +
                         [{'id': s[0], 'state': 'pending'} for s in plan]}
    active = None

    def interrupted(signum, _frame):
        """Persist cancellation immediately; the supervisor reclaims all owned descendants."""
        summary['state'] = 'cancelled'
        summary['error'] = f'interrupted by signal {signum}; see check-run for cancellation/timeout and cleanup status'
        if active:
            active['state'] = 'cancelled'
        for row in summary['stages']:
            if row['state'] == 'pending':
                row['state'] = 'not_run'
        summary['finished_at'] = now()
        write_summary(output, summary)
        raise SystemExit(1)

    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    write_summary(output, summary)
    try:
        ensure_idle()
        if config['mode'] == 'load':
            ensure_ports()
        command = gradle_command(root, config)
        environment = {key: value for key, value in os.environ.items() if not key.startswith('VIGILANT_')}
        environment.update(JAVA_HOME=config['java_home'], VIGILANT_CONFIG=str(output / 'vigilant.conf'))
        active = summary['stages'][0]
        active['state'] = 'running'
        started = time.monotonic()
        preparation = ['installDist', 'perfContractTest', 'inspectionResourceContractTest', 'jmhJar'] \
            if config['mode'] == 'load' else ['testClasses']
        active['command'] = command + preparation
        write_summary(output, summary)
        with (output / 'prepare.log').open('w') as log:
            result = subprocess.run(active['command'], cwd=root, env=environment, stdout=log, stderr=subprocess.STDOUT)
        active.update(exit_code=result.returncode, duration_seconds=round(time.monotonic() - started, 3), reports=['prepare.log'])
        active['state'] = 'passed' if result.returncode == 0 else 'failed'
        if result.returncode:
            raise ValueError('build or required contract preparation failed; see prepare.log')
        for stage, row in zip(plan, summary['stages'][1:]):
            ensure_idle()
            if config['mode'] == 'load':
                ensure_ports()
            active = row
            row.update(state='running', started_at=now())
            artifact_root = output / stage[0]
            artifact_root.mkdir()  # Existing output is never admitted as fresh evidence.
            row['command'] = command + [f'-PbenchmarkOutputRoot={artifact_root}', stage[1]]
            # JMH's producer (not merely its lifecycle wrapper) must execute on every cycle.
            if stage[0] == 'pii-jmh':
                row['command'] += ['jmh', '--rerun']
            row['reports'] = [f'{stage[0]}/command.log']
            write_summary(output, summary)
            started = time.monotonic()
            with (artifact_root / 'command.log').open('w') as log:
                result = subprocess.run(row['command'], cwd=root, env=environment, stdout=log, stderr=subprocess.STDOUT)
            row.update(exit_code=result.returncode, duration_seconds=round(time.monotonic() - started, 3), finished_at=now())
            row['state'] = 'failed' if result.returncode else 'passed'
            try:
                verdict, metrics = inspect_result(stage, artifact_root)
                row.update(verdict=verdict, metrics=metrics)
                if verdict == 'DEVIATION':
                    row['state'] = 'failed'
            except (OSError, ValueError, KeyError, TypeError) as error:
                row.update(state='failed', verdict='INCOMPLETE', error=str(error) if isinstance(error, ValueError)
                           else 'invalid or incomplete report schema')
            row['reports'] += [str(p.relative_to(output)) for p in sorted(artifact_root.rglob('*'))
                               if p.is_file() and (p.suffix in ('.json', '.md', '.properties') or p.name == 'index.html')]
            write_summary(output, summary)
        summary['state'] = 'passed' if all(row['state'] == 'passed' for row in summary['stages']) else 'failed'
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        summary.update(state='failed', error=str(error) if isinstance(error, ValueError)
                       else 'infrastructure failure; inspect stage logs')
        if active and active['state'] == 'running':
            active['state'] = 'failed'
        for row in summary['stages']:
            if row['state'] == 'pending':
                row['state'] = 'not_run'
    summary['finished_at'] = now()
    write_summary(output, summary)
    return 0 if summary['state'] == 'passed' else 1
