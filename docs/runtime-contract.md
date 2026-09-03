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
а исходные bytes остаются неизменными. После shadow inspection upstream
получает исходные method, path/query, end-to-end headers и byte-identical body.
Hop-by-hop headers, authority, `Host` и `Content-Length` обрабатывает gateway.
Принятый `Authorization` остаётся обычным end-to-end header и передаётся
upstream с исходным значением без изменений.

Response body, включая SSE, не агрегируется и остаётся streaming pass-through.
Response inspection пока отсутствует, но request URL, model и normalized
identity сохраняются в request-scoped handoff. Response context использует тот
же snapshot и отличается только phase `RESPONSE`; модель из upstream response
его не переопределяет.

## Bearer identity

Startup выбирает ровно один общий Bearer extractor. `DUMMY` доступен только в
`development`/`test`, проверяет representation header и возвращает configured
normalized user/groups. `JWT` доступен в том числе в `production` и выполняет
полностью локальную проверку RS256 по immutable pinned public JWK set. Сетевые
discovery, JWKS fetch, refresh, introspection, UserInfo и identity lookup
отсутствуют.

Каждый поддержанный request обязан содержать ровно один `Authorization` с
case-insensitive scheme `Bearer`. Missing или другой scheme получает `401` с
`WWW-Authenticate: Bearer realm="vigilant"`; duplicate или malformed
representation получает safe `400`. В `DUMMY` token может быть пустым и
игнорируется. В `JWT` compact token обязан иметь `alg=RS256` и точный `kid`,
который выбирает одну configured key. Signature, exact `iss`, containing
`aud`, обязательный неистёкший `exp` и optional `nbf` проверяются до чтения
identity claims. Затем required string `sub` и optional top-level array
`groups` нормализуются по общему identity contract; missing `groups` даёт
empty set, а invalid/duplicate normalized values получают safe `400`.

JWT validation выполняется на blocking-safe request executor до body demand.
Raw token и decoded claim values не сохраняются и не попадают в audit, logs,
metrics, traces или errors; policy context получает только normalized
user/groups. Принятый Authorization передаётся upstream с исходным значением
без изменений.

## Shadow PII analysis outcome

Policy snapshot выбирает global request policy и запускает `fast-pii` для
каждого независимого text fragment. Terminal `policy.analysis_completed`
публикует outcome:

- `DETECTED` - найден хотя бы один PII finding;
- `CLEAN` - все доступные fragments проверены, findings и gaps нет;
- `INSPECTION_GAP` - известный non-text content передан без изменений;
- `ERROR` - inspection не удалось завершить корректно.

Для успешных `CLEAN`, `DETECTED` и `INSPECTION_GAP` текущая reaction всегда
`ALLOW`: findings не блокируют и не изменяют request. `ERROR` не содержит
reaction и публикует stable `error.code`.

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
| Некорректный request source, включая несовпадение `Content-Length` | `400` | нет | `{"error":"invalid_request_source"}` |
| Per-request byte limit | `413` | нет | `{"error":"request_too_large"}` |
| Owner/global retained capacity | `503` | `1` | `{"error":{"message":"Request inspection unavailable.","type":"server_error","code":"request_inspection_unavailable"}}` |
| Inspection executor admission failure | `503` | `1` | `{"error":{"message":"Request inspection unavailable.","type":"server_error","code":"request_inspection_unavailable"}}` |
| Request source или orchestration failure | `503` | `1` | `{"error":{"message":"Request inspection unavailable.","type":"server_error","code":"request_inspection_unavailable"}}` |

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
stable `error.code` и без reaction. Current shadow policy не блокирует request,
поэтому при завершённой orchestration исходный body всё равно отправляется
upstream.
Непредвиденный сбой request source, orchestration или context assembly
возвращает закрытый VIG-29 `503 request_inspection_unavailable` до upstream
handoff и не раскрывает внутреннюю причину.

Client cancellation до analysis отменяет ingest/inspection, освобождает
source и не публикует пару. Cancellation после start best-effort
публикует terminal `ERROR` с `error.code=ANALYSIS_CANCELLED`; новый
upstream handoff запрещён. Отменённому соединению delivery HTTP error не
гарантируется.

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

В текущем shadow runtime подключён только request technical outcome для
capacity, executor, source и orchestration failures. `BLOCK`, response
inspection failure и invalid inspected upstream response не являются
доступными runtime outcomes до реализации VIG-34 и owning leaves EPIC-20.

## Upstream errors

Корректные upstream HTTP responses, включая `4xx` и `5xx`, передаются без
изменений.

| Ситуация | HTTP status | JSON body |
|---|---:|---|
| Connection failure, unknown host или malformed upstream HTTP | `502` | `{"error":"upstream_unavailable"}` |
| Upstream response timeout | `504` | `{"error":"upstream_timeout"}` |

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
запрещаются, а active exchanges получают время на drain. После drain gateway
закрывает inspection, upstream и telemetry resources. Quiet period и force
timeout конфигурируются через `VIGILANT_SHUTDOWN_*`.
