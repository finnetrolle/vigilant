# VIG-21-01: Контракт минимального обязательного audit trail

**Статус:** Ready for implementation
**Epic:** [EPIC-21](../../epics/epic_21_post_milestone_architecture_closure.md)
**Ветка:** Audit governance > minimum mandatory audit trail
**Зависит от:** нет
**Блокирует:** [VIG-21-05](issue_21_05_roadmap_frontier_reconciliation.md)
**Оценка:** 2-3 инженерных дня
**Уверенность:** High

## Результат

Нормативный contract однозначно определяет минимальный audit trail Vigilant:
момент acceptance, durability boundary, retained safe fields, failure outcomes,
shutdown behavior и ответственность внешнего Collector. Выбранный contract
разложен на independently grabbable implementation issues размером не более
пяти инженерных дней.

Это documentation-only decision issue. Production logging и data path не
изменяются.

## Требования

`MVP-18`, `MVP-19`, `OUT-06`, finding AR-10.

## Критерии готовности

- [ ] Различены четыре состояния: policy decision создан, принят audit
  boundary, durably retained, доставлен external consumer.
- [ ] Выбрано одно проверяемое значение минимальной durability guarantee;
  `logger.atInfo()` и best-effort stdout не объявляются durable acceptance.
- [ ] Для normal success, supported-request failure, queue/storage exhaustion,
  process shutdown и crash перечислены exact externally observable outcomes.
- [ ] Зафиксировано, может ли request завершиться успешно до audit acceptance и
  что происходит, если acceptance невозможно.
- [ ] Safe schema сохраняет policy ID/version, decision, disposition, coverage,
  correlation и bounded aggregates без payload, matched text, identity values,
  credentials, locators или reversible hashes.
- [ ] Resource ownership, byte/event bounds, backpressure и blocking boundary
  определены без file/network I/O на Netty event loop.
- [ ] OUT-06 соблюдён: собственная SIEM, query UI и полное observability storage
  не добавлены.
- [ ] Альтернативы, включая application-owned WAL и acknowledged external
  delivery, сравнены по durability, operability, privacy и failure semantics.
- [ ] Выбранный contract разложен на готовые implementation leaves с hard
  dependencies, estimates, test seams и non-goals; placeholders без файла не
  считаются work items.
- [ ] `./gradlew validateWorkItems` проходит после публикации decomposition.

## Test/demo seam

Normative contract matrix, alternatives decision, dependency graph и
опубликованные implementation leaves. Каждая leaf issue называет public
process or module seam, на котором проверяется её durability outcome.

## Не входит

Production implementation, выбор конкретного vendor/SIEM, raw audit payload,
traces/metrics retention, response inspection и enforcement reactions.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.05  minimum audit trail outcome задан
  Acceptance:   0.10  state, failure и decomposition matrices требуются
  Boundaries:   0.0   documentation-only decision issue
  Alternatives: 0.30  durability mechanism ещё должен быть выбран
  Assumptions:  0.20  deployment boundary требует явного решения
  Aggregate:    0.13  below threshold (0.3 issue)
```
