# VIG-20-05: SSE response inspection and enforcement

**Статус:** Done
**Epic:** [EPIC-20](../../epics/epic_20_atomic_in_memory_response_analysis.md)
**Ветка:** Response enforcement > SSE Chat Completions
**Зависит от:** [VIG-06-03](../epic_06/issue_06_03_chat_completions_response_parser.md), [VIG-20-01](issue_20_01_retained_memory_response_source.md), [VIG-20-02](issue_20_02_response_inspection_enforcement.md), [VIG-20-03](issue_20_03_reusable_text_masker.md), [VIG-29](../issue_29_openai_error_contract.md), [VIG-32-01](../epic_32/issue_32_01_stdout_request_audit_migration.md)
**Блокирует:** нет
**Оценка:** 4-5 инженерных дней
**Уверенность:** Medium

## Цель

После полного получения Chat Completions SSE response выполнить response
inspection и применить итоговый `ALLOW`, `MASK` или `BLOCK` до первого client
byte. Разрешённый stream раскрывается клиенту атомарно; blocked или invalid
stream не раскрывает upstream status, headers или event bytes.

## Подтверждённая граница

- Завершённый VIG-20-02 поставляет production `ResponseInspectionWorkflow`,
  typed outcomes, one-shot response handoff и ordinary
  `ResponseSourceMap.jsonStrings`. Эта issue расширяет тот же source-map model
  SSE segments и transport-specific rewrite dispatch без второго workflow.
- VIG-20-02 владеет общим response analysis orchestration: context и policy
  selection, detector execution, final reaction, safe audit pair и mapping
  технических outcomes.
- VIG-20-05 переиспользует этот workflow и не создаёт второй response policy
  pipeline.
- Эта issue владеет только SSE-specific mapping detector spans к исходным
  events, `MASK` rewrite, atomic replay и real-Armeria E2E evidence.
- VIG-06-03 остаётся единственным owner SSE framing, terminal parsing и
  normalized fragments. VIG-20-05 не создаёт competing parser.
- Retained source lifecycle завершён в VIG-20-01, transport-neutral masking
  принадлежит VIG-20-03, client errors используют exact VIG-29 matrix.

## Уже зафиксированное поведение

- Supported stream завершается standalone `data: [DONE]`; malformed или
  incomplete stream даёт safe `502 invalid_upstream_response` без partial
  disclosure.
- `ALLOW` раскрывает original SSE byte-for-byte после final decision.
- Любой applicable `BLOCK` даёт safe `403 policy_blocked`; upstream response
  полностью скрыт.
- Detector/policy failure или timeout даёт safe `503
  response_inspection_unavailable` с `Retry-After: 1`.
- Client cancellation, upstream interruption и shutdown прекращают работу и
  освобождают retained source ownership по VIG-20-01.
- Один реально проанализированный response публикует safe
  `policy.analysis_started`/`policy.analysis_completed` pair через общий
  orchestration seam VIG-20-02.

## SSE `MASK` rewrite contract

- Rewrite работает над retained original bytes и изменяет только JSON string
  literals, соответствующие выбранным masking instructions. Affected JSON
  objects не сериализуются заново.
- Незатронутые events, comments, SSE fields, field order, separators и unknown
  metadata сохраняются byte-for-byte.
- Для masking span внутри одного delta value marker заменяет exact selected
  decoded UTF-8 text, а prefix и suffix остаются в том же value.
- Если masking span пересекает несколько delta values одного logical field,
  marker вставляется ровно один раз в value, содержащем начало span. Covered
  text удаляется из каждого затронутого value; полностью покрытый value
  остаётся существующим event field с пустой строкой.
- Events не удаляются, не объединяются и не меняют порядок. Concatenation
  rewritten delta values одного logical field точно равна результату
  transport-neutral `TextMasker` из VIG-20-03.
- JSON escaping marker, сохранённого prefix и suffix создаёт valid UTF-8 JSON.
  Mapping использует decoded UTF-8 offsets и никогда не считает их raw JSON
  byte offsets.
- Adjacent и overlapping instructions поступают после canonical merge
  VIG-20-03. Невалидная UTF-8 boundary, отсутствующий locator или невозможное
  однозначное mapping дают VIG-29 `503 response_inspection_unavailable` без
  partial client disclosure и без частично переписанного output.

## Inspection fragment scope

VIG-20-05 инспектирует все text fragments, опубликованные SSE adapter
VIG-06-03:

- `OUTPUT_TEXT` из `choices[].delta.content`;
- `REFUSAL` из `choices[].delta.refusal`;
- `TOOL_ARGUMENT` из
  `choices[].delta.tool_calls[].function.arguments`;
- `TOOL_ARGUMENT` из deprecated
  `choices[].delta.function_call.arguments`.

Каждый logical field является независимым inspection fragment. Finding может
пересекать transport chunks и несколько delta events одного field, но не
пересекает разные `choice.index`, разные tool calls, semantic fields либо
modern/deprecated function-call sources. Empty buffer не создаёт fragment.
Unknown или malformed content-bearing shape следует typed fail-closed result
VIG-06-03 и не получает fallback только на `delta.content`.

## Source-coordinate mapping seam

Единственный VIG-06-03 parser в том же parse pass строит immutable source map
для каждого normalized response fragment. Source map содержит только:

- существующий opaque `ProtocolLocator` fragment-а;
- ordered SSE delta segments этого logical field;
- decoded UTF-8 range каждого segment внутри logical fragment;
- raw byte range соответствующего JSON string literal в retained source;
- mapping valid decoded UTF-8 boundaries к raw escaped JSON byte positions.

Source map не содержит копию raw body, decoded payload или reconstructed
response. `ChatCompletionsResponseParser` остаётся единственным parser API;
rewriter не повторяет SSE framing, terminal validation, JSON parsing или field
selection.

Pure SSE rewriter принимает retained original source, parser source map и
canonical masking instructions. Он валидирует locators/ranges до первой
записи, затем применяет patches в descending raw-offset order и возвращает
новый immutable source либо typed failure без partial output. Gateway mapping
переводит такую failure в VIG-29 `503 response_inspection_unavailable`.

## Основной public test seam

Основной acceptance seam проходит через real Armeria client, production
`PiiShadowProxyService` и controlled upstream в существующем
`PiiShadowProxyServiceTest`. Causal response lifecycle использует real Armeria
boundaries; wire-level `Connection` semantics доказываются raw HTTP/1 upstream,
потому что Armeria server удаляет этот hop-by-hop field до gateway. Прямой
вызов rewriter не заменяет этот E2E.

Fixture использует две причинные test-only synchronization точки:

1. Upstream отправляет response headers и первые SSE events, подтверждает их
   отправку и удерживает standalone `[DONE]`. Client observer к этому моменту
   не должен получить ни response headers, ни один body byte.
2. После разрешённого `[DONE]` parser завершает stream, общий VIG-20-02
   workflow начинает detector execution и останавливается на detector barrier.
   До final policy decision client observer по-прежнему не получает headers
   или body.

После снятия detector barrier тест наблюдает final HTTP outcome и terminal
source cleanup. Все ожидания используют causal latches или
`GatewayTestFixture.awaitUntil` с bounded deadline и last observed state;
`sleep`, timing threshold и повторное использование released ephemeral port
запрещены. Barriers остаются test fixture и не создают production switch.

Проверка отсутствия ранних response headers обязательна отдельно от body:
предварительно раскрытый upstream status не позволил бы gateway вернуть exact
VIG-29 `403`, `502` или `503`.

## Response headers и framing

- `ALLOW` использует существующий proxy response-header filtering, сохраняет
  upstream status и разрешённые end-to-end headers, затем replay-ит original
  SSE body byte-for-byte.
- `MASK` сохраняет upstream status и разрешённые end-to-end headers.
  `Content-Type: text/event-stream` сохраняется вместе с charset parameters.
- Для `MASK` `Content-Length` пересчитывается по exact rewritten byte count.
  `Transfer-Encoding`, `Connection`, перечисленные ими fields и остальные
  hop-by-hop headers удаляются canonical proxy helper-ом.
- Для `MASK` representation validators `ETag`, `Content-MD5` и `Digest`
  удаляются, потому что upstream values больше не описывают rewritten body.
- Guardrail-enabled SSE поддерживает отсутствующий `Content-Encoding` или
  exact `identity`. Иной encoding, включая gzip, не декодируется и не
  перекодируется этой issue: client получает VIG-29 `502
  invalid_upstream_response` без upstream disclosure.
- Request IDs, rate-limit headers, cache directives и другие разрешённые
  end-to-end metadata сохраняются.
- `BLOCK`, invalid upstream и inspection unavailable responses содержат только
  собственные VIG-29 headers. Upstream headers в них не переносятся.

Реализация обязана создать справочный файл
`docs/response-masking-headers.md`. Он описывает таблицей поведение status и
каждого класса headers для `ALLOW`, `MASK`, `BLOCK`, `502` и `503`, причину
удаления validators/hop-by-hop fields и explicit non-goal для compressed SSE.
Документ обновляется в том же change set, что production behavior.

## План компонентов и файлов

- Расширить JSON source-map contract VIG-20-02 в response success model и
  существующем `ChatCompletionsResponseParser` SSE segment metadata,
  создаваемой в том же parse pass.
- Добавить один pure SSE rewriter в existing OpenAI protocol package. Новый
  module, provider registry или второй parser interface не создавать.
- В response integration VIG-20-05 подключить rewriter после final `MASK`
  result общего VIG-20-02 workflow; `ALLOW`, `BLOCK` и technical failures не
  проходят через rewrite.
- Переиспользовать retained source lifecycle VIG-20-01, `TextMasker` VIG-20-03,
  canonical response-header filtering и VIG-29 error factory.
- Добавить pure source-map/rewrite tests и causal real-Armeria client/gateway
  cases в существующий `PiiShadowProxyServiceTest` fixture; raw HTTP/1 upstream
  использовать только для wire-level `Connection` semantics.
- Создать `docs/response-masking-headers.md` и синхронизировать current runtime
  documentation/requirements coverage только после появления production
  behavior.

## Рассмотренная альтернатива

Повторно читать retained source отдельным SSE/JSON parser внутри rewriter
отклонено: это дублирует framing, terminal и content-selection rules и может
разойтись с VIG-06-03. Полная сериализация affected JSON object также
отклонена, потому что не сохраняет unknown metadata и formatting byte-for-byte.

## Обязательная test matrix

### Real-Armeria E2E

- [x] `ALLOW`: обе causal checkpoints подтверждают отсутствие client headers
  и body; после final decision client получает original status, разрешённые
  headers и SSE bytes byte-for-byte.
- [x] `MASK`: PII пересекает два `delta.content` events; marker появляется
  один раз, незатронутые bytes сохраняются, client disclosure начинается
  только после final decision. `Content-Length` соответствует rewritten bytes;
  hop-by-hop headers и representation validators удалены, разрешённые
  end-to-end metadata сохранены.
- [x] `BLOCK`: client получает exact VIG-29 `403 policy_blocked`; upstream
  status, headers и event bytes не раскрываются.
- [x] Malformed event, missing `[DONE]` и upstream interruption возвращают
  exact VIG-29 `502 invalid_upstream_response` без partial disclosure.
- [x] Detector failure и timeout возвращают exact VIG-29 `503
  response_inspection_unavailable` с `Retry-After: 1` без partial disclosure.
- [x] Client cancellation до `[DONE]`, cancellation во время analysis и
  shutdown освобождают source ownership и не раскрывают partial response.
- [x] Safe audit observations доказывают started/completed ordering;
  parse failure и cancellation до analysis не создают audit pair.

### Pure SSE mapping и rewrite

- [x] Cases покрывают `OUTPUT_TEXT`, `REFUSAL`, modern tool arguments и
  deprecated function arguments; interleaved choices и tool-call indexes не
  смешивают logical fields.
- [x] Cases покрывают span внутри одного event, span через несколько events,
  несколько independent spans и уже объединённые adjacent/overlapping
  instructions.
- [x] Cases покрывают ASCII, multibyte UTF-8, JSON escapes, `\uXXXX`, empty
  rewritten value и сохранение decoded-text semantics после valid re-encoding.
- [x] Cases покрывают `LF`, `CRLF`, comments, multi-line `data`, unknown
  metadata и byte-identical preservation каждого незатронутого source range.
- [x] Missing, duplicate или ambiguous locator, invalid UTF-8 boundary и
  невозможное source mapping возвращают typed failure без partial output.
- [x] Source и masking instructions не мутируются; повторение одного input
  даёт byte-identical deterministic result.
- [x] `docs/response-masking-headers.md` содержит актуальную reference matrix
  для `ALLOW`, `MASK`, `BLOCK`, `502`, `503` и compressed-SSE non-goal.
- [x] Все новые и изменённые Kotlin declarations, test methods, causal
  callbacks и lifecycle helpers имеют актуальный KDoc; focused suites,
  `./gradlew validateWorkItems` и `./gradlew build` проходят.

## Dynamic evidence

- Behavioral rewriter RED: `rtk proxy ./gradlew test --tests
  'io.vigilant.protocol.openai.SseResponseRewriterTest'` до production rewrite
  вернул expected `Success`, actual typed `Failure` и завершил 1 test failure.
- Behavioral gateway RED: `rtk proxy ./gradlew test --tests
  'io.vigilant.gateway.proxy.PiiShadowProxyServiceTest.SSE MASK is atomic across
  events and rewrites representation headers'` до shared-workflow routing
  не достиг detector barrier, потому что old SSE path replay-ил без analysis.
- Duplicate-logical-locator RED/GREEN: `rtk proxy ./gradlew test --tests
  'io.vigilant.protocol.openai.SseResponseRewriterTest.invalid SSE rewrite matrix
  fails atomically without mutating inputs'` сначала упал на новом
  independent oracle, после uniqueness validation прошёл за 1 s.
- Pure parser/rewriter GREEN: `rtk proxy ./gradlew test --tests
  'io.vigilant.protocol.openai.ChatCompletionsResponseParserTest' --tests
  'io.vigilant.protocol.openai.JsonResponseRewriterTest' --tests
  'io.vigilant.protocol.openai.SseResponseRewriterTest'` прошёл за 1 s.
- Полный affected GREEN: `rtk proxy ./gradlew test --tests
  'io.vigilant.protocol.openai.ChatCompletionsResponseParserTest' --tests
  'io.vigilant.protocol.openai.JsonResponseRewriterTest' --tests
  'io.vigilant.protocol.openai.SseResponseRewriterTest' --tests
  'io.vigilant.gateway.proxy.ResponseInspectionWorkflowTest' --tests
  'io.vigilant.gateway.proxy.PiiShadowProxyServiceTest'` прошёл за 5 min 32 s.
- Wire-header evidence RED: добавление upstream `Connection`, nominated field и
  `Transfer-Encoding` в real-Armeria server fixture оставило
  `x-private-hop` у клиента, потому что server удалил `Connection` до gateway;
  повтор с forced H1C воспроизвёл тот же invalid-fixture result.
- Wire-header evidence GREEN: после согласованного raw HTTP/1 upstream seam
  `rtk proxy ./gradlew test --tests
  'io.vigilant.gateway.proxy.PiiShadowProxyServiceTest.SSE MASK is atomic across
  events and rewrites representation headers'` прошёл за 6 s и доказал удаление
  chunked framing, `Connection` и его nominated field на real Armeria
  client/gateway boundaries.
- Sonar RED: `scripts/pipeline-sonar` нашёл `kotlin:S3776` в
  `SseResponseRewriter.rewrite`: cognitive complexity 27 при лимите 15.
- Sonar refactor GREEN: после декомпозиции без изменения
  поведения `rtk proxy ./gradlew test --tests
  'io.vigilant.protocol.openai.SseResponseRewriterTest'` прошёл за 1 s,
  `rtk proxy ./gradlew detekt` прошёл за 4 s.
- Static/style и work-item gates: `rtk proxy ./gradlew detekt`, `rtk proxy
  ./gradlew validateWorkItems` и `rtk proxy ./gradlew build` завершились
  `BUILD SUCCESSFUL`; validator опубликовал `Work-item graph is valid.`.

## Не входит

- Повторная реализация response policy selection, detector orchestration,
  reaction aggregation, audit schema или client error matrix.
- Response source implementation, новый или повторный SSE parser либо
  transport-neutral masker. Source-map metadata создаётся существующим
  VIG-06-03 parser в его единственном parse pass.
- Non-stream JSON rewrite, request enforcement, OpenAI Responses API,
  Realtime, Batch, `REMOVE`, disk spill, quota или новый runtime config.

## Ambiguity Report

```text
Goals:        0.05  atomic SSE response enforcement fixed
Acceptance:   0.05  E2E and pure rewrite matrices explicit
Boundaries:   0.05  orchestration, mapping and fragment boundaries fixed
Alternatives: 0.10  source-patching rewrite selected
Assumptions:  0.15  integration reuses contracts delivered by dependencies
Aggregate:    0.08  Done.
```
