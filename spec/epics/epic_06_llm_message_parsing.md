# Epic 06: Разбор LLM-сообщений и извлечение payload

**ID:** `EPIC-06`  
**Тип:** Epic  
**Статус:** Draft  
**Приоритет:** High  
**Предварительная оценка:** после закрытия протокольного контракта  
**Связанные требования:** `MVP-14`, `MVP-20`, `PROXY-01`, `PROXY-02`

## Подтверждённое решение

Разбор сообщений LLM API и извлечение payload оформляются как самостоятельный
epic. EPIC-03 не расширяется этой ответственностью.

В рамках MVP epic ограничен OpenAI API. Другие providers не участвуют в
текущем контракте, декомпозиции и acceptance criteria. Их возможная поддержка
относится к отдельному будущему scope и не должна влиять на решения MVP.

Размер и независимость контролируются на уровне дочерних issues: каждая
OpenAI API surface, transport и direction декомпозируются в отдельные
небольшие листья.

В активный MVP scope входят следующие OpenAI API surfaces:

- OpenAI Responses API;
- OpenAI Chat Completions API.

OpenAI Realtime API и OpenAI Batch API остаются post-MVP placeholders внутри
EPIC-06. До отдельного решения об их активации для них не уточняются field
maps, terminal semantics, transport contracts и implementation issues.

Парсер возвращает payload как упорядоченную immutable-коллекцию независимых
текстовых фрагментов. Каждый фрагмент относится ровно к одному логическому
content-bearing полю и содержит provenance, достаточную для однозначной связи
с исходным protocol message, item или content block. Тексты из разных полей
не конкатенируются в один payload.

Parser сохраняет порядок фрагментов, заданный исходным сообщением или
порядком protocol events. Последующий слой передаёт каждый фрагмент в policy
engine отдельным вызовом, не создавая совпадений через границы полей.

EPIC-06 владеет извлечением всех данных, источник которых находится внутри
OpenAI request/response body или SSE event. Это включает `model`, content
metadata и другие schema-derived attributes, которые будут нужны для
построения `PolicyContext` или проверки payload. Ни один соседний epic не
разбирает protocol message повторно.

Результат parser разделяет normalized protocol attributes и payload
fragments. [EPIC-03](epic_03_policy_context_extraction.md) получает готовые
attributes и собирает из них и HTTP-derived данных `PolicyContext`. Payload
fragments передаются последующим слоем в policy engine отдельно от context.

Payload извлекается только из явно перечисленных content-bearing полей
поддерживаемой protocol schema. Parser не обходит JSON рекурсивно в поиске
всех строк.

Content-bearing text включает:

- instructions и текст сообщений всех ролей, передаваемый модели;
- model-visible message names, tool/function names, descriptions и
  пользовательские текстовые элементы schema;
- tool call arguments и tool results;
- output text, refusal и tool call arguments, возвращаемые клиенту.

Служебные IDs, model name, timestamps, usage, status, sequence indexes,
finish reason и другие transport metadata не являются payload. Нужные
политикам metadata возвращаются отдельно как normalized attributes.

Для текущего этапа schema-recognized non-text content и provider-opaque
reasoning обрабатываются в явном fail-open режиме:

- parser отмечает inspection gap для изображения, аудио, файла или другого
  распознанного media content, который текущие text detectors не проверяют;
- доступные подписи, transcripts и другие отдельные text fields по-прежнему
  возвращаются как text payload fragments;
- исходное сообщение пересылается дальше без изменения из-за самого факта
  наличия распознанного non-text content;
- inspection gap не скрывается и не выводится из пустого списка payload;
- этот режим не является runtime-настройкой текущей версии.

OpenAI reasoning content разделяется по inspectability:

- доступный plaintext reasoning text или summary возвращается отдельным
  fragment с semantic kind `REASONING`;
- `encrypted_content` не декодируется, не передаётся detector и создаёт
  inspection gap `OPAQUE_REASONING`;
- original encrypted value сохраняется без изменений для lossless forwarding
  и multi-turn continuation;
- пустой plaintext при наличии `encrypted_content` остаётся inspection gap, а
  не считается проверенным пустым fragment.

Это решение не распространяется на malformed JSON, неизвестный discriminator
или ambiguous content-bearing structure. Их error semantics остаётся отдельным
решением.

Parser возвращает явный success либо failure result. Успешный result содержит
один обязательный coverage status:

```text
FULLY_INSPECTABLE
PARTIALLY_INSPECTABLE
UNINSPECTABLE
```

- `FULLY_INSPECTABLE` означает, что все распознанные content-bearing части
  представлены text payload fragments; inspection gaps отсутствуют.
- `PARTIALLY_INSPECTABLE` означает, что присутствуют и text payload fragments,
  и schema-recognized non-text или provider-opaque content; список inspection
  gaps непустой.
- `UNINSPECTABLE` означает, что content распознан, но проверяемых text payload
  fragments нет; список inspection gaps непустой.
- Все три success status разрешают lossless forwarding на текущем этапе.
- Пустой список fragments сам по себе не определяет coverage status.

Expected parse failures возвращаются типизированно с одним stable code:

```text
MALFORMED_MESSAGE
UNSUPPORTED_SCHEMA
AMBIGUOUS_CONTENT
UNRESOLVED_CONTEXT
```

Эти failure results запрещают forwarding. Их сообщения, exception details и
логи не содержат raw body, payload, content preview или secret-bearing source
values. Expected failure не передаётся обычным необработанным exception.

`UNRESOLVED_CONTEXT` означает, что поддерживаемая schema ссылается на
LLM-visible textual context, которого нет в доступном parser source. Для
OpenAI Responses это включает как минимум `conversation`,
`previous_response_id` и hosted `prompt`. Наличие inline input или prompt
variables не делает такой request inspectable, потому что referenced history
или template остаётся неизвестным.

До появления отдельного resolver такие requests обрабатываются fail-closed.
Parser распознаёт reference field и возвращает `UNRESOLVED_CONTEXT`, но не
выполняет network lookup. Будущий resolver должен получить referenced context,
представить его теми же normalized fragments и только после этого повторно
передать request в обычный parse and policy flow.

Каждый text payload fragment содержит provenance из двух слоёв.

Общая guardrail-facing часть:

- `ordinal`, задающий исходный порядок fragment внутри одного parse result;
- direction `REQUEST` или `RESPONSE`;
- semantic kind, различающий как минимум instruction, message text, label,
  schema text, tool description, tool argument, tool result, output text,
  refusal и reasoning;
- role только тогда, когда она явно задана protocol schema; parser не выводит
  role эвристически.

Protocol-specific locator однозначно указывает исходный item, content block,
JSON field или SSE event. Его структура принадлежит соответствующему adapter
и будущему rewriter. Locator и OpenAI identifiers не передаются detector или
policy engine и не попадают в safe logs/errors.

Detector offsets всегда локальны для decoded text одного fragment. Нельзя
считать их offsets исходного JSON, SSE frame или полного сообщения без
явного обратного mapping соответствующего adapter.

Текущий normalized attributes contract для EPIC-03 содержит ровно одно
body-derived поле: `model`. Это единственное protocol-derived измерение
существующего `PolicyContext` и policy matching contract.

Protocol family, operation, transport и direction остаются metadata parse
result envelope и не добавляются в `PolicyContext`. Произвольная attributes
map запрещена. Новое policy dimension требует явного версионированного
изменения контрактов EPIC-03, EPIC-04 и EPIC-06.

`model` для policy matching извлекается из исходного request. EPIC-03
переносит это значение в соответствующий `RESPONSE` context. Значение модели,
которое upstream может сообщить в response body или event, не переопределяет
`PolicyContext` и не меняет применимые policies в ходе одного exchange.

Streaming parser собирает deltas по логическому content field и создаёт
payload fragment только после protocol-события завершения этого field, block
или item. Network chunk, SSE frame и отдельный delta event не являются
границами payload.

Parser не выдаёт provisional fragments и не запускает detector. Tool argument
JSON и другой chunked text декодируются только после завершения соответствующей
логической единицы. Одинаковая последовательность protocol events должна дать
одинаковые fragments и UTF-8 text независимо от TCP chunking.

Terminal semantics streaming parser:

- обязательное protocol completion event завершает соответствующий parse
  result успешно;
- clean EOF без обязательного terminal event либо с открытым logical field
  возвращает `MALFORMED_MESSAGE`;
- cancellation вызывающей стороны остаётся cancellation, освобождает всё
  request/session-scoped состояние и не преобразуется в parse failure;
- transport error и валидное provider error event передаются вызывающему слою
  как upstream outcome, а не преобразуются в parse failure этого модуля;
- незавершённые buffers не создают fragment; уже завершённые fragments не
  изменяются задним числом.

Выбор canonical value при повторении одного logical field в delta buffer,
field-specific done event и последующих item/response snapshots осознанно
отложен. Возможны как минимум canonical done event с validation snapshots,
canonical terminal snapshot либо adapter-specific правило. До выбора parser
не имеет default mismatch behavior, а SSE adapter issues остаются `Draft`.

SSE response является одной атомарной policy-транзакцией MVP. Parser может
возвращать завершённые fragments внутреннему evaluation flow по мере разбора,
но integration layer не отправляет клиенту upstream status, headers или body
до terminal event и итогового policy decision. Если хотя бы один fragment
даёт `BLOCK`, клиент получает stable safe proxy error без единого upstream SSE
byte. Если все fragments разрешены, integration replay-ит original SSE; при
наличии разрешённых transformations сначала применяется адресный rewrite.

Атомарное удержание source, bounded spill, replay, cleanup и backpressure
принадлежат EPIC-08. Sliding window решает detector payload limit, но само по
себе не разрешает ранний forwarding. Incremental release до итогового decision
не входит в MVP.

Parser отвечает за protocol structure, но не за mapping upstream outcome в
HTTP proxy error. Raw provider error message не попадает в parser logs.

Protocol parsing реализуется независимыми adapters для следующих пар family и
transport:

```text
OpenAI Responses          + JSON
OpenAI Responses          + SSE
OpenAI Chat Completions   + JSON
OpenAI Chat Completions   + SSE
```

Точная operation surface:

- `POST /v1/responses` с обычным JSON response или SSE при streaming;
- `POST /v1/chat/completions` с обычным JSON response или SSE при streaming.

Realtime events, WebSocket/WebRTC transport, SDP, RTP/audio frames, SIP
signaling, Batch JSONL records и Batch Files/job lifecycle не являются input
MVP parser. Их placeholders не создают требований к текущей реализации.

OpenAI API mode передаётся через operation descriptor. Version selection
является частью adapter routing и не требует чтения body:

- OpenAI adapters поддерживают стабильную `/v1` surface и имеют внутреннюю
  версию contract snapshot с датой использованной primary documentation;
- parser не выбирает `latest` schema во время выполнения и не определяет
  version эвристически по body.

Каждая protocol-specific issue фиксирует внутреннюю версию contract snapshot,
дату primary documentation и соответствующие contract tests.

Schema evolution обрабатывается по месту неизвестного элемента:

- неизвестное additional property внутри известного object сохраняется в
  original source и игнорируется normalized view, кроме пока не решённого
  случая unknown keyword внутри model-visible JSON Schema;
- неизвестное значение явно non-content metadata сохраняется без parse
  failure;
- неизвестный discriminator внутри content-bearing union возвращает
  `AMBIGUOUS_CONTENT`;
- неизвестный SSE event type возвращает `UNSUPPORTED_SCHEMA`;
- parser не определяет семантику content type или event эвристически по
  присутствующим fields.

Общими между OpenAI adapters являются только guardrail-facing result,
attributes, fragment, provenance, inspection gap и error contracts. Общий
mega-DTO для разных OpenAI API surfaces запрещён.

Adapter выбирается до чтения body по явному operation descriptor от transport
layer: HTTP method, normalized path, content type и transport kind. Body
sniffing, эвристическое определение schema и fallback от одного adapter к
другому запрещены. Отсутствие точного adapter возвращает
`UNSUPPORTED_SCHEMA`.

Tool arguments и results следуют типу protocol field:

- textual arguments field возвращается одним fragment с полным decoded text;
- обязательный повторный parse JSON внутри textual field не выполняется;
- invalid inner JSON в корректном textual field остаётся inspectable model
  output и не превращает protocol message в `MALFORMED_MESSAGE`;
- structured JSON object или array сохраняется как typed normalized structure;
- каждое string leaf structured value создаёт отдельный text fragment с
  locator до этого leaf;
- object keys, numbers, booleans и `null` не stringified для text detector, но
  сохраняются в structured view для будущих structured policies.

Tool parameter schemas и structured-output schemas обрабатываются отдельным
явным schema walker:

- OpenAI message `name`, tool/function name и другие пользовательские
  model-visible labels возвращаются отдельными `LABEL` fragments;
- JSON Schema property names, `title`, `description`, строковые значения
  `enum`, `const`, `default`, `examples` и пользовательские строковые
  ограничения, включая regex `pattern`, возвращаются отдельными
  `SCHEMA_TEXT` fragments;
- fixed JSON Schema keywords, protocol discriminator values, OpenAI IDs,
  числовые и boolean constraints не превращаются в text fragments;
- порядок fragments следует порядку соответствующих значений в original
  source, а locator указывает точный schema element;
- walker знает только явную карту поддерживаемого schema vocabulary и не
  собирает все JSON strings рекурсивно.

Правило для schema property names является осознанным исключением из правила
runtime structured arguments: object keys в tool arguments не являются text
payload, а пользовательские property names в передаваемой модели schema
являются model-visible payload.

Поведение unknown keyword внутри tool или structured-output JSON Schema
осознанно отложено. Рассматриваются как минимум три варианта: игнорировать его
как additive field, всегда возвращать typed failure либо различать scalar и
potentially textual subtree. Ни один вариант не является default до отдельного
решения. Implementation issue schema walker остаётся `Draft`, пока вариант и
contract tests не утверждены.

Normalized structured view не используется для пересборки forwarded body и не
передаётся EPIC-03 как policy attribute.

Original body и normalized parse result имеют разное ownership:

- transport/integration layer владеет точной исходной последовательностью
  bytes или events и сохраняет её через bounded spool/tee;
- parser читает source в read-only режиме и строит normalized result;
- parse result не содержит копию raw body и не возвращает reconstructed body;
- forwarding без modifications использует только original source;
- будущий rewriter получает original source и protocol locators отдельно и
  изменяет только целевые fields.

Spooling, replay, secure temporary storage, cleanup и forwarding lifecycle не
входят в EPIC-06 и описаны отдельным
[EPIC-08](epic_08_message_spooling_replay.md).

Detector payload limit не является максимальной длиной LLM exchange. Несколько
messages уже представлены несколькими независимыми fragments и не
конкатенируются перед detector execution.

Если один logical text fragment превышает detector limit, система не должна
блокировать сообщение только по этой причине и не должна молча обрезать текст.
Fragment передаётся отдельной protocol-agnostic windowing capability, которая
обеспечит overlap, глобальные offsets и дедупликацию findings. Parser не
выполняет detectors и не агрегирует результаты окон.

Windowing capability описана отдельным
[EPIC-07](epic_07_windowed_payload_processing.md) за границей EPIC-06.
Контракт передачи decoded text и provenance между parser и windowing должен
быть согласован до реализации больших fragments. Bounded raw-body parsing,
spooling исходного request до policy decision и hard resource exhaustion не
считаются решёнными одним sliding window и требуют отдельных контрактов.

## Предварительная карта декомпозиции

```text
EPIC-06 LLM message parsing
├── protocol contract and supported surface
├── OpenAI Responses
│   ├── request and non-streaming response
│   └── SSE response stream
├── OpenAI Chat Completions
│   ├── request and non-streaming response
│   └── SSE response stream
├── lossless and security behavior
└── post-MVP placeholders
    ├── OpenAI Realtime events
    └── OpenAI Batch JSONL input and output
```

Карта является предварительной. Исполняемые issues, кроме контрактной,
создаются после определения точной поверхности каждой protocol family и
единицы payload, чтобы не закреплять неподтверждённую архитектуру.

## Дочерние issues

- [ ] [VIG-06-01: Контракт разбора LLM-сообщений](../issues/epic_06/issue_06_01_protocol_contract.md) - `Draft`

## Контекст

Policy engine получает готовый `PolicyContext` и один текстовый payload, а
detector не знает HTTP и схемы OpenAI API. Между HTTP gateway и policy engine
нужен отдельный протокольный слой, который понимает только явно поддерживаемые
OpenAI formats.

Текущий v0 остаётся прозрачным bypass proxy и не разбирает тела. Подключение
этого слоя к request path относится к более позднему guardrail-enabled
инкременту и не должно неявно менять поведение v0.

## Цель

Определить и реализовать модуль, который для явно поддерживаемого клиентского
запроса или ответа upstream:

- распознаёт протокольную форму сообщения;
- извлекает schema-derived данные, необходимые последующим слоям;
- извлекает логические текстовые payload для проверки политиками;
- создаёт отдельное нормализованное представление для guardrails;
- позволяет сохранить исходное представление без потери неизвестных полей и
  без пересборки разрешённого сообщения из typed DTO.

Набор schema-derived данных, точная структура provenance и SSE contracts для
Responses и Chat Completions пока не выбраны и перечислены как открытые
решения.

## Нормативные ограничения проекта

- Парсер не выбирает политики и не запускает detectors.
- Detector получает только текстовый payload и не получает HTTP request,
  JSON document model или protocol-specific types.
- Policy engine не разбирает HTTP-запросы и ответы.
- Неизвестные поля поддерживаемого сообщения должны сохраняться при lossless
  forwarding.
- Разрешённое без изменения сообщение пересылается в исходном виде, а не
  пересобирается из typed DTO.
- Неоднозначная или неподдерживаемая content-bearing структура не должна
  молча допускаться к upstream или клиенту.
- Тела, content preview, authentication values и полные headers не попадают в
  логи и safe error messages.
- Полная агрегация обычного или streaming body допускается только при явно
  обоснованной необходимости. Для guardrail-enabled SSE такой необходимостью
  является принятая атомарная policy-транзакция; bounded spool, backpressure,
  cancellation и увеличенный time-to-first-byte учитываются EPIC-08.

## Предварительная граница ответственности

Внутри epic:

- protocol-specific parsing клиентского сообщения;
- protocol-specific parsing обычного ответа upstream;
- согласованное поведение JSON и SSE для Responses и Chat Completions;
- извлечение schema-derived policy attributes и текстовых payload;
- явные результаты для malformed, unsupported и uninspectable content;
- сохранение provenance каждого payload до исходной protocol structure;
- safe errors и тесты, не раскрывающие содержимое сообщений.

За пределами epic:

- извлечение URL и identity из HTTP metadata;
- выбор и исполнение политик;
- реализация detector;
- применение `ALLOW`, `BLOCK`, `MASK` или `REMOVE` к HTTP exchange;
- подключение parser к production request path;
- изменение поведения текущего bypass v0;
- получение conversation history, previous response или hosted prompt по
  внешнему identifier;
- SDK middleware поверх уже поддерживаемых wire protocols.

## Связи с соседними epics

- [EPIC-02](epic_02_fast_pii_detector.md) принимает уже извлечённый текстовый
  payload.
- [EPIC-03](epic_03_policy_context_extraction.md) строит `PolicyContext` из
  URL, identity, phase и готовых protocol-derived attributes. Он не извлекает
  данные из body или protocol events повторно.
- [EPIC-04](epic_04_policy_engine.md) получает один `PolicyContext` и один
  payload на вызов и не знает протокол сообщения.
- [EPIC-07](epic_07_windowed_payload_processing.md) обрабатывает большие
  fragments скользящими окнами и возвращает findings в координатах исходного
  fragment.
- [EPIC-08](epic_08_message_spooling_replay.md) владеет original source,
  bounded spool, replay, cleanup и lossless forwarding lifecycle.

## Нормативные protocol sources

- [OpenAI Responses API](https://platform.openai.com/docs/api-reference/responses)
  и [Responses streaming events](https://platform.openai.com/docs/api-reference/responses-streaming/response/refusal/delta).
- [OpenAI Chat Completions API](https://platform.openai.com/docs/api-reference/chat/create).
- [OpenAI API backwards compatibility](https://platform.openai.com/docs/api-reference/backward-compatibility).

Protocol-specific issue обязана зафиксировать дату или версию использованной
схемы. Добавление optional properties и новых event types считается обычной
эволюцией upstream schema и не должно автоматически ломать lossless parser.

## Открытые решения

1. Точная карта request content fields для каждой выбранной OpenAI surface и
   contract snapshot в соответствии с принятой semantic field selection.
2. Точная карта response content fields для каждой выбранной OpenAI surface и
   contract snapshot в соответствии с принятой semantic field selection.
3. Поведение unknown keyword внутри model-visible JSON Schema и stable result
   для выбранного варианта.
4. Точные result boundaries и terminal event names для Responses и Chat
   Completions в non-streaming и SSE режимах, включая canonical source,
   deduplication и mismatch result для повторных final snapshots.
5. Контракт передачи больших fragments в отдельную windowing capability, а
   также bounded parsing state, spooling и hard resource exhaustion.
6. Контракт отдельного rewriter: как detector UTF-8 offsets в decoded fragment
   отображаются обратно в encoded original source при `MASK` или `REMOVE`.

## Предварительные критерии готовности epic

- Поддерживаемая protocol surface перечислена без формулировки
  "OpenAI-compatible вообще".
- Для каждого поддерживаемого request/response shape однозначно определены
  извлекаемые attributes и payload.
- Payload возвращается упорядоченными независимыми фрагментами с provenance;
  detector никогда не получает искусственную конкатенацию разных полей.
- Payload строится по явной semantic field map; служебные metadata не
  превращаются в detector input.
- Schema-recognized non-text content создаёт явный inspection gap и на текущем
  этапе пересылается без изменений.
- Success coverage и expected parse failures возвращаются только через
  принятые explicit statuses; пустые fragments и exceptions не заменяют
  result semantics.
- Provenance содержит общую guardrail-facing semantics и закрытый protocol locator;
  detector offsets относятся только к одному decoded fragment.
- Normalized attributes contract содержит только `model` и не имеет
  произвольной extension map.
- Request model используется для обеих policy phases; reported response model
  не изменяет response context.
- Streaming deltas одного logical field дают один fragment только после
  protocol completion event; transport chunking не влияет на payload.
- Canonical source и mismatch semantics для повторяющих field value SSE
  events остаются open decision без default; SSE adapter issues не Ready.
- Cancellation, incomplete EOF и upstream outcome имеют разные terminal
  semantics; незавершённый buffer никогда не становится fragment.
- Detector limit не приводит к blanket reject большого fragment: применяется
  отдельная protocol-agnostic windowing capability без silent truncation.
- Каждая protocol/transport pair имеет независимый adapter; routing не зависит
  от body content и не использует общий mega-DTO.
- Realtime и Batch явно отмечены post-MVP placeholders и не создают требований
  к текущим adapters или integration layer.
- Additive fields сохраняются lossless; unknown content discriminators и event
  types дают typed failure без body sniffing или schema guessing.
- OpenAI adapters привязаны к `/v1` и внутреннему contract snapshot.
- Schema-recognized reference на внешний textual context, включая Responses
  `conversation`, `previous_response_id` и hosted `prompt`, даёт
  `UNRESOLVED_CONTEXT` и запрещает forwarding до разрешения reference внешним
  resolver. Parser не выполняет network lookup.
- Textual tool arguments остаются одним fragment; structured arguments
  сохраняют types и создают text fragments только для string leaves.
- Message/tool labels и пользовательские строки tool или structured-output
  schemas создают отдельные `LABEL` и `SCHEMA_TEXT` fragments через явный
  schema walker. Schema property names включаются; fixed keywords, IDs и
  numeric/boolean constraints исключаются.
- Unknown schema keyword остаётся явным open decision без default behavior;
  зависящая от него implementation issue не переводится в Ready.
- Original source принадлежит integration spool, parse result не содержит raw
  или reconstructed body, unmodified forwarding использует original source.
- Malformed, unknown-discriminator и ambiguous content имеют явную fail-closed
  семантику и safe stable errors.
- Schema-recognized non-text content следует нормативному временному
  исключению: inspection gap обязателен, forwarding остаётся lossless.
- Plaintext OpenAI reasoning text или summary становится отдельным
  `REASONING` fragment. `encrypted_content` создаёт `OPAQUE_REASONING` gap, не
  декодируется и пересылается без изменений.
- Unknown non-content fields поддерживаемого shape сохраняются byte-for-byte
  при forwarding без модификации.
- Streaming behavior не смешивает payload разных сообщений и корректно
  освобождает state при completion, error и cancellation.
- SSE response атомарен: до terminal event и итогового policy decision клиент
  не получает upstream bytes; любой `BLOCK` даёт только safe proxy error, а
  полный `ALLOW` приводит к lossless replay original SSE.
- Парсер не логирует raw body, payload, content preview или credentials.
- Pure parser tests и обязательные E2E integration tests перечислены в
  дочерних issues после закрытия контракта.
- Для добавленных и изменённых Kotlin declarations написан KDoc.
- `./gradlew build` проходит после реализации всех дочерних issues.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.10  цель и adapter decomposition понятны
  Acceptance:   0.20  schema и SSE canonical source отложены явно
  Boundaries:   0.15  adapters, windowing и spooling отделены явно
  Alternatives: 0.35  schema, SSE, resource и reverse-mapping варианты открыты
  Assumptions:  0.35  field maps и resource contracts требуют дочерних issues
  Aggregate:    0.23  ниже порога, но protocol-specific details ещё открыты
```

Оставить `Draft` до закрытия оставшихся protocol-specific решений.
