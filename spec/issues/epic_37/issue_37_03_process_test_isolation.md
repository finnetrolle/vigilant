# VIG-37-03: Isolate child-process E2E

**Статус:** Ready for implementation
**Epic:** [EPIC-37](../../epics/epic_37_predictable_test_throughput.md)
**Ветка:** Test topology > serial child-process lane
**Зависит от:** [VIG-37-02](issue_37_02_gateway_e2e_split.md)
**Блокирует:** [VIG-37-04](issue_37_04_four_worker_qualification.md)
**Оценка:** 2-3 инженерных дня
**Уверенность:** Medium

## Результат

Каждый JUnit case, который запускает child JVM или installed `MainKt`, имеет
tag `process-e2e` и выполняется ровно один раз отдельным Gradle task
`processTest`. Process lane строго последовательный; основной `test` не
запускает tagged cases, но зависит от `processTest`, поэтому обычные
`./gradlew test` и `./gradlew build` сохраняют полный regression suite.

Все gateway child-process launchers используют `GatewayProcessFixture`, который
владеет process, stdout/stderr readers, bounded shutdown и port allocation.

## Complete initial inventory

Перед первым RED implementation выполняется repository sweep. На текущем tree
обязательный начальный inventory включает:

- все methods `ExternalIdentityProcessTest`;
- все methods `ShutdownLifecycleTest`;
- все methods `PiiShadowProxyProcessTest`;
- все child-process methods `MainTest`;
- `HealthEndpointsTest.graceful shutdown answers readyz with 503 before the gateway closes`;
- child-process JSONL scenarios `BypassProxyServiceTest`;
- все methods `UpstreamTimeoutMemoryStabilityTest`.

Inventory расширяется каждым JUnit method/dynamic descendant, достижимым через
direct `ProcessBuilder`, `GatewayProcessFixture.launch*`, installed
distribution executable или запуск `io.vigilant.gateway.MainKt`. Static sweep
по этим четырём seams обязан вернуть либо tagged test owner, либо явно
allowlisted non-test fixture declaration. Неперечисленный executable test case
является defect этой issue, а не будущим scope.

`reserveNonEphemeralPort()` без запуска child JVM сам по себе не делает test
process E2E; такие in-process dead-end/connection tests остаются в `test`.

## Gradle execution contract

- `processTest` включает только JUnit tag `process-e2e`, использует тот же test
  runtime classpath и всегда `maxParallelForks=1`.
- `test` исключает `process-e2e` и на этом leaf также остаётся с одним worker;
  four-worker default принадлежит VIG-37-04.
- `test` зависит от завершившегося `processTest`. Ни один tagged case не
  обнаруживается повторно основной task.
- `./gradlew processTest --tests <pattern>` является supported focused process
  command.
- `./gradlew test` и `./gradlew build` выполняют process и non-process sets
  exactly once. `./gradlew processTest` выполняет только process set.
- JUnit XML tasks имеют разные output directories. `testTimingReport`
  агрегирует оба без duplicate class/testcase identities.

## Canonical process and port ownership

Direct `ProcessBuilder` для gateway `MainKt` и installed distribution
переносится внутрь `GatewayProcessFixture`; test может передать arguments,
environment и expected startup mode, но не владеет необработанными streams или
cleanup protocol.

Fixture:

- запускает readers до ожидания readiness/exit и boundedly завершает их;
- на failure сообщает exit state и последний безопасный output;
- в `close` сначала завершает process, затем readers и освобождает bookkeeping;
- выдаёт loopback port из task-local monotonic/non-reuse registry и никогда не
  возвращает тот же port второму reservation в одном Gradle invocation;
- не обещает удержание socket после reservation: consumers синхронизируются на
  readiness/exit, не переиспользуют released ephemeral port и не используют
  fixed port.

## Public seam и TDD slices

Первый seam - Gradle Test tasks над small tagged/untagged sentinel tests:
execution log доказывает include/exclude, order и single execution. Второй seam
- static launcher inventory contract, который падает для executable process
test вне tag/canonical fixture. Третий seam - real focused process execution с
two sequential reservations и cleanup assertion.

## Критерии готовности

- [ ] Complete repository sweep классифицирует каждый direct/fixture/installed/
  `MainKt` launcher; каждый executable owner из начального и найденного
  inventory имеет `process-e2e`, а non-test declarations allowlisted по имени и
  причине.
- [ ] Sentinel Gradle contract доказывает: `processTest` выполняет каждый
  tagged case ровно один раз с одним fork; `test` не выполняет tagged cases и
  выполняет каждый untagged case ровно один раз.
- [ ] Runtime report для `./gradlew test` и `./gradlew build` не содержит
  omissions/duplicates относительно pre-isolation inventory; direct
  `processTest --tests` проходит.
- [ ] Все gateway child processes используют `GatewayProcessFixture`; direct
  gateway `ProcessBuilder` вне fixture отсутствует. Два и более reservations в
  одном invocation получают попарно разные ports.
- [ ] Failure, normal exit, startup rejection и forced cleanup paths boundedly
  закрывают process и readers; focused evidence не оставляет orphan child JVM
  или test worker.
- [ ] `testTimingReport` агрегирует distinct `processTest` и `test` records с
  correct whole-suite totals. KDoc/Javadoc описывает tag, lifecycle и port
  ownership.
- [ ] Focused topology/fixture/process tests,
  `./gradlew test --rerun-tasks -PtestMaxParallelForks=1`,
  `./gradlew validateWorkItems` и `./gradlew build` проходят.

## Не входит

- Parallel process execution или влияние `testMaxParallelForks` на
  `processTest`.
- Four-worker qualification, speed threshold либо auto-tuning.
- Изменение gateway production startup/shutdown, ports или configuration.
- GitHub Actions, Docker orchestration или новый external process framework.

## Ambiguity Report

```text
Goals:        0.0   exact serial lane и default task lifecycle заданы
Acceptance:   0.05  named inventory plus exhaustive seam sweep определены
Boundaries:   0.0   port reservation не меняет production semantics
Alternatives: 0.05  JUnit tag выбран как explicit ownership marker
Assumptions:  0.10  все executable launchers видимы static repository sweep
Aggregate:    0.04  Ready for implementation.
```
