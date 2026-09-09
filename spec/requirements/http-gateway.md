# HTTP gateway

Нормативный owner HTTP descriptor, transport, headers, stable errors,
health/readiness и shutdown для `PROXY-01..03`, `CONC-01`, `CONC-04` и
[PROXY-03](../MVP_NON_FUNCTIONAL_REQUIREMENTS.md#proxy-03-stable-technical-failures).
Protocol field/terminal semantics принадлежат
[Chat Completions](chat-completions-protocol.md), retained JSON/SSE enforcement -
[RESPONSE enforcement](response-enforcement.md). Действующая сборка и
операторские примеры описаны в [runtime](../../docs/runtime-contract.md) и
[deployment](../../docs/deployment.md).

## Descriptor

Gateway допускает guardrail request только `POST /v1/chat/completions` с
`application/json`. Сравнение type/subtype case-insensitive, parameters
игнорируются; routing использует normalized path без query. Другие method,
path, `application/*+json` и неподдерживаемый transport не получают silent
bypass, body sniffing или fallback. Unsupported request descriptor возвращает
`400 {"error":"unsupported_schema"}` до body read, detector и upstream call,
без analysis audit pair.

Request source полностью принимается до parser. Обычный response выбирает
JSON adapter по `application/json`; SSE выбирается по `text/event-stream`.
Absent или exact `Content-Encoding: identity` поддержаны, другое encoding
не декодируется. Unsupported response descriptor/media type/encoding даёт
`502 invalid_upstream_response` из таблицы ниже без upstream disclosure.

## Request parse outcomes

| Typed result | HTTP | Exact JSON body |
|---|---:|---|
| `MALFORMED_MESSAGE` | 400 | `{"error":"malformed_message"}` |
| `UNSUPPORTED_SCHEMA` | 400 | `{"error":"unsupported_schema"}` |
| `AMBIGUOUS_CONTENT` | 400 | `{"error":"ambiguous_content"}` |
| `UNRESOLVED_CONTEXT` | 400 | `{"error":"unresolved_context"}` |

Эти outcomes не имеют `Retry-After`, не запускают detector и не отправляют
ни одного upstream byte. Failure содержит только safe category, без raw body,
source preview, parser exception, locator и partial normalized result.
Structural parser budgets относятся к `UNSUPPORTED_SCHEMA`; request source
exhaustion происходит до parser и не маскируется под parse error.
Per-request limit даёт `413 {"error":"request_too_large"}`, shared capacity
или source failure - request inspection unavailable из inspection matrix.

Recognized non-text/provider-opaque content возвращает explicit
[coverage/gap](chat-completions-protocol.md#coverage-и-safe-failures), а не
parse failure. При отсутствии policy mutation original source пересылается
byte-identical. Inspection gap не превращается в CLEAN: safe aggregate audit
использует `INSPECTION_GAP`, если нет detection. Audit lifecycle, включая
отсутствие pair до detector execution, задаётся
[observability contract](observability.md#analysis-lifecycle-audit).

## Inspection error matrix

Пять закрытых inspection outcomes используют `Content-Type: application/json`
и ровно следующие wire bodies:

| Ситуация | HTTP | `Retry-After` | Exact JSON body |
|---|---:|---|---|
| Request policy BLOCK или structural MASK | 403 | отсутствует | `{"error":{"message":"Request blocked: PII detected.","type":"policy_violation","code":"policy_blocked"}}` |
| Response policy BLOCK | 403 | отсутствует | `{"error":{"message":"Response blocked: PII detected.","type":"policy_violation","code":"policy_blocked"}}` |
| Request inspection/capacity failure, detector/policy failure или timeout | 503 | `1` | `{"error":{"message":"Request inspection unavailable.","type":"server_error","code":"request_inspection_unavailable"}}` |
| Response detector/policy failure или timeout, invalid masking/source-map/rewrite | 503 | `1` | `{"error":{"message":"Response inspection unavailable.","type":"server_error","code":"response_inspection_unavailable"}}` |
| Malformed upstream JSON/SSE, protocol failure, missing/malformed terminal или interrupted response | 502 | отсутствует | `{"error":{"message":"Invalid upstream response.","type":"upstream_error","code":"invalid_upstream_response"}}` |

Body имеет ровно один root field `error` и ровно три string fields внутри:
`message`, `type`, `code`. Optional fields, policy ID/version, payload-derived
values/spans/previews, identity/groups, credentials, raw internal causes,
upstream status/headers/body запрещены. Policy references допустимы только в
safe stdout audit deployment/operations boundary. `Retry-After` - ровно одна
строка `1`, целое число секунд; дата, динамический расчёт и policy override
отсутствуют. В строках 403/502 заголовок отсутствует.

Request 403/503 не начинает upstream handoff; response 403/502/503 заменяет
весь upstream response до первого disclosure и не выпускает partial status,
headers или body. Request technical failure не утверждает detection и имеет
приоритет над policy BLOCK/structural MASK. Текущий единственный безопасный
client-facing reason для policy block - `PII detected.`; direction различает
только message. Новые detector/reason classes не расширяют API без отдельного
согласованного изменения contract.

External identity unavailability имеет отдельный согласованный outcome,
который не переопределяется request inspection error:
[identity failure contract](identity-and-context.md#external-bridge).
Bearer authentication/invalid-identity outcomes также принадлежат
[identity boundary](identity-and-context.md#single-bearer-boundary).

## Low-level transport

Низкоуровневый bypass выполняет один upstream exchange и не агрегирует request
или response body. Request bytes передаются по upstream demand до client
completion; slow upstream ограничивает outstanding request demand. Response
status и первый body byte могут достигнуть client до upstream EOF; slow client
ограничивает upstream response demand. Transport сохраняет byte content и
порядок, но не обещает сохранить физические chunk boundaries.

Guardrail route переиспользует тот же exchange seam. Request enforcement
полностью удерживает request до handoff, а response enforcement удерживает
output transport в отдельном retained source. Эти application boundaries не
изменяют streaming contract самого bypass service.

Client cancellation до завершения streaming exchange отменяет связанный
upstream request через service context. Уже раскрытый client prefix остаётся
тем же ordered upstream prefix и не заменяется local error. Последовательные
cancellations не оставляют application-owned exchanges или connections.

Application владеет одним Armeria `ClientFactory` и upstream connection pool.
Последовательные requests до idle timeout переиспользуют connection; bounded
concurrent traffic не создаёт connection на каждый request; после configured
idle timeout новый request может использовать новую connection без изменения
client outcome. HTTP/2 multiplexing policy, retry и circuit breaker не входят в
этот контракт.

## Routing and headers

Upstream URL задаёт scheme, authority и optional base path. Gateway сохраняет
исходный method, query и route suffix, соединяет suffix с base path, заменяет
authority/`Host` и не передаёт inbound framing как authority source.
`Content-Length` для exact original body сохраняется или вычисляется transport,
для rewritten body устанавливается по exact byte count.

В обоих направлениях удаляются фиксированные hop-by-hop fields:
`Connection`, `Keep-Alive`, `Proxy-Authenticate`, `Proxy-Authorization`, `TE`,
`Trailer`, `Transfer-Encoding`, `Upgrade` и `Proxy-Connection`. Также удаляется
каждое непустое comma-separated field name из всех значений `Connection`, без
учёта регистра. Остальные end-to-end headers сохраняются. Accepted
`Authorization` остаётся end-to-end credential и передаётся upstream без
изменения. Effective trace/session propagation следует
[observability contract](observability.md#tracing-and-propagation).

Корректный upstream HTTP response в bypass mode, включая `4xx` и `5xx`,
сохраняет status, body и разрешённые end-to-end headers. Malformed status line,
headers или framing до начала client response отображаются как stable transport
failure. После начала streaming response заменить уже раскрытый status/body
невозможно, поэтому mid-response failure abort-ит exchange.

## Response outcomes and headers

Для guardrail-enabled ordinary JSON и SSE действуют одни правила:

| Свойство | `ALLOW` | `MASK` | `BLOCK` | Invalid upstream `502` | Inspection `503` |
|---|---|---|---|---|---|
| Status | upstream | upstream | local `403` | local `502` | local `503` |
| Body | original bytes | exact source-patched bytes | local inspection JSON | local inspection JSON | local inspection JSON |
| `Content-Type` | upstream | upstream, включая SSE charset | local JSON | local JSON | local JSON |
| `Content-Length` | filtered upstream representation | exact rewritten length | local length | local length | local length |
| `Content-Encoding` | absent или exact `identity` | absent или exact `identity` | upstream не копируется | upstream не копируется | upstream не копируется |
| Fixed и `Connection`-named hop-by-hop | удалены | удалены | upstream не копируется | upstream не копируется | upstream не копируется |
| `ETag`, `Content-MD5`, `Digest` | сохранены | удалены как stale | upstream не копируется | upstream не копируется | upstream не копируется |
| Остальные end-to-end metadata | сохранены | сохранены | upstream не копируется | upstream не копируется | upstream не копируется |
| Trailers | original | original | upstream не копируется | upstream не копируется | upstream не копируется |
| `Retry-After` | upstream | upstream | отсутствует | отсутствует | local `1` |

Absent или exact case-sensitive `identity` являются единственными
поддерживаемыми content encodings retained path. `gzip` и любое другое значение
не декодируются и дают `502 invalid_upstream_response`. `MASK` удаляет
representation validators, потому что они описывают original body. Полный
source-patching contract принадлежит
[RESPONSE enforcement](response-enforcement.md#source-maps-and-exact-rewrite),
а короткая operator reference -
[response masking headers](../../docs/response-masking-headers.md).

## Upstream failures and timeouts

До начала client response connection refusal, unknown host, cancellation и
другая transport failure дают `502` с exact body
`{"error":"upstream_unavailable"}`. Timeout даёт `504` с exact body
`{"error":"upstream_timeout"}`. Эти low-level outcomes различимы для
telemetry и не содержат exception class/message, stack trace, request body,
query values или credentials. На retained guardrail route такие generated
bodies не являются valid Chat Completions response и поэтому безопасно
заменяются `502 invalid_upstream_response` до disclosure.

Connect, write, response и connection-idle timeouts задаются независимо.
Response timeout действует до первого response object и затем как максимальная
пауза между последовательными objects; каждый полученный object переносит
deadline, поэтому общая длительность активного stream не ограничена. Idle
connection timeout не закрывает exchange in flight. Defaults, validation и env
names принадлежат [configuration reference](../../docs/configuration.md).

## Health, admission and shutdown

- `GET /healthz` локально возвращает `200` с body `ok`, пока server принимает
  connections.
- `GET /readyz` локально возвращает `200` с body `ready` в serving state и
  `503` с body `draining` после начала shutdown.
- Probes никогда не проксируются upstream и readiness не проверяет его
  доступность, logging delivery или telemetry exporter.
- Начало shutdown сначала переводит readiness в `503` и запрещает admission
  новых proxy exchanges и нового response-analysis handoff. Уже admitted
  exchanges получают bounded drain.
- По force timeout остаток active work отменяется. Request/response sources,
  replay publishers, detector tasks и Bridge exchanges освобождают ownership.
- После server drain закрываются inspection executors, External cache/Bridge и
  общий outbound factory; затем flush/close выполняются для traces и metrics.
  Каждый owner пытается закрыть все свои ресурсы, сохраняя первый cleanup
  failure и последующие suppressed.

Quiet period и force timeout configurable; force timeout не меньше quiet
period. Exact defaults и validation принадлежат
[configuration reference](../../docs/configuration.md). Packaging предоставляет
versioned application distribution и stateless non-root OCI image с
`STOPSIGNAL SIGTERM`; обязательные startup settings и read-only policy mount
описаны в [deployment guide](../../docs/deployment.md). Historical smoke run не
является новой qualification после documentation migration.

## Проверка

[Response and gateway checks](../../docs/development.md#response-and-gateway-contract-checks)
проверяют literal status, body shape, message/type/code, наличие и значение
Retry-After, privacy всех пяти строк, bypass transport, lifecycle и JSON/SSE
atomicity. Encoder reuse не доказывает наличие integration path:
upstream handoff/disclosure наблюдаются отдельно через request и JSON/SSE
response E2E. [Coverage](../../docs/requirements-coverage.md#response-and-gateway-evidence)
указывает фактическую границу имеющегося evidence без нового runtime run.
