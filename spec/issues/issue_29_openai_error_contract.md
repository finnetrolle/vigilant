# VIG-29: OpenAI-compatible error contract for enforcement

- **ID:** `VIG-29`
- **Тип:** Issue
- **Статус:** Ready for implementation
- **Приоритет:** High
- **Зависит от:** нет
- **Блокирует:** [VIG-20-02](epic_20/issue_20_02_response_inspection_enforcement.md), [VIG-34](issue_34_request_pii_enforcement.md)
- **Оценка:** не оценено

## Цель

Определить точный OpenAI-compatible HTTP contract для результатов enforcement,
прежде чем реализовывать `BLOCK` и технические отказы request/response path.

## Известный контекст

- Policy action `BLOCK` не передаёт трафик дальше.
- Нехватка capacity, failure/timeout `fast-pii` и failure identity lookup дают
  `503 Service Unavailable` с `Retry-After: 1`. Для request они используют
  точный body:

  ```json
  {"error":{"message":"Request inspection unavailable.","type":"server_error","code":"request_inspection_unavailable"}}
  ```
- Error body не раскрывает PII, raw payload, Bearer token, identity data или
  внутренние причины работы gateway.
- Для `BLOCK` response клиент получает `403` и OpenAI-compatible safe JSON
  error. Upstream status, headers и body не раскрываются; `Retry-After` не
  передаётся.
- Human-readable `message` называет единственную безопасную причину MVP —
  `PII detected.`. Он не содержит
  detected value, span, body fragment или другие payload-derived details.
- Client error не раскрывает exact policy ID/version. Эти references остаются
  только в stdout audit event, доступном deployment/operations boundary.
- Exact `BLOCK` body для обоих направлений:

  ```json
  {"error":{"message":"<Request|Response> blocked: PII detected.","type":"policy_violation","code":"policy_blocked"}}
  ```

  `message` различает только direction; `type` и `code` стабильны. Эта схема не
  получает optional detail fields, policy references или payload-derived data.
- Других client-facing классов причины блокировки в MVP нет: новые detector или
  policy причины не добавляют новый текст, код либо поле API без отдельной
  задачи и изменения этого контракта.
- Malformed upstream response/SSE, upstream protocol error или missing SSE
  terminal `data: [DONE]` дают `502`:

  ```json
  {"error":{"message":"Invalid upstream response.","type":"upstream_error","code":"invalid_upstream_response"}}
  ```

  Этот outcome не раскрывает upstream status, headers или partial body.
- Detector/policy timeout or failure during response analysis gives `503` with
  `Retry-After: 1`:

  ```json
  {"error":{"message":"Response inspection unavailable.","type":"server_error","code":"response_inspection_unavailable"}}
  ```

  Client не получает partial upstream response.
- Значение `Retry-After` фиксировано: целое число `1` (одна секунда), без
  динамического расчёта, даты или policy-specific override.

## Принятая status/body matrix

| Ситуация | HTTP | `Retry-After` | `error.message` | `error.type` | `error.code` |
| --- | --- | --- | --- | --- | --- |
| Request `BLOCK` по PII | 403 | нет | `Request blocked: PII detected.` | `policy_violation` | `policy_blocked` |
| Response `BLOCK` по PII | 403 | нет | `Response blocked: PII detected.` | `policy_violation` | `policy_blocked` |
| Request inspection/capacity/identity failure | 503 | `1` | `Request inspection unavailable.` | `server_error` | `request_inspection_unavailable` |
| Response detector/policy failure | 503 | `1` | `Response inspection unavailable.` | `server_error` | `response_inspection_unavailable` |
| Malformed upstream response/SSE/protocol | 502 | нет | `Invalid upstream response.` | `upstream_error` | `invalid_upstream_response` |

Во всех строках body имеет ровно верхнеуровневое поле `error` с тремя строковыми
полями `message`, `type`, `code`; optional поля запрещены. Ответы не содержат
payload-derived details, policy references, identity, credentials, upstream
headers/status/body. `503` request не начинает upstream handoff; `503` response
не раскрывает никакой части upstream response.

## Не входит

- Реализация enforcement, response spool, policy engine или identity lookup.
- Изменение OpenAI protocol surface за пределами Chat Completions.

## Критерии готовности

- [ ] Реализация возвращает в точности указанную matrix: HTTP status, наличие и
  значение `Retry-After`, JSON fields и их значения.
- [ ] Контрактные HTTP tests покрывают все пять строк matrix и доказывают
  отсутствие optional/internal/payload-derived полей.
- [ ] Tests доказывают, что `BLOCK` не раскрывает upstream response, а request
  technical failure не начинает upstream handoff.
- [ ] Изменённые Kotlin declarations/test methods имеют KDoc; focused tests и
  `./gradlew build` проходят.

## Ambiguity Report

```text
Goals: 0.0; Acceptance: 0.10; Boundaries: 0.0; Alternatives: 0.0;
Assumptions: 0.10; Aggregate: 0.04. Ready for implementation.
```
