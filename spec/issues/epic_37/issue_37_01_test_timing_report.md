# VIG-37-01: Stable test timing report

**Статус:** Ready for implementation
**Epic:** [EPIC-37](../../epics/epic_37_predictable_test_throughput.md)
**Ветка:** Measurement > deterministic report and baseline
**Зависит от:** [VIG-30](../issue_30_external_identity_extractor.md)
**Блокирует:** [VIG-37-02](issue_37_02_gateway_e2e_split.md)
**Оценка:** 1-2 инженерных дня
**Уверенность:** High

## Результат

Gradle task `testTimingReport` преобразует свежие JUnit XML results в
детерминированные JSON и Markdown reports внутри
`build/reports/test-throughput/`. Отчёт показывает whole-suite/task totals и
duration каждого test class, так что последующие structural changes можно
сравнить с зафиксированным single-worker baseline, не анализируя console log
вручную.

## Public seam и TDD slices

Основной seam - Gradle task над synthetic JUnit XML fixture. Первый RED case
фиксирует exact JSON/Markdown для двух tasks/classes с разным порядком входных
файлов. Следующие slices добавляют metadata, stale-output handling,
missing/malformed input failures и запуск над реальным `test` report.

Report implementation остаётся build/test infrastructure и не добавляет
runtime dependency или production module.

## Report contract

`build/reports/test-throughput/test-timing.json` содержит `schemaVersion=1` и:

- snapshot metadata: Git HEAD, dirty-tree fingerprint, OS/architecture,
  available processors, Java version, Gradle version, requested tasks,
  effective non-process worker count и наличие `--rerun-tasks`;
- task records: task path, tests, failures, skipped и duration milliseconds;
- class records: task path, fully qualified class name, tests, failures,
  skipped и duration milliseconds;
- whole-suite totals по тем же четырём полям.

Task records сортируются по task path. Class records сортируются по duration
descending, затем task path и class name ascending. Duration записывается как
целое число milliseconds. Arrays и object fields имеют стабильный порядок.

`build/reports/test-throughput/test-timing.md` описывает тот же snapshot и
whole-suite totals, затем выводит все classes от самой медленной к самой
быстрой. При равной duration применяется тот же task/class tie-breaker. JSON
является source of truth для automated comparison; Markdown не добавляет
значения, которых нет в JSON.

Перед generation task удаляет собственные предыдущие JSON/Markdown outputs.
Отсутствующий, пустой или malformed XML, duplicate testcase identity либо XML
с незавершённым suite приводит к понятному task failure, а не к успешному stale
report. Generated files не добавляются в Git.

До VIG-37-03 task читает текущий `test` XML. После появления `processTest`
VIG-37-03 расширяет тот же schema/report до обоих tasks без второго формата.

## Baseline protocol

Baseline создаётся тремя последовательными командами на одном HEAD, одном
dirty-tree fingerprint и одной машине, без concurrent Gradle invocations:

```bash
./gradlew test testTimingReport --rerun-tasks --no-daemon -PtestMaxParallelForks=1
```

Каждый run сохраняет отдельную копию generated JSON внутри временного
`build/reports/test-throughput/baseline/` каталога. Итоговый baseline summary
содержит три command wall-clock values, их median и ссылки на три snapshots.
Ни individual reports, ни summary не коммитятся.

## Критерии готовности

- [ ] RED/GREEN tests доказывают exact `schemaVersion=1`, metadata, task/class/
  whole-suite totals, integer milliseconds и deterministic ordering независимо
  от порядка XML files.
- [ ] Отдельные cases доказывают clean replacement старых outputs и explicit
  failure для missing, empty, malformed, duplicate и incomplete input.
- [ ] `testTimingReport` на реальном suite создаёт JSON и Markdown только под
  `build/reports/test-throughput/`; `git status` не показывает generated files.
- [ ] Три baseline runs выполнены строго последовательно с одним worker на
  одном HEAD/tree fingerprint; summary содержит все три wall-clock values и
  median.
- [ ] Report tests и task доступны focused commands; актуальные KDoc/Javadoc
  добавлены для новых build/test declarations и lifecycle helpers.
- [ ] `./gradlew validateWorkItems` и `./gradlew build` проходят.

## Не входит

- Изменение test topology, class split или включение parallel workers.
- GitHub Actions, published dashboard, historical database или committed
  benchmark artifacts.
- CPU profiler, flame graph, per-method optimization или абсолютный duration
  threshold.
- Изменение production code либо test assertions.

## Ambiguity Report

```text
Goals:        0.0   two deterministic report artifacts определены
Acceptance:   0.05  schema, ordering и failure cases explicit
Boundaries:   0.0   measurement отделено от topology changes
Alternatives: 0.05  JUnit XML выбран как canonical existing input
Assumptions:  0.10  Gradle exposes required invocation metadata
Aggregate:    0.04  Ready for implementation.
```
