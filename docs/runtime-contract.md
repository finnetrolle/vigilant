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

Нормативные границы этого пути: [request source](../spec/requirements/request-source.md)
для quota/leases/replay и
[REQUEST enforcement](../spec/requirements/request-enforcement.md) для
selection/reactions/rewrite/handoff.

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

Нормативные atomic boundary, fragment/reaction matrix, source maps и ownership
принадлежат [RESPONSE enforcement](../spec/requirements/response-enforcement.md).
Transport, headers, timeouts, probes и shutdown принадлежат
[HTTP gateway](../spec/requirements/http-gateway.md).

Request URL, model и normalized identity сохраняются в request-scoped handoff.
Response context использует тот же snapshot и отличается только phase
`RESPONSE`; модель из upstream response его не переопределяет. Каждое textual
response field оценивается как независимый fragment; findings не пересекают
choice, semantic field, tool call или transcript boundaries.

## Bearer identity

Нормативные [mode/header/JWT/Bridge/cache matrices](../spec/requirements/identity-and-context.md)
и [identity telemetry](../spec/requirements/observability.md#identity) доступны
без completed tasks. Operator examples и env mapping находятся в
[configuration](configuration.md).

`AppComponent` выбирает Dummy, offline JWT или External. Shared
`BearerHeaderParser` находится в `DummyIdentityExtractor.kt`; successful
extractor передаёт только `NormalizedIdentity`. JWT проверяет pinned RS256
trust локально. В EXTERNAL `CachingExternalIdentityLookup` стоит между
extractor и `BridgeIdentityClient`: completed hit обходит Bridge, cold miss
проходит его exact one-attempt HTTP boundary. Caffeine хранит successful
identity с write TTL/maximumSize и full HMAC keys отдельного hasher.

Decorator владеет caller futures и generations; Bridge - exchange, deadline,
permit и CLIENT span. Cancellation одного caller сохраняет shared lookup для
остальных, последнего - отменяет exchange. `OutboundClientResources` закрывает
cache, Bridge и sole factory через общий cleanup helper. Defaults и exact
terminal paths определены [identity owner](../spec/requirements/identity-and-context.md#lifecycle).

`PiiShadowProxyService` инициирует extraction до body demand на blocking-safe
request executor. Continuation каждого caller возвращается на него под своим
Armeria request context, даже при shared completion в контексте инициатора.
Original accepted Authorization остаётся transport-owned и достигает upstream
без изменений. `PolicyContextHandoff` сохраняет request snapshot и меняет
только phase для response; body-derived model приходит из protocol parser.
[Coverage](requirements-coverage.md#identity-evidence) отдельно фиксирует
известную границу strict config validation и применимость existing tests.

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

Полная schema, matching, overrides, deadlines и domain decision принадлежат
[policy engine](../spec/requirements/policy-engine.md).

Malformed JSON, неизвестный content discriminator и неоднозначная
content-bearing structure обрабатываются fail-closed и не достигают upstream.

## Request-side errors

Нормативные [descriptor и parse outcomes](../spec/requirements/http-gateway.md#request-parse-outcomes)
и [inspection errors](../spec/requirements/http-gateway.md#inspection-error-matrix)
имеют одного owner. Остальные runtime outcomes приведены ниже.

| Ситуация | HTTP status | `Retry-After` | JSON body |
|---|---:|---:|---|
| Некорректный configured session ID | `400` | нет | `{"error":"invalid_session_id"}` |
| Duplicate, malformed или invalid JWT identity | `400` | нет | `{"error":"invalid_identity"}` |
| Missing или non-Bearer Authorization | `401` | нет | `{"error":"authentication_required"}` + Bearer challenge |
| External provider status/protocol/transport/timeout/overload failure | `503` | `1` | `{"error":{"message":"Identity service unavailable.","type":"server_error","code":"identity_unavailable"}}` |
| Некорректный request source, включая несовпадение `Content-Length` | `400` | нет | `{"error":"invalid_request_source"}` |
| Per-request byte limit | `413` | нет | `{"error":"request_too_large"}` |

Descriptor проверяется до identity и body demand. Некорректный session ID
отклоняется ещё раньше, в tracing decorator. Identity, source, parser, context,
empty policy selection и cancellation до detector execution не публикуют
request audit. Полные trigger/absence/schema/privacy rules принадлежат
[observability contract](../spec/requirements/observability.md#analysis-lifecycle-audit).
Когда после selection действительно начинается detector
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
возвращает закрытый inspection outcome `503 request_inspection_unavailable` до upstream
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

Полный нормативный контракт, включая все fragments, gap/reaction cases,
cross-event mapping и terminal paths, находится у
[RESPONSE enforcement](../spec/requirements/response-enforcement.md). Ниже
описано текущее runtime wiring.

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

## Inspection errors

`OpenAiErrorResponses` кодирует [пять inspection outcomes](../spec/requirements/http-gateway.md#inspection-error-matrix)
через один закрытый encoder. Он принимает только outcome и не принимает body,
headers, credentials, identity, policy references или внутренние причины.
Request BLOCK выбирается для policy BLOCK и structural MASK; technical refusal
никогда не утверждает обнаружение PII. External identity unavailable -
[отдельный identity contract](../spec/requirements/identity-and-context.md#external-bridge).

## Upstream errors

Корректные ordinary Chat Completions responses, включая `4xx` и `5xx`,
проходят retention, protocol validation и response policy decision. `ALLOW` сохраняет
их status/body, а `MASK`/`BLOCK` применяются так же, как для `200`. Malformed
JSON/SSE, missing или malformed standalone `[DONE]`, upstream body interruption
и transport-generated non-protocol body дают exact `502
invalid_upstream_response` без upstream disclosure.

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
