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

| Ситуация | HTTP status | JSON body |
|---|---:|---|
| Некорректный configured session ID | `400` | `{"error":"invalid_session_id"}` |
| Неподдерживаемые method, path, content type или schema | `400` | `{"error":"unsupported_schema"}` |
| Malformed supported message | `400` | `{"error":"malformed_message"}` |
| Ambiguous content | `400` | `{"error":"ambiguous_content"}` |
| External или unresolved context | `400` | `{"error":"unresolved_context"}` |
| Duplicate, malformed или invalid JWT identity | `400` | `{"error":"invalid_identity"}` |
| Missing или non-Bearer Authorization | `401` | `{"error":"authentication_required"}` + Bearer challenge |
| Некорректный request source, включая несовпадение `Content-Length` | `400` | `{"error":"invalid_request_source"}` |
| Per-request byte limit | `413` | `{"error":"request_too_large"}` |
| Owner/global retained capacity | `503` | `{"error":"inspection_capacity_exhausted"}` |
| Inspection executor admission failure | `503` | `{"error":"audit_unavailable"}` |
| Непредвиденный inspection failure | `500` | `{"error":"inspection_failed"}` |

Descriptor проверяется до identity и body demand. Некорректный session ID
отклоняется ещё раньше, в tracing decorator. Identity, source, parser, context,
empty policy selection и cancellation до detector execution не публикуют
request audit. Когда после selection действительно начинается detector
execution, gateway best-effort публикует в existing non-blocking JSONL stdout
ровно одну пару `policy.analysis_started` и `policy.analysis_completed`.
Terminal event появляется до разрешённого upstream handoff, но request path не
резервирует audit record, не submit-ит его в WAL и не ожидает write, `force(true)`
или delivery. Logging overload или failure не меняет исходный stable response
и не запрещает upstream.

До VIG-32-02 transitional LocalAuditStore, его readiness integration и
Collector handoff остаются в процессе, но request analysis больше не создаёт в
нём records и не проверяет его capacity перед body demand. Active WAL segment
становится ready по exact byte bound, age bound или graceful close. Collector
читает immutable ready segments через документированный
[file handoff](audit-collector-file-handoff.md) и публикует ack только после
durable retention всего segment во внешнем destination. Valid contiguous ack
разрешает asynchronous reclaim. Outage Collector не меняет request
outcome или upstream handoff, но после исчерпания
`audit-max-retained-bytes` transitional `/readyz` остаётся `503` до valid
ack/reclaim.

Packaged [qualification 2026-08-31](durability-qualification-2026-08-31.md)
подтверждает прежний durable request contract и не описывает
текущую request path после VIG-32-01. Lifecycle shutdown независимо
сохраняет plain `503 draining` для нового traffic.

Policy deadline или typed detector error отражается как outcome `ERROR` со
stable `error.code` и без reaction. Current shadow policy не блокирует request,
поэтому при завершённой orchestration исходный body всё равно отправляется
upstream.
`500 inspection_failed` предназначен для непредвиденного сбоя самой
orchestration или context assembly.

Client cancellation до analysis отменяет ingest/inspection, освобождает
source и не публикует пару. Cancellation после start best-effort
публикует terminal `ERROR` с `error.code=ANALYSIS_CANCELLED`; новый
upstream handoff запрещён. Отменённому соединению delivery HTTP error не
гарантируется.

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
- `GET /readyz` до VIG-32-02 возвращает `200` только при доступном
  transitional audit store; `503` означает draining, exhausted store
  capacity или store health failure, но не блокирует уже admitted request.
- Probes принадлежат gateway и никогда не проксируются upstream.
- Readiness не проверяет доступность upstream.

При SIGTERM readiness сначала переключается на `503`, новые proxy exchanges и
store admissions запрещаются, а active exchanges получают время на drain. После
drain gateway force-ит pending audit records, seal-ит active segment, затем
закрывает inspection, upstream и telemetry resources. Quiet period и force
timeout конфигурируются через `VIGILANT_SHUTDOWN_*`.
