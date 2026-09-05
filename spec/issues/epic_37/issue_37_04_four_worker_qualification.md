# VIG-37-04: Qualify four test workers

**Статус:** Ready for implementation
**Epic:** [EPIC-37](../../epics/epic_37_predictable_test_throughput.md)
**Ветка:** Parallel execution > fixed four-worker default and qualification
**Зависит от:** [VIG-37-03](issue_37_03_process_test_isolation.md)
**Блокирует:** [VIG-31](../issue_31_identity_lookup_cache.md)
**Оценка:** 2-3 инженерных дня
**Уверенность:** Medium

## Результат

Основной Gradle `test` использует ровно четыре parallel forks по умолчанию,
while serial `processTest` остаётся на одном fork. Reproducible local
qualification доказывает минимум 30% median wall-clock improvement против
single-worker baseline и десять последовательных стабильных полных runs.

Issue закрывается только на topology `4 + serial process lane`. Результат хуже
threshold либо любой instability не разрешает fallback на два/три workers и не
считается частичным completion.

## Configuration contract

- Default effective `test.maxParallelForks` равен `4` независимо от machine
  core count.
- Project property `-PtestMaxParallelForks=N` переопределяет только `test` и
  принимает exact integer `1..4`. Missing property означает `4`.
- Zero, negative, fraction, whitespace-only, non-numeric и значения больше
  четырёх завершают Gradle configuration понятной ошибкой.
- `processTest.maxParallelForks` всегда равен `1`; property не может его
  изменить.
- Effective values присутствуют в `testTimingReport` metadata.

## Qualification protocol

Qualification runner выполняет команды только последовательно и перед первым
run фиксирует machine identity, Git HEAD и dirty-tree fingerprint. Любое
изменение этих трёх значений во время серии invalidates весь result.
Concurrent Gradle invocation запрещён.

Baseline состоит из трёх runs:

```bash
./gradlew test testTimingReport --rerun-tasks --no-daemon -PtestMaxParallelForks=1
```

Candidate состоит из трёх runs:

```bash
./gradlew test testTimingReport --rerun-tasks --no-daemon -PtestMaxParallelForks=4
```

Runner измеряет monotonic wall-clock от старта Gradle command до её exit и
записывает все шесть values. Для каждой тройки выбирается mathematical median.
Performance gate проходит только если:

```text
candidateMedianMs <= baselineMedianMs * 0.70
```

После performance gate runner выполняет десять последовательных candidate
runs той же команды с четырьмя workers. Каждый run обязан иметь exit code 0,
нулевые failures, нулевые timeouts и complete pre-isolation testcase inventory.
После каждого exit bounded cleanup check доказывает отсутствие процессов,
запущенных этим run: gateway child JVM и Gradle test worker. Проверка связывает
process с recorded PID/start time этого run и не завершает чужие процессы.

Generated `qualification.json` и `qualification.md` под
`build/reports/test-throughput/` содержат snapshot metadata, три baseline
values/median, три candidate values/median, exact ratio, десять stability
results и общий pass/fail. Они удаляются перед новой series и не коммитятся.

## Public seam и TDD slices

Первый RED seam - Gradle configuration tests для default/override/invalid
matrix и неизменного serial process fork. Второй - pure qualification result
calculator с boundary cases ровно 70%, чуть выше threshold, failed run и
changed fingerprint. Финальный seam - real sequential qualification protocol.

## Критерии готовности

- [ ] Configuration tests покрывают default `4`, overrides `1`, `2`, `3`, `4`,
  invalid `0`, `-1`, `1.5`, blank, non-numeric и `5`, а также неизменный
  `processTest=1` для каждого valid override.
- [ ] Calculator tests доказывают median для каждой перестановки трёх values,
  inclusive pass на exact 70%, failure выше 70%, failure любого non-zero run и
  invalidation при изменении machine/HEAD/tree fingerprint.
- [ ] Real baseline содержит три последовательных green uncached full test
  runs с одним worker; candidate содержит три таких же runs с четырьмя workers.
  Candidate median не превышает 70% baseline median.
- [ ] После performance comparison десять из десяти последовательных full runs
  с четырьмя workers проходят без failure, timeout, testcase omission/
  duplication, port collision, hung worker или orphan process.
- [ ] `qualification.json` и `.md` содержат все 16 individual run results,
  medians, ratio, metadata и pass/fail, deterministic ordering и не появляются
  в Git.
- [ ] Default `./gradlew test` и `./gradlew build` выполняют полный serial
  process lane и four-worker non-process lane. Актуальные KDoc/Javadoc есть у
  новых build/test declarations и qualification lifecycle helpers.
- [ ] Если любой speed/stability criterion не выполнен, issue и EPIC остаются
  незавершёнными; конфигурация не переключается автоматически на два/три
  workers.
- [ ] Focused configuration/calculator tests, `./gradlew validateWorkItems` и
  финальный `./gradlew build` проходят.

## Не входит

- Два/три workers как fallback, dynamic CPU/RAM auto-tuning или process E2E
  parallelism.
- GitHub Actions, remote runners, CI matrix, workflow artifacts или billing.
- Изменение test contents, assertion strength, production code или runtime
  dependencies ради достижения threshold.
- Абсолютный duration SLO или сравнение результатов разных machines/trees.

## Ambiguity Report

```text
Goals:        0.0   fixed four-worker outcome и 30% gate exact
Acceptance:   0.0   3+3 timing and 10-run stability protocol complete
Boundaries:   0.0   no fallback, CI, process parallelism or behavior change
Alternatives: 0.0   user selected immediate qualification at four workers
Assumptions:  0.10  host resources support repeatable four-worker execution
Aggregate:    0.02  Ready for implementation.
```
