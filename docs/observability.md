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

## Safe request analysis pair and response counterpart

Для каждой request, ordinary-response или SSE-response фазы, где после parse,
identity/context assembly и policy selection действительно начинается detector
execution, logger best-effort
публикует ровно одну пару:

- `policy.analysis_started` непосредственно перед первым detector execution;
- `policy.analysis_completed` после всех запущенных fragment evaluations и до
  разрешённого transport handoff.

Оба event содержат `protocol=openai.chat_completions`, `phase=REQUEST|RESPONSE`,
`trace.id`, server-generated `span.id` и `parent.span.id`, canonical sorted
policy references `id@version` и detector ID/version. Trace ID либо генерируется
server-side, либо продолжает валидный W3C parent по current tracing contract.
Terminal event дополнительно
содержит `outcome=CLEAN|DETECTED|INSPECTION_GAP|ERROR`, `coverage`,
`fragments.inspected`, `findings.total`, canonical `findings.by_type` и
`findings.by_evidence_strength`, а также non-negative `analysis.duration_ms`.
Successful request shadow analysis содержит `reaction=ALLOW`; ordinary и SSE
response paths публикуют final `reaction=ALLOW|MASK|BLOCK`. ERROR не содержит reaction и
публикует только stable `error.code`. Response outcome сохраняет precedence
`DETECTED` > `INSPECTION_GAP` > `CLEAN` независимо от partial coverage.

Unsupported/malformed request или response, identity/context/source failure, empty policy
selection и cancellation до detector execution не публикуют пару. Cancellation
после start best-effort публикует terminal ERROR. Ни один event не содержит
payload, PII values/spans, path/query, headers, credentials, identity,
user/groups, session, raw exception, generated event ID или raw inbound
propagation values. Policy/detector references остаются stdout-only.

Пара не является durable acceptance. Existing Logback `AsyncAppender` с
`neverBlock=true` остаётся единственной queue и может отбросить оба INFO event.
Request/response path не ждёт queue delivery, stdout write или external delivery;
logging overload/failure не меняет HTTP outcome и upstream handoff.

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
Итог того же request остаётся в terminal
`policy.analysis_completed` с outcome `ERROR`, stable `error.code` и без
reaction.

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

Один поддержанный запрос прокси создаёт SERVER span и три непосредственных
дочерних span: INTERNAL `vigilant.request.inspect`, HTTP CLIENT span вышестоящего
запроса и INTERNAL `vigilant.response.inspect` после retained response ingest.
В External mode request inspection дополнительно владеет дочерним CLIENT span
`vigilant.identity.external.lookup`. Полное
происхождение и момент передачи контекста показаны
на диаграмме последовательности UML 2.0
[tracing-sequence.puml](diagrams/tracing-sequence.puml).

Оба INTERNAL span и upstream CLIENT являются прямыми children SERVER span. External
identity CLIENT является child request inspection span. Request inspection
завершается после принятия решения и создания upstream response exchange;
CLIENT span живёт до завершения upstream exchange, response inspection span — до final
response outcome. Все четыре span несут один
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
| `vigilant.identity.external.lookups` | counter | `{lookup}` | `identity.mode=EXTERNAL`, `identity.outcome`, optional `http.response.status_class` |
| `vigilant.identity.external.lookup.duration` | histogram | `s` | те же finite attributes |

Метрики не содержат payload, headers, query, identity или tenant dimensions.
Proxy overhead не вычисляется на production traffic: его измеряет отдельный
PERF-01 load test.

External identity outcome принимает только `success`, `provider_status`,
`invalid_response`, `timeout`, `transport_error`, `overloaded` или `cancelled`.
Status class `2xx`/`3xx`/`4xx`/`5xx` добавляется только после получения Bridge
headers. Failure span имеет `ERROR`, кроме cancellation. Endpoint, token,
Authorization, user/groups, response body/preview и raw exception не
записываются; `Span.recordException` не используется. Per-lookup application
log и active-lookups gauge отсутствуют.

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
