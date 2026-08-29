# VIG-08-01: Контракт source, spool и replay

**Статус:** Done  
**Epic:** [EPIC-08](../../epics/epic_08_message_spooling_replay.md)  
**Ветка:** Source/spool contract  
**Зависит от:** [VIG-06-01](../epic_06/issue_06_01_protocol_contract.md)  
**Блокирует:** [VIG-08-02](issue_08_02_bounded_request_source.md)
**Оценка:** 2-3 инженерных дня

## Результат

Request source ownership, in-memory lifecycle, replay semantics и resource
boundaries определены настолько точно, что первый implementation leaf можно
выполнить независимо. Response/SSE source и disk spill не получили скрытых
defaults и после завершения request increment перенесены в future
[EPIC-20](../../epics/epic_20_response_spooling_secure_spill.md).

Это documentation-only issue. Production spool и integration не добавляются.

## Подтверждённые решения

- Original source принадлежит integration spool, а не protocol parser.
- Parser читает source в read-only режиме и не возвращает raw body.
- Unmodified forwarding replay-ит original source без DTO serialization.
- Bounded in-memory request spooling оформляется отдельным EPIC-08.
- Для future response-inspection increment SSE остаётся атомарной
  policy-транзакцией: до terminal event и итогового decision клиент не получает
  upstream bytes. В первом production increment response/SSE storage не
  активно, а существующий response path остаётся streaming pass-through.
  Future implementation принадлежит EPIC-20.

## Закрытые решения первого production increment

1. Integration layer создаёт один request source owner. После complete ingest
   owner выдаёт последовательные read-only views parser-у и replay publisher-у;
   views не владеют quota. Только idempotent owner close освобождает bytes.
2. Первый increment является in-memory only. Memory-to-spill transition,
   temporary files и disk quota не имеют скрытого default и остаются future
   scope.
3. Source хранится только в process-private memory, не сериализуется и не
   попадает в logs/errors. Cleanup matrix покрывает complete success, parse и
   detector failure, capacity rejection, timeout, cancellation и shutdown.
4. Configurable defaults: per-request `8 MiB`, global retained `64 MiB`, `128`
   concurrent request owners и `128` retained segments на owner. Per-request
   overflow имеет precedence и даёт `REQUEST_TOO_LARGE` -> HTTP
   `413 {"error":"request_too_large"}`. Owner-slot или global-byte reservation
   failure даёт `INSPECTION_CAPACITY_EXHAUSTED` -> HTTP
   `503 {"error":"inspection_capacity_exhausted"}`.
5. Owner slot резервируется до body demand. Ingest coalesce/split-ит transport
   chunks до configured segment-count bound, резервирует global bytes до retain
   и запрашивает следующий chunk только после успешного accounting. Exact
   bookkeeping bound равен concurrent-owner limit, умноженному на per-owner
   segment limit. Replay запрашивает source bytes только по downstream demand.
   Blocking I/O отсутствует.
6. Активный lifecycle только request direction: весь body принят и проверен до
   первого upstream byte. Ordinary response и SSE не spool-ятся и остаются
   существующим pass-through первого increment.
7. Parser получает complete immutable segmented byte view без ownership и
   второй полной копии. Replay сохраняет exact byte sequence, но не обязан
   сохранять transport chunk boundaries. Future rewriter требует отдельного
   mutable-patch contract и не входит в source API.

## Критерии готовности

- [x] Все семь решений имеют один выбранный вариант и rationale.
- [x] Request и response lifecycle разделены явно.
- [x] Response/SSE source lifecycle сохранён как future Draft в EPIC-20 и не
  создаёт требований к request-only first increment.
- [x] Byte-for-byte replay проверяем для in-memory path; spill path не входит.
- [x] Все активные resource classes имеют exact configurable bounds.
- [x] Cleanup matrix покрывает success, block, failures, timeout, cancellation
  и shutdown.
- [x] Source не раскрывается через logs или safe errors.
- [x] Создана [VIG-08-02](issue_08_02_bounded_request_source.md) размером не
  более пяти инженерных дней.
- [x] EPIC-08 и готовая issue имеют ambiguity aggregate не выше `0.3`.

## Не входит

Production spool, protocol parsing, policy execution, windowing и rewriting.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ request source outcome fixed
  Acceptance:   0.10  ✓ byte replay, quotas and cleanup observable
  Boundaries:   0.05  ✓ future response/disk scope moved to EPIC-20
  Alternatives: 0.10  ✓ in-memory lifecycle selected
  Assumptions:  0.20  ✓ defaults are profiling baselines
  Aggregate:    0.09  ✓ below threshold (0.3 issue)
```
