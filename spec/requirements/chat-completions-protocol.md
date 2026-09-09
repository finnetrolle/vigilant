# Chat Completions: protocol contract

Нормативный owner descriptor, normalized result, recognized fields и terminal
semantics для [MVP-07](../MVP_FUNCTIONS.md#mvp-07-минимальная-интеграция),
[PROXY-01/02](../MVP_NON_FUNCTIONAL_REQUIREMENTS.md#proxy-и-protocol).
[Runtime guide](../../docs/openai-chat-completions.md) объясняет реализацию;
[coverage](../../docs/requirements-coverage.md#protocol-evidence) отделяет
согласованный target от source/test observations и gaps.

## Surface

Публичный MVP поддерживает только Chat Completions JSON request и ordinary
JSON/SSE response. Responses API остаётся future scope
[EPIC-06](../epics/epic_06_llm_message_parsing.md), вне MVP. Realtime и Batch -
неактивированные post-MVP placeholders без field maps, terminal semantics,
transport contracts и implementation issues. WebSocket/WebRTC, SDP, RTP/audio,
SIP, Batch JSONL и Files/job lifecycle не являются input текущего parser.

Request contract snapshot: [openai-openapi 2.3.0](https://github.com/openai/openai-openapi/blob/1665a18fe20217c989c66dd73888345c6e4eb63c/openapi.yaml), commit
`1665a18fe20217c989c66dd73888345c6e4eb63c`, дата фиксации `2026-08-26`.
Runtime не выбирает `latest`, не угадывает schema по body и не переключает
adapter после неудачного parse. Pinned vocabulary не расширяется неизвестными
полями. Explicit descriptor задаёт family, operation, method, normalized path,
media type, direction, transport и внутреннюю версию contract до чтения source.
Общие result types не являются mega-DTO разных API surfaces.

## Normalized result

Parser возвращает immutable ordered collection независимых decoded text
fragments: ровно одно logical content-bearing поле на fragment. Разные поля,
messages, choices и tool calls не конкатенируются. Каждый fragment проверяется
отдельно. Пустое известное text field inspectable, но не создаёт zero-length
fragment. Fragment длиннее detector limit передаётся целиком в
[windowing](windowed-inspection.md#fragment), без blanket reject и truncation.

Provenance содержит ordinal, direction, semantic kind, explicit role (только
если она задана schema) и opaque protocol-specific locator до исходного поля,
item, content block или SSE logical field. Guardrail-facing kinds:
`INSTRUCTION`, `MESSAGE_TEXT`, `LABEL`, `SCHEMA_TEXT`, `TOOL_DESCRIPTION`,
`TOOL_ARGUMENT`, `TOOL_RESULT`, `OUTPUT_TEXT`, `REFUSAL`, `REASONING`.
Locator/OpenAI IDs не передаются detector/policy engine и не попадают в safe
logs/errors. Detector UTF-8 offsets локальны для decoded fragment и не являются
JSON/SSE source offsets.

Единственный body-derived normalized attribute - request `model`; model name
не является payload. Произвольной attributes map нет. Protocol family,
operation, direction и transport остаются envelope metadata. Сборка context
и перенос request model в RESPONSE принадлежат
[context owner](identity-and-context.md#assembly-и-handoff): reported response
model не переопределяет policy context. Повторный body/event parse ради context
или policy execution запрещён.

Parser читает complete immutable source, но не владеет original source,
не закрывает его owner, не replay-ит его и не возвращает raw/reconstructed body.
Read stream закрывается после parse; source ownership остаётся у integration.
Request хранится в bounded source; response - в retained in-memory source.
Parser не выбирает policies, не запускает detectors/windowing/enforcement и не
раскрывает upstream bytes. ALLOW replay использует только exact original bytes,
включая unknown fields, whitespace, escapes и source order, без DTO serialization.
Rewriter получает source и parser-owned coordinates отдельно; parser result
не служит материалом для пересборки body.

## Request


### Routing и единица parse

Request adapter выбирается до чтения body по exact descriptor:

```text
family=OPENAI
operation=CHAT_COMPLETIONS
method=POST
normalized_path=/v1/chat/completions
media_type=application/json
direction=REQUEST
transport=JSON
contract=openai-chat-completions-request@2026-08-26
```

Media type сравнивается case-insensitive по type/subtype; parameters, включая
`charset`, не участвуют в выборе. `application/*+json`, другой path или method
не получают fallback и возвращают `UNSUPPORTED_SCHEMA`. Complete bounded
request source является одной единицей parse. `stream=true` влияет только на
upstream response transport и не меняет request result.

Root обязан быть JSON object с непустым string `model` и непустым array
`messages`. `model` становится единственным normalized attribute и никогда не
становится payload fragment. Duplicate object keys на любом уровне дают
`AMBIGUOUS_CONTENT`, потому что выбор first/last value был бы schema guessing.

### Semantic field map

Fragments следуют порядку начала соответствующих values в original JSON.
Empty known text field считается полностью inspectable, но не создаёт
zero-length fragment. Один array content part или scalar field создаёт не
более одного fragment. JSON Pointer может использоваться внутри opaque
protocol locator, но locator не является public offset contract.

| Source | Semantic kind | Role | Правило |
|---|---|---|---|
| `messages[*].content` у `developer`/`system` | `INSTRUCTION` | explicit role | String или каждый `type=text` part отдельно |
| `messages[*].content` у `user`/`assistant` | `MESSAGE_TEXT` | explicit role | String или каждый text/refusal part отдельно; refusal использует `REFUSAL` |
| `messages[*].content` у `tool`/`function` | `TOOL_RESULT` | explicit role | String или каждый разрешённый text part отдельно |
| `messages[*].name` и function/tool/custom names | `LABEL` | message role, если есть | OpenAI IDs и call IDs исключаются |
| assistant `tool_calls[*].function.arguments` и deprecated `function_call.arguments` | `TOOL_ARGUMENT` | `assistant` | Весь decoded string, без обязательного inner JSON parse |
| custom tool-call textual input | `TOOL_ARGUMENT` | `assistant` | Весь decoded string |
| `tools[*].function.description` и `tools[*].custom.description` | `TOOL_DESCRIPTION` | absent | Name идёт отдельным `LABEL` |
| named `tool_choice`, `allowed_tools` и deprecated `function_call.name` | `LABEL` | absent | Только user-supplied tool/function/custom names; fixed modes исключаются |
| custom tool grammar `definition` | `SCHEMA_TEXT` | absent | `syntax` является fixed discriminator и исключается |
| deprecated root `functions[*]` | как function tool | absent | Та же семантика name, description и parameters |
| `web_search_options.user_location.approximate.{country,region,city,timezone}` | `TOOL_ARGUMENT` | absent | Каждый present string отдельно; `type` и search size исключаются |
| `prediction.content` | `OUTPUT_TEXT` | absent | String или каждый `type=text` part отдельно |
| `response_format.json_schema` и function `parameters` | `SCHEMA_TEXT`/`LABEL` | absent | Только явный schema vocabulary ниже |

Assistant `audio.id`, user `image_url`, `input_audio` и `file` являются
schema-recognized non-text/provider-opaque content. Они не передаются text
detector и создают соответственно gaps `OPAQUE_AUDIO_REFERENCE`, `IMAGE`,
`AUDIO` и `FILE`. Доступный `file.filename` создаёт `LABEL`, но file data,
file ID, media URL и filename не попадают в safe errors или audit events.

Control и metadata fields, включая `model`, `store`, `metadata`, `user`,
`safety_identifier`, sampling parameters, token limits, `stop`, `seed`,
`stream`, `stream_options`, `modalities`, output audio settings,
`service_tier`, `verbosity`, `reasoning_effort`, moderation options,
web-search context size, fixed tool-choice modes и `parallel_tool_calls`, не
являются detector payload. Их unknown siblings сохраняются только в original
source.

### JSON Schema walker

Walker принимает только bounded in-document schema и не делает recursive
all-string traversal. Text fragments создают:

- property names из `properties`, `patternProperties` и `dependentSchemas`;
- `title`, `description`, string `enum`, `const`, `default`, `examples` и
  regex `pattern`;
- те же значения внутри известных containers `$defs`, `definitions`,
  `items`, `prefixItems`, `contains`, `additionalProperties`, `allOf`, `anyOf`,
  `oneOf`, `not`, `if`, `then`, `else`, `propertyNames` и schema-valued
  dependency keywords.

Fixed keywords, `$id`, `$anchor`, local `$ref`, `type`, `required`, format
names, boolean/numeric constraints и protocol discriminator values не
становятся fragments. External `$ref` даёт `UNRESOLVED_CONTEXT`; cyclic или
unresolvable local `$ref` даёт `AMBIGUOUS_CONTENT`.

Unknown schema keyword с `null`, boolean или number сохраняется и
игнорируется. Unknown keyword со string, object или array даёт
`AMBIGUOUS_CONTENT`, потому что может скрывать model-visible text.

### Coverage, failures и resource boundary

- Text-only request без gaps возвращает `FULLY_INSPECTABLE`.
- Text вместе хотя бы с одним gap возвращает `PARTIALLY_INSPECTABLE`.
- Только recognized non-text/provider-opaque content возвращает
  `UNINSPECTABLE`.
- Отсутствие непустых text values в корректной text-only schema остаётся
  `FULLY_INSPECTABLE`, а не становится gap.
- Invalid UTF-8/JSON, missing required fields или неверный JSON type даёт
  `MALFORMED_MESSAGE`.
- Unknown role/content/tool discriminator и duplicate key дают
  `AMBIGUOUS_CONTENT`.
- Structural nesting глубже `128` уровней или больше `16 384` normalized
  fragments даёт `UNSUPPORTED_SCHEMA` без частичного result.
- Per-request/global source exhaustion происходит до parser и принадлежит
  source owner; parser не переводит его в parse failure.
- Cancellation остаётся cancellation и отбрасывает partial normalized state.

Parser читает immutable source, но не закрывает, не replay-ит и не копирует
его в result. Fragment длиннее detector limit передаётся
[windowed inspection](windowed-inspection.md#fragment) целиком.

### Recognized message shapes

| Поле/shape | Допустимая форма и нормализация |
|---|---|
| Message role | `developer`, `system`, `user`, `assistant`, `tool`, `function`; непустая string role обязательна |
| Message content | String или array отдельных parts; `null` допустим у assistant. Остальные roles требуют `content`; assistant без content требует tool calls, function call, audio либо reasoning |
| Text part | `type=text` с string `text`; semantic kind задаёт role |
| Refusal part | Только assistant, `type=refusal` с string `refusal`, kind `REFUSAL` |
| Image part | Только user; `type=image_url`, object `image_url` с непустой string `url`; gap `IMAGE` |
| Audio part | Только user; `type=input_audio`, object с непустыми `data`, `format=wav\|mp3`; gap `AUDIO` |
| File part | Только user; object `file` с ровно одним непустым string `file_data` или `file_id`; optional string `filename` даёт отдельный `LABEL`, gap `FILE` остаётся |
| Assistant audio | Object с непустой string `id`, gap `OPAQUE_AUDIO_REFERENCE` |
| Function call | Modern `type=function` или deprecated assistant `function_call`: object с name и string arguments; empty arguments допустимы, invalid inner JSON остаётся текстом |
| Custom call | `type=custom`, object `custom` с name и string input; весь input - один `TOOL_ARGUMENT`, без разбора вложенного языка |
| Reasoning | Assistant object `reasoning`: string `text` и `summary` дают отдельные `REASONING`; string `encrypted_content` даёт `OPAQUE_REASONING` и не декодируется |
| Custom definition grammar | `format.type=grammar`, `grammar.syntax=lark\|regex`, string `definition`; только definition становится `SCHEMA_TEXT` |
| Prediction | `type=content`, string content или array `type=text` parts |
| Response format | `type=text\|json_object` без fragments; `type=json_schema` требует object `json_schema`, name и schema |

Empty plaintext при наличии encrypted content остаётся gap. Gap сам по себе
не запрещает lossless forwarding, но не отменяет policy BLOCK, structural MASK
или technical failure. Media URL, data, IDs и encrypted values не становятся
text payload. Unknown additional properties известного object сохраняются
только в source. Unknown role/content/tool discriminator не является gap.

Structured argument containers не добавляются: закреплённая Chat Completions
surface принимает textual arguments/input. Общая идея typed structured
arguments не разрешает новые schema variants или stringification values.

### Schema vocabulary и порядок

Нормативный semantic kind model-visible property names - `SCHEMA_TEXT`;
текущее отличие runtime `LABEL` явно отражено в coverage. Tool/schema names
остаются `LABEL`. Внутри `properties`, `patternProperties`, `dependentSchemas`
имя предшествует child schema; `$defs` и `definitions` обходят values, а их
служебные definition keys не становятся payload. Boolean schema допустима.
`enum` и `examples` требуют array; только string elements дают fragments.
`const`, `default`, `title`, `description`, `pattern` извлекают string values;
non-string values не stringified и не обходятся как произвольные subtrees.

Полный container vocabulary: named maps `properties`, `patternProperties`,
`dependentSchemas`; definition maps `$defs`, `definitions`; single schemas
`items`, `contains`, `additionalProperties`, `not`, `if`, `then`, `else`,
`propertyNames`; arrays `prefixItems`, `allOf`, `anyOf`, `oneOf`; legacy
`dependencies` обходят schema-valued object/boolean и исключают arrays имён
required properties. Иные формы известных containers дают typed malformed.

Полный fixed vocabulary: `$schema`, `$id`, `$anchor`, `$dynamicAnchor`, `type`,
`required`, `format`, `multipleOf`, `maximum`, `exclusiveMaximum`, `minimum`,
`exclusiveMinimum`, `maxLength`, `minLength`, `maxItems`, `minItems`,
`uniqueItems`, `maxContains`, `minContains`, `maxProperties`, `minProperties`,
`dependentRequired`, `contentEncoding`, `contentMediaType`, `$comment`,
`readOnly`, `writeOnly`, `deprecated`. Они не становятся fragments.
`$ref` проверяется отдельно по правилам local/external references выше.

### Request source coordinates

В одном structural parse parser создаёт immutable association fragment ordinal,
locator, field classification и source identity. Для free text сохраняется raw
opening-quote byte offset; structural fields не получают rewrite coordinates.
Это не делает decoded UTF-8 offsets raw offsets. Назначение FREE_TEXT/STRUCTURAL
и enforcement перечислены в
[request contract](request-enforcement.md#field-classification), raw-token
mapping объяснён в [runtime guide](../../docs/openai-chat-completions.md#передача-без-потерь).

## Response descriptor

Обе response forms используют family `OPENAI`, operation `CHAT_COMPLETIONS`,
method `POST`, normalized path `/v1/chat/completions`, direction `RESPONSE`,
contract `openai-chat-completions-response@2026-09-03`.

| Transport | Media type | Единица parse |
|---|---|---|
| JSON | `application/json` | Complete ordinary JSON source |
| SSE | `text/event-stream` | Complete retained sequence до terminal и проверки EOF |

Media type сравнивается case-insensitive без parameters. Несовпадение любого
поля descriptor, включая version/direction/transport, даёт `UNSUPPORTED_SCHEMA`
до source access; sniffing/fallback запрещены. HTTP response descriptor
выбирается из upstream Content-Type; unsupported type и non-identity encoding
отклоняются согласно [gateway contract](http-gateway.md#descriptor).

## Ordinary JSON response

Root - object с array `choices` (включая empty array). Каждый choice и его
`message` - objects. Optional explicit role имеет string value `assistant`;
другая role ambiguous, не-string malformed. `finish_reason`, reported model,
IDs, timestamps, usage и choice indexes не являются payload или дополнительным
terminal event. Complete valid JSON завершает parse; trailing JSON/garbage
не допускаются. Unknown additive metadata сохраняются в original source.

| Source внутри каждого `choices[]` | Shape | Kind / outcome |
|---|---|---|
| `message.content` | String | `OUTPUT_TEXT` |
| `message.refusal` | String | `REFUSAL`, после content того же choice |
| `message.tool_calls[]` | Object, `type=function`, object function, string arguments | Каждый `function.arguments` - отдельный `TOOL_ARGUMENT` в array order |
| `message.function_call` | Object со string arguments | Deprecated `TOOL_ARGUMENT`, без inner JSON parse |
| `message.audio` | Object со string data и transcript | `transcript` - `OUTPUT_TEXT`, audio - `AUDIO` gap |
| Optional content/refusal/tool_calls/function_call/audio | Missing или null | Не создают fragment/gap |
| Empty recognized text | Empty string | Не создаёт fragment, само по себе не gap |

Порядок: choices array; внутри choice сначала content, затем refusal, затем
modern/deprecated calls и audio в source property order. Tool names и IDs
response не добавляют fragments. Optional null относится к полю-envelope,
но present function arguments и audio data/transcript обязаны быть strings.
Content object/array и unknown tool discriminator дают `AMBIGUOUS_CONTENT`;
missing/non-array choices, non-object choice/message, неверный known field type,
missing required nested shape, malformed UTF-8/JSON дают `MALFORMED_MESSAGE`.
Duplicate keys на любом уровне дают `AMBIGUOUS_CONTENT`. Failure не возвращает
partial normalized response. Gap/coverage следуют общей таблице ниже.

## SSE

### Framing и logical fields

Parser поддерживает LF и CRLF, comments, empty/comment-only events и standard
joining нескольких `data` lines через LF. Chunk boundaries внутри UTF-8 code
point, JSON token, SSE field или separator не меняют результат. Каждый
non-terminal data event содержит JSON Chat Completions chunk; explicit
`event: message` допустим. Event и TCP chunk не являются payload boundaries.

| Delta field | Logical key | Нормализация |
|---|---|---|
| `choices[].delta.content` | choice.index + content | `OUTPUT_TEXT` |
| `choices[].delta.refusal` | choice.index + refusal | `REFUSAL` |
| `choices[].delta.tool_calls[].function.arguments` | choice.index + tool-call index + arguments | `TOOL_ARGUMENT` |
| `choices[].delta.function_call.arguments` | choice.index + deprecated arguments | `TOOL_ARGUMENT` |

Canonical text равен concatenation string deltas в event order, независимо
по каждому key. Порядок fragments - первое появление logical field; внутри
одного choice event content, refusal, modern calls, deprecated call. Empty
buffers не создают fragments. Optional null content/refusal и null call
envelopes не создают buffer; present arguments - strings. Tool function object
обязателен, arguments могут отсутствовать на промежуточном event. Optional
role - assistant, optional tool type - function; names/IDs не являются text.
Final snapshots не заменяют и не сверяют deltas. Parser не выдаёт provisional
fragments и не запускает enforcement.

### Terminal outcomes

| Событие / форма | Результат |
|---|---|
| Standalone `data: [DONE]` с завершённым separator | Завершает все buffers; Success после проверки оставшегося source |
| `[DONE]` вместе с другими data, event type или other field | `MALFORMED_MESSAGE` |
| Content/events после `[DONE]`, в том числе второй terminal | `MALFORMED_MESSAGE`; дополнительные пустые separators допустимы |
| Clean EOF без `[DONE]`, открытый event или logical stream | `MALFORMED_MESSAGE`, без partial result |
| Invalid UTF-8/JSON, незавершённый event, bare CR | `MALFORMED_MESSAGE` |
| Missing/non-array choices, non-object choice/delta/call/function | `MALFORMED_MESSAGE` |
| Missing, negative, non-integral/out-of-range choice/tool-call index; duplicate index внутри одного event/choice | `MALFORMED_MESSAGE`; одинаковый index в разных events продолжает logical buffer |
| Incompatible repeated known field shape или неверный text type | `MALFORMED_MESSAGE` |
| Duplicate JSON key, duplicate event type | `AMBIGUOUS_CONTENT` |
| Unknown role/tool discriminator, unknown content-bearing delta field со string/object/array | `AMBIGUOUS_CONTENT` |
| Unknown delta field с null/boolean/number | Additive metadata, не fragment |
| Unknown SSE event type | Target `UNSUPPORTED_SCHEMA`; runtime mismatch указан в coverage |
| Valid `event: error` с object `error` в JSON envelope | Safe `UpstreamError` outcome вызывающего слоя; не partial Success и не parser failure |
| Transport error | Остаётся upstream/transport outcome вызывающего слоя |
| Caller cancellation | Остаётся cancellation, partial state отбрасывается |

Незавершённые buffers отбрасываются; ранее собранный текст не возвращается в
failure и не изменяется задним числом. Ни success parser result, ни typed
failure/upstream marker не содержат raw response, preview, headers или
credentials. HTTP mapping находится у [gateway owner](http-gateway.md).
Атомарное удержание до terminal и final policy decision принадлежит
[response runtime](../../docs/runtime-contract.md#response-enforcement);
раннее раскрытие status/headers/body и incremental release не входят в MVP.

## Response source maps

Один parse pass создаёт immutable source coordinates; повторный structural
parse ради rewriting не нужен. Ordinary JSON map связывает fragment ordinal,
locator и каждую valid decoded UTF-8 boundary с absolute raw string offset.
SSE map хранит ordered segments каждого logical field: decoded start/end в
полном fragment, raw content start/end исходного delta string и segment-local
UTF-8-to-raw boundary map. Mapping сохраняется через multi-line data, Unicode,
escapes, interleaved choices и tool calls. Raw source не включается в map.
Source spans могут пересекать несколько delta events; rewriting сохраняет
нетронутые bytes/events, не пересериализует event objects. Applying reactions,
retention/replay и resource cleanup остаются у integration/rewriter owners.

## Coverage и safe failures

| Text fragments | Gaps | Coverage |
|---|---|---|
| Любое число, в том числе 0 | Нет | `FULLY_INSPECTABLE` |
| Есть | Есть | `PARTIALLY_INSPECTABLE` |
| Нет | Есть | `UNINSPECTABLE` |

Два последних статуса всегда имеют непустой список explicit gaps. Ни пустой
payload, ни gap не означают CLEAN автоматически. Success означает допустимый
parse для последующего policy decision, а не безусловный ALLOW.

Expected failures содержат только stable code `MALFORMED_MESSAGE`,
`UNSUPPORTED_SCHEMA`, `AMBIGUOUS_CONTENT` или `UNRESOLVED_CONTEXT`. Они fail-closed,
не выбрасывают необработанную expected exception и не раскрывают source values,
body/preview, credentials, locator или partial normalized state. Parser не
выполняет network lookup для references. Privacy и audit lifecycle integration
описаны в [observability contract](observability.md#analysis-lifecycle-audit).

## Проверка контракта

Public parser examples, exhaustive field/segmentation/negative matrices и HTTP
observations определены в [development guide](../../docs/development.md#protocol-contract-checks).
Наличие теста или новой ссылки не подтверждает отсутствующие cases;
[coverage ledger](../../docs/requirements-coverage.md#protocol-evidence) сохраняет
target/runtime/evidence различия.
