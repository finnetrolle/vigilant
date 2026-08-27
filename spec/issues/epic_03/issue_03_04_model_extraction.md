# VIG-03-04: Сборка PolicyContext из нормализованных данных

**Статус:** Done
**Epic:** [EPIC-03](../../epics/epic_03_policy_context_extraction.md)  
**Ветка:** Context assembly  
**Зависит от:** [VIG-03-01](issue_03_01_context_contract.md), [VIG-03-02](issue_03_02_url_normalization.md), [VIG-03-03](issue_03_03_identity_extraction.md), [VIG-06-01](../epic_06/issue_06_01_protocol_contract.md)  
**Блокирует:** [VIG-03-05](issue_03_05_response_handoff.md)  
**Оценка:** 2-3 инженерных дня

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

## Нормативный input contract

- EPIC-06 передаёт immutable `NormalizedProtocolAttributes` с единственным
  полем `model`; arbitrary attributes map отсутствует.
- Successful attributes обязаны содержать exact decoded non-blank model.
  Missing/blank model и contradictory phase дают typed safe assembly failure.
- Assembler принимает только normalized URL, normalized identity, explicit
  phase и normalized attributes. Target остается existing immutable
  `PolicyContext` EPIC-04.

## Тестовый seam

Table-driven pure unit tests с синтетическими normalized inputs, без Armeria,
JSON parser и сетевых вызовов.

## Критерии приёмки

- [x] Одинаковые normalized inputs дают структурно одинаковый context.
- [x] Model и другие protocol-derived значения переносятся без повторного
  parsing или normalization, не принадлежащего EPIC-03.
- [x] Missing, contradictory и invalid inputs имеют явные typed outcomes.
- [x] Protocol-specific и transport-specific types отсутствуют в public API
  EPIC-03.
- [x] Context не содержит payload, provenance, credentials или raw headers.
- [x] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [x] Focused tests и `./gradlew test` проходят.

## Не входит

Разбор protocol message, извлечение attributes или payload, lossless
forwarding, policy matching и detector execution.
