# VIG-31: Cache external identity lookup

- **ID:** `VIG-31`
- **Тип:** Issue
- **Статус:** Draft
- **Приоритет:** High
- **Зависит от:** [VIG-30](issue_30_external_identity_extractor.md)
- **Блокирует:** нет
- **Оценка:** не оценено

## Цель

Добавить bounded cache результатов external identity lookup, чтобы прогретый
identity path не входил в latency budget guardrail processing.

## Известный контекст

- VIG-35 сохраняет `DUMMY`, `JWT` и `EXTERNAL` как три startup-selectable
  реализации общего async `BearerIdentityExtractor`. Cache принадлежит только
  `EXTERNAL`; локальные `DUMMY` и JWT не используют и не конфигурируют его.
- VIG-30 реализовал internal `ExternalIdentityLookup` seam с safe
  `Resolved(NormalizedIdentity)`/`Unavailable(code)` result. Cache decorator
  вставляется между `ExternalIdentityExtractor` и `BridgeIdentityClient`:
  hit не требует Bridge permit, miss проходит существующий immediate admission.
- Performance SLO `p99 <= 2 ms` при 2 000 RPS измеряется с warm cache или mock
  extractor.
- Cache хранит только безопасно выбранный key и normalized user/groups; raw
  Bearer token не сохраняется и не логируется.
- Cache miss вызывает external lookup. Его failure даёт `503`.

## Открытые решения

- Cache key derivation, TTL/expiry и invalidation.
- Size bound, eviction policy, concurrency и memory limit.
- Metrics hit/miss/eviction и правила безопасной диагностики.
- Deterministic test clock и E2E seam.

## Не входит

- External provider protocol, policy matching или distributed shared cache.
- Persistence cache на диск и cross-replica synchronization.
- Cache или fallback для `DUMMY`/JWT и переключение identity mode без restart.

## Критерий готовности задачи

Issue становится `Ready for implementation`, когда cache contract исключает
retention token, задаёт expiry/eviction и имеет воспроизводимую test matrix.

## Ambiguity Report

```text
Goals: 0.25; Acceptance: 0.75; Boundaries: 0.0; Alternatives: 0.5;
Assumptions: 0.5; Aggregate: 0.40. Draft: cache semantics deferred.
```
