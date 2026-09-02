# VIG-30: External Bearer identity extractor

- **ID:** `VIG-30`
- **Тип:** Issue
- **Статус:** Draft
- **Приоритет:** High
- **Зависит от:** [VIG-35](issue_35_production_identity_mode.md)
- **Блокирует:** [VIG-31](issue_31_identity_lookup_cache.md)
- **Оценка:** не оценено

## Цель

Добавить production identity extractor, который по Bearer token получает у
внешней системы пользователя и его группы для policy matching.

## Известный контекст

- Bearer token принадлежит конечному LiteLLM/LLM service и передаётся upstream
  byte-for-byte.
- Vigilant временно использует token только для identity lookup; token не
  попадает в audit, logs, metrics, traces или errors.
- Request без доступной identity при cache miss получает `503` и не доходит до
  upstream.
- Extractor возвращает user/groups; policy groups match exact name или `*` по
  правилу `ANY`.

## Открытые решения

- External system endpoint, request/response protocol и trust model.
- Mapping внешнего ответа в normalized user/groups.
- Lookup timeout, retries и безопасная диагностика.
- Deployment configuration и test double для provider.

## Не входит

- Cache результатов lookup, JWT verification, token exchange, refresh и
  изменение upstream authorization.
- Policy reaction, PII detection или enforcement transport.

## Критерий готовности задачи

Issue становится `Ready for implementation`, когда внешний identity contract,
failure matrix, configuration и E2E seam согласованы.

## Ambiguity Report

```text
Goals: 0.25; Acceptance: 0.75; Boundaries: 0.0; Alternatives: 0.5;
Assumptions: 0.75; Aggregate: 0.45. Draft: external provider contract deferred.
```
