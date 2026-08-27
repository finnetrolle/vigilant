# VIG-17: Сквозной tracing context и OTLP JSON через stdout

**ID:** `VIG-17`
**Тип:** Issue
**Статус:** Done
**Приоритет:** High
**Зависит от:** [VIG-01](issue_01_logging.md), [VIG-05-06](epic_05/issue_05_06_trace_id_otlp.md), [VIG-05-07](epic_05/issue_05_07_otlp_metrics.md), [VIG-13](issue_13_pii_shadow_request_tracer.md)
**Блокирует:** нет
**Оценка:** 3-5 инженерных дней
**Уверенность:** High

## Контекст

Клиент перед Vigilant создаёт session ID и W3C trace context. Gateway не
должен зависеть от типа клиента и не должен навязывать собственные имена
headers. Его задача состоит в том, чтобы продолжить полученную цепочку,
создать собственные spans, передать обновлённый context upstream LLM и вернуть
effective context вызывающему клиенту.

VIG-05-06 и VIG-05-07 исходно предполагали прямой сетевой OTLP exporter из
приложения. Эта issue фиксирует итоговый runtime contract: приложение пишет
application logs и OTLP JSON records в stdout, а внешний Collector разделяет и
доставляет их в Langfuse, MLflow и log storage. В части propagation и
транспорта этот контракт уточняет исторические решения VIG-05-06 и VIG-05-07.

## Результат

Каждый поддержанный proxy request имеет effective session, trace и span
lineage от клиента через Vigilant до upstream. Полученные или сгенерированные
идентификаторы коррелируют HTTP headers, spans и request-scoped logs. Vigilant
не открывает соединение с Collector: application logs и OTLP/JSON traces и
metrics выходят атомарными JSON Lines через stdout.

## Принятое решение

### Ingress configuration

- `VIGILANT_TRACING_SESSION_HEADER`, default `x-session-id`, задаёт header с
  opaque session ID.
- `VIGILANT_TRACING_TRACEPARENT_HEADER`, default `traceparent`, задаёт header,
  значение которого соответствует W3C `traceparent`.
- HOCON keys имеют имена `vigilant.tracing-session-header` и
  `vigilant.tracing-traceparent-header`; действует precedence
  `env > file > defaults`.
- Оба имени валидируются как HTTP header names, canonicalize-ятся и обязаны
  различаться без учёта регистра. Нарушение завершает startup ошибкой.
- `tracestate` использует стандартное имя и не настраивается. `baggage` не
  поддерживается этой issue.

### Session contract

- Session ID является opaque visible ASCII string длиной не более 256
  characters.
- Отсутствующее или пустое значение заменяется сгенерированным UUIDv7.
- Некорректное непустое значение даёт
  `400 {"error":"invalid_session_id"}` без вызова upstream.
- Effective session ID передаётся upstream и возвращается клиенту под тем же
  настроенным именем header.

### Trace propagation

- Валидный входящий `traceparent` продолжает исходный trace, а его span ID
  становится parent для SERVER span Vigilant.
- При отсутствующем `traceparent` gateway создаёт новый root trace.
- Malformed `traceparent` трактуется как отсутствующий: gateway создаёт новый
  root trace, помечает context как replaced, не логирует исходное значение и
  отбрасывает связанный `tracestate`.
- Клиент получает `traceparent` SERVER span под настроенным именем header.
- Upstream получает `traceparent` CLIENT span под тем же именем header,
  effective session ID и валидный входящий `tracestate`.
- Vigilant сохраняет формат контракта, но обновляет span ID для каждого hop.

### Span model

```text
incoming parent span (если есть)
  -> Vigilant SERVER span
       -> vigilant.request.inspect INTERNAL span
       -> upstream HTTP CLIENT span
```

INTERNAL и CLIENT spans являются прямыми siblings под SERVER span. Все spans
имеют один trace ID и attribute `session.id`. Audit event выполняется в
INTERNAL span, upstream failure event в CLIENT span, request completion event
в SERVER span. Поэтому `span_id` и `parent_span_id` в каждом log record
описывают реальный текущий участок выполнения, а не один статический request
span.

### Logging context

В request-scoped MDC входят только tracing-поля:

- `session_id`, `trace_id`, `span_id`, `parent_span_id`;
- валидные входящие `traceparent` и `tracestate`, если они присутствовали;
- `session_id_generated`, `trace_context_generated`,
  `trace_context_replaced`.

Произвольные HTTP headers, payload, query values, credentials, identity
values и malformed trace context в логи не копируются.

### Два stdout-канала

1. Logback пишет application JSONL records в stdout.
2. При `VIGILANT_OTLP_ENABLED=true` OpenTelemetry providers пишут в тот же
   stdout по одному OTLP/JSON ExportRequest на строку: trace record с
   top-level `resourceSpans`, metric record с top-level `resourceMetrics`.

Общий line-buffered writer не допускает перемешивания bytes параллельных
application и OTLP records. При shutdown providers сначала flush-ятся, затем
закрываются после proxy drain. `VIGILANT_OTLP_ENABLED=false` выключает только
OTLP JSON output; trace context, application logs и внутренний сбор metrics
остаются активными.

Collector endpoint в конфигурации Vigilant отсутствует. Внешний Collector
читает container stdout через `filelog`, направляет OTLP JSON records в
`otlpjson` connector, а остальные records в application logs pipeline.
Backend-specific exporters, authentication и routing для Langfuse и MLflow
задаются deployment configuration.

## Public seams

- HTTP E2E через real Armeria client, production gateway и upstream stub
  проверяет custom header names, propagation, response headers и отсутствие
  upstream call при invalid session.
- In-memory OpenTelemetry SDK и captured JSON line writer проверяют span tree,
  attributes, OTLP trace/metric envelopes и атомарность records.
- Captured application logger проверяет MDC для audit, upstream failure и
  completion events.
- Config loading tests проверяют defaults, HOCON, environment overrides и
  fail-fast validation.

## Критерии готовности

- [x] Имена session и traceparent headers настраиваются через HOCON и env,
  имеют documented defaults и fail-fast validation.
- [x] Валидные session ID, `traceparent` и `tracestate` продолжаются через
  SERVER span к CLIENT span и возвращаются клиенту в согласованном формате.
- [x] Отсутствующая session генерируется как UUIDv7; отсутствующий trace
  context создаёт новый root trace.
- [x] Malformed trace context безопасно заменяется, не логируется и не
  переносит входящий `tracestate`.
- [x] Invalid session возвращает stable `400` и не достигает upstream.
- [x] SERVER, INTERNAL и CLIENT spans сохраняют trace ID, корректные parent
  relationships и attribute `session.id`.
- [x] Request-scoped logs содержат effective tracing context текущего span и
  не содержат произвольные headers или sensitive values.
- [x] Traces и metrics выводятся как OTLP JSON Lines с top-level
  `resourceSpans` и `resourceMetrics` только при включённом output.
- [x] Параллельные application и OTLP writers создают отдельные валидные JSON
  lines без byte interleaving.
- [x] Приложение не содержит Collector endpoint и не выполняет прямой OTLP
  network export.
- [x] Документация содержит пример Collector pipeline, который разделяет два
  логических stdout-канала.

## Проверка реализации

- `./gradlew test`: 377 tests passed.
- `./gradlew detekt`: passed.
- `./gradlew build`: production compilation, tests и detekt прошли; общий
  lifecycle остановился на `validateWorkItems` из-за существующего формата
  metadata VIG-11..16. Диагностика не относится к поведению VIG-17 и не была
  скрыта изменениями этой issue.

## Не входит

- `baggage` propagation и sampling policy;
- прямые SDK-интеграции Vigilant с Langfuse или MLflow;
- Collector deployment, backend authentication, dashboards, alerts и
  centralized storage;
- копирование всех входящих HTTP headers в логи или spans;
- response content inspection и изменение shadow-only policy behavior.

## Рассмотренные альтернативы

- Прямой OTLP HTTP exporter из Vigilant отклонён: он связывает runtime с
  Collector availability и дублирует delivery responsibility платформы.
- Proprietary `request_id` вместо W3C `traceparent` отклонён: он не выражает
  parent relationship и требует отдельного correlation protocol.
- Один статический span ID для всех request events отклонён: он скрывает
  границы inspection и upstream exchange.
- Копирование всех headers в structured logs отклонено из-за утечек секретов
  и неконтролируемой cardinality.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0  session, trace, span и parent chain определены
  Acceptance:   0.0  HTTP, span, log и stdout seams наблюдаемы
  Boundaries:   0.0  Collector и backend integrations исключены
  Alternatives: 0.1  transport и correlation alternatives зафиксированы
  Assumptions:  0.1  Collector versions pin-ятся в deployment
  ------------------------------------------------------------
  Aggregate:    0.04 below threshold (0.2 issue)
```
