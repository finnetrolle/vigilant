# VIG-14: Strict protocol and inspection-gap outcomes

- **ID:** `VIG-14`
- **Тип:** Issue
- **Статус:** Done
- **Приоритет:** High
- **Зависит от:** [VIG-13](issue_13_pii_shadow_request_tracer.md)
- **Блокирует:** [VIG-16](issue_16_packaged_shadow_proxy_evidence.md)
- **Оценка:** 2-3 инженерных дня
- **Уверенность:** Medium

## Результат

Malformed, ambiguous и unsupported request не отправляют ни одного upstream
byte и получают stable safe HTTP outcome. Schema-recognized non-text content
forwarding-ится unchanged, но audit event явно сообщает inspection gap и
partial или uninspectable coverage.

## Public seam

Real Armeria E2E seam из VIG-13 с upstream request counter и captured safe logs.

## Критерии готовности

- [x] Unsupported descriptor возвращает stable `unsupported_schema` без upstream call.
- [x] Malformed JSON/schema возвращает stable `malformed_message`.
- [x] Ambiguous content возвращает stable `ambiguous_content`.
- [x] Unresolved context и parser resource outcome имеют stable safe mapping.
- [x] Known non-text gap forwarding-ится byte-identical и даёт
  `INSPECTION_GAP`, не `CLEAN`.
- [x] Каждый supported terminal path создаёт ровно один safe audit event.
- [x] Focused E2E tests и `./gradlew build` проходят.

## Не входит

External reference lookup, non-text detector, Responses API и response parsing.
