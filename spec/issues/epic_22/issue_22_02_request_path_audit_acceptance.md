# VIG-22-02: Mandatory audit acceptance в request path

**Статус:** Ready for implementation
**Epic:** [EPIC-22](../../epics/epic_22_durable_minimum_audit_trail.md)
**Ветка:** Request-path tracer bullet > durable-before-forwarding
**Зависит от:** [VIG-22-01](issue_22_01_local_durable_audit_store.md)
**Блокирует:** [VIG-22-04](issue_22_04_packaged_durability_qualification.md)
**Оценка:** 4-5 инженерных дней
**Уверенность:** High

## Результат

Real supported Chat Completions request резервирует mandatory audit capacity
до body demand, сохраняет одну safe aggregate record и достигает upstream либо
исходного supported-request response только после durable acknowledgement.
Audit capacity/I/O failure возвращает stable `audit_unavailable`, не вызывает
upstream и отражается в readiness.

## Требования

`MVP-18`, `MVP-19`, `CONC-01`, `CONC-03`, current Chat Completions runtime
contract; [minimum audit trail contract](../../MINIMUM_AUDIT_TRAIL_CONTRACT.md).

## Критерии готовности

- [ ] Supported descriptor резервирует audit token до identity extraction и
  body demand. Unsupported descriptor и invalid tracing session сохраняют
  текущий no-shadow-audit contract.
- [ ] Reservation exhaustion возвращает
  `503 {"error":"audit_unavailable"}`, не demand-ит request body и не вызывает
  upstream.
- [ ] Existing aggregate decision формируется как immutable safe record, а
  stdout `policy.shadow_decision`, если сохранён, является только производной
  best-effort projection и не завершает audit acceptance.
- [ ] Matrix `CLEAN`, `DETECTED`, `INSPECTION_GAP` и detector/policy `ERROR`
  подтверждает, что первый upstream request byte наблюдается только после
  durable acknowledgement соответствующей record.
- [ ] Все supported identity, source, parser, URL/context assembly, handoff и
  unexpected inspection failures сохраняют одну `ERROR` record до публикации
  исходного stable response. Exact error code и HTTP mapping остаются
  неизменными, если audit acceptance успешен.
- [ ] Audit `CAPACITY_EXHAUSTED`, `EVENT_TOO_LARGE`, `IO_FAILURE` и `CLOSED`
  outcomes отображаются только в `503 audit_unavailable`; raw exception, path
  и record contents не попадают в client response или operational telemetry.
- [ ] Audit failure supersedes ещё не опубликованный normal supported-request
  response, не вызывает upstream и переводит `/readyz` в `503` до
  подтверждённого восстановления store capacity/health.
- [ ] Client cancellation до decision освобождает reservation без record.
  Cancellation после decision не снимает append obligation: workflow завершает
  handoff в `STORE_OWNED`, затем store сохраняет ownership до durable outcome;
  новый upstream handoff запрещён.
- [ ] Graceful shutdown сначала переводит readiness в not-ready и запрещает
  новые audit admissions, затем boundedly ждёт active decisions и durable
  appends, seal-ит store и только потом завершает normal process stop.
- [ ] Request, tracing, packaged-process, performance, OCI smoke и другие
  shared launch fixtures получают mandatory audit directory и settings через
  canonical configuration helper; released ephemeral ports не используются.
- [ ] No file operation, force или blocking future wait выполняется на Netty
  event loop. Orchestration ждёт store на существующей blocking-safe boundary.
- [ ] Audit record и captured logs не содержат body, matched-text, query,
  identity, session, auth/cookie или locator sentinels во всех success и
  failure cases.
- [ ] Для всех добавленных и изменённых Kotlin/Java declarations, lifecycle
  helpers и test methods написан актуальный KDoc/Javadoc.
- [ ] Focused real-Armeria E2E suite, affected process tests и
  `./gradlew build` проходят.

## Test/demo seam

Real Armeria client, gateway и upstream плюс public test audit store, который
использует causal barriers на `STORE_OWNED` и `DURABLY_RETAINED`.
Upstream fixture сигнализирует observation первого request byte. Assertions
доказывают ordering через barriers и bounded waits, а не timestamps или
sleeps.

## Не входит

Collector implementation, segment acknowledgement/reclaim, vendor delivery,
response audit/inspection, `BLOCK`/`MASK`/`REMOVE`, retry upstream и изменение
существующих protocol error codes кроме нового `audit_unavailable`.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   no forwarding before durable acceptance
  Acceptance:   0.10  decision, error, cancellation и shutdown matrices explicit
  Boundaries:   0.0   Collector и future reactions исключены
  Alternatives: 0.05  existing blocking-safe orchestration seam используется
  Assumptions:  0.10  module contract VIG-22-01 является hard dependency
  Aggregate:    0.05  below threshold (0.3 issue)
```
