# EPIC-37: Predictable and faster test suite

**ID:** `EPIC-37`
**Тип:** Epic
**Статус:** Ready for implementation
**Приоритет:** High
**Суммарная оценка:** 8-13 инженерных дней
**Связанные требования:** engineering productivity и deterministic test infrastructure

## Контекст и целевой результат

Полный локальный `./gradlew build` на текущем test topology выполняется примерно
10 минут: последние наблюдения лежат в диапазоне 10:02-10:25. Исторические
прогоны росли от 3:33 через 5:52 и 7:49 до 10 минут. Главный концентрационный
риск - `PiiShadowProxyServiceTest`: один файл содержит десятки E2E-сценариев и
многократно поднимает real Armeria servers. Process E2E дополнительно запускают
child JVM и не имеют отдельного execution lane.

Epic должен сократить медиану полного uncached test run минимум на 30% без
ослабления assertions, удаления сценариев или изменения production behavior.
Безопасные unit и in-process Armeria tests выполняются четырьмя Gradle test
workers, а каждый test, запускающий child JVM, остаётся в отдельном строго
последовательном `processTest`.

Текущие числа являются исходными наблюдениями, а не абсолютным performance SLO.
Сравнение выполняется на одном machine/tree snapshot сериями одинаковых
uncached команд.

## Нормативные решения

- Epic состоит из четырёх последовательных leaves: измеримый timing report,
  тематический split gateway E2E, изоляция process E2E и квалификация четырёх
  workers.
- Gradle task `testTimingReport` создаёт machine-readable JSON и Markdown со
  slowest classes в `build/reports/test-throughput/`. Generated reports не
  коммитятся.
- `PiiShadowProxyServiceTest` разделяется на четыре independently selectable
  E2E-класса: request inspection, JSON response enforcement, SSE response
  enforcement и gateway identity.
- Все tests, которые запускают child JVM, маркируются JUnit tag
  `process-e2e`. `processTest` включает только этот tag и всегда использует
  `maxParallelForks=1`; основной `test` исключает tag.
- `test` зависит от `processTest`, поэтому `./gradlew test` и
  `./gradlew build` сохраняют полный набор тестов. Focused process invocation
  использует `./gradlew processTest --tests ...`.
- Основной `test` по умолчанию использует ровно четыре workers. Project
  property `testMaxParallelForks` допускает значение от 1 до 4 только для
  воспроизводимого локального сравнения; process lane от него не зависит.
- Canonical `GatewayProcessFixture` владеет child process, output readers и
  reserved port для всех `MainKt`/installed-distribution launchers. Один port
  не выдаётся повторно в пределах одного Gradle invocation.
- Успех квалификации требует медианы трёх uncached runs с четырьмя workers не
  более 70% от медианы трёх runs с одним worker и десяти последовательных
  зелёных полных runs с четырьмя workers.
- Если performance threshold не достигнут либо хотя бы один stability run
  завершается failure, timeout или оставляет child process, VIG-37-04 не
  закрывается. Автоматического fallback на два или три workers нет.

## Карта декомпозиции

```text
EPIC-37 Predictable and faster test suite
+-- VIG-37-01 stable timing report
|   +-- deterministic JSON schema and Markdown slowest classes
|   +-- three-run single-worker baseline
+-- VIG-37-02 gateway E2E split
|   +-- request inspection
|   +-- JSON response enforcement
|   +-- SSE response enforcement
|   +-- gateway identity
+-- VIG-37-03 process test isolation
|   +-- process-e2e inventory and tag
|   +-- serial processTest and complete default lifecycle
|   +-- canonical process/port ownership
+-- VIG-37-04 four-worker qualification
    +-- exact four-worker default
    +-- 30% median improvement gate
    +-- ten-run deterministic stability gate
```

## Delivery graph

```text
VIG-30 External identity extractor (Done)
    |
VIG-37-01 Timing report
    |
VIG-37-02 Gateway E2E split
    |
VIG-37-03 Process test isolation
    |
VIG-37-04 Four-worker qualification
    |
VIG-31 Identity lookup cache clarification and implementation
```

## Дочерние issues

- [ ] [VIG-37-01: Stable test timing report](../issues/epic_37/issue_37_01_test_timing_report.md) - `Ready for implementation`
- [ ] [VIG-37-02: Split gateway E2E by behavior](../issues/epic_37/issue_37_02_gateway_e2e_split.md) - `Ready for implementation`
- [ ] [VIG-37-03: Isolate child-process E2E](../issues/epic_37/issue_37_03_process_test_isolation.md) - `Ready for implementation`
- [ ] [VIG-37-04: Qualify four test workers](../issues/epic_37/issue_37_04_four_worker_qualification.md) - `Ready for implementation`

## Не входит

- GitHub Actions, hosted CI, workflow YAML, CI billing или branch protection.
- Изменение production behavior, public protocol, policy, identity, tracing,
  audit, response enforcement или runtime configuration.
- Удаление tests, сокращение case matrices, ослабление assertions, увеличение
  tolerances или замена deterministic synchronization на sleeps.
- Parallel execution process E2E, динамический auto-tuning числа workers или
  экспериментальный fallback на два/три workers.
- Изменение mutation, OWASP, detekt или SonarQube scope.
- Committed timing results либо абсолютный cross-machine duration SLO.

## Критерии готовности epic

- Все четыре leaves имеют status `Done`, checklist и `spec/WORK_ITEMS.md`
  обновлены в тех же change sets.
- `testTimingReport` детерминированно публикует JSON и Markdown для полного
  test/processTest результата и не использует stale XML.
- До и после split совпадают exact testcase inventory, counts и outcomes;
  четыре новых E2E-класса доступны отдельными focused commands.
- Каждый child-JVM test выполняется ровно один раз в serial `processTest`, а ни
  один non-process test туда не попадает. `test` и `build` выполняют оба lanes.
- Основной `test` по умолчанию использует ровно четыре workers; `processTest`
  всегда использует один.
- На одном HEAD и одном dirty-tree fingerprint три последовательных baseline
  runs с одним worker и три candidate runs с четырьмя workers подтверждают не
  менее 30% улучшения медианы command wall-clock.
- Десять последовательных полных uncached runs с четырьмя workers проходят без
  failure, timeout, port collision, зависшего Gradle worker и orphan child JVM.
- Все новые и изменённые Kotlin/Java declarations, test methods и lifecycle
  helpers имеют актуальный KDoc/Javadoc. Focused checks,
  `./gradlew validateWorkItems` и `./gradlew build` проходят.

## Ambiguity Report

```text
Goals:        0.0   exact speedup и stability outcomes определены
Acceptance:   0.05  benchmark и complete inventory заданы явно
Boundaries:   0.0   production, CI и process parallelism исключены
Alternatives: 0.0   four-worker topology выбрана без fallback
Assumptions:  0.10  текущий machine/tree остаётся доступен для qualification
Aggregate:    0.03  Ready for implementation.
```
