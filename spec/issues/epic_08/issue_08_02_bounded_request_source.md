# VIG-08-02: Bounded in-memory request source

**Статус:** Done
**Epic:** [EPIC-08](../../epics/epic_08_message_spooling_replay.md)  
**Ветка:** Request source > bounded in-memory ingest and replay  
**Зависит от:** [VIG-08-01](issue_08_01_spool_contract.md), [VIG-06-01](../epic_06/issue_06_01_protocol_contract.md)  
**Блокирует:** PII shadow request tracer bullet  
**Оценка:** 3-5 инженерных дней  
**Уверенность:** Medium

## Результат

Public request-source contract принимает client bytes с backpressure под
per-request/global quota, предоставляет complete read-only parser view и
replay-ит exact original byte sequence либо возвращает stable typed capacity
outcome без единого upstream byte и без retained quota.

## Public seam

Source ingest/read/replay lifecycle тестируется controlled Reactive Streams
publisher/subscriber demand без Armeria. Real gateway integration остаётся
отдельным tracer bullet. Tests наблюдают bytes, demand, state transitions,
quota counters и terminal outcome только через public source/quota API.

## Критерии приёмки

- [x] Public source/quota conformance suite покрывает каждый lifecycle, bounds,
  precedence и stable-outcome rule раздела EPIC-08 «Нормативный request source
  contract» без проверки private storage representation.
- [x] Demand matrix доказывает bounded ingest/replay и exact byte sequence через
  controlled publisher/subscriber, включая incorrect length и concurrent
  reservations.
- [x] State matrix проверяет complete read-only view, replay и все misuse
  outcomes только через public source/quota API без передачи ownership parser-у.
- [x] Cleanup matrix покрывает каждый terminal path parent contract и доказывает
  однократное освобождение всех owner, byte и bookkeeping reservations.
- [x] Sentinel corpus подтверждает, что public results, logs и state
  descriptions раскрывают только разрешённые parent contract данные.
- [x] Focused source tests и `./gradlew build` проходят.

## Edge cases

- Empty body, exact-limit body и overflow на один byte.
- Chunk, который одновременно превышает per-request и available global quota.
- Concurrent reservations around the final available byte.
- Concurrent owner admission around the final available slot; adversarial
  one-byte transport chunks остаются в configured retained-segment bound.
- Cancellation between reservation and retention, during parser view и during
  replay.
- Slow ingest producer, slow replay subscriber and repeated close.

## Не входит

JSON parsing, detector/window execution, HTTP mapping/integration, response or
SSE storage, disk spill, encryption at rest, source rewriting и production
resource baseline report.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ one bounded request lifecycle
  Acceptance:   0.15  ✓ quotas, demand and replay are observable
  Boundaries:   0.05  ✓ parser and HTTP integration separated
  Alternatives: 0.10  ✓ in-memory-only strategy selected
  Assumptions:  0.20  ✓ exact buffer representation is implementation detail
  Aggregate:    0.10  ✓ below threshold (0.3 issue)
```
