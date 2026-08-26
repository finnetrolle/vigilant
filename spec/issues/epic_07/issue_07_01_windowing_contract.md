# VIG-07-01: Контракт windowed payload processing

**Статус:** Done  
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

## Закрытые решения

1. Public windowed executor владеет window generation, invocation одного
   detector для одного complete logical fragment, offset translation,
   validation, deduplication и aggregation. Parser, policy matching и
   protocol rewriting остаются за границей.
2. Detector capability публикует `maxWindowUtf8Bytes` и nullable
   `maximumEvidenceSpanUtf8Bytes`. Evidence span включает весь finding и
   обязательный left/right lookaround recognizer-а. Для bounded detector
   overlap равен `maximumEvidenceSpanUtf8Bytes - 1`; отсутствие finite proof
   запрещает windowing большого fragment и даёт `WINDOWING_UNSUPPORTED`.
3. Window start/end всегда являются UTF-8 code-point boundaries. Следующий
   start выбирается на последней boundary не правее `previousEnd - overlap`,
   поэтому фактический overlap не меньше required. Capability обязана
   оставлять progress минимум на один maximum-width UTF-8 code point.
4. Local finding offsets проверяются относительно window и переводятся через
   `windowStartUtf8 + localOffset`. Duplicate identity состоит из type,
   translated span и recognizer ID; metadata duplicates обязаны совпасть,
   иначе result равен safe `INCONSISTENT_WINDOW_RESULT`.
5. Первый increment выполняет окна последовательно и exhaustive
   (`stopOnFirst=false`) на bounded CPU executor. Первый detector error
   прекращает новые calls и возвращает единый error result без partial
   findings. Cancellation остаётся cancellation и прекращает новые calls.
6. Executor удерживает original fragment и не более одного window/result
   batch, не создаёт queue и не управляет HTTP demand. Fragment ограничен
   EPIC-08 request source; собственный invalid capability/resource state даёт
   typed safe error без truncation.
7. Aggregated findings сохраняют original fragment provenance без window ID.
   Reaction видит offsets decoded fragment; mapping в encoded JSON остаётся
   будущим contract EPIC-06 rewriter.

## Критерии готовности

- [x] Все семь решений имеют один выбранный вариант и rationale.
- [x] EPIC-07 не дублирует parser, policy matcher или detector recognizers.
- [x] Для detectors без bounded match length задана честная поддерживаемая
  стратегия, а не необоснованный фиксированный overlap.
- [x] Все window-local results однозначно переводятся в original-fragment
  coordinates и дедуплицируются.
- [x] Создана [VIG-07-02](issue_07_02_windowed_fast_pii_execution.md) размером
  не более пяти инженерных дней.
- [x] Для issue заданы test seam, cancellation и non-goals.
- [x] EPIC-07 и готовая дочерняя issue имеют ambiguity aggregate не выше
  `0.3`.

## Не входит

Production windowing, protocol parsing, detector implementation, HTTP
integration и применение reaction к encoded message.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ one fragment-to-findings capability
  Acceptance:   0.10  ✓ boundary corpus and exact offsets fixed
  Boundaries:   0.05  ✓ executor ownership explicit
  Alternatives: 0.10  ✓ overlap and unbounded-detector behavior selected
  Assumptions:  0.20  ✓ Fast PII capability proof remains implementation evidence
  Aggregate:    0.09  ✓ below threshold (0.3 issue)
```
