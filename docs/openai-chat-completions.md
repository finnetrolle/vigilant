# Реализация протокола OpenAI Chat Completions

Нормативные [descriptor, полная field map, schema vocabulary и JSON/SSE terminal rules](../spec/requirements/chat-completions-protocol.md)
живут у protocol owner. Точные [HTTP outcomes](../spec/requirements/http-gateway.md)
принадлежат gateway owner; [coverage](requirements-coverage.md#protocol-evidence)
сохраняет target/runtime/evidence gaps.

## Runtime parsing boundary

`PiiShadowProtocol` проверяет request descriptor до body access.
`ChatCompletionsRequestParser` читает единственную sequential source view и
возвращает model, ordered fragments, explicit gaps и source metadata.
`RetainedResponseHandler` выбирает JSON/SSE descriptor по upstream Content-Type;
`ChatCompletionsResponseParser` создаёт terminal normalized response и immutable
source maps в одном parse pass. Он не начинает policy enforcement.

Request source defaults: 8 MiB на request, 64 MiB retained bytes на process,
128 owners и 128 segments/request. Они настраиваются через
[configuration](configuration.md). Parser bounds 128 nesting levels и 16 384
fragments закреплены в code и не являются startup settings; превышение даёт
UNSUPPORTED_SCHEMA. Это не подтверждение более высоких product source targets.

Полная последовательность показана в UML 2.0
[request-inspection-sequence.puml](diagrams/request-inspection-sequence.puml).

## Field classification и REQUEST enforcement

Нормативная classification, reaction priority, marker и rewrite semantics
принадлежат [REQUEST enforcement](../spec/requirements/request-enforcement.md).

Parser фиксирует назначение каждого fragment независимо от его semantic kind
и написания JSON Pointer. MASK в structural field блокирует весь request:
имена участников всех шести roles; modern/custom/deprecated tool definitions,
calls, choices и allowed-tools names; response schema name; schema member
names и string enum/const/default/pattern; function arguments/custom input;
custom lark/regex grammar и approximate location fields. Arguments/input
остаются полной opaque string, включая пустую строку и malformed inner JSON;
вложенный язык не разбирается ради rewrite.

Free text допускает exact-span MASK: scalar/part message content всех roles,
refusal, modern/custom/deprecated descriptions, schema title/description/string
examples, filename, open reasoning text/summary и prediction content.
Schema rules одинаковы для modern/legacy function parameters и response schema.
Property с именем `description` или `examples` остаётся structural key, а
дочерняя annotation является free text. Empty/number/boolean/null/object/array
values не расширяют закреплённый parser vocabulary.

Technical error/deadline имеет приоритет `503`, затем whole-request `403` при
policy BLOCK/structural MASK, затем текстовые masks или original ALLOW. Marker
сокращается до decoded UTF-8 budget selected span без сохранения части PII:
`1.1.1.1` -> `[IP_MA]`, budgets 1/2 -> `*`/`**`. Full RESPONSE markers сохраняются.
Все untouched raw bytes, gaps, Unicode, escapes, unknown fields и formatting
остаются исходными; masked body не превышает original ingress limit.

## Coverage и rejection

Parser вычисляет [coverage и gaps](../spec/requirements/chat-completions-protocol.md#coverage-и-safe-failures)
явно. Policy BLOCK, structural MASK и technical failure запрещают request;
recognized media/opaque gap сам по себе не блокирует exact replay.
Audit outcome использует DETECTED > INSPECTION_GAP > CLEAN. До фактического
detector execution typed parse rejection не создаёт analysis pair;
ошибка не раскрывает source/preview/locator или parser exception.
HTTP status/body принадлежат [error matrix](../spec/requirements/http-gateway.md).

## Передача без потерь

Анализатор читает одно представление только для чтения и строит
нормализованные атрибуты и фрагменты. Результат разбора не содержит заново
собранного тела. ALLOW и no-policy передают источник транспорту одноразово и воспроизводят
исходные bytes. MASK использует immutable parser-owned class/raw-token metadata
и один последовательный проход исходных string literals для validated patches.
Structural parse и detector не повторяются; whole-body copy и DTO serialization
отсутствуют. Original quota остаётся занятой до terminal output callback, включая
последний pending output, cancellation, peer close и shutdown. Неизвестные поля вне содержимого, пробельные символы и порядок
полей сохраняются.

Шлюз изменяет только транспортную границу:

- схему, сетевое имя, порт, базовый путь вышестоящего сервера и `Host`;
- заголовки одного соединения (hop-by-hop) и имена из `Connection`;
- `Content-Length` равен validated patched byte length для MASK;
- MASK удаляет Content-MD5, Digest, Content-Digest и Repr-Digest, сохраняя
  Want-Content-Digest, Want-Repr-Digest и остальные end-to-end preferences;
- набор заголовков, использованных для идентификации;
- итоговые заголовки трассировки и сеанса.

## Не поддерживается

- API OpenAI Responses, Realtime и Batch;
- внешний механизм разрешения разговора или системной инструкции;
- `REMOVE`, compressed request bodies и request trailer forwarding;
- произвольные OpenAI-совместимые конечные точки и резервное распознавание по
  телу запроса.

## Response parsing и enforcement

`ChatCompletionsResponseParser` поддерживает ordinary JSON и SSE response
через единый public typed result и один parse pass. Runtime полностью
удерживает ordinary/SSE response до EOF или standalone `data: [DONE]`,
проверяет protocol и применяет один response policy workflow. Ordinary JSON
извлекает каждый string `choices[].message.content`, `refusal`, modern/deprecated
function arguments и `audio.transcript`. SSE собирает independent
`delta.content`, `delta.refusal`, modern tool arguments и deprecated function
arguments, не смешивая choices, semantic fields и tool calls.

Response policy выбирает exact byte-for-byte `ALLOW`, source-patched `MASK` или
whole-response `BLOCK` до первого client byte. SSE `MASK` может
патчить exact span через несколько delta events, сохраняя остальные
event bytes. Audio data остаётся gap и при `MASK` сохраняется byte-for-byte.
Missing/malformed terminal, malformed protocol и upstream interruption дают safe
`502 invalid_upstream_response` без partial disclosure. Detector/rewrite failure или
timeout дают safe `503 response_inspection_unavailable`. Каждый реально
проанализированный ordinary/SSE response публикует safe RESPONSE audit pair.
