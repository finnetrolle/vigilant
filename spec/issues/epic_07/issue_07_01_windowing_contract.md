# VIG-07-01: Контракт windowed payload processing

**Статус:** Draft  
**Epic:** [EPIC-07](../../epics/epic_07_windowed_payload_processing.md)  
**Ветка:** Windowing contract and detector capabilities  
**Зависит от:** [VIG-06-01](../epic_06/issue_06_01_protocol_contract.md)  
**Блокирует:** остальные issues EPIC-07  
**Оценка:** 2-3 инженерных дня

## Результат

Нормативный windowing contract определён настолько точно, что генерацию окон,
detector execution, offset translation, deduplication и resource bounds можно
декомпозировать в независимо исполняемые issues.

Это documentation-only issue. Production implementation и интеграция не
добавляются.

## Подтверждённые решения

- Большой fragment не блокируется только из-за detector payload limit.
- Silent truncation запрещён.
- Windowing protocol-agnostic и никогда не пересекает logical fragment
  boundaries.
- Итоговые findings используют UTF-8 offsets исходного fragment.
- Windowing оформляется отдельным EPIC-07, а не частью protocol parser.

## Решения, которые нужно закрыть

1. Ownership window generation, detector invocation и aggregation.
2. Detector capability contract для safe window size и overlap.
3. Exact boundary semantics и гарантия отсутствия false negatives.
4. Offset translation и deduplication.
5. Ordering, parallelism, cancellation и detector errors.
6. Memory, backpressure и hard resource exhaustion.
7. Reaction mapping через EPIC-06 provenance.

## Критерии готовности draft

- [ ] Все семь решений имеют один выбранный вариант и rationale.
- [ ] EPIC-07 не дублирует parser, policy matcher или detector recognizers.
- [ ] Для detectors без bounded match length задана честная поддерживаемая
  стратегия, а не необоснованный фиксированный overlap.
- [ ] Все window-local results однозначно переводятся в original-fragment
  coordinates и дедуплицируются.
- [ ] Созданы implementation issues размером не более пяти инженерных дней.
- [ ] Для каждой issue заданы test seam, cancellation и non-goals.
- [ ] EPIC-07 и готовые дочерние issues имеют ambiguity aggregate не выше
  `0.3`.

## Не входит

Production windowing, protocol parsing, detector implementation, HTTP
integration и применение reaction к encoded message.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.10
  Acceptance:   0.50
  Boundaries:   0.45
  Alternatives: 0.60
  Assumptions:  0.55
  Aggregate:    0.44
```

Оставить `Draft` до закрытия перечисленных решений.
