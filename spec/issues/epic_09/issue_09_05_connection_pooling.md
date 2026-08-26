# VIG-09-05: E2E connection pooling upstream

**Статус:** Done
**Epic:** [EPIC-09](../../epics/epic_09_v0_architecture_closure.md)
**Ветка:** Proxy reliability > connection pooling/reuse
**Зависит от:** нет
**Блокирует:** нет
**Оценка:** 2-3 инженерных дня
**Уверенность:** Medium

## Результат

E2E tests подтверждают observable pooling contract: последовательные requests
переиспользуют upstream connection, concurrent traffic остаётся bounded, а
connection после configured idle timeout заменяется новым без нарушения
ответа client.

## Требования

Раздел «Входит в v0»: connection pooling; finding AR-05.

## Критерии готовности

- [x] Реальный upstream фиксирует connection identity без зависимости от
  library-private implementation API.
- [x] Несколько последовательных requests до idle timeout используют одну
  upstream connection.
- [x] Bounded concurrent scenario не создаёт connection на каждый request.
- [x] После превышения configured idle timeout следующий request создаёт новую
  connection и получает корректный response.
- [x] Test использует production `loadAppConfig -> buildUpstreamWebClient`
  wiring с малыми test durations.
- [x] Production code не меняется. Если test выявляет defect, fix создаётся
  отдельной issue с RED-first TDD.
- [x] Focused test и `./gradlew build` проходят.

## Test/demo seam

Реальные Armeria gateway/upstream и server-side observable connection identity
на HTTP/1.1 keep-alive exchange.

## Не входит

Per-route pool limits, HTTP/2 multiplexing policy, retry/circuit breaker,
production fix внутри test-only issue.
