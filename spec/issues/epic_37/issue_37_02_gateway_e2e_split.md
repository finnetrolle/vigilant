# VIG-37-02: Split gateway E2E by behavior

**Статус:** Done
**Epic:** [EPIC-37](../../epics/epic_37_predictable_test_throughput.md)
**Ветка:** Test structure > independently selectable gateway behaviors
**Зависит от:** [VIG-37-01](issue_37_01_test_timing_report.md) (Done; generated baseline evidence is available locally under `build/reports/test-throughput/baseline/`)
**Блокирует:** [VIG-37-03](issue_37_03_process_test_isolation.md)
**Оценка:** 3-5 инженерных дней
**Уверенность:** Medium

## Результат

Monolithic `PiiShadowProxyServiceTest` разделён на четыре independently
selectable in-process Armeria E2E classes без изменения production behavior,
testcase inventory, assertions или lifecycle semantics:

- `RequestInspectionE2eTest`: request parsing, audit, quota/capacity,
  cancellation, policy errors и replay;
- `JsonResponseEnforcementE2eTest`: retained JSON response, `ALLOW`/`MASK`/
  `BLOCK`, invalid JSON, response audit и lifecycle;
- `SseResponseEnforcementE2eTest`: terminal event, atomic enforcement, rewrite,
  invalid SSE и cancellation;
- `GatewayIdentityE2eTest`: Dummy/JWT/External identity, tracing, metrics и
  identity lookup shutdown.

## Public seam и TDD slices

Основной seam - JUnit discovery/execution report. Первый RED contract test
снимает полный inventory исходного `PiiShadowProxyServiceTest`: dynamic leaves
разворачиваются до display names, а для каждого case сохраняются outcome,
skipped state и assertion count where available. После каждого thematic move
focused new class становится GREEN, а inventory comparison остаётся exact.

Итоговый oracle сравнивает multiset `(displayName, dynamicPath)` до и после
split, намеренно игнорируя owning class name. Test counts, failures и skipped
counts совпадают. Duplicate display paths запрещены, поэтому потерянный или
дважды выполненный scenario виден как contract failure.

## Ownership и fixtures

Каждый существующий testcase переносится ровно в одну из четырёх групп:

- request-side parse/inspection/transport-before-response cases принадлежат
  `RequestInspectionE2eTest`;
- ordinary JSON response parse/enforcement/transport cases принадлежат
  `JsonResponseEnforcementE2eTest`;
- `text/event-stream` framing, terminal `[DONE]`, SSE enforcement и abort cases
  принадлежат `SseResponseEnforcementE2eTest`;
- identity mode, Bridge lookup, trace/metric correlation и lookup shutdown
  cases принадлежат `GatewayIdentityE2eTest`.

Case, затрагивающий несколько стадий, принадлежит группе observable outcome,
который он утверждает. Общие test-only helpers извлекаются только если
семантика, defaults, ordering и lifecycle ownership идентичны для всех
consumers. Domain-specific expected values остаются рядом с owning class.

Каждый test создаёт и закрывает собственные mutable servers/resources, если
они не являются immutable fixture. Shared global server, mutable singleton,
fixed port и cross-class ordering запрещены. Synchronization остаётся на
наблюдении, которое утверждает test, с bounded waits и last observed state;
новые sleeps и расширение tolerances запрещены.

## Критерии готовности

- [x] Pre-split inventory зафиксирован generated evidence VIG-37-01; post-split
  multiset display paths, total tests, failures и skipped совпадают exactly.
- [x] Все исходные request-side cases находятся только в
  `RequestInspectionE2eTest`, JSON response cases только в
  `JsonResponseEnforcementE2eTest`, SSE cases только в
  `SseResponseEnforcementE2eTest`, identity/tracing/metrics/lookup cases только
  в `GatewayIdentityE2eTest`.
- [x] Каждый из четырёх classes проходит отдельной focused `--tests` командой;
  исходный `PiiShadowProxyServiceTest` больше не содержит tests и удалён.
- [x] Shared helpers имеют одно canonical behavior и deterministic resource
  ownership. Нет shared mutable server/process/port, inter-class ordering,
  новых sleeps или widened timeout/assertion contract.
- [x] Production sources, runtime dependencies и user-visible behavior не
  изменены. Diff review подтверждает отсутствие удалённых cases и ослабленных
  assertions.
- [x] `testTimingReport` показывает четыре отдельных class records и тот же
  whole-suite inventory. Актуальные KDoc/Javadoc присутствуют у всех новых или
  изменённых declarations, test methods и lifecycle helpers.
- [x] Focused classes, `./gradlew test --rerun-tasks -PtestMaxParallelForks=1`,
  `./gradlew validateWorkItems` и `./gradlew build` проходят.

## Dynamic evidence

- Pre-refactor characterization
  `./gradlew test --tests "io.vigilant.gateway.proxy.PiiShadowProxyServiceTest" --rerun-tasks -PtestMaxParallelForks=1`
  прошёл за 7m51s: generated XML содержит 75 unique testcase display names,
  0 failures и 0 skipped. JUnit XML не публикует assertion count, поэтому это
  поле недоступно для всех 75 leaves и не заменено вычисленным proxy count.
- Discovery RED до split:
  `./gradlew test --tests "io.vigilant.gateway.proxy.RequestInspectionE2eTest" --tests "io.vigilant.gateway.proxy.JsonResponseEnforcementE2eTest" --tests "io.vigilant.gateway.proxy.SseResponseEnforcementE2eTest" --tests "io.vigilant.gateway.proxy.GatewayIdentityE2eTest" -PtestMaxParallelForks=1`
  завершился expected failure `No tests found for given includes`.
- Четыре независимых focused reruns прошли: request 21/0/0, JSON 15/0/0,
  SSE 11/0/0 и identity 28/0/0 по tests/failures/skipped. Combined rerun прошёл
  за 7m31s; owner-independent multiset `(displayName, dynamicPath)` совпал с
  pre-split 75/75, outcomes совпали, duplicate paths отсутствуют.
- `./gradlew inspectionResourceContractTest --rerun-tasks --no-daemon -PtestMaxParallelForks=1`
  прошёл за 7m52s: task сохранил полный pre-split gateway inventory через все
  четыре новых classes (21 + 15 + 11 + 28 = 75 tests) и по-прежнему включает
  `BoundedRequestSourceTest` (15) и `ShutdownLifecycleTest` (2). Итоговый task
  inventory - 92 tests, 0 failures, 0 errors и 0 skipped.
- `./gradlew test testTimingReport --rerun-tasks --no-daemon -PtestMaxParallelForks=1`
  прошёл за 17m18s: whole suite 1116 tests, 0 failures, 0 skipped. Report содержит
  четыре distinct records: request 21, JSON 15, SSE 11 и identity 28. Старый
  `PiiShadowProxyServiceTest` record отсутствует; generated evidence остаётся
  только в ignored `build/reports/test-throughput/`.
- `./gradlew validateWorkItems --no-daemon` и `./gradlew build --no-daemon`
  прошли; build включает detekt, runtime-classpath guard, validator tests и
  повторную проверку work-item graph.

## Не входит

- Parallel execution, `processTest`, child-process tagging или benchmark
  qualification следующих leaves.
- Новые behavior cases, production refactor, protocol variant или response/
  identity feature.
- Удаление slow tests, sampling dynamic matrices, assertion deduplication либо
  переход от real Armeria seam к mocks.
- GitHub Actions или committed timing artifacts.

## Ambiguity Report

```text
Goals:        0.0   four owning classes названы и ограничены
Acceptance:   0.05  complete inventory equality является exact oracle
Boundaries:   0.0   behavior и assertions неизменны
Alternatives: 0.05  split follows observable behavior ownership
Assumptions:  0.10  dynamic display paths уникальны или могут быть уточнены
Aggregate:    0.04  Ready for implementation.
```
