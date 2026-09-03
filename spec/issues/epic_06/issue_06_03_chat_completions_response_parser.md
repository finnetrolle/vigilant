# VIG-06-03: Chat Completions JSON and SSE response parser

**Статус:** Done
**Epic:** [EPIC-06](../../epics/epic_06_llm_message_parsing.md)
**Ветка:** OpenAI Chat Completions > JSON and SSE response
**Зависит от:** [VIG-06-02](issue_06_02_chat_completions_request_parser.md)
**Блокирует:** [VIG-20-01](../epic_20/issue_20_01_retained_memory_response_source.md), [VIG-20-02](../epic_20/issue_20_02_response_inspection_enforcement.md)
**Оценка:** 4-5 инженерных дней
**Уверенность:** Medium

## Результат

Один transport-neutral public parser contract принимает read-only OpenAI Chat
Completions response byte segments и явно выбранный transport kind `JSON` или
`SSE`. Он возвращает ordered normalized text fragments, provenance, inspection
gaps и terminal coverage либо typed safe failure.

Один leaf намеренно покрывает оба transport: result model, field semantics и
failure vocabulary общие, а transport-specific JSON и SSE adapters остаются
внутренними частями одного public response parser. EPIC-06 является
единственным owner protocol parsing; EPIC-20 не разбирает JSON или SSE
повторно.

## Public seam

Focused tests вызывают public response parser над deterministic immutable byte
segments и explicit operation descriptor. Segmentation matrix подаёт один и
тот же logical response целиком, по одному байту и с boundaries внутри UTF-8,
JSON token, SSE field и separator. Tests наблюдают только typed parse result,
а не internal parser callbacks.

## Нормативный field contract

### Ordinary JSON

- Каждый string `choices[].message.content` создаёт отдельный `OUTPUT_TEXT`
  fragment в порядке `choices`.
- String `choices[].message.refusal` создаёт отдельный `REFUSAL` fragment после
  content того же choice.
- String arguments каждого `choices[].message.tool_calls[].function.arguments`
  и deprecated `choices[].message.function_call.arguments` создают отдельные
  `TOOL_ARGUMENT` fragments в source order; inner JSON не reparsed.
- `null` в известных optional content fields не создаёт text fragment.
- Recognized non-text content создаёт explicit inspection gap. Unknown
  content-bearing discriminator или shape даёт `AMBIGUOUS_CONTENT`.
- Missing/non-array `choices`, non-object choice/message и invalid known field
  type дают `MALFORMED_MESSAGE`. Unknown additive metadata сохраняются только
  в original source и не становятся fragments.

### SSE

- Parser принимает SSE fields независимо от transport chunk boundaries,
  поддерживает `LF` и `CRLF`, comments и standard multi-line `data` joining.
- Каждый non-terminal event обязан содержать JSON Chat Completions chunk.
  String `choices[].delta.content`, `choices[].delta.refusal` и function/tool
  arguments добавляются к logical buffers независимо по `choice.index` и
  semantic field.
- Canonical fragment равен concatenation delta values в event order. Final
  snapshot fields не переопределяют и не сверяют этот source.
- Choice buffers публикуются только после standalone terminal event, содержащего
  ровно `data: [DONE]`. `[DONE]` вместе с другим data или content после него
  даёт `MALFORMED_MESSAGE`.
- EOF без `[DONE]`, незавершённый event, invalid UTF-8/JSON, missing or duplicate
  `choice.index`, incompatible repeated shape и unknown content-bearing field
  дают typed failure без partial result.
- Empty/comment-only events не создают fragments. Transport error и caller
  cancellation остаются transport error/cancellation, а не parse failure.

## Критерии приёмки

- [x] Versioned conformance corpus покрывает каждую ordinary JSON field branch,
  exact fragment order, semantic kind, provenance, gaps и coverage.
- [x] SSE corpus покрывает `LF`/`CRLF`, comments, multi-line data, interleaved
  `choice.index`, text/refusal/tool arguments и standalone `[DONE]`.
- [x] Exhaustive segmentation matrix доказывает одинаковый result при границах
  каждого byte, UTF-8 code point, JSON token, SSE field и event separator.
- [x] Negative matrix покрывает malformed/ambiguous shapes, duplicate or missing
  index, incomplete EOF, malformed terminal и bytes/events after `[DONE]`.
- [x] Success и failure result не содержат raw response, preview, headers,
  credentials или partial normalized state.
- [x] Все новые и изменённые Kotlin declarations и test methods имеют KDoc;
  focused parser suite и `./gradlew build` проходят.

## Требования

`MVP-01`, `MVP-02`, `MVP-07`, `PROXY-01`, `PROXY-02`: Chat Completions
response имеет один schema-tolerant parser contract, transport chunking не
меняет normalized result, а original source остаётся за пределами parser.

## Не входит

Response retention/replay, HTTP outcome mapping, policy/context assembly,
detector execution, masking, audit, client disclosure, OpenAI Responses API,
Realtime, Batch, source reconstruction и network lookup.

## Ambiguity Report

```text
Goals:        0.0   one public Chat Completions response parser
Acceptance:   0.10  JSON/SSE matrices and terminal rules explicit
Boundaries:   0.0   EPIC-06 owns all protocol parsing
Alternatives: 0.10  combined leaf selected over separate adapters
Assumptions:  0.15  pinned Chat Completions schema remains implementation input
Aggregate:    0.00  Done.
```
