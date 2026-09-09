# RESPONSE enforcement

Нормативный owner атомарной проверки OpenAI Chat Completions response для
`MVP-01`, `MVP-03`, `PERF-03`, `CONC-01`, `CONC-03`, `CONC-04` и
`PROXY-01..03`. Parsing, field types и terminal semantics принадлежат
[Chat Completions protocol](chat-completions-protocol.md), domain selection и
reaction aggregation - [policy engine](policy-engine.md), а HTTP routing,
headers и client errors - [HTTP gateway](http-gateway.md).

## Atomic boundary

Guardrail route удерживает upstream status, headers, trailers и все body bytes
в retained in-memory response source. До complete protocol-valid source и
итогового policy decision клиент не получает status, header, trailer или body
byte. Ordinary JSON достигает terminal state только по end-of-stream, SSE -
только после отдельного standalone `data: [DONE]`; transport completion без
этого SSE event не является success.

Все корректно сформированные Chat Completions responses проходят один и тот же
workflow независимо от upstream status, включая `200`, `429` и `500`.
Request-derived URL, model и normalized identity сохраняются в immutable
handoff; response меняет только phase на `RESPONSE` и не переопределяет context
своими полями.

`ALLOW` раскрывает original source byte-for-byte. `MASK` раскрывает только
полностью проверенную exact-source representation. Любой `BLOCK`, protocol или
technical failure заменяет весь upstream response локальным safe outcome до
первого disclosure. Низкоуровневый bypass transport остаётся streaming и не
получает эту retention boundary.

## Retained source and ownership

Response source использует только доступный JVM heap. У него нет
application-level raw/text byte limit, shared quota, capacity admission, disk
spill, temporary file или persistent representation. Это намеренная граница,
а не application memory-safety guarantee: deployment отвечает за heap sizing и
runtime OOM policy, JVM GC - за фактическое освобождение heap после удаления
ссылок.

Source запрашивает у upstream ровно один body item, копирует его в
source-owned memory и только затем запрашивает следующий. После complete ingest
доступны один последовательный parser view и один demand-driven replay lease.
Одновременные views/replays и повторное получение replay запрещены.

One-shot ownership проходит следующие состояния:

1. `RetainedResponseHandler` владеет source во время ingest и до передачи
   complete source workflow.
2. Workflow владеет source во время parse, policy evaluation и rewrite.
3. `ForwardOriginal` или `ForwardMasked` передаёт source в ready response;
   synchronous transport callback может принять его ровно один раз.
4. После принятого handoff terminal replay publisher владеет cleanup. До
   принятия close, repeated transfer и callback failure закрывают source.

Каждый terminal path очищает все source-owned byte arrays и references:
original или masked replay completion/failure/cancellation, policy rejection,
parse/analysis/rewrite/handoff failure, upstream interruption, client
cancellation, peer/caller close и normal или forced shutdown. Cleanup
идемпотентен; failure одного external cleanup action не отменяет попытки
остальных и сохраняет первый failure с последующими suppressed.

## Fragments, gaps and reactions

Каждый textual field является независимым inspection fragment. Findings не
пересекают choices, semantic fields, tool calls, audio transcript и другие
logical fields. Полный набор ordinary JSON fragments:

- `OUTPUT_TEXT`: `choices[].message.content`;
- `REFUSAL`: `choices[].message.refusal`;
- `TOOL_ARGUMENT`: modern
  `choices[].message.tool_calls[].function.arguments`;
- `TOOL_ARGUMENT`: deprecated
  `choices[].message.function_call.arguments`;
- `OUTPUT_TEXT`: `choices[].message.audio.transcript`, когда он присутствует.

Полный набор SSE logical fragments, независимо собираемых по `choice.index`,
semantic field и tool-call index:

- `OUTPUT_TEXT`: `choices[].delta.content`;
- `REFUSAL`: `choices[].delta.refusal`;
- `TOOL_ARGUMENT`: modern
  `choices[].delta.tool_calls[].function.arguments`;
- `TOOL_ARGUMENT`: deprecated
  `choices[].delta.function_call.arguments`.

Recognized `null` и empty SSE buffer не создают fragment или gap. Recognized
non-text media/file/audio data создают inspection gap и сохраняются без
изменений; соседние textual fragments всё равно проверяются. Unknown content
discriminator, missing/non-array `choices`, malformed или ambiguous
content-bearing shape являются protocol failure, а не gap.

Результат всех независимых fragments агрегируется атомарно:

| Наблюдение | Final reaction | Audit outcome |
|---|---|---|
| Только clean text, gaps нет | `ALLOW` | `CLEAN` |
| Только gap или clean + gap | `ALLOW` | `INSPECTION_GAP` |
| Finding только у `ALLOW` policies | `ALLOW` | `DETECTED` |
| Finding у `MASK`, `BLOCK` отсутствует | `MASK` | `DETECTED` |
| Finding хотя бы у одной `BLOCK` policy | `BLOCK` всего response | `DETECTED` |
| Detected `MASK` или `BLOCK` вместе с gap | Та же reaction; gap bytes неизменны | `DETECTED` |
| Detector/policy error или deadline | Нет reaction, technical reject | `ERROR` |

`BLOCK` имеет приоритет над `MASK` и `ALLOW`. Technical failure не кодируется
как synthetic reaction и не падает обратно в unmasked forwarding. RESPONSE
audit pair и privacy принадлежат
[observability contract](observability.md#analysis-lifecycle-audit); gateway
не ждёт её delivery перед transport outcome.

## Canonical masking

Policy domain один раз строит immutable
`MaskingInstruction(utf8Span, marker)` из selected `MASK` reactions и findings;
detector повторно не запускается. Transport-neutral `TextMasker` не знает
OpenAI, HTTP, detector или policy file и меняет только selected decoded UTF-8
spans. Typed irreversible markers включают `[EMAIL_MASKED]`, `[CARD_MASKED]`,
`[PHONE_MASKED]` и эквиваленты остальных поддерживаемых PII types.

Non-overlapping spans сохраняют typed markers. Adjacent и overlapping spans
объединяются в один union: одинаковые markers сохраняются, конфликтующие дают
`[PII_MASKED]`. Результат детерминирован независимо от порядка policies и
detectors. Out-of-range span, non-UTF-8 boundary или invalid marker дают typed
failure без partial output и отображаются как
`503 response_inspection_unavailable`. `REMOVE` не входит в MVP.

## Source maps and exact rewrite

Единственный protocol parse pass строит immutable source-coordinate metadata,
не сохраняя в ней копию body или decoded payload. Она связывает opaque locator,
decoded UTF-8 boundaries и raw escaped JSON byte positions. Rewriter сначала
валидирует все locators, ranges и boundaries, затем применяет patches в
descending raw-offset order. Missing, duplicate или ambiguous locator и
невозможное mapping завершаются typed failure до первой записи.

Ordinary JSON rewrite изменяет только выбранные JSON string literals. Unknown
fields, field order, whitespace, number formatting, escapes и все
незатронутые bytes сохраняются byte-for-byte; `ObjectNode` не сериализуется
заново.

SSE source map дополнительно хранит ordered delta segments одного logical
field, decoded range каждого segment и raw range его JSON string literal.
Finding может пересекать transport chunks и несколько delta events одного
logical field, но не разные choices, tool calls или semantic fields. Для
cross-event span marker вставляется ровно один раз в value, содержащий начало;
covered text удаляется из всех затронутых values, а полностью покрытый value
остаётся на месте как empty string. Events не удаляются, не объединяются и не
переупорядочиваются. LF/CRLF, comments, multi-line `data`, unknown SSE fields,
separators и незатронутые source ranges сохраняются byte-for-byte.
Concatenation rewritten values точно равна transport-neutral masked text.

После `MASK` marker, prefix и suffix кодируются как valid UTF-8 JSON;
`Content-Length` пересчитывается по exact rewritten bytes. Header matrix,
representation validators и hop-by-hop handling принадлежат
[HTTP gateway](http-gateway.md#response-outcomes-and-headers).

## Failures and cancellation

- Unsupported response media type или non-identity `Content-Encoding`,
  malformed JSON/SSE, invalid terminal state и upstream body interruption дают
  exact `502 invalid_upstream_response`.
- Detector/policy failure или timeout, invalid masking instruction,
  source-map/rewrite failure и невозможный replay handoff дают exact
  `503 response_inspection_unavailable` с `Retry-After: 1`.
- Policy `BLOCK` даёт exact `403 policy_blocked`.
- Во всех трёх случаях upstream status, headers, trailers и body не
  раскрываются; точные bodies определены
  [inspection error matrix](http-gateway.md#inspection-error-matrix).
- Cancellation во время ingest отменяет upstream subscription и не начинает
  analysis. Cancellation во время analysis отменяет work и не создаёт новый
  handoff. Cancellation после принятого handoff завершает replay cleanup.
- Shutdown не начинает новый response analysis; admitted work может drain до
  force deadline, после чего active source/replay отменяется и очищается.

## Verification boundary

Нормативные cases проверяются через pure parser/source-map/rewriter suites,
source ownership tests и causal real-Armeria JSON/SSE E2E. Отдельно наблюдаются
отсутствие ранних headers и body, protocol terminal state, detector barrier,
final wire outcome и terminal cleanup. Test names сами по себе и client
completion не доказывают release или audit publication. Полная обязательная
matrix и команды находятся в
[development guide](../../docs/development.md#response-and-gateway-contract-checks),
а фактические gaps - в
[requirements coverage](../../docs/requirements-coverage.md#response-and-gateway-evidence).

## Не входит

Application response quota/limit, disk spill, persistent storage, compressed
response decoding, retry/regeneration, `REMOVE`, новые reactions, новые
protocols, Realtime и Batch.
