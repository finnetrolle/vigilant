# VIG-03-04: Сборка PolicyContext из нормализованных данных

**Статус:** Draft  
**Epic:** [EPIC-03](../../epics/epic_03_policy_context_extraction.md)  
**Ветка:** Context assembly  
**Зависит от:** [VIG-03-01](issue_03_01_context_contract.md), [VIG-03-02](issue_03_02_url_normalization.md), [VIG-03-03](issue_03_03_identity_extraction.md), [VIG-06-01](../epic_06/issue_06_01_protocol_contract.md)  
**Блокирует:** [VIG-03-05](issue_03_05_response_handoff.md)  
**Оценка после уточнения:** 2-3 инженерных дня

## Результат

Pure deterministic assembler получает normalized URL, identity, phase и
provider-neutral protocol attributes из EPIC-06 и возвращает один immutable
`PolicyContext` для request phase.

EPIC-03 не получает raw body, JSON document model, SSE events, Realtime
events или Batch records. Все извлечения из этих источников выполняет EPIC-06.

## Нормативные ограничения

- Assembler не знает схемы OpenAI или Anthropic.
- `model` и остальные body-derived attributes принимаются из normalized
  результата EPIC-06 и не извлекаются повторно.
- Payload fragments и их content не входят в `PolicyContext`.
- Raw credentials и исходные identity header values не входят в context.
- Missing, contradictory или invalid input имеет явную typed safe семантику,
  а не выводится из nullable-полей или exception message.
- Результат не зависит от `PolicyProvider` и не содержит transport types.

## Требует решения

- Точный provider-neutral attributes contract EPIC-06.
- Представление missing и invalid protocol-derived attributes.
- Инварианты `PolicyContext` и typed result сборки.

## Тестовый seam

Table-driven pure unit tests с синтетическими normalized inputs, без Armeria,
JSON parser и сетевых вызовов.

## Критерии готовности после уточнения

- [ ] Одинаковые normalized inputs дают структурно одинаковый context.
- [ ] Model и другие protocol-derived значения переносятся без повторного
  parsing или normalization, не принадлежащего EPIC-03.
- [ ] Missing, contradictory и invalid inputs имеют явные typed outcomes.
- [ ] Protocol-specific и transport-specific types отсутствуют в public API
  EPIC-03.
- [ ] Context не содержит payload, provenance, credentials или raw headers.
- [ ] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [ ] Focused tests и `./gradlew test` проходят.

## Не входит

Разбор protocol message, извлечение attributes или payload, lossless
forwarding, policy matching и detector execution.
