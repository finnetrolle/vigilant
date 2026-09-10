# Observability

Нормативный owner stdout logging, REQUEST/RESPONSE audit lifecycle, tracing,
metrics, privacy и delivery boundary для `MVP-06`, `OBS-01`, `OBS-02` и
`OUT-06`. HTTP outcomes принадлежат [HTTP gateway](http-gateway.md),
identity lookup/cache behavior принадлежит
[identity/context](identity-and-context.md), а его telemetry сохраняется в
named section [Identity](#identity) этого документа. Фактическая композиция и
операторские примеры находятся в
[runtime observability](../../docs/observability.md).

## Stdout topology and ownership

У приложения один физический sink: process stdout. В нём находятся два
логических JSONL-потока:

1. Application records кодируются Logback `JsonEncoder` и проходят через
   единственный root appender `ASYNC_STDOUT` к console appender `STDOUT`.
2. При включённом OTLP output завершённые traces и collected metrics выходят
   по одной OTLP/JSON ExportRequest на строку.

Application queue имеет ровно `queueSize=8192`,
`discardingThreshold=2048`, `neverBlock=true`, `includeCallerData=false` и
`maxFlushTime=2000` milliseconds. При достижении threshold первыми могут быть
отброшены `TRACE`, `DEBUG` и `INFO`; при заполненной queue может быть потерян
event любого уровня. Producer не ждёт sink или свободное место. Shutdown
пытается boundedly сбросить queue, но оставшиеся events могут быть потеряны.
Runtime log level задаёт `VIGILANT_LOG_LEVEL`, default `INFO`; допустимы
`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `OFF`.

Application record является одним JSON object и заканчивается `\n`. Encoder
публикует `timestamp`, `level`, `threadName`, `loggerName`,
`formattedMessage`, optional `kvpList`, `mdc` и `throwable`; порядок JSON
fields не является contract. OTLP writer удерживает неполный document до
newline и публикует complete line под общим stdout monitor, поэтому bytes
параллельных application/trace/metric records не перемешиваются.

Приложение не создаёт file sink, WAL, manifest, acknowledgement, audit
reservation, отдельную audit queue/worker, direct network exporter, retry,
Collector handoff или audit-driven readiness/admission. Queue acceptance,
stdout write и external delivery не являются durable acknowledgement. Ошибка
или overload logging не меняет startup, readiness, HTTP outcome, policy
decision, upstream handoff или response disclosure.

Retention, rotation, bounded local buffering, захват stdout, external delivery
и credentials принадлежат container runtime/deployment. Потеря event при
queue, process или delivery failure допустима; zero-loss guarantee отсутствует.
Vigilant не является observability storage или SIEM.

Целевая deployment chain первого Docker deployment: Docker stdout -> Fluentd
-> OpenTelemetry Collector -> OpenObserve. Администратор настраивает и проверяет
её на стендах, сохраняя application logs, traces и metrics как соответствующие
signal types. Vigilant предоставляет описание JSONL envelopes, metric
names/units/attributes и correlation в [operator reference](../../docs/observability.md).
Конфигурации этой chain, endpoints и secrets не входят в поставку приложения.
Наличие stdout records не доказывает external delivery; bounded non-blocking
и best-effort semantics выше сохраняются.

## Analysis lifecycle audit

Для каждого реально начатого направления анализа публикуется best-effort одна
пара `policy.analysis_started` и `policy.analysis_completed`.

| Phase | Условие `started` | Условие terminal `completed` |
|---|---|---|
| `REQUEST` | Complete request source успешно parsed, identity/context собран, non-empty policy selection готов; event вызывается непосредственно перед первым detector execution | Все реально начатые fragment evaluations дали terminal result либо начавшийся analysis завершился error/cancellation; event публикуется до разрешённого upstream handoff или local outcome |
| `RESPONSE` ordinary JSON | Complete retained response protocol-valid, request-derived context и non-empty selection готовы; event вызывается непосредственно перед первым detector execution | Все fragment evaluations и final reaction/rewrite preparation завершены либо начавшийся analysis дал error/cancellation; event публикуется до первого client disclosure |
| `RESPONSE` SSE | Complete retained SSE имеет standalone `[DONE]`, request-derived context и non-empty selection готовы; event вызывается непосредственно перед первым detector execution | Та же terminal boundary, включая cross-event evaluation/rewrite, до первого client disclosure |

Unsupported или malformed descriptor/source, identity/context/source failure,
empty selection и cancellation до первого detector execution не создают пару.
Если `started` был вызван, normal completion, detector/policy error, deadline,
orchestration failure и cancellation best-effort вызывают ровно один terminal
`completed`. Cancellation после validated ready result не создаёт вторую
completion attempt. Несколько fragments/policies не создают несколько pairs.

Оба events содержат один safe envelope:

- `event.name`, `protocol=openai.chat_completions` и
  `phase=REQUEST|RESPONSE`;
- `trace.id`, server-generated current `span.id` и его `parent.span.id`;
- `policies` как distinct canonical `id@version`, отсортированные по
  ID/version;
- `detector.id` и `detector.version` фактически используемого Fast PII
  adapter.

Terminal event дополнительно содержит:

- `outcome=CLEAN|DETECTED|INSPECTION_GAP|ERROR`;
- protocol `coverage`, `fragments.inspected`, `findings.total`;
- canonical sorted strings `findings.by_type` и
  `findings.by_evidence_strength` в формате `category:count`;
- non-negative integral `analysis.duration_ms`;
- фактическую `reaction=ALLOW|MASK|BLOCK` только для successful analysis;
- один stable `error.code` и отсутствие `reaction` для `ERROR`.

Outcome выбирается детерминированно: detector/policy/analysis failure после
старта даёт `ERROR`; иначе наличие finding даёт `DETECTED`; иначе наличие gap
даёт `INSPECTION_GAP`; иначе `CLEAN`. Finding имеет приоритет над gap.
REQUEST и ordinary/SSE RESPONSE используют одну schema; application boundary
и reaction semantics остаются у [REQUEST enforcement](request-enforcement.md)
и [RESPONSE enforcement](response-enforcement.md).

## Operational application events

- Каждый proxy exchange после terminal request log публикует один INFO
  `request_completed` с method, path без query, optional response status,
  optional `upstream.duration_ms` и `gateway.duration_ms`.
- Upstream transport failure публикует один WARN
  `upstream_request_failed` с stable `upstream.error`, exception simple class
  в `upstream.cause`, method и path без query. Body, headers, query и exception
  message/stack отсутствуют.
- Каждый actual detector `ERROR` может публиковать `detector.failed` с
  detector ID, bounded code/message и affected policy IDs.
- Каждый policy deadline может публиковать `policy.deadline_exceeded` с policy
  ID/version, deadline и unfinished detector IDs.

Эти operational events не заменяют analysis pair и не меняют его terminal
outcome.

## Tracing and propagation

Gateway читает configured session header
`VIGILANT_TRACING_SESSION_HEADER` (`x-session-id` по умолчанию), configured
W3C parent header `VIGILANT_TRACING_TRACEPARENT_HEADER` (`traceparent` по
умолчанию) и standard `tracestate`. Имена configured headers валидны,
case-insensitively distinct и применяются к ingress, upstream и response.
`baggage` не поддерживается.

Session ID является opaque visible ASCII string длиной `1..256`. Отсутствующее
или empty значение заменяется UUIDv7; другое invalid значение даёт
`400 {"error":"invalid_session_id"}` без upstream call. Effective session ID
возвращается client и передаётся upstream.

Valid inbound `traceparent` продолжает trace и становится parent SERVER span.
Без него создаётся новый root. Malformed parent заменяется новым root, не
возвращается и не попадает в logs; связанный `tracestate` отбрасывается.
Client получает SERVER `traceparent`, upstream получает CLIENT `traceparent`;
каждый hop использует новый span ID.

Span tree одного guardrail exchange:

```text
inbound parent, если есть
+-- SERVER HTTP span Vigilant
    +-- INTERNAL vigilant.request.inspect
    |   +-- CLIENT vigilant.identity.external.lookup, только cold-miss owner
    +-- HTTP CLIENT upstream
    +-- INTERNAL vigilant.response.inspect, только начавшийся response analysis
```

Request/response INTERNAL и upstream CLIENT являются direct children SERVER.
External cold miss CLIENT является child request INTERNAL и сохраняет parent
инициатора до shared terminal event; cache hit/join не создают новый span или
link. SERVER, request/response INTERNAL и upstream CLIENT несут `session.id`;
SERVER также несёт method, path без query, response status, durations и flags
`session.id.generated`, `trace.context.generated`,
`trace.context.replaced`. Policy BLOCK не записывается как exception.

Operational request-scoped MDC может содержать effective `session_id`,
`trace_id`, current `span_id`, `parent_span_id`, valid received `traceparent`/
`tracestate` и generation/replacement flags. Analysis audit MDC намеренно
исключает session и raw received propagation, сохраняя только generated
trace/span/parent identifiers и safe flags. Audit correlation относится к
current INTERNAL span, upstream failure к CLIENT, completion к SERVER.

## Gateway metrics

| Instrument | Type | Unit | Единственные attributes |
|---|---|---|---|
| `vigilant.proxy.requests` | counter | `{request}` | нет |
| `vigilant.proxy.responses` | counter | `{response}` | `http.response.status_class` |
| `vigilant.proxy.timeouts` | counter | `{timeout}` | нет |
| `vigilant.proxy.transport_errors` | counter | `{error}` | `error.type` |
| `vigilant.proxy.cancellations` | counter | `{cancellation}` | нет |
| `vigilant.proxy.active_requests` | gauge | `{request}` | нет |
| `vigilant.proxy.upstream.duration` | histogram | `s` | нет |
| `vigilant.proxy.gateway.duration` | histogram | `s` | нет |

Request counter и active gauge начинают ownership у outer proxy decorator.
Active возвращается к baseline по terminal request-log callback, включая
cancellation. Response counter публикуется только при available valid status;
status class является bounded HTTP class. Upstream duration публикуется только
после actual handoff и available response start. Gateway duration публикуется
на каждом terminal exchange. Timeout, transport failure и client cancellation
имеют отдельные counters; cancellation не дублируется как transport error.

Metric attributes обязаны иметь finite allowlist и не включать payload,
headers, query, session, identity, user/group, policy или tenant dimensions.
`OBS-01` также требует latency/outcome/capacity/deadline и aggregate PII
observations inspection path. В текущем runtime эти inspection aggregates
публикуются только в `policy.analysis_completed`; dedicated OTel instruments и
их names пока отсутствуют. Это conformance gap, а не разрешение удалить target.
Текущий `error.type` формируется из exception class и также не подтверждает
finite allowlist; фактическая граница отмечена в coverage.

## Privacy by channel

| Channel | Разрешено | Запрещено |
|---|---|---|
| Analysis audit | Generated trace/span/parent IDs, safe policy/detector refs, finite outcomes, aggregate counts/duration | Body/preview, PII value/span, offset/locator, path/query, headers/cookies/credentials, session, identity/user/groups, raw inbound propagation, raw exception/message/stack, derived hashes |
| Client errors | Stable status/code/message and explicitly owned safe headers | Upstream body/headers/status where replaced, payload-derived values, policy refs, identity, credentials, raw cause |
| Operational application logs | Method, path without query, finite outcome/code, effective tracing MDC including session and valid propagation where specified | Body/preview, query, arbitrary headers, credentials, PII values/spans, identity/user/groups, exception message/stack |
| Metrics | Fixed instrument names, units and finite allowlisted attributes | Body, query, headers, session, identity/user/groups, PII values/spans, policy/user/tenant cardinality, raw exception class/message |
| Traces | Defined lineage, session ID, method, path without query, status, durations, finite outcome flags | Body/preview, query, headers/credentials, PII values/spans, identity/user/groups, raw exception event/message/stack |

Текущий SERVER и upstream CLIENT implementation вызывает `recordException`
для некоторых transport failures. Поэтому запрет raw exception details в
traces является сохраняемым target с явным implementation/evidence gap; он не
ослабляется до фактического поведения этой documentation migration.

## OTLP output and lifecycle

При `VIGILANT_OTLP_ENABLED=true` trace record имеет top-level
`resourceSpans`, metric record имеет top-level `resourceMetrics`. При `false`
stdout export обоих signals отключён, но trace context, application logs,
spans и internal metric collection остаются активны. Prometheus endpoint и
Collector endpoint отсутствуют.

После proxy drain tracing и metrics providers пытаются flush, затем close;
ошибка одного cleanup action не отменяет остальные. External Collector читает
stdout, разделяет OTLP и application records и владеет backend routing,
authentication, delivery, retention, deduplication и alerts. Конкретные
Collector/exporter versions pin-ятся и квалифицируются deployment-командой;
application не обещает delivery каждого event.

## Verification boundary

Normative matrix проверяется logging topology/queue tests, causal real-Armeria
REQUEST/ordinary/SSE audit E2E, tracing/metrics SDK tests и packaged stdout
observations. Test name или historical durable qualification не доказывают
current stdout delivery. Полная методика находится в
[development guide](../../docs/development.md#observability-contract-checks),
а implementation/evidence gaps в
[coverage](../../docs/requirements-coverage.md#observability-evidence).

## Identity

Этот named section владеет identity-specific metrics и span contract для
[OBS-01/02](../MVP_NON_FUNCTIONAL_REQUIREMENTS.md#наблюдаемость-и-audit).
Общий stdout/log/audit/privacy contract определён разделами выше;
фактический pipeline описан в [observability](../../docs/observability.md).
[Identity owner](identity-and-context.md) определяет lookup/cache outcomes и lifecycle.

| Instrument | Unit | Единственные attributes |
|---|---|---|
| `vigilant.identity.external.lookups`, counter | `{lookup}` | `identity.mode=EXTERNAL`, `identity.outcome`, optional `http.response.status_class` |
| `vigilant.identity.external.lookup.duration`, histogram | `s` | те же attributes |
| `vigilant.identity.external.cache.requests`, counter | `{request}` | `identity.mode=EXTERNAL`, `cache.result=hit\|miss` |
| `vigilant.identity.external.cache.coalesced`, counter | `{request}` | `identity.mode=EXTERNAL` |
| `vigilant.identity.external.cache.removals`, counter | `{entry}` | `identity.mode=EXTERNAL`, `cache.removal.reason=expired\|size` |

Каждый Bridge lookup terminal outcome (`success`, `provider_status`,
`invalid_response`, `timeout`, `transport_error`, `overloaded`, `cancelled`)
публикует ровно один lookup counter sample, duration record и CLIENT span
`vigilant.identity.external.lookup`. Он является child request inspection span
инициатора. Любой failure кроме cancellation получает `ERROR`.
Status class может быть только `2xx`, `3xx`, `4xx`, `5xx` и только после
полученных final headers; без headers status class не изобретается.

Каждый запрос к открытому cache считает ровно один hit либо miss. Hit - готовая
неистёкшая identity; join тоже miss и дополнительно coalesced. Три concurrent
callers одного cold key дают `miss=3`, `coalesced=2`, один Bridge lookup.
Waiter overload - miss без coalesced/Bridge lookup. Failure не превращается
в hit. Lookup после close не создаёт cache request observation.

Expired/size removals считают фактическое удаление Caffeine, не наступление
TTL по таймеру. Failure/cancellation in-flight, explicit close clear и
replacement не считаются expired/size. Listeners используют только finite
cause, без capture key/value. Ошибка cache telemetry не меняет identity outcome.

Hit не создаёт Bridge CLIENT span; shared miss имеет один span с trace/parent
инициатора, без дополнительных spans или links для joined requests. Отмена
инициатора при оставшемся caller не меняет lineage и не завершает shared span
до shared terminal event. Каждый caller продолжает работу в своём request
context. Existing gateway metrics наблюдают итоговый client `503`; отдельного
active-lookups gauge нет.

Per-request application log, per-token/per-user dimensions и дополнительные
cache spans отсутствуют. Endpoint, Bearer/Authorization, raw token, digest,
HMAC secret, user/groups, decoded claims, Bridge body/preview и raw exception
запрещены в audit/logs/metrics/traces/errors. `Span.recordException` для
provider failures не используется, публикуется только safe finite category.
Это правило распространяется на success, hit, failures, cancellation,
shutdown и config diagnostics.
