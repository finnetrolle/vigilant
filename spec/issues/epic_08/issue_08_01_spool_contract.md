# VIG-08-01: Контракт source, spool и replay

**Статус:** Draft  
**Epic:** [EPIC-08](../../epics/epic_08_message_spooling_replay.md)  
**Ветка:** Source/spool contract  
**Зависит от:** [VIG-06-01](../epic_06/issue_06_01_protocol_contract.md)  
**Блокирует:** остальные issues EPIC-08  
**Оценка:** 2-3 инженерных дня

## Результат

Source ownership, spool lifecycle, replay semantics и resource boundaries
определены настолько точно, что memory path, spill path, cleanup и E2E tests
можно декомпозировать в независимо исполняемые issues.

Это documentation-only issue. Production spool и integration не добавляются.

## Подтверждённые решения

- Original source принадлежит integration spool, а не protocol parser.
- Parser читает source в read-only режиме и не возвращает raw body.
- Unmodified forwarding replay-ит original source без DTO serialization.
- Spooling оформляется отдельным EPIC-08.
- SSE response является атомарной policy-транзакцией MVP: до terminal event и
  итогового decision клиент не получает upstream status, headers или body.
  Полный `ALLOW` replay-ит original SSE, любой `BLOCK` даёт safe proxy error
  без partial forwarding.

## Решения, которые нужно закрыть

1. Source abstractions и ownership readers.
2. Memory-to-spill transition без двойной полной копии.
3. Storage security и cleanup lifecycle.
4. Quotas, exhaustion и stable outcomes.
5. Ingest/replay backpressure и blocking I/O isolation.
6. Request и ordinary response semantics, а также bounded mechanics уже
   принятого atomic SSE lifecycle.
7. Contract с parser и future rewriter.

## Критерии готовности draft

- [ ] Все семь решений имеют один выбранный вариант и rationale.
- [ ] Request и response lifecycle не смешаны неявно.
- [ ] SSE tests подтверждают отсутствие client-visible upstream bytes до
  decision, lossless replay при `ALLOW` и отсутствие partial forwarding при
  `BLOCK`.
- [ ] Byte-for-byte replay проверяем для memory и spill paths.
- [ ] Все resource classes имеют bounds либо baseline issue для их выбора.
- [ ] Cleanup matrix покрывает success, block, failures, timeout, cancellation
  и shutdown.
- [ ] Temporary source не раскрывается через logs или safe errors.
- [ ] Созданы implementation issues размером не более пяти инженерных дней.
- [ ] EPIC-08 и готовые issues имеют ambiguity aggregate не выше `0.3`.

## Не входит

Production spool, protocol parsing, policy execution, windowing и rewriting.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.10
  Acceptance:   0.50
  Boundaries:   0.35
  Alternatives: 0.60
  Assumptions:  0.60
  Aggregate:    0.43
```

Оставить `Draft` до закрытия перечисленных решений.
