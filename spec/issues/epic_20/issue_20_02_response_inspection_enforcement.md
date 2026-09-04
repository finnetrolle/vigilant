# VIG-20-02: Non-stream response inspection and enforcement

**Статус:** Ready for implementation
**Epic:** [EPIC-20](../../epics/epic_20_atomic_in_memory_response_analysis.md)
**Ветка:** Response enforcement > non-stream Chat Completions
**Зависит от:** [VIG-06-03](../epic_06/issue_06_03_chat_completions_response_parser.md), [VIG-20-01](issue_20_01_retained_memory_response_source.md), [VIG-20-03](issue_20_03_reusable_text_masker.md), [VIG-29](../issue_29_openai_error_contract.md), [VIG-32-01](../epic_32/issue_32_01_stdout_request_audit_migration.md)
**Блокирует:** [VIG-20-05](issue_20_05_sse_response_enforcement.md)
**Оценка:** 3-5 инженерных дней; confidence Medium

## Цель

После полного получения ordinary non-stream Chat Completions response извлечь
inspectable text, выбрать response policies из request identity/context,
выполнить detectors и применить `ALLOW`, `MASK` или `BLOCK` до первого client
byte.

Ровно одна stdout audit pair из VIG-32-01 отражает реально начатый и завершённый
response analysis.

Прежний scope VIG-20-02, включавший одновременно non-stream JSON, SSE framing,
cross-chunk masking, audit и полный lifecycle, сохранён на нормативном уровне
[EPIC-20](../../epics/epic_20_atomic_in_memory_response_analysis.md). SSE framing
завершён в VIG-06-03; SSE enforcement опубликован как отдельный ready узкий
leaf VIG-20-05.

## Принятые решения

- Response enforcement является обязательной частью MVP, а не shadow-only
  observability.
- Response JSON/SSE parsing использует единственный public contract VIG-06-03;
  EPIC-20 не создаёт competing parser API.
- Каждый выбранный parser-ом textual field является независимым inspection
  fragment. Findings не пересекают fragment boundaries.
- `ALLOW` раскрывает exact original response byte-for-byte.
- `MASK` изменяет только exact selected PII spans в затронутых choices,
  сохраняет valid OpenAI JSON, unknown fields и остальную structure,
  пересчитывает `Content-Length` и удаляет upstream `Transfer-Encoding` плюс
  другие hop-by-hop headers по proxy rules.
- `MASK` также удаляет `ETag`, `Content-MD5` и `Digest` как invalid
  representation validators. Разрешённые end-to-end metadata сохраняются.
- Guardrail-enabled JSON принимает отсутствующий `Content-Encoding` или exact
  `identity`. Иной encoding, включая gzip, не декодируется этой issue и даёт
  VIG-29 `502 invalid_upstream_response` без disclosure.
- Любой applicable `BLOCK` блокирует весь response. Клиент получает VIG-29
  safe `403 policy_blocked` и не получает upstream status, headers или body.
- Все upstream Chat Completions responses, включая `4xx` и `5xx`, проходят
  inspection до client disclosure. Upstream error status не является policy
  bypass.
- Recognized `null` не создаёт fragment или gap. Recognized non-text
  media/file/audio shapes создают inspection gap, но не отменяют evaluation
  соседних textual fragments и сами по себе не создают reject.
- Missing `choices`, invalid content type, malformed или ambiguous
  content-bearing shape дают VIG-29 safe `502 invalid_upstream_response`.
- Detector/policy timeout или failure дают VIG-29 safe `503` с
  `Retry-After: 1` и `response_inspection_unavailable` без partial disclosure.
- Dependency VIG-29 завершена: закрытая production matrix уже предоставляет
  response `BLOCK`, response inspection failure и invalid upstream outcomes без
  optional details. Эта issue владеет только выбором этих outcomes после
  фактического response analysis.
- `analysis_started` публикуется после parse, context assembly и policy
  selection непосредственно перед detector execution. `analysis_completed`
  публикуется после final outcome и содержит только safe aggregate fields.
- Существующий расширенный policy engine сохраняется. Эта issue не удаляет и
  не упрощает URL/model/subject matching, overrides или detector registry.
- `Disposition` сохраняет только `ALLOW` и `BLOCK`. Итоговый `MASK` означает
  `ALLOW` с непустыми immutable `MaskingInstruction(span, marker)` из
  VIG-20-03; новый третий disposition не добавляется.
- `ReactionPlan` остаётся результатом одного independently inspected fragment.
  `ReactionAggregator` создаёт canonical masking instructions один раз из
  selected reactions и detector findings. Marker выбирается по `FindingType`;
  detector не запускается повторно, а `TextMasker` не угадывает type или marker.
- Response workflow сохраняет fragment ordinal/locator рядом с его
  `ReactionPlan`; UTF-8 offsets разных fragments никогда не смешиваются.
- Любой fragment с `Disposition.BLOCK` блокирует весь response. При отсутствии
  `BLOCK` хотя бы одна masking instruction даёт response `MASK`; пустой общий
  instruction set даёт `ALLOW`.
- Detector error/timeout остаётся typed technical failure и не кодируется как
  synthetic reaction.
- Response только с clean text и/или recognized gaps получает `ALLOW` без
  изменения. При textual findings обычный `MASK` или `BLOCK` применяется даже
  при наличии gap; uninspectable content при `MASK` сохраняется byte-for-byte.
- Audit outcome использует precedence `DETECTED` > `INSPECTION_GAP` > `CLEAN`,
  совпадающий с request-side `ShadowAuditLogger`. Coverage независимо остаётся
  `PARTIALLY_INSPECTABLE` или `UNINSPECTABLE`, если parser сообщил gaps.

## Gateway ownership seam

- Existing gateway package получает один internal upstream exchange seam. Он
  переписывает outbound request, вызывает `WebClient`, получает upstream
  headers/body, применяет canonical hop-by-hop filtering и возвращает
  gateway-owned response handle, ещё не связанный с client response.
- `BypassProxyService.serve()` сохраняет streaming bypass behavior и использует
  тот же exchange seam для немедленного forwarding. Bypass не получает
  retention, parsing или policy behavior.
- Guardrail path остаётся в `PiiShadowProxyService`. После successful request
  inspection он выполняет upstream exchange, передаёт response headers/body в
  retained source, реализованный в VIG-20-01, и не создаёт client response до complete ingest и
  final policy decision.
- Один `ResponseInspectionWorkflow` выполняет parser, response-context handoff,
  policy evaluation, final reaction и safe audit. Он возвращает только typed
  `ForwardOriginal`, `ForwardMasked` или `Reject(OpenAiErrorOutcome)`; HTTP
  adapter не повторяет domain decisions.
- Forward outcomes владеют one-shot response handoff, аналогичным
  `ReplayReadyRequest`. До successful transport claim source принадлежит
  response workflow; после claim terminal replay publisher владеет cleanup.
  `BLOCK`, failure, cancellation и rejected handoff закрывают source без replay.
- VIG-20-05 использует тот же upstream exchange, response workflow и ownership
  transfer. SSE-specific source map/rewrite не создают второй gateway pipeline.
- Response policy context использует сохранённый request identity, normalized
  path и request model; upstream response fields не переопределяют request
  context.

## Ordinary JSON fragment scope

VIG-20-02 инспектирует все textual fragments из VIG-06-03 ordinary response:

- `OUTPUT_TEXT` из `choices[].message.content`;
- `REFUSAL` из `choices[].message.refusal`;
- `TOOL_ARGUMENT` из
  `choices[].message.tool_calls[].function.arguments`;
- `TOOL_ARGUMENT` из deprecated
  `choices[].message.function_call.arguments`;
- `OUTPUT_TEXT` из `choices[].message.audio.transcript`, если transcript
  присутствует.

Каждый field остаётся отдельным fragment; findings не пересекают choices,
semantic fields, tool calls или transcript boundaries. `audio.data` не
анализируется и остаётся inspection gap; при textual finding transcript
получает обычный `MASK`/`BLOCK`, а audio bytes сохраняются как uninspectable
content. Role, IDs, finish reason и additive metadata не становятся detector
input. Malformed или ambiguous content-bearing shape даёт VIG-29 `502
invalid_upstream_response`.

## Ordinary JSON `MASK` rewrite

- Existing VIG-06-03 JSON parser в своём единственном parse pass строит
  immutable source-coordinate metadata для каждого normalized fragment.
  Metadata связывает valid decoded UTF-8 boundaries с raw escaped JSON byte
  positions и не содержит копию body или payload.
- Pure JSON rewriter принимает retained original source, parser source map и
  canonical masking instructions. Он валидирует все locators/ranges до первой
  записи, затем применяет patches в descending raw-offset order.
- Rewrite изменяет только selected JSON string literals. Unknown fields, field
  order, whitespace, number formatting и все незатронутые bytes сохраняются
  byte-for-byte; полный `ObjectNode` не сериализуется заново.
- Невозможное source mapping возвращает typed failure без partial output и
  отображается в VIG-29 `503 response_inspection_unavailable`.
- После `MASK` `Content-Length` пересчитывается по exact rewritten bytes;
  canonical proxy helper удаляет hop-by-hop headers. `ETag`, `Content-MD5` и
  `Digest` удаляются как invalid representation validators.
- Этот JSON source-map contract является базой, которую VIG-20-05 расширяет
  SSE event segments и cross-event mapping, не создавая второй parser.

## Требования

- `MVP-01`, `MVP-02`, `MVP-03`, `MVP-04`: response inspection, fast-pii,
  reactions и response policy selection.
- `PERF-03`, `CONC-01`, `CONC-03`, `CONC-04`: deadline, retained memory,
  execution isolation, cancellation и shutdown.
- `PROXY-01`, `PROXY-02`, `PROXY-03`: hold-before-release, exact masking и safe
  errors.
- `MVP-06`, `OBS-01`, `OBS-02`: safe stdout audit pair, metrics и tracing.

## Не входит

- SSE framing, `data: [DONE]` и text assembly по `choice.index` завершены в
  VIG-06-03; cross-chunk findings и SSE response rewrite принадлежат отдельному
  enforcement leaf VIG-20-05.
- Response source ownership и memory lifecycle: VIG-20-01.
- Audit persistence, custom queue, WAL, file handoff или Collector.
- Упрощение существующей policy model, новые detector types, policy hot reload,
  protocol кроме Chat Completions, retries или response regeneration.

## Условия перехода в Ready

- [x] EPIC-06 назначен owner единственного public response parser seam VIG-06-03.
- [x] Зафиксировано преобразование existing policy result в final reaction и
  canonical masking instructions без изменения policy scope.
- [x] Назван real-Armeria E2E seam для удержания response до decision и exact
  client disclosure.
- [x] Все acceptance cases ниже сопоставлены конкретному test evidence.

## План компонентов и файлов

- Выделить в existing gateway package один internal upstream exchange seam;
  `BypassProxyService.serve()` продолжает использовать его для streaming
  bypass без retention или policy behavior.
- Добавить один `ResponseInspectionWorkflow`, который принимает retained
  response owner и сохранённый request context, затем возвращает typed
  `ForwardOriginal`, `ForwardMasked` или `Reject(OpenAiErrorOutcome)`.
- Добавить one-shot response handoff по existing `ReplayReadyRequest`
  ownership pattern; не создавать второй source lifecycle или generic storage
  framework.
- Расширить existing response parser success model immutable JSON source-map
  metadata и добавить pure source-patching JSON rewriter в existing OpenAI
  protocol package.
- Переиспользовать VIG-20-03 `MaskingInstruction`/`TextMasker`, VIG-29 error
  factory, canonical response-header filtering и VIG-32-01 stdout logger.
- Добавить deterministic pure tests и causal real-Armeria cases в existing
  gateway fixture; синхронизировать current runtime docs и requirements
  coverage только после появления production behavior.

## Обязательная test matrix

### Real-Armeria E2E

- [ ] Causal `ALLOW` case удерживает upstream headers и partial JSON до EOF,
  затем удерживает complete source на detector barrier. В обеих точках client
  не видит headers/body; после decision получает original status, разрешённые
  headers и body byte-for-byte.
- [ ] Valid Chat Completions bodies со status `200`, `429` и `500` проходят
  один response workflow; `ALLOW` сохраняет каждый original status/body.
- [ ] `MASK` case покрывает несколько choices, unknown metadata, JSON escapes
  и multibyte UTF-8. Меняются только selected literals; `Content-Length`
  пересчитан, hop-by-hop headers и representation validators удалены, остальные
  end-to-end metadata сохранены.
- [ ] Finding одного fragment с `BLOCK` блокирует весь response. Client
  получает exact VIG-29 `403 policy_blocked` без upstream status, headers или
  body.
- [ ] Gap matrix покрывает only-gap, clean-plus-gap, detected-plus-gap `MASK`
  и detected-plus-gap `BLOCK`; assertions проверяют reaction, coverage и audit
  precedence `DETECTED` > `INSPECTION_GAP` > `CLEAN`.
- [ ] Missing/non-array `choices`, malformed JSON, ambiguous content и
  non-identity `Content-Encoding` дают exact VIG-29 `502
  invalid_upstream_response` без partial disclosure.
- [ ] Detector failure и timeout дают exact VIG-29 `503
  response_inspection_unavailable` с `Retry-After: 1` без partial disclosure.
- [ ] Client cancellation во время response ingest, analysis и handoff race,
  а также shutdown освобождают retained source ровно один раз без partial
  disclosure.
- [ ] Audit assertions покрывают exact ordering, `CLEAN`, `DETECTED`,
  `INSPECTION_GAP`, `ERROR`, отсутствие pair до analysis и отсутствие payload,
  spans, credentials, identity и upstream body.

### Pure domain, source-map и rewrite

- [ ] Cases покрывают derived `ALLOW`/`MASK`/`BLOCK`, global `BLOCK`
  precedence и immutable per-fragment instruction grouping без detector rerun.
- [ ] Cases покрывают `OUTPUT_TEXT`, `REFUSAL`, modern/deprecated tool
  arguments и audio transcript; fragment offsets никогда не смешиваются.
- [ ] Cases покрывают ASCII, multibyte UTF-8, JSON escapes, `\uXXXX`, несколько
  spans/choices и byte-identical preservation unknown fields/formatting.
- [ ] Invalid, duplicate или ambiguous locator и невозможное UTF-8/source
  mapping дают typed failure без partial output и без mutation input.
- [ ] One-shot response handoff tests покрывают successful transfer, repeated
  transfer, close-before-transfer, synchronous handoff failure и cancellation
  races с exact-once cleanup.

## Критерии выполнения

- [ ] Deterministic real-Armeria E2E покрывает `ALLOW` byte-for-byte replay,
  `MASK` с exact replacement и header rewrite, а также `BLOCK` без одного
  upstream byte клиенту.
- [ ] Tests покрывают independent choices, upstream `4xx`/`5xx`, recognized
  non-text `INSPECTION_GAP`, malformed/ambiguous response, detector/policy
  timeout/failure, cancellation и shutdown cleanup.
- [ ] Response публикует ровно одну safe VIG-32-01 stdout pair после actual parse
  и policy selection. Payload, spans, credentials и identity не логируются.
- [ ] Новые и изменённые Kotlin declarations, test methods и lifecycle helpers
  имеют KDoc; focused tests, `validateWorkItems` и `./gradlew build` проходят.

## Ambiguity Report

```text
Goals:        0.0   non-stream enforcement outcome fixed
Acceptance:   0.05  E2E and pure evidence matrices explicit
Boundaries:   0.05  exchange, workflow and one-shot ownership fixed
Alternatives: 0.05  source patching selected over full serialization
Assumptions:  0.15  implementation reuses contracts delivered by dependencies
Aggregate:    0.06  Ready for implementation.
```
