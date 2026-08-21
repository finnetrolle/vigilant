# VIG-06-01: Контракт разбора LLM-сообщений

**Статус:** Draft  
**Epic:** [EPIC-06](../../epics/epic_06_llm_message_parsing.md)  
**Ветка:** Protocol contract and supported surface  
**Зависит от:** нет  
**Блокирует:** остальные issues EPIC-06  
**Оценка:** 1-2 инженерных дня

## Результат

Нормативный контракт parser определён настолько точно, что разбор request,
обычного response и streaming response можно декомпозировать и реализовать
независимо без догадок о schema, payload или error semantics.

Это documentation-only issue. Она обновляет EPIC-06 и создаёт исполняемые
дочерние issues, но не добавляет production implementation.

## Принятые решения

- Payload представлен упорядоченной immutable-коллекцией независимых
  текстовых фрагментов.
- Один фрагмент относится к одному логическому content-bearing полю и имеет
  provenance до исходной protocol structure.
- Разные поля не конкатенируются. Последующий слой вызывает policy engine
  отдельно для каждого фрагмента.
- EPIC-06 выполняет все извлечения из body и protocol events, включая
  `model`. EPIC-03 получает normalized attributes и только собирает
  `PolicyContext`, не разбирая сообщение повторно.
- Payload включает только явно перечисленные content-bearing поля: model
  input, model-visible labels и schemas, tool descriptions/arguments/results
  и model output. Служебные IDs, model name, timestamps, usage, status и
  finish reason не являются payload.
- Parser не собирает все строки JSON рекурсивным обходом. Каждая
  protocol-specific issue фиксирует явную semantic field map.
- Schema-recognized non-text content создаёт явный inspection gap, но на
  текущем этапе не блокирует forwarding исходного сообщения. Malformed,
  unknown и ambiguous structures этим решением не покрываются.
- Доступный OpenAI plaintext reasoning text или summary создаёт отдельный
  `REASONING` fragment. `encrypted_content` не декодируется, создаёт
  `OPAQUE_REASONING` inspection gap и сохраняется без изменений. Пустой
  plaintext при наличии encrypted content остаётся gap.
- Success coverage имеет ровно один status: `FULLY_INSPECTABLE`,
  `PARTIALLY_INSPECTABLE` или `UNINSPECTABLE`. Два последних требуют непустые
  inspection gaps; все три разрешают forwarding на текущем этапе.
- Expected failure имеет stable code `MALFORMED_MESSAGE`,
  `UNSUPPORTED_SCHEMA`, `AMBIGUOUS_CONTENT` или `UNRESOLVED_CONTEXT` и
  запрещает forwarding без раскрытия body или content preview.
- Fragment provenance содержит guardrail-facing `ordinal`, direction,
  semantic kind и explicit role, если она присутствует, а также закрытый
  protocol-specific locator. Locator не передаётся detector или policy engine.
- Detector UTF-8 offsets локальны для decoded text одного fragment и не
  трактуются как offsets encoded protocol source.
- Normalized attributes contract для EPIC-03 содержит только `model`.
  Protocol family, operation, transport и direction остаются parse-result
  metadata; произвольная attributes map отсутствует.
- Policy context обеих phases использует request model. Reported response
  model не переопределяет context текущего exchange.
- Streaming deltas накапливаются до protocol completion одного logical field.
  Только завершённый field создаёт fragment; TCP chunks, SSE frames и delta
  events не являются payload boundaries.
- Clean EOF с открытым field или без обязательного terminal event возвращает
  `MALFORMED_MESSAGE`. Cancellation остаётся cancellation. Transport error и
  valid provider error event остаются upstream outcome вызывающего слоя.
- Незавершённые buffers отбрасываются, завершённые fragments не меняются.
- SSE response является одной атомарной policy-транзакцией MVP. Parser может
  передавать завершённые fragments внутреннему evaluation flow, но integration
  не раскрывает клиенту upstream status, headers или body до terminal event и
  итогового decision. Любой `BLOCK` даёт только safe proxy error; полный
  `ALLOW` replay-ит original SSE. Incremental release не входит в MVP.
- Большой logical fragment не блокируется только из-за detector payload limit
  и не обрезается. Он передаётся отдельной protocol-agnostic windowing
  capability; parser не запускает detectors и не агрегирует window findings.
- В рамках MVP рассматриваются только OpenAI API. Отдельные adapters реализуют
  Responses JSON/SSE и Chat Completions JSON/SSE. Общего mega-DTO для этих
  OpenAI surfaces нет.
- Adapter выбирается по method, normalized path, content type и transport до
  чтения body. Body sniffing и adapter fallback запрещены; отсутствие точного
  adapter даёт `UNSUPPORTED_SCHEMA`.
- Operation surface включает `POST /v1/responses`,
  `POST /v1/chat/completions` и их обычные JSON или SSE responses.
- Realtime и Batch остаются post-MVP placeholders без field maps, terminal
  semantics, transport contracts и implementation issues. OpenAI API mode
  приходит через operation descriptor.
- OpenAI adapters поддерживают `/v1` и фиксируют внутреннюю версию contract
  snapshot с датой primary documentation. Runtime selection схемы `latest`
  отсутствует.
- Schema-recognized reference на внешний textual context, включая OpenAI
  Responses `conversation`, `previous_response_id` и hosted `prompt`,
  возвращает `UNRESOLVED_CONTEXT` и запрещает forwarding. Parser не выполняет
  network lookup; resolver вне EPIC-06 должен сначала представить полученный
  context теми же normalized fragments.
- Unknown additional properties и non-content metadata сохраняются lossless.
  Unknown content discriminator даёт `AMBIGUOUS_CONTENT`, unknown SSE event
  type даёт `UNSUPPORTED_SCHEMA`; schema guessing запрещён.
- Textual tool arguments возвращаются одним decoded fragment без обязательного
  inner JSON parsing. Invalid inner JSON остаётся text content, а не protocol
  failure.
- Structured tool arguments сохраняются typed; string leaves становятся
  отдельными fragments, non-string values не stringified и остаются в
  structured view.
- Message `name`, tool/function names и другие model-visible custom labels
  становятся отдельными `LABEL` fragments. Schema property names, `title`,
  `description`, строковые `enum`, `const`, `default`, `examples` и custom
  string constraints, включая regex `pattern`, становятся отдельными
  `SCHEMA_TEXT` fragments.
- Dedicated schema walker использует явный vocabulary map и source order.
  Fixed JSON Schema keywords, protocol discriminators, OpenAI IDs и
  numeric/boolean constraints исключаются; recursive all-string traversal
  запрещён. Runtime argument keys остаются не-текстовыми, schema property
  names включаются как model-visible content.
- Original bytes/events принадлежат bounded integration spool/tee. Parser
  читает source, но result не содержит raw или reconstructed body. Unmodified
  forwarding использует original source.

## Решения, которые нужно закрыть

1. Protocol-specific semantic field maps для request и response.
2. Поведение unknown keyword внутри model-visible JSON Schema. Решение
   отложено без default; schema-walker implementation остаётся `Draft`.
3. Точные result boundaries и terminal event names для Responses и Chat
   Completions в non-streaming и SSE режимах. Canonical source, deduplication
   и mismatch result для повторных final snapshots отложены без default; SSE
   adapter implementations остаются `Draft`.
4. Граница с windowing capability, bounded parsing state, spooling и hard
   resource exhaustion.
5. Reverse-mapping contract отдельного rewriter для адресной модификации
   original source.

## Критерии готовности draft

- [ ] Все пять оставшихся групп решений имеют один выбранный вариант и
  rationale.
- [ ] EPIC-06 не содержит конфликтующих или устаревших open decisions.
- [ ] Граница с EPIC-02, EPIC-03 и EPIC-04 не дублирует ответственность.
- [ ] Protocol contract следует schema-tolerant, lossless и strict
  inspectability principle проекта с согласованным узким исключением для
  schema-recognized non-text и provider-opaque content.
- [ ] Созданы независимо исполняемые issues размером не более пяти
  инженерных дней каждая.
- [ ] Для каждой implementation issue заданы test seam, edge cases и
  non-goals.
- [ ] EPIC-06 и все готовые implementation issues имеют ambiguity aggregate
  не выше `0.3`.

## Не входит

Production parser, HTTP integration, policy execution, detector execution и
применение reactions.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.05
  Acceptance:   0.20
  Boundaries:   0.15
  Alternatives: 0.35
  Assumptions:  0.35
  Aggregate:    0.22
```

Оставить `Draft` до закрытия перечисленных решений.
