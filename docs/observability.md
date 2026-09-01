# Observability

## Логи

У приложения один физический sink - stdout. В нём находятся два логических
потока:

1. application logs как JSON Lines через Logback;
2. traces и metrics как OTLP JSON Lines через OpenTelemetry stdout exporters.

Производитель журналов приложения использует SLF4J 2. Компонент Logback
`AsyncAppender` отделяет поток запроса от `JsonEncoder` и единственного места
вывода `stdout`. Это описание ответственности компонента, а не отдельный
гарантированный канал доставки.

Async queue ограничена `queueSize=8192` и использует `neverBlock`. При
переполнении события отбрасываются вместо блокировки Netty event loop; сначала
отбрасываются низкие уровни согласно `discardingThreshold`. Канал не является
гарантированным audit storage.

JSON object содержит:

- `timestamp` в epoch milliseconds;
- `level`;
- `threadName`;
- `loggerName`;
- `formattedMessage`;
- `kvpList` с SLF4J key-value pairs;
- `mdc`;
- `throwable`.

Порядок полей не фиксирован. Consumer должен разбирать JSON, а не текстовую
строку.

Runtime log level настраивается через `VIGILANT_LOG_LEVEL`. Default -
`INFO`.

## Безопасность данных

На любом log level запрещено записывать:

- request/response body и content preview;
- query string и raw URI;
- `Authorization`, `Proxy-Authorization`, cookies и API keys;
- полный набор headers;
- matched text, offsets, locators, media URL и filenames;
- identity values и reversible hashes.

Raw Armeria access log отсутствует. Каждый завершённый proxy exchange создаёт
safe structured `request_completed` event с method, path без query, HTTP status
и upstream/gateway durations. Health/readiness probes этим decorator не
обрабатываются.

## Safe aggregate shadow event

Для каждого поддержанного Chat Completions request создаётся один aggregate
event `event.name=policy.shadow_decision`. Он содержит:

- `protocol=openai.chat_completions`;
- `phase=REQUEST`;
- `decision=DETECTED|CLEAN|INSPECTION_GAP|ERROR`;
- `disposition=ALLOW`;
- inspection coverage;
- trace ID;
- sorted policy IDs/versions;
- detector ID/version;
- число inspected fragments;
- aggregate finding counts по type и evidence strength;
- evaluation duration;
- safe error code для failed request.

Неподдерживаемый descriptor и invalid tracing session отклоняются до audit
reservation и body demand, поэтому durable record и stdout projection не
создаются.

Identity extraction failure поддержанного descriptor создаёт тот же один
aggregate event с `decision=ERROR` и bounded `error.code`. Configured header
values, Basic username/password и raw `Authorization` в event не добавляются.

Этот event является projection уже durably accepted record. `AsyncAppender`
может отбросить INFO record до stdout, поэтому `policy.shadow_decision` сам по
себе не является mandatory audit acceptance.

## Guaranteed minimum audit trail

Нормативный [minimum audit trail contract](../spec/MINIMUM_AUDIT_TRAIL_CONTRACT.md)
отделяет creation safe decision, store ownership, durable retention и
external delivery. Минимальная guarantee - application-owned WAL record,
подтверждённая covering `force(true)` до forwarding или normal
supported-request response.

Текущий runtime реализует local durable стадии `RESERVED`, `DECISION_CREATED`,
`STORE_OWNED`, `DURABLY_RETAINED` и `EXTERNALLY_DELIVERED`.
Application-owned segmented WAL использует
versioned JSON, length/checksum frame, persistent sequence, exclusive directory
lock и `force(true)` до upstream handoff или original supported-request
response. Directory и bounds перечислены в
[configuration reference](configuration.md).

Граница долговечности на диаграмме состояний UML 2.0:
[audit-lifecycle-state.puml](diagrams/audit-lifecycle-state.puml).

`CAPACITY_EXHAUSTED`, `EVENT_TOO_LARGE`, `IO_FAILURE` и `CLOSED` дают только
`503 {"error":"audit_unavailable"}` и переводят readiness в `503`, пока store
не восстановил admission. External Collector асинхронно читает immutable ready
segments по [file handoff contract](audit-collector-file-handoff.md). Exact
contiguous ack force-backed переводит segment в `EXTERNALLY_DELIVERED` и
разрешает reclaim; Collector outage оставляет records локально до retained
bound и не добавляет synchronous delivery в request path.

Packaged [qualification report](durability-qualification-2026-08-31.md)
подтверждает exact retained-capacity `503 audit_unavailable` и отсутствие
payload, identity, session, credentials, locators и reversible hashes в WAL,
manifests, acks, stdout, errors и самом report.

Rejected acknowledgement пишет один bounded WARN
`event.name=audit.collector_ack_rejected` только со stable `error.code`:
`MALFORMED_ACK`, `UNKNOWN_SEGMENT`, `DUPLICATE_ACK`, `OUT_OF_ORDER_ACK`,
`MISSING_SEGMENT`, `TERMINAL_SEQUENCE_MISMATCH`, `DIGEST_MISMATCH` или
`SEGMENT_INTEGRITY_MISMATCH`. Event не содержит filename, path, manifest/ack
contents, record fields или raw exception.

## Upstream failure event

Upstream failure создаёт один structured WARN event
`event.name=upstream_request_failed` с bounded category и safe cause class.
Request/response data и exception details клиенту не раскрываются.

## Policy engine events

Policy engine пишет дополнительные safe structured events:

- `event.name=detector.failed` на каждый actual detector outcome `ERROR` с
  detector ID, bounded error code/message и списком затронутых policy IDs;
- `event.name=policy.deadline_exceeded` для policy, чей detector-set deadline
  исчерпан, с policy ID/version, deadline и unfinished detector IDs.

Эти события не содержат payload, matched text, offsets или protocol locators.
Итог того же request остаётся в единственном aggregate
`policy.shadow_decision` с decision `ERROR` и disposition `ALLOW`.

## Request completion event

Каждый proxy exchange создаёт structured INFO event
`event.name=request_completed`. Он содержит:

- `http.request.method`;
- `url.path` без query;
- `http.response.status_code`;
- `upstream.duration_ms`, когда response start доступен;
- `gateway.duration_ms`;
- trace ID в MDC.

## Tracing

### Входной и выходной контракт

Gateway читает два настраиваемых headers:

- `VIGILANT_TRACING_SESSION_HEADER`, default `x-session-id`, содержит opaque
  session ID для группы запросов одной задачи;
- `VIGILANT_TRACING_TRACEPARENT_HEADER`, default `traceparent`, содержит полное
  W3C `traceparent` value.

`tracestate` остаётся стандартным header и не переименовывается. Оба
настраиваемых header name должны быть валидными и различными.

Валидный входящий `traceparent` становится parent для SERVER span Vigilant.
Если header отсутствует или malformed, gateway создаёт новый root trace.
Malformed value не возвращается и не попадает в логи; связанный `tracestate`
также отбрасывается. Отсутствующий или пустой session ID заменяется UUIDv7.
Session ID считается opaque, но ограничен 256 visible ASCII characters;
некорректное значение даёт `400 {"error":"invalid_session_id"}` без вызова
upstream.

Effective session ID и SERVER `traceparent` возвращаются клиенту в тех же
настроенных headers. Upstream получает effective session ID и `traceparent`
CLIENT span также под тем же настроенным именем. Валидный `tracestate`
сохраняется. Поэтому shadow proxy не меняет формат tracing контракта, но
корректно обновляет span ID на каждом hop.

### Span model

Один поддержанный запрос прокси создаёт SERVER span и два непосредственных
дочерних span: INTERNAL span `vigilant.request.inspect` и HTTP CLIENT span
вышестоящего запроса. Полное происхождение и момент передачи контекста показаны
на диаграмме последовательности UML 2.0
[tracing-sequence.puml](diagrams/tracing-sequence.puml).

INTERNAL и CLIENT являются прямыми children SERVER span. Inspection
завершается после принятия решения и создания upstream response exchange;
CLIENT span живёт до завершения upstream exchange. Все три span несут один
trace ID и attribute `session.id`.

SERVER span также содержит method, path без query, status,
`upstream.duration_ms`, `gateway.duration_ms`, а также flags
`session.id.generated`, `trace.context.generated` и
`trace.context.replaced`. Тела, query values и auth headers в spans не
добавляются. Gateway-owned health/readiness probes не проходят через tracing
decorator.

### Correlation в application logs

Request-scoped events получают в MDC:

- effective `session_id`, `trace_id`, current `span_id` и его
  `parent_span_id`;
- валидные входные `traceparent` и `tracestate`, если они были получены;
- `session_id_generated`, `trace_context_generated` и
  `trace_context_replaced`.

Для audit event current span равен INTERNAL inspection span, для upstream
failure event он равен CLIENT span, для request completion event он равен
SERVER span. Другие HTTP headers в MDC не копируются.

## Metrics

Gateway создаёт следующие OpenTelemetry instruments:

| Instrument | Type | Unit | Attributes |
|---|---|---|---|
| `vigilant.proxy.requests` | counter | `{request}` | нет |
| `vigilant.proxy.responses` | counter | `{response}` | `http.response.status_class` |
| `vigilant.proxy.timeouts` | counter | `{timeout}` | нет |
| `vigilant.proxy.transport_errors` | counter | `{error}` | `error.type` |
| `vigilant.proxy.cancellations` | counter | `{cancellation}` | нет |
| `vigilant.proxy.active_requests` | gauge | `{request}` | нет |
| `vigilant.proxy.upstream.duration` | histogram | `s` | нет |
| `vigilant.proxy.gateway.duration` | histogram | `s` | нет |

Метрики не содержат payload, headers, query, identity или tenant dimensions.
Proxy overhead не вычисляется на production traffic: его измеряет отдельный
PERF-01 load test.

## OTLP JSON stdout

При `VIGILANT_OTLP_ENABLED=true` завершённые spans и collected metrics
выводятся в stdout по одному OTLP/JSON ExportRequest на строку:

- trace record имеет top-level `resourceSpans`;
- metric record имеет top-level `resourceMetrics`.

Application log record имеет top-level `timestamp`, `level` и другие поля
Logback JsonEncoder. Это позволяет Collector разделить два логических потока
без сетевого подключения из Vigilant. `VIGILANT_OTLP_ENABLED=false` отключает
только OTLP/JSON records. Prometheus scrape endpoint отсутствует.

На shutdown providers явно flush-ятся, затем закрываются после proxy drain.
Stdout exporter имеет development stability в OpenTelemetry Java, а OTLP/JSON
connector имеет alpha stability в Collector Contrib. Версии Collector и Java
exporter должны быть закреплены и проверены перед production rollout.

## Ответственность deployment

Container runtime должен захватывать stdout, использовать non-blocking delivery
и ограничивать локальные log files. Пример Docker Compose:

~~~yaml
services:
  vigilant:
    logging:
      driver: json-file
      options:
        mode: non-blocking
        max-buffer-size: 4m
        max-size: 10m
        max-file: "3"
~~~

Для централизованного хранения нужен внешний OpenTelemetry Collector. Базовый
pipeline приведён в [otel-collector.yaml](otel-collector.yaml). Он:

1. читает container stdout через `filelog`;
2. направляет records с `resourceSpans`/`resourceMetrics` в `otlpjson`
   connector;
3. оставляет остальные records в application logs pipeline;
4. передаёт traces, metrics и logs во внешние exporters.

Пример использует `debug` exporter как безопасную заглушку. В deployment его
нужно заменить отдельными exporters для Langfuse, MLflow и log storage с
подходящими endpoint/auth settings. Для application logs также нужно добавить
parser, который переносит `level`, `kvpList` и `mdc` в поля OpenTelemetry Log
Record; конкретный mapping зависит от backend schema.
