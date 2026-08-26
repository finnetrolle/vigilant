# VIG-06-02: Chat Completions JSON request parser

**Статус:** Ready for implementation  
**Epic:** [EPIC-06](../../epics/epic_06_llm_message_parsing.md)  
**Ветка:** OpenAI Chat Completions > JSON request  
**Зависит от:** [VIG-06-01](issue_06_01_protocol_contract.md)  
**Блокирует:** [VIG-03-07](../epic_03/issue_03_07_anonymous_request_context.md), PII shadow request tracer bullet  
**Оценка:** 3-5 инженерных дней  
**Уверенность:** Medium

## Результат

Pure public parser принимает complete read-only JSON source для pinned
`POST /v1/chat/completions` request и возвращает normalized request model,
ordered text fragments, provenance и coverage либо typed safe failure. Parser
не реконструирует source и не теряет unknown additional properties, потому что
lossless forwarding использует original source за пределами EPIC-06.

## Public seam

Transport-neutral parse operation над immutable byte source и versioned
operation descriptor. Tests проверяют только public success/failure result:
normalized `model`, ordered fragments, semantic kinds, explicit roles, opaque
locators, inspection gaps и stable failure code. Armeria, policy engine,
detector и spool implementation не мокируются и не входят в seam.

## Критерии приёмки

- [ ] Versioned conformance corpus покрывает каждую строку semantic field map и
  каждое routing/resource rule из раздела EPIC-06 «Нормативный Chat Completions
  JSON request contract» через public parser seam.
- [ ] Для каждого success case corpus проверяет normalized attributes, exact
  order и semantic metadata fragments, opaque locators, inspection gaps и
  итоговый coverage status.
- [ ] Negative corpus покрывает каждую stable failure branch нормативного
  contract и проверяет отсутствие source preview или partial parse result.
- [ ] Отдельная schema-walker matrix доказывает conformance явному vocabulary,
  unknown-keyword rules и reference outcomes parent contract.
- [ ] Unicode и cancellation cases подтверждают точное декодирование fragment,
  opaque locator semantics и удаление partial normalized state.
- [ ] Parse result и safe failures не раскрывают original source или иные
  запрещённые parent contract данные; forwarding по-прежнему использует source
  за пределами parser.
- [ ] Focused parser tests и `./gradlew build` проходят.

## Edge cases

- Root properties и object properties приходят в произвольном порядке.
- Escaped JSON strings, supplementary Unicode code points и empty strings.
- Text и non-text content parts в одном user message.
- Multiple tool calls, string arguments с invalid inner JSON и deprecated
  function-call/message forms.
- Unknown additive fields рядом с known content fields.
- Deeply nested schema, excessive fragment count and cancelled parse.

## Не входит

HTTP routing, request ingest/replay, policy context assembly, detector/window
execution, reactions, Chat Completions response/SSE, Responses API, Realtime,
Batch, external reference lookup и source rewriting.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ one versioned request parser result
  Acceptance:   0.15  ✓ field map and failure examples are normative
  Boundaries:   0.05  ✓ pure parser seam isolated
  Alternatives: 0.15  ✓ schema evolution behavior selected
  Assumptions:  0.20  ✓ parser library choice remains implementation detail
  Aggregate:    0.11  ✓ below threshold (0.3 issue)
```
