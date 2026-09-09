# Runtime contract

## Поддерживаемая поверхность

Текущий production route принимает только:

- method `POST`;
- path `/v1/chat/completions`;
- media type `application/json`, параметры media type допускаются;
- OpenAI Chat Completions request schema, которую parser может однозначно
  нормализовать для inspection.

Другие OpenAI endpoints не проходят через silent bypass.

Точная карта полей, обход JSON Schema, распознаваемые непроверяемые части и
ограничения анализатора описаны в
[контракте запросов Chat Completions](openai-chat-completions.md).

Для поддержанного descriptor gateway проверяет identity, затем полностью
принимает request body в bounded in-memory source. Parser
создаёт отдельное normalized view model-visible content,
а original bytes остаются неизменными у единственного source owner. ALLOW и
no-policy передают их byte-identical; MASK передаёт validated non-expanding
free-text patches с сохранением остальных raw bytes. BLOCK/technical refusal
не начинают upstream handoff. Method/path/query и end-to-end headers сохраняются.
Hop-by-hop headers, authority, `Host` и `Content-Length` обрабатывает gateway.
Принятый `Authorization` остаётся обычным end-to-end header и передаётся
upstream с исходным значением без изменений.

Guardrail-enabled response, включая SSE, полностью удерживается в RAM до
protocol completion и final response-policy decision. Клиент до этого не
получает upstream status, headers или body. Ordinary JSON считается
complete по end-of-stream, SSE только после standalone `data: [DONE]`.
Единый parser разбирает каждый transport один раз с immutable source
coordinates, после чего общий workflow выполняет response policy
evaluation. `ALLOW` replay-ит original status, разрешённые headers/trailers и
exact body. `MASK` меняет только selected decoded UTF-8 spans в исходных
JSON string literals; SSE span может пересекать несколько delta events одного
logical field. Остальные bytes сохраняются, а `BLOCK` скрывает весь
upstream response.

Request URL, model и normalized identity сохраняются в request-scoped handoff.
Response context использует тот же snapshot и отличается только phase
`RESPONSE`; модель из upstream response его не переопределяет. Каждое textual
response field оценивается как независимый fragment; findings не пересекают
choice, semantic field, tool call или transcript boundaries.

## Bearer identity

Startup выбирает ровно один общий Bearer extractor. `DUMMY` доступен только в
`development`/`test`, проверяет representation header и возвращает configured
normalized user/groups. `JWT` выполняет полностью локальную проверку RS256 по
immutable pinned public JWK set. `EXTERNAL` передаёт non-empty opaque Bearer
token trusted Bridge service и принимает normalized user/groups. JWT и
`EXTERNAL` доступны во всех environments, включая `production`.

Каждый поддержанный request обязан содержать ровно один `Authorization` с
case-insensitive scheme `Bearer`. Missing или другой scheme получает `401` с
`WWW-Authenticate: Bearer realm="vigilant"`; duplicate или malformed
representation получает safe `400`. В `DUMMY` token может быть пустым и
игнорируется. В JWT compact token обязан иметь `alg=RS256` и точный `kid`,
который выбирает одну configured key. Signature, exact `iss`, containing
`aud`, обязательный неистёкший `exp` и optional `nbf` проверяются до чтения
identity claims. Затем required string `sub` и optional top-level array
`groups` нормализуются по общему identity contract; missing `groups` даёт
empty set, а invalid/duplicate normalized values получают safe `400`.

External после shared Bearer parsing использует process-local Caffeine cache.
Ключом служит полный HMAC-SHA-256 UTF-8 token в lowercase hex, с отдельным
32-byte случайным секретом на запуск. Cache хранит только normalized successful
identity; raw token не удерживается. По умолчанию TTL равен `10m`, maximumSize
равен `10000` completed entries. `expireAfterWrite` начинается при successful
completion, hit не продлевает срок, возраст `>= TTL` даёт miss. Expired или
evicted identity не используется даже при Bridge failure; idle не запускает refresh.
Caffeine maintenance ограничивает число entries, без обещания точного heap budget.

Создатель cold miss выполняет ровно один `POST` на exact
configured path/query с единственными provider headers `Authorization: Bearer
<token>`, `Accept: application/json`, `Content-Length: 0` и без body. Redirect
не follow-ится. Только `200 application/json` с object, required string `user`
и required array-of-strings `groups` успешен; duplicate JSON keys, invalid или
duplicate-after-normalization identity и больше 128 groups отклоняются.
Неизвестные top-level fields игнорируются. Standard Armeria aggregate limit
остаётся 10 MiB, отдельной identity response-size настройки нет.

Один positive `identity-external-timeout`, default `1s`, начинается до client
connection acquisition и охватывает acquisition, connect, request write,
headers и полный response body. Immediate nonfair semaphore использует
effective `inspection-max-concurrent-request-sources`; N+1 не ждёт в очереди и
сразу получает unavailable. Decorator отдельно ограничивает тем же `N` всех
ожидающих callers, включая joins. Miss при исчерпании waiter slots получает
тот же `503 identity_unavailable`; ready hit не занимает slot или Bridge permit.
Concurrent misses одного key делят exchange и исходный deadline. Отмена одного
caller не влияет на остальных, отмена последнего отменяет Bridge exchange.
Failure и cancellation не кешируются, следующий miss запускает новую попытку.
Graceful drain позволяет shared lookup завершиться в исходном deadline; forced
shutdown закрывает cache, затем Bridge и общий outbound factory. Cache close
отменяет callers, удаляет entries/in-flight state и ссылку на hasher, повторный
close безопасен; после close даже прежний hit возвращает cancelled future.
Cache теряется при restart, новый запуск получает новый секрет.

Все extractors запускаются на blocking-safe request executor до body demand;
Dummy/JWT завершают локальный future, а External связывает его с async Armeria
exchange. Каждая async continuation возвращается на тот же inspection executor
и входит в контекст своего request, даже если shared lookup завершён под
контекстом инициатора.
Raw token и decoded claim values не сохраняются и не попадают в audit, logs,
metrics, traces или errors; policy context получает только normalized
user/groups. Принятый Authorization передаётся upstream с исходным значением
без изменений.

## PII analysis outcome

Policy snapshot выбирает request или response policies и запускает `fast-pii`
для каждого независимого text fragment. Terminal `policy.analysis_completed`
публикует outcome:

- `DETECTED` - найден хотя бы один PII finding;
- `CLEAN` - все доступные fragments проверены, findings и gaps нет;
- `INSPECTION_GAP` - известный non-text content передан без изменений;
- `ERROR` - inspection не удалось завершить корректно.

REQUEST допускает detected ALLOW/MASK/BLOCK, clean только ALLOW, error только
BLOCK без transformations. Technical failure даёт 503 выше PII BLOCK; structural
MASK даёт whole-request 403. RESPONSE phase разрешает `ALLOW`, detected `MASK` или `BLOCK`; любой fragment с
`BLOCK` блокирует весь response. `ERROR` не содержит reaction и публикует
stable `error.code`.

Malformed JSON, неизвестный content discriminator и неоднозначная
content-bearing structure обрабатываются fail-closed и не достигают upstream.

## Request-side errors

| Ситуация | HTTP status | `Retry-After` | JSON body |
|---|---:|---:|---|
| Некорректный configured session ID | `400` | нет | `{"error":"invalid_session_id"}` |
| Неподдерживаемые method, path, content type или schema | `400` | нет | `{"error":"unsupported_schema"}` |
| Malformed supported message | `400` | нет | `{"error":"malformed_message"}` |
| Ambiguous content | `400` | нет | `{"error":"ambiguous_content"}` |
| External или unresolved context | `400` | нет | `{"error":"unresolved_context"}` |
| Duplicate, malformed или invalid JWT identity | `400` | нет | `{"error":"invalid_identity"}` |
| Missing или non-Bearer Authorization | `401` | нет | `{"error":"authentication_required"}` + Bearer challenge |
| External provider status/protocol/transport/timeout/overload failure | `503` | `1` | `{"error":{"message":"Identity service unavailable.","type":"server_error","code":"identity_unavailable"}}` |
| Некорректный request source, включая несовпадение `Content-Length` | `400` | нет | `{"error":"invalid_request_source"}` |
| Per-request byte limit | `413` | нет | `{"error":"request_too_large"}` |
| Owner/global retained capacity | `503` | `1` | `{"error":{"message":"Request inspection unavailable.","type":"server_error","code":"request_inspection_unavailable"}}` |
| Inspection executor admission failure | `503` | `1` | `{"error":{"message":"Request inspection unavailable.","type":"server_error","code":"request_inspection_unavailable"}}` |
| Detector error/deadline, invalid rewrite, request source или orchestration failure | `503` | `1` | `{"error":{"message":"Request inspection unavailable.","type":"server_error","code":"request_inspection_unavailable"}}` |

Descriptor проверяется до identity и body demand. Некорректный session ID
отклоняется ещё раньше, в tracing decorator. Identity, source, parser, context,
empty policy selection и cancellation до detector execution не публикуют
request audit. Когда после selection действительно начинается detector
execution, gateway best-effort публикует в existing non-blocking JSONL stdout
ровно одну пару `policy.analysis_started` и `policy.analysis_completed`.
Terminal event появляется до разрешённого upstream handoff, но request path не
ждёт queue delivery, stdout write или external delivery. Logging overload или
failure не меняет исходный stable response и не запрещает upstream.

Application-owned audit persistence и delivery отсутствуют. Lifecycle shutdown
независимо сохраняет plain `503 draining` для нового traffic.

Policy deadline или typed detector error отражается как outcome `ERROR` со
stable `error.code` и без reaction, возвращает `503` с `Retry-After: 1` до
upstream и имеет приоритет над PII BLOCK независимо от порядка fragments/policies.
Непредвиденный сбой request source, orchestration или context assembly
возвращает закрытый VIG-29 `503 request_inspection_unavailable` до upstream
handoff и не раскрывает внутреннюю причину.

Client cancellation до analysis отменяет ingest/inspection, освобождает
source и не публикует пару. Cancellation во время analysis best-effort
публикует terminal `ERROR` с `error.code=ANALYSIS_CANCELLED`; новый
upstream handoff запрещён. Отменённому соединению delivery HTTP error не
гарантируется. После validated ready completion отмена не создаёт второй
audit event. Original reservations освобождаются после terminal replay callback,
а не после последнего прочитанного input; partial upload может уже раскрыть
upstream отправленный prefix и никогда не повторяется как unmasked fallback.

## Response enforcement

Ordinary JSON parser извлекает independent `content`, `refusal`, modern/deprecated
function arguments и audio transcript fragments. SSE parser собирает
independent `delta.content`, `delta.refusal`, modern tool arguments и
deprecated function arguments по choice, semantic field и tool-call index.
Recognized audio data остаётся inspection gap; `null` и empty SSE buffer не
создают fragment или gap. Audit outcome использует precedence `DETECTED` >
`INSPECTION_GAP` > `CLEAN`.

`MASK` проверяет все locators и decoded UTF-8 boundaries до первой записи,
применяет patches в descending raw-offset order и не пересериализует JSON
объекты. Для SSE cross-event span marker появляется ровно в первом
затронутом value, а covered text удаляется из последующих values.
`Content-Length` пересчитывается; hop-by-hop headers, `ETag`, `Content-MD5` и
`Digest` удаляются, остальные end-to-end metadata сохраняются.
Absent или exact `Content-Encoding: identity` поддерживаются; gzip и любое
другое encoding дают safe `502` без декодирования.
Полная таблица status и headers для `ALLOW`, `MASK`, `BLOCK`, `502` и
`503` приведена в [response masking reference](response-masking-headers.md).

Missing/non-array `choices`, malformed/ambiguous content и unsupported content type дают
exact `502 invalid_upstream_response`. Detector/policy deadline и failure, invalid masking
instruction или source-map/rewrite failure дают exact `503
response_inspection_unavailable` с `Retry-After: 1`. Ни один из этих paths не
раскрывает upstream status, headers или body.

## Закрытая матрица VIG-29

Production encoder фиксирует пять исчерпывающих OpenAI-compatible errors из
[VIG-29](../spec/issues/issue_29_openai_error_contract.md):

| Outcome | HTTP status | `Retry-After` | Exact JSON body |
|---|---:|---:|---|
| Request `BLOCK` | `403` | нет | `{"error":{"message":"Request blocked: PII detected.","type":"policy_violation","code":"policy_blocked"}}` |
| Response `BLOCK` | `403` | нет | `{"error":{"message":"Response blocked: PII detected.","type":"policy_violation","code":"policy_blocked"}}` |
| Request inspection unavailable | `503` | `1` | `{"error":{"message":"Request inspection unavailable.","type":"server_error","code":"request_inspection_unavailable"}}` |
| Response inspection unavailable | `503` | `1` | `{"error":{"message":"Response inspection unavailable.","type":"server_error","code":"response_inspection_unavailable"}}` |
| Invalid upstream response | `502` | нет | `{"error":{"message":"Invalid upstream response.","type":"upstream_error","code":"invalid_upstream_response"}}` |

Encoder принимает только закрытый outcome и не принимает body, headers,
credentials, identity, policy references или внутренние причины. Поэтому JSON
имеет ровно поле `error`, а оно ровно три string fields: `message`, `type`,
`code`.

Все пять outcomes подключены к runtime. Request BLOCK выбирается для policy
BLOCK и structural MASK, technical refusal никогда не утверждает обнаружение PII.

## Upstream errors

Корректные ordinary Chat Completions responses, включая `4xx` и `5xx`,
проходят retention, protocol validation и response policy decision. `ALLOW` сохраняет
их status/body, а `MASK`/`BLOCK` применяются так же, как для `200`. Malformed
JSON/SSE, missing или malformed standalone `[DONE]`, upstream body interruption
и transport-generated non-protocol body дают exact VIG-29 `502
invalid_upstream_response` без upstream disclosure.

| Ситуация | HTTP status | JSON body |
|---|---:|---|
| Invalid, incomplete или interrupted Chat Completions response | `502` | `{"error":{"message":"Invalid upstream response.","type":"upstream_error","code":"invalid_upstream_response"}}` |

Низкоуровневый `BypassProxyService` по-прежнему кодирует connection failure как
`502 upstream_unavailable` и timeout как `504 upstream_timeout`. На
guardrail-enabled route эти transport-generated bodies также проходят response
protocol gate и не раскрываются как valid Chat Completions response.

Stable proxy errors не содержат Armeria exception messages, stack traces,
request bodies, query string или credentials.

## Upstream timeout model

`upstream-response-timeout` ограничивает:

- время до первого полученного response object;
- паузу между двумя последовательными response objects.

Общая длительность активного stream не ограничена: каждый полученный object
переносит deadline. `upstream-connection-idle-timeout` относится только к idle
connection в pool и не обрывает response in flight.

Connect, write, response и idle timeouts задаются независимо. Defaults и
environment variables перечислены в
[configuration reference](configuration.md).

## Health и shutdown

- `GET /healthz` возвращает `200`, пока server принимает соединения.
- `GET /readyz` возвращает `200` в serving lifecycle state; `503` означает
  graceful shutdown/draining и не блокирует уже admitted request.
- Probes принадлежат gateway и никогда не проксируются upstream.
- Readiness не проверяет доступность upstream.

При SIGTERM readiness сначала переключается на `503`, новые proxy exchanges
запрещаются, и process lifecycle gate не разрешает уже проверенному request
начать новый upstream/response-analysis handoff. Active exchanges получают
время на drain; forced shutdown отменяет retained ingest/replay через request
lifecycle и очищает buffers. После drain gateway закрывает inspection,
upstream и telemetry resources. Quiet period и force timeout конфигурируются
через `VIGILANT_SHUTDOWN_*`.
