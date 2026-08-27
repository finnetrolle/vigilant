# VIG-13: PII shadow request tracer bullet

- **ID:** `VIG-13`
- **Тип:** Issue
- **Статус:** Done
- **Приоритет:** High
- **Зависит от:** [VIG-03-07](epic_03/issue_03_07_anonymous_request_context.md), [VIG-06-02](epic_06/issue_06_02_chat_completions_request_parser.md), [VIG-07-02](epic_07/issue_07_02_windowed_fast_pii_execution.md), [VIG-08-02](epic_08/issue_08_02_bounded_request_source.md), [VIG-11](issue_11_fast_pii_policy_adapter.md), [VIG-12](issue_12_global_shadow_coverage_validation.md)
- **Блокирует:** [VIG-14](issue_14_strict_protocol_gap_outcomes.md), [VIG-15](issue_15_capacity_cancellation_outcomes.md)
- **Оценка:** 3-5 инженерных дней
- **Уверенность:** Medium

## Результат

Real `POST /v1/chat/completions` JSON request полностью spooled и проверяется
до первого upstream byte. Request с PII forwarding-ится byte-for-byte из
original source в shadow-only mode и создаёт ровно один safe aggregated
`policy.shadow_decision` event с decision `DETECTED` и disposition `ALLOW`.

## Public seam

E2E tests используют real Armeria client, production gateway service и real
upstream на ephemeral ports. Exact upstream bytes и structured log event
наблюдаются только через HTTP и captured application logger. Completion logs
проверяются deadline-bounded polling через `GatewayTestFixture.awaitUntil`.

## Критерии готовности

- [x] Supported descriptor выбирается до body parse.
- [x] Body полностью retained, parsed и inspected вне Netty event loop.
- [x] Anonymous context применяет configured global `fast-pii` policy.
- [x] Allowed request сохраняет method, path/query, end-to-end headers и exact body.
- [x] Один audit event содержит protocol, decision, disposition, coverage,
  trace ID, sorted policies/detector versions, counts и total duration.
- [x] Event не содержит body, matched text, offsets, locators, headers или credentials.
- [x] Existing upstream response, включая SSE, остаётся streaming pass-through.
- [x] Focused E2E tests и `./gradlew build` проходят.

## Не входит

Response inspection, body transformation, `BLOCK`, identity extraction и
другие OpenAI APIs.
