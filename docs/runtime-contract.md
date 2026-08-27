# Runtime contract

## Поддерживаемая поверхность

Текущий production route принимает только:

- method `POST`;
- path `/v1/chat/completions`;
- media type `application/json`, параметры media type допускаются;
- OpenAI Chat Completions request schema, которую parser может однозначно
  нормализовать для inspection.

Другие OpenAI endpoints не проходят через silent bypass.

Gateway полностью принимает request body в bounded in-memory source до первого
upstream byte. Parser создаёт отдельное normalized view model-visible content,
а исходные bytes остаются неизменными. После shadow inspection upstream
получает исходные method, path/query, end-to-end headers и byte-identical body.
Hop-by-hop headers, authority, `Host` и `Content-Length` обрабатывает gateway.

Response body, включая SSE, не агрегируется и остаётся streaming pass-through.
Response inspection пока отсутствует.

## Shadow PII decision

Policy snapshot выбирает global request policy и запускает `fast-pii` для
каждого независимого text fragment. Результат audit:

- `DETECTED` - найден хотя бы один PII finding;
- `CLEAN` - все доступные fragments проверены, findings и gaps нет;
- `INSPECTION_GAP` - известный non-text content передан без изменений;
- `ERROR` - inspection не удалось завершить корректно.

Текущая disposition всегда `ALLOW`. Findings не блокируют и не изменяют
request.

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
| Некорректный request source, включая несовпадение `Content-Length` | `400` | `{"error":"invalid_request_source"}` |
| Per-request byte limit | `413` | `{"error":"request_too_large"}` |
| Owner/global retained capacity | `503` | `{"error":"inspection_capacity_exhausted"}` |
| Непредвиденный inspection failure | `500` | `{"error":"inspection_failed"}` |

Descriptor validation выполняется до body demand. Некорректный session ID
отклоняется ещё раньше, в tracing decorator. Для поддержанного descriptor
создаётся ровно один `policy.shadow_decision`, включая fail-closed parser,
source и inspection outcomes. Для неподдержанного descriptor и invalid session
ID shadow audit не создаётся.

Policy deadline или typed detector error отражается как decision `ERROR` с
disposition `ALLOW`: current shadow policy не блокирует request, поэтому при
успешной orchestration исходный body всё равно отправляется upstream.
`500 inspection_failed` предназначен для непредвиденного сбоя самой
orchestration или context assembly.

Client cancellation отменяет ingest, inspection task и replay, освобождая
retained capacity. Отменённому соединению delivery HTTP error не
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
- `GET /readyz` возвращает `200` в рабочем состоянии и `503` после начала
  graceful shutdown.
- Probes принадлежат gateway и никогда не проксируются upstream.
- Readiness не проверяет доступность upstream.

При SIGTERM readiness сначала переключается на `503`, новый proxy traffic
отклоняется, а active exchanges получают время на drain. После drain gateway
закрывает upstream client resources, затем flush/close providers traces и
metrics. Quiet period и force timeout конфигурируются через
`VIGILANT_SHUTDOWN_*`.
