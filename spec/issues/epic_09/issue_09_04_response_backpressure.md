# VIG-09-04: E2E response backpressure

**Статус:** Ready for implementation
**Epic:** [EPIC-09](../../epics/epic_09_v0_architecture_closure.md)
**Ветка:** Streaming verifiability > response backpressure
**Зависит от:** нет
**Блокирует:** нет
**Оценка:** 2-3 инженерных дня
**Уверенность:** Medium

## Результат

E2E test доказывает, что медленный client subscriber ограничивает upstream
response demand и gateway не превращает streaming response в unbounded
in-memory queue. Существующий early-first-byte/SSE test остаётся отдельным
доказательством отсутствия полной агрегации.

## Требования

`PROXY-01`, `CONC-01`; finding AR-04.

## Критерии готовности

- [ ] Client запрашивает response data по одному или малому фиксированному
  числу элементов вместо `Long.MAX_VALUE`.
- [ ] Controllable upstream publisher наблюдает bounded outstanding demand и
  не выпускает весь response до разрешения client.
- [ ] После последовательного release всех permits client получает полный
  content в правильном порядке и exchange завершается успешно.
- [ ] Test чувствителен к unbounded prefetch/buffering и сохраняет существующие
  TTFB/SSE assertions.
- [ ] Production code не меняется. Если test выявляет defect, fix создаётся
  отдельной issue с RED-first TDD.
- [ ] Focused test и `./gradlew build` проходят.

## Test/demo seam

Реальные Armeria upstream и gateway, controllable response publisher и slow
client subscriber с deadline-bounded latches/barriers.

## Не входит

Request streaming (VIG-09-03), client cancellation, metrics backpressure,
production fix внутри test-only issue.
