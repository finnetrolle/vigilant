# VIG-21-04: Детерминированный streaming evidence

**Статус:** Done
**Epic:** [EPIC-21](../../epics/epic_21_post_milestone_architecture_closure.md)
**Ветка:** Verification determinism > streaming-before-final-chunk observation barrier
**Зависит от:** нет
**Блокирует:** [VIG-21-05](issue_21_05_roadmap_frontier_reconciliation.md)
**Оценка:** 1-2 инженерных дня
**Уверенность:** High

## Результат

Response-streaming E2E causally доказывает, что client получил первый body
chunk до того, как test разрешил upstream записать последний chunk. Assertion
не зависит от cross-thread timestamps, scheduler delay или завершения всего
response.

## Требования

`PROXY-01`, response backpressure evidence, finding AR-13, open papercut
`pc_617014d8204d`.

## Критерии готовности

- [x] Upstream пишет headers и первый chunk, затем ждёт bounded release barrier
  перед последним chunk.
- [x] Client subscriber сигнализирует observation первого non-empty body chunk;
  только после этого test освобождает upstream writer.
- [x] Test падает при full response aggregation, даже если callback scheduling
  после completion меняет относительные timestamps.
- [x] Plain response и SSE-like cases используют один canonical helper.
- [x] Full byte content и order сохраняются; transport coalescing не считается
  нарушением, если logical boundary не является public contract.
- [x] Все waits bounded и выводят last observed upstream/client state; широкие
  sleep-only assertions удалены.
- [x] Production code не меняется.
- [x] Focused repeated test и `./gradlew build` проходят, open papercut закрыт с
  root-cause note.

## Completion evidence

30 августа 2026 года `BypassProxyStreamingTest` заменил cross-thread
`System.nanoTime` и scheduled sleeps на causal barrier. Real Armeria upstream
пишет headers и первый body chunk, затем bounded ждёт release остатка. Client
subscriber публикует первый non-empty body observation, и только после этого
normal test path разрешает upstream завершить response. При full aggregation
client signal невозможен до release, поэтому bounded first-data assertion
падает независимо от callback scheduling после completion.

Plain и SSE-like cases используют один `assertStreamedWithoutBuffering`.
Helper сравнивает полный concatenated byte stream, включая multibyte SSE data,
но не утверждает transport chunk boundaries. Client first-data, upstream
release и response completion waits ограничены; failure сообщает последние
upstream/client states. Production code не изменён.

Focused class прошёл `5/5` последовательных forced `--rerun-tasks` запусков и
ещё один post-format focused run. `./gradlew build --no-daemon` прошёл весь
suite, `detekt` и `validateWorkItems` за `5m 52s`. Papercut
`pc_617014d8204d` закрыт root-cause и verification note.

## Test/demo seam

Real Armeria upstream, gateway and client subscriber, two causal latches or
futures: first-client-data observed and final-upstream-chunk released.

## Не входит

Request backpressure, response chunk-boundary guarantee, production buffering
change, response inspection и SSE atomic policy semantics.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   causal no-aggregation proof
  Acceptance:   0.05  exact event ordering specified
  Boundaries:   0.0   test fixture only
  Alternatives: 0.10  latch or future is implementation detail
  Assumptions:  0.10  Armeria may coalesce transport chunks
  Aggregate:    0.05  below threshold (0.3 issue)
```
