# VIG-03-04: Извлечение модели без потери исходного body

**Статус:** Draft  
**Epic:** [EPIC-03](../../epics/epic_03_policy_context_extraction.md)  
**Ветка:** LLM protocol > model extraction  
**Зависит от:** [VIG-03-01](issue_03_01_context_contract.md)  
**Блокирует:** [VIG-03-05](issue_03_05_response_handoff.md)  
**Оценка после уточнения:** 4-7 инженерных дней на один API shape

## Результат

Versioned compatibility adapter извлекает model из явно поддерживаемого
OpenAI-compatible request shape, сохраняя исходные body bytes для lossless
forwarding разрешённого запроса.

## Нормативные ограничения

- Не восстанавливать forwarded body из typed DTO.
- Unknown fields сохраняются без изменений.
- Неподдерживаемая или ambiguous content-bearing структура не разрешается
  молча и даёт stable safe error.
- Adapter создаёт отдельный normalized view только для нужных guardrails data.
- Detector и policy engine не получают raw HTTP request.

## Требует решения VIG-03-01

- Поддерживаемые endpoints и schema versions.
- Поведение при missing/non-string/ambiguous `model`.
- Граница buffering и максимальный inspectable request size.

## Критерии готовности после уточнения

- [ ] Поддерживаемые shapes дают точное normalized model.
- [ ] Unknown fields и точные original bytes сохраняются для forwarding.
- [ ] Unsupported shapes fail closed со stable error без body preview.
- [ ] E2E/adapter tests проверяют byte-for-byte forwarding unknown fields.
- [ ] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [ ] Focused tests и `./gradlew test` проходят.

## Не входит

Все OpenAI/Anthropic endpoints, content extraction для detectors и payload
modification.
