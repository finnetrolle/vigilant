# Runtime contract

## Поддерживаемая поверхность

Текущий production route принимает только:

- method `POST`;
- path `/v1/chat/completions`;
- media type `application/json`, параметры media type допускаются;
- OpenAI Chat Completions request schema, которую parser может однозначно
  нормализовать для inspection.

Другие OpenAI endpoints не проходят через silent bypass.

Для поддержанного descriptor gateway сначала резервирует bounded audit token,
затем полностью принимает request body в bounded in-memory source. Parser
создаёт отдельное normalized view model-visible content,
а исходные bytes остаются неизменными. После shadow inspection upstream
получает исходные method, path/query, end-to-end headers и byte-identical body.
Hop-by-hop headers, authority, `Host` и `Content-Length` обрабатывает gateway.
Исключение составляют только headers, consumed настроенным identity mode:
configured user/groups headers в `TRUSTED_HEADERS` или `Authorization` после
успешного Basic extraction. Unconfigured identity-like headers остаются
обычными end-to-end headers.

Response body, включая SSE, не агрегируется и остаётся streaming pass-through.
Response inspection пока отсутствует, но request URL, model и normalized
identity сохраняются в request-scoped handoff. Response context использует тот
же snapshot и отличается только phase `RESPONSE`; модель из upstream response
его не переопределяет.

## Identity modes

Startup выбирает ровно один mode:

- `ANONYMOUS` не читает и не удаляет identity-like headers;
- `TRUSTED_HEADERS` читает configured user/groups headers только от immediate
  socket peer внутри configured CIDR. `Forwarded` и `X-Forwarded-For` не
  расширяют boundary;
- `BASIC` потребляет один корректный Basic `Authorization`, использует только
  normalized ASCII username и удаляет header перед upstream forwarding.

Отсутствующее identity value даёт `user=null`, empty groups. Duplicate и
malformed values отклоняются безопасно. Password, raw Authorization и исходные
identity values не попадают в policy context, errors или logs.

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
| Duplicate или malformed identity | `400` | `{"error":"invalid_identity"}` |
| Configured identity header от untrusted peer | `403` | `{"error":"untrusted_identity"}` |
| Некорректный request source, включая несовпадение `Content-Length` | `400` | `{"error":"invalid_request_source"}` |
| Per-request byte limit | `413` | `{"error":"request_too_large"}` |
| Owner/global retained capacity | `503` | `{"error":"inspection_capacity_exhausted"}` |
| Audit admission, size, I/O или lifecycle failure | `503` | `{"error":"audit_unavailable"}` |
| Непредвиденный inspection failure | `500` | `{"error":"inspection_failed"}` |

Descriptor и audit reservation выполняются до identity и body demand.
Некорректный session ID отклоняется ещё раньше, в tracing decorator. Для
поддержанного descriptor создаётся ровно одна immutable audit record, включая
fail-closed identity, source, parser, context и inspection outcomes. Исходный
stable response или первый upstream byte разрешён только после force-backed
durable acknowledgement. `policy.shadow_decision` публикуется после него как
best-effort projection. Для неподдержанного descriptor и invalid session ID
audit reservation и record не создаются.

External Collector не находится в request critical path. Active WAL segment
становится ready по exact byte bound, age bound или graceful close. Collector
читает immutable ready segments через документированный
[file handoff](audit-collector-file-handoff.md) и публикует ack только после
durable retention всего segment во внешнем destination. Valid contiguous ack
разрешает asynchronous reclaim. Outage Collector не меняет уже допустимый
request outcome, пока хватает `audit-max-retained-bytes`; после исчерпания
следующий supported request получает `503 audit_unavailable` без body demand и
upstream. Valid ack освобождает capacity и автоматически восстанавливает
readiness/admission.

Packaged [qualification 2026-08-31](durability-qualification-2026-08-31.md)
подтверждает эту строку контракта: retained-capacity admission получает exact
`503 audit_unavailable`, readiness остаётся `503`, body не demand-ится и
upstream не вызывается. Lifecycle shutdown независимо сохраняет plain
`503 draining` для нового traffic.

Policy deadline или typed detector error отражается как decision `ERROR` с
disposition `ALLOW`: current shadow policy не блокирует request, поэтому при
успешной orchestration исходный body всё равно отправляется upstream.
`500 inspection_failed` предназначен для непредвиденного сбоя самой
orchestration или context assembly.

Client cancellation до decision отменяет ingest/inspection и освобождает source
и audit reservation без record. После decision store ownership и durable append
сохраняются независимо от HTTP cancellation; новый upstream handoff запрещён.
Отменённому соединению delivery HTTP error не гарантируется.

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
- `GET /readyz` возвращает `200` только при доступном audit admission; `503`
  означает draining, exhausted audit capacity или store health failure.
- Probes принадлежат gateway и никогда не проксируются upstream.
- Readiness не проверяет доступность upstream.

При SIGTERM readiness сначала переключается на `503`, новые audit admissions и
proxy traffic запрещаются, а active exchanges получают время на drain. После
drain gateway force-ит pending audit records, seal-ит active segment, затем
закрывает inspection, upstream и telemetry resources. Quiet period и force
timeout конфигурируются через `VIGILANT_SHUTDOWN_*`.
