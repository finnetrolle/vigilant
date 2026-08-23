# VIG-09-03: E2E request streaming и backpressure

**Статус:** Ready for implementation
**Epic:** [EPIC-09](../../epics/epic_09_v0_architecture_closure.md)
**Ветка:** Streaming verifiability > request streaming and backpressure
**Зависит от:** нет
**Блокирует:** нет
**Оценка:** 2-3 инженерных дня
**Уверенность:** Medium

## Результат

E2E test доказывает, что gateway передаёт streaming request body upstream до
получения последнего client chunk и не запрашивает unbounded data, когда
upstream consumer читает медленно.

## Требования

`PROXY-01`, `CONC-01`, раздел «Входит в v0»: streaming requests/backpressure;
finding AR-03.

## Критерии готовности

- [ ] Client отправляет request body несколькими управляемыми chunks через
  реальный gateway и upstream.
- [ ] Upstream получает первый body data до отправки последнего client chunk;
  тест падает при полной request aggregation.
- [ ] Slow upstream consumer ограничивает demand/число buffered chunks в
  детерминированном bound вместо `Long.MAX_VALUE` relay.
- [ ] Полный byte content и порядок сохраняются; transport coalescing не
  трактуется как нарушение логических chunk boundaries.
- [ ] Test использует deadline-bounded synchronization без широких sleep-only
  assertions и не логирует body sentinels.
- [ ] Production code не меняется. Если test выявляет defect, fix создаётся
  отдельной issue с RED-first TDD.
- [ ] Focused test и `./gradlew build` проходят.

## Test/demo seam

Реальные Armeria client, gateway и upstream на ephemeral ports; custom
request publisher и controllable upstream subscriber.

## Не входит

Response backpressure (VIG-09-04), request cancellation, JSON parsing,
изменение production data path внутри test-only issue.
