# Vigilant: нефункциональные требования и стек MVP

## Производительность

### PERF-01. Guardrail latency

При 2 000 RPS одна replica должна выполнять `fast-pii`, policy evaluation и
применение реакции с `p99 <= 2 ms` отдельно для request и response path.

Request measurement начинается после получения полного request body Vigilant и
заканчивается при передаче body upstream. Response measurement начинается после
получения полного upstream response и заканчивается перед передачей client.
Identity lookup не входит в этот budget: нагрузочный тест использует warm cache
или mock extractor.

### PERF-02. Load profile

SLO подтверждается non-streaming Chat Completions profile с request `1 KiB` и
response `4 KiB`. Отчёт фиксирует warmup, duration, hardware, JVM, connections,
payload sizes и percentiles. Max-size и overload scenarios проверяются отдельно
и не обязаны удовлетворять `2 ms`.

### PERF-03. Policy deadline

Каждая policy задаёт свой positive deadline в `politics.conf`. Для нескольких
применимых policies действует minimum deadline. Timeout `fast-pii` или policy
даёт `503`; implicit fail-open не допускается.
Детальный shared-execution contract определён в
[policy engine](requirements/policy-engine.md#execution-and-deadlines), а
REQUEST priority - в
[request enforcement](requirements/request-enforcement.md#request-reactions-and-priority).

## Ресурсы и cancellation

### CONC-01. Request bounds и response heap lifecycle

Один request допускает до `16 MiB` aggregate inspectable text и до `20 MiB`
raw body. Большие text fragments проверяются UTF-8-safe windowing без silent
truncation. Превышение request limits не передаётся дальше.

Detector preflight и одновызовный limit определены в
[Fast PII](requirements/fast-pii.md#preflight), evidence-span capability,
bounded execution и cancellation - в
[windowed inspection](requirements/windowed-inspection.md#capability).
Эти внутренние bounds не заменяют request targets и enforcement SLO выше.

Response не имеет application-level raw/text limit или shared capacity quota:
он использует доступный JVM heap до terminal policy decision. После `ALLOW`,
`MASK`, `BLOCK`, cancellation, upstream failure или shutdown response source
освобождает owned buffers и references; фактическое освобождение heap выполняет
JVM GC. Heap sizing и OOM policy принадлежат deployment.
Полный retained-source ownership и cleanup contract принадлежит
[RESPONSE enforcement](requirements/response-enforcement.md#retained-source-and-ownership).

### CONC-02. Request capacity outcome

Request RAM or spool exhaustion не создаёт unbounded queue и не пропускает
непроверенный traffic. Gateway быстро отвечает `503 Service Unavailable` с
`Retry-After`; exact request capacity limits являются deployment configuration.
Response не резервирует отдельную application capacity; его heap lifecycle
определён в `CONC-01`.
Точные request quotas, precedence и terminal cleanup принадлежат
[bounded request source](requirements/request-source.md#resource-bounds).

### CONC-03. Execution classes

Netty event loop не выполняет blocking I/O или CPU-bound `fast-pii`. Blocking
identity integration выполняется вне event loop; detector использует bounded CPU
executor и queue.

### CONC-04. Cancellation и shutdown

Client cancellation прекращает inspection, spool и upstream work, если оно
больше не нужно. Graceful shutdown немедленно переводит readiness в `503`,
останавливает admission новых requests, bounded-drain-ит активные операции и
затем отменяет остаток.

## Proxy и protocol

### PROXY-01. Retained in-memory response enforcement

Request удерживается в bounded request source, а response, включая SSE, - в
retained in-memory response source до policy decision. Response source не имеет
application-level limit или shared quota. Клиент не получает response byte до
`ALLOW` или `MASK`; при `BLOCK` body upstream не раскрывается.
Точные atomic outcomes принадлежат
[RESPONSE enforcement](requirements/response-enforcement.md#atomic-boundary).

### PROXY-02. Lossless forwarding and mutation

Разрешённый body передаётся losslessly. `MASK` patch-ит только exact text spans,
сохраняет JSON structure и unknown fields, затем корректирует transport headers.
Response source maps и rewrite определены в
[RESPONSE enforcement](requirements/response-enforcement.md#source-maps-and-exact-rewrite),
header matrix - в
[HTTP gateway](requirements/http-gateway.md#response-outcomes-and-headers).

### PROXY-03. Stable technical failures

Capacity exhaustion, detector/policy failure и unavailable identity дают `503`
с `Retry-After`. Exact inspection и policy BLOCK outcomes определены в
[HTTP error contract](requirements/http-gateway.md#inspection-error-matrix),
External identity outcome - в [identity contract](requirements/identity-and-context.md#external-bridge).

## Наблюдаемость и audit

### OBS-01. Metrics и tracing

Полная нормативная matrix принадлежит
[observability contract](requirements/observability.md).

Gateway публикует request/response outcomes, latency каждого path, PII counts по
type, deadline/errors, spool/capacity rejects и identity cache hit/miss/lookup
latency. Audit queue depth/drops не публикуются: stdout delivery принадлежит
Logback/container runtime. Tracing содержит session, trace ID, span ID и
parent span ID.
Текущая REQUEST inspection и ordinary JSON/SSE RESPONSE enforcement публикуют
stdout `policy.analysis_started`/`policy.analysis_completed` с outcome, latency и
safe aggregate PII counts.

### OBS-02. Privacy

Audit, logs, metrics, traces и errors не содержат body, PII value/span, Bearer
token, user ID или groups. Исключение: tracing identifiers хранятся для
корреляции.
Этот privacy contract покрывает текущие REQUEST и ordinary JSON/SSE
RESPONSE stdout pairs. Channel-specific разрешения и текущие conformance gaps
определены в [privacy matrix](requirements/observability.md#privacy-by-channel).

## Deployment и stack

- MVP запускается в нескольких stateless replicas за load balancer. Каждая
  replica владеет local identity cache и response source; audit delivery идёт
  через container-managed stdout.
- Численный availability SLO пока не задан; его определяет
  [VIG-33](issues/issue_33_availability_slo_and_operations.md) после production
  telemetry.
- Stack: Kotlin 2.4.10, Java 25, Armeria/Netty, Metro, Gradle Kotlin DSL и OCI
  image. Spring Boot и GraalVM Native Image не входят в MVP.
