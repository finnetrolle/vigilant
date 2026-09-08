# VIG-37-04: Qualify four test workers

**Статус:** Done
**Epic:** [EPIC-37](../../epics/epic_37_predictable_test_throughput.md)
**Ветка:** Parallel execution > fixed four-worker default and qualification
**Зависит от:** [VIG-37-03](issue_37_03_process_test_isolation.md) (Done; serial `processTest` lane and canonical process fixture are available), [VIG-37-05](issue_37_05_health_endpoints_determinism.md) (Done; health test owns isolated Armeria client resources and passed the ten-run focused gate)
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
нулевые failures, нулевые timeouts и complete current testcase inventory. После
обязательного causal regression из VIG-37-05 exact inventory равен `1128`.
После каждого exit bounded cleanup check доказывает отсутствие процессов,
запущенных этим run: gateway child JVM и Gradle test worker. Проверка связывает
process с recorded PID/start time этого run и не завершает чужие процессы.

Generated `qualification.json` и `qualification.md` под
`build/reports/test-throughput/` содержат snapshot metadata, три baseline
values/median, три candidate values/median, exact ratio, десять stability
results и общий pass/fail. Они удаляются перед новой series и не коммитятся.

Если новая series причинно обнаруживает non-production lifecycle defect в
test-owned server, client, process или response fixture, эта issue владеет
минимальным deterministic remediation, необходимым для продолжения exact
four-worker stability gate. Такой fix обязан сохранить assertions, пройти
targeted regression и не меняет production behavior. VIG-37-05 остаётся
владельцем только исходного health-specific defect.

## Public seam и TDD slices

Первый RED seam - Gradle configuration tests для default/override/invalid
matrix и неизменного serial process fork. Второй - pure qualification result
calculator с boundary cases ровно 70%, чуть выше threshold, failed run и
changed fingerprint. Финальный seam - real sequential qualification protocol.

## Критерии готовности

- [x] Configuration tests покрывают default `4`, overrides `1`, `2`, `3`, `4`,
  invalid `0`, `-1`, `1.5`, blank, non-numeric и `5`, а также неизменный
  `processTest=1` для каждого valid override.
- [x] Calculator tests доказывают median для каждой перестановки трёх values,
  inclusive pass на exact 70%, failure выше 70%, failure любого non-zero run и
  invalidation при изменении machine/HEAD/tree fingerprint.
- [x] Real baseline содержит три последовательных green uncached full test
  runs с одним worker; candidate содержит три таких же runs с четырьмя workers.
  Candidate median не превышает 70% baseline median.
- [x] После performance comparison десять из десяти последовательных full runs
  с четырьмя workers проходят без failure, timeout, testcase omission/
  duplication, port collision, hung worker или orphan process.
- [x] `qualification.json` и `.md` содержат все 16 individual run results,
  medians, ratio, metadata и pass/fail, deterministic ordering и не появляются
  в Git.
- [x] Default `./gradlew test` и `./gradlew build` выполняют полный serial
  process lane и four-worker non-process lane. Актуальные KDoc/Javadoc есть у
  новых build/test declarations и qualification lifecycle helpers.
- [x] Если любой speed/stability criterion не выполнен, issue и EPIC остаются
  незавершёнными; конфигурация не переключается автоматически на два/три
  workers.
- [x] Focused configuration/calculator tests, `./gradlew validateWorkItems` и
  финальный `./gradlew build` проходят.

## Не входит

- Два/три workers как fallback, dynamic CPU/RAM auto-tuning или process E2E
  parallelism.
- GitHub Actions, remote runners, CI matrix, workflow artifacts или billing.
- Изменение test contents или assertion strength ради достижения performance
  threshold; minimal causal test-fixture remediation для stability gate
  разрешена только по правилам выше. Production code и runtime dependencies не
  меняются.
- Абсолютный duration SLO или сравнение результатов разных machines/trees.

## Successful qualification evidence

Новая полная qualification series 2026-09-08 прошла на immutable snapshot:
machine `Maksims-MacBook-Pro.local|Mac OS X|26.3.1|aarch64|14`, Git HEAD
`e6b3ba0771fd8ade88d517191bdb65a50aba04a1`, dirty-tree fingerprint
`d3ac6b37bc680b591d118bb91c82bcda6c4d9b910f18d17e8c3223ec2b1e4ac8`.

- Три baseline run с одним worker заняли `1141539`, `1141213` и `1145652`
  ms; mathematical median равна `1141539` ms.
- Три candidate run с четырьмя workers заняли `743401`, `744576` и `744106`
  ms; mathematical median равна `744106` ms.
- Exact ratio `744106/1141539 = 0.651845` проходит inclusive threshold
  `<= 0.70` и соответствует улучшению медианы на `34.82%`.
- Десять stability runs заняли `742663`, `749095`, `747719`, `741852`,
  `740102`, `742280`, `742044`, `743996`, `745712` и `749948` ms.
- Все 16 runs завершились exit `0`, каждый содержал exact `1128` tests без
  failures, timeouts, snapshot mismatch, cleanup failure или orphan process.
- Generated `qualification.json`, `qualification.md` и 16 raw run logs остаются
  uncommitted под `build/reports/test-throughput/`; общий verdict `passed: true`.

### Stability remediation observed during fresh qualification attempts

До успешной серии новые полные attempts причинно выявили независимые
test-owned lifecycle defects, которые не проявлялись в focused health gate:

- shared/global Armeria client pools позволяли teardown одного test instance
  закрыть transport другого; instance-owned pools и server-first cleanup
  устранили этот interleaving без production diff;
- wildcard IPv6 server binding не совпадал с IPv4 `127.0.0.1` client seam и
  давал connection reset; canonical loopback helper сохраняет kernel-assigned
  port `0`, но фиксирует ту же address family;
- process fixture не владел client factory и мог скрыть pre-shutdown reader
  failure; ownership и terminal reader semantics теперь explicit;
- response-cancellation и timeout-wins regressions синхронизируются на
  публикуемом owning-boundary observation, сохраняя исходную силу assertions.

Каждый causal fix прошёл targeted test, затем затронутый transport/lifecycle
набор прошёл десять последовательных four-worker повторов. После этой волны
новая full 3+3+10 series прошла 16/16.

## Superseded failed qualification evidence

Qualification on 2026-09-07 остаётся историческим failed result и не входит в
успешную серию 2026-09-08. Его immutable snapshot был machine
`Maksims-MacBook-Pro.local|Mac OS X|26.3.1|aarch64|14`, Git HEAD
`e6b3ba0771fd8ade88d517191bdb65a50aba04a1`, and dirty-tree fingerprint
`4bc106a3c8c3c14709458bbc91202aa0d7e195400decfe4a287cb4b07ce69f73`.

- Baseline runs were `1060797`, `1061065`, and `1061474` ms; all exited `0`
  with `1127` tests, zero failures/timeouts/orphans, matching snapshots, and
  successful cleanup. Median: `1061065` ms.
- Candidate runs were `735968`, `735751`, and `724628` ms. Runs 4 and 6
  exited `0` with complete `1127`-test inventory and clean lifecycle. Run 5
  exited `1` because `HealthEndpointsTest > readyz answers 503 once graceful
  shutdown has started while healthz stays 200` failed with
  `UnprocessedRequestException` caused by `ClosedSessionException`. It had no
  timeout, snapshot change, or orphan/cleanup failure.
- Candidate median was `735751` ms. Exact ratio `735751/1061065` passes the
  inclusive 70% gate, but the non-zero candidate exit invalidates the
  performance series. The runner therefore correctly performed `0/10`
  stability runs and emitted overall `passed: false` without a two/three-worker
  fallback.
- Raw generated evidence remains uncommitted under
  `build/reports/test-throughput/`: `qualification.json`, `qualification.md`,
  and all six `qualification-runs/run-*.log` files.
- Follow-up [VIG-37-05](issue_37_05_health_endpoints_determinism.md) owns causal
  reproduction and correction of the observed health lifecycle instability and
  is now `Done`. Успешная VIG-37-04 series была начата с нуля; retained run 1-6
  results не переиспользовались.

## Ambiguity Report

```text
Goals:        0.0   fixed four-worker outcome и 30% gate exact
Acceptance:   0.0   3+3 timing and 10-run stability protocol complete
Boundaries:   0.0   no fallback, CI, process parallelism or behavior change
Alternatives: 0.0   user selected immediate qualification at four workers
Assumptions:  0.10  host resources support repeatable four-worker execution
Aggregate:    0.02  Ready for implementation.
```
