# VIG-29: OpenAI-compatible error contract for enforcement

- **ID:** `VIG-29`
- **Тип:** Issue
- **Статус:** Draft
- **Приоритет:** High
- **Зависит от:** нет
- **Блокирует:** нет
- **Оценка:** не оценено

## Цель

Определить точный OpenAI-compatible HTTP contract для результатов enforcement,
прежде чем реализовывать `BLOCK` и технические отказы request/response path.

## Известный контекст

- Policy action `BLOCK` не передаёт трафик дальше.
- Нехватка capacity, failure/timeout `fast-pii` и failure identity lookup дают
  `503 Service Unavailable` с `Retry-After`.
- Error body не раскрывает PII, raw payload, Bearer token, identity data или
  внутренние причины работы gateway.

## Открытые решения

- HTTP status и stable machine code для policy `BLOCK`.
- JSON schema и обязательные/запрещённые поля OpenAI-compatible error body.
- Различение request-side и response-side enforcement failure.
- Exact `Retry-After` semantics и безопасный client-facing text.

## Не входит

- Реализация enforcement, response spool, policy engine или identity lookup.
- Изменение OpenAI protocol surface за пределами Chat Completions.

## Критерий готовности задачи

Issue становится `Ready for implementation`, когда содержит полную status/body
matrix и E2E acceptance cases для всех перечисленных outcome без утечки
защищённых данных.

## Ambiguity Report

```text
Goals: 0.0; Acceptance: 0.75; Boundaries: 0.0; Alternatives: 0.5;
Assumptions: 0.5; Aggregate: 0.35. Draft: error contract intentionally deferred.
```
