# VIG-03-06: E2E security identity handoff

**Статус:** Done
**Epic:** [EPIC-03](../../epics/epic_03_policy_context_extraction.md)  
**Ветка:** Lifecycle > end-to-end security behavior  
**Зависит от:** [VIG-03-03](issue_03_03_identity_extraction.md), [VIG-03-05](issue_03_05_response_handoff.md)  
**Оценка:** 3-4 инженерных дня

## Результат

Historical E2E security path заменён VIG-27. Current real-Armeria evidence
подтверждает configured Dummy identity в request/response context, safe
pre-body Bearer rejects, отсутствие token в logs/audit и unchanged accepted
Authorization upstream.

## Критерии приёмки

- [x] Legacy extractor modes и consumed-header stripping удалены VIG-27.
- [x] End-to-end headers и raw body, не относящиеся к identity, сохраняются.
- [x] Token и identity sentinel values отсутствуют stdout/stderr; accepted
  Authorization достигает upstream unchanged.
- [x] Request и streaming/non-streaming response получают один context.
- [x] Existing proxy transparency, backpressure и cancellation tests проходят.
- [x] `./gradlew build` проходит.

## Не входит

Policy engine invocation, masking/block response и external authentication.
