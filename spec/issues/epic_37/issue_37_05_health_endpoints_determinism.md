# VIG-37-05: Stabilize health endpoint lifecycle test under four workers

**Статус:** Done
**Epic:** [EPIC-37](../../epics/epic_37_predictable_test_throughput.md)
**Ветка:** Parallel execution > deterministic health endpoint lifecycle
**Зависит от:** [VIG-37-03](issue_37_03_process_test_isolation.md) (Done; serial `processTest` lane and canonical process fixture are available)
**Блокирует:** [VIG-37-04](issue_37_04_four_worker_qualification.md)
**Оценка:** 1-2 инженерных дня
**Уверенность:** Medium

## Контекст

Реальная four-worker qualification VIG-37-04 на неизменном machine/HEAD/tree
snapshot прошла speed threshold, но candidate run 2 из 3 завершился с exit
`1`. Единственный failure:

```text
HealthEndpointsTest > readyz answers 503 once graceful shutdown has started while healthz stays 200
CompletionException
  -> UnprocessedRequestException
    -> ClosedSessionException
```

Сбой произошёл на первом `GET /readyz` после `ReadinessService.markNotReady()`,
до проверки status/body. Два соседних candidate runs с тем же exact topology
прошли. Последующий `./gradlew build --no-daemon` также прошёл, поэтому текущая
evidence указывает на intermittent lifecycle/resource interference, но ещё не
доказывает конкретную root cause.

VIG-37-04 запрещает менять test contents, assertion strength или production
behavior ради прохождения threshold. Диагностика и исправление обнаруженного
дефекта поэтому принадлежат отдельному work item.

## Цель

Установить причинный interleaving, из-за которого readiness request получает
closed client session при four-worker execution, и устранить его на owning
lifecycle boundary. Health contract остаётся прежним: после
`markNotReady()` `/readyz` отвечает exact `503 draining`, пока `/healthz`
отвечает `200`.

## Результат

`HealthEndpointsTest` больше не использует process-global default
`ClientFactory`. Каждый test instance владеет одним isolated factory с
одним event loop для probe и upstream clients. После test body fixture
boundedly закрывает все servers в reverse order, затем factory;
каждый cleanup action выполняется даже после предыдущей ошибки,
а terminal server/factory states наблюдаются прямо.

Причинный real-HTTP regression устанавливает HTTP/2 session через
foreign/default factory, удерживает его exact client event loop,
инициирует и наблюдает server-owned connection shutdown, отправляет
первый `/readyz` и только затем снимает barrier. До isolation этот
stimulus детерминированно завершал `/readyz` через
`UnprocessedRequestException -> GoAwayReceivedException`; после isolation тот
же stimulus возвращает exact `503 draining` и `/healthz -> 200 ok`.

## Подтверждённая граница

- Issue является child EPIC-37 с ID `VIG-37-05`.
- VIG-37-05 зависит от завершённой VIG-37-03 и блокирует завершение VIG-37-04.
- Исправление выполняется отдельным TDD change set; VIG-37-04 после него
  повторяет полную qualification series с нуля.
- Regression evidence начинается с детерминированного RED через управляемый
  lifecycle interleaving на реальном HTTP seam. Случайный повтор полного suite
  или ожидание удачного scheduler order не является causal evidence.
- После исправления тот же causal fixture обязан стать GREEN. Дополнительно
  проходят десять последовательных focused four-worker runs без retries,
  failure, timeout или resource leak.
- Fix boundary определяется causal fixture. Если владельцем преждевременного
  close является test-owned server, client, event loop или fixture, меняется
  только test infrastructure ownership. Если fixture доказывает настоящий
  production shutdown/lifecycle defect, допустимо минимальное production
  исправление только после отдельного E2E RED на production boundary.
- Production code, global scheduling и test topology не меняются на основании
  одной гипотезы или для статистического улучшения pass rate.

## Failure semantics

Если deterministic causal RED получить не удаётся, implementation
останавливается без speculative fix. Issue остаётся незавершённой; в неё
добавляются проверенные hypotheses, exact diagnostic commands и observations.
Серия случайных successful reruns не заменяет causal evidence и не разрешает
возврат к VIG-37-04 qualification.

## Компоненты и ответственность

- `HealthEndpointsTest` остаётся public in-process HTTP seam для exact
  readiness/liveness behavior. Regression fixture управляет interleaving через
  explicit barriers и наблюдает response на этом seam.
- Владелец обнаруженного premature close отвечает за исправление и bounded
  cleanup. Test-owned и production-owned resources не смешиваются в одном
  lifecycle abstraction без доказанной общей семантики.
- Gradle `test` с `-PtestMaxParallelForks=4` остаётся orchestration seam для
  focused повторов. VIG-37-05 не меняет worker topology или qualification
  calculator/runner.
- VIG-37-04 после `Done` этой issue владеет новой полной 3+3+10 qualification
  series, итоговыми status updates и только теми дополнительными non-health
  test-fixture defects, которые причинно обнаружит её собственный stability
  gate. Они не расширяют health-specific scope этой issue.

## Диагностический и TDD порядок

1. На текущем pre-fix коде выполнить isolated health test и целевой
   four-worker diagnostic sweep. Использовать retained VIG-37-04 run-5 failure
   только как исходное observation, не как causal oracle.
2. Найти resource owner и terminal event, затем добавить один deterministic
   regression test с explicit barrier. Он должен воспроизвести
   `ClosedSessionException` либо эквивалентный преждевременный close на первом
   `/readyz` request без loop, retry, sleep или scheduler luck.
3. Если новый public contract ещё не компилируется, добавить только минимальный
   behaviorless scaffold, затем добиться behavioral RED.
4. Исправить только доказанный ownership boundary. Все owned resources
   закрываются boundedly; cleanup пытается закрыть каждый ресурс, сохраняет
   первую ошибку и добавляет последующие как suppressed.
5. Тем же causal stimulus доказать exact GREEN response и observable cleanup.
6. Выполнить десять отдельных последовательных focused commands с four-worker
   property. Любой non-zero exit, timeout или leak обнуляет evidence series;
   выборочный retry не разрешён.

## Non-goals

- Retry, sleep, увеличение timeout/tolerance или игнорирование intermittent
  failure.
- Ослабление exact `503 draining` и `200` assertions.
- JUnit quarantine, исключение `HealthEndpointsTest` из `test` либо перенос
  обычного in-process case в serial `processTest` только ради зелёного run.
- Fallback VIG-37-04 на два/три workers, dynamic worker tuning или parallel
  process E2E.
- Изменение публичного health/readiness contract без отдельного нормативного
  решения.
- Speculative production changes до доказанного production E2E RED.

## Evidence contract

- Stimulus: four-worker Gradle execution достигает причинного lifecycle
  interleaving, ранее закрывавшего client session до readiness response.
- Public seam: HTTP `GET /readyz` и `GET /healthz` через реально запущенный
  in-process Armeria gateway; orchestration запускается поддерживаемой Gradle
  test command.
- Observable result: readiness всегда возвращает exact `503 draining`,
  liveness возвращает `200`, Gradle command завершается `0`, все owned servers,
  clients/event loops и workers завершаются без leak.
- Independent oracle: literal HTTP status/body assertions плюс bounded
  observation owned resource termination. Детерминированный pre-fix RED и
  post-fix GREEN одного causal fixture являются обязательными; десять
  последовательных focused four-worker runs дополняют, но не заменяют этот
  oracle.

## Критерии готовности

- [x] Управляемый lifecycle interleaving детерминированно воспроизводит
  pre-fix `ClosedSessionException` или эквивалентный premature close через
  первый реальный HTTP request к `/readyz`; test использует explicit barrier,
  а не loop, retry, sleep, timing race или случайный class order.
- [x] Evidence однозначно назначает owner преждевременного close. Diff меняет
  только этот ownership boundary; production diff присутствует только при
  отдельном production E2E RED.
- [x] После минимального исправления тот же stimulus возвращает exact
  `/readyz -> 503 draining` и `/healthz -> 200` без retry или sleep.
- [x] Success, assertion failure, request failure и teardown failure paths
  boundedly закрывают все fixture-owned servers, clients, factories/event
  loops и executors. Cleanup не закрывает foreign/global resource и не
  short-circuit'ится после первой ошибки.
- [x] Десять последовательных focused Gradle runs с четырьмя workers проходят
  с exit `0`, без timeout, пропущенных assertions и owned resource leak. Exact
  command каждого run:

  ```bash
  ./gradlew test -x processTest --tests "io.vigilant.gateway.health.HealthEndpointsTest" --rerun-tasks --no-daemon -PtestMaxParallelForks=4
  ```

- [x] Existing process E2E graceful-shutdown scenario остаётся GREEN и
  подтверждает production ordering отдельной командой:

  ```bash
  ./gradlew processTest --tests "io.vigilant.gateway.health.HealthEndpointsTest" --no-daemon
  ```

- [x] Все новые/изменённые Kotlin declarations, test methods и lifecycle
  helpers имеют актуальный KDoc. `./gradlew validateWorkItems --no-daemon` и
  финальный `./gradlew build --no-daemon` проходят перед возвратом к
  VIG-37-04 qualification.

## Dynamic evidence

- Pre-fix isolated health command и focused four-worker diagnostic были GREEN,
  что подтвердило intermittent, но не causal характер retained failure.
- Causal RED
  `./gradlew test -x processTest --tests "io.vigilant.gateway.health.HealthEndpointsTest.foreign session shutdown cannot close the first draining health probes" --rerun-tasks --no-daemon -PtestMaxParallelForks=4`
  завершился exit `1`: первый `/readyz` получил
  `UnprocessedRequestException`, caused by `GoAwayReceivedException`, после
  observed server session close и до release client-event-loop barrier.
- После client-factory isolation тот же exact command завершился
  exit `0`; causal request наблюдал `503 draining`, а следующий
  `/healthz` наблюдал `200 ok`.
- Owner-cleanup RED прямо наблюдал, что bounded `Server.stop()` оставлял
  каждый restartable test server в non-closed state. Замена на bounded
  `Server.closeAsync()` сделала direct `Server.isClosed` oracle GREEN.
  Focused health + canonical cleanup-helper command завершился exit `0`.
- Десять отдельных последовательных exact focused four-worker
  commands завершились exit `0` без retry, timeout и terminal resource
  assertion failure; каждый run занял `49s`.
- `./gradlew processTest --tests "io.vigilant.gateway.health.HealthEndpointsTest" --no-daemon`
  завершился exit `0` за `20s` и сохранил production shutdown ordering.
- `./gradlew validateWorkItems --no-daemon` завершился exit `0`,
  `Work-item graph is valid`.
- `./gradlew build --no-daemon` завершился exit `0` за `12m30s`;
  full serial process lane, four-worker non-process lane, detekt, runtime-classpath
  guard, work-item validator и validator tests были GREEN.
- Последующая полная VIG-37-04 qualification 2026-09-08 выполнила все три
  candidate и десять stability runs с четырьмя workers. Все 13 runs имели exit
  `0`, exact `1128` tests, zero failures/timeouts/orphans и успешный cleanup,
  поэтому causal health lifecycle fix подтверждён в полном suite topology.

## Рассмотренные альтернативы

- Просто повторить VIG-37-04 до зелёного результата: отклонено, скрывает
  observed instability и нарушает ten-run deterministic gate.
- Добавить retry/sleep либо увеличить timeout: отклонено, не назначает owner и
  не устраняет premature close.
- Перенести in-process health case в serial `processTest`: отклонено, уменьшает
  qualified four-worker inventory вместо исправления lifecycle defect.
- Сразу изменить production shutdown: отклонено без отдельного production E2E
  RED, потому что текущий failure возник в in-process test-owned lifecycle.

## Ambiguity Report

```text
Goals:        0.05  exact existing health contract и causal outcome заданы
Acceptance:   0.10  RED/GREEN, lifecycle paths и commands воспроизводимы
Boundaries:   0.05  fix разрешён только на доказанном owning boundary
Alternatives: 0.05  retry, quarantine, serialization и speculative fix rejected
Assumptions:  0.20  конкретный resource owner должен установить RED slice
Aggregate:    0.09  Ready for implementation.
```
