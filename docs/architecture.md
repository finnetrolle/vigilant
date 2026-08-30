# Архитектура Vigilant

## Текущий runtime

Vigilant - однопроцессный HTTP gateway на Kotlin и Armeria. Текущий
production increment поддерживает request-side PII inspection для OpenAI Chat
Completions в shadow mode. Он проверяет запрос и публикует безопасный audit
event, но не блокирует и не изменяет payload.

Поддерживаемый маршрут:

- `POST /v1/chat/completions`;
- `Content-Type: application/json`, параметры media type допускаются;
- schema-tolerant Chat Completions JSON, который можно однозначно разобрать для
  проверки.

Response inspection, OpenAI Responses API и enforcement reactions пока не
подключены к runtime. Подробные границы HTTP-контракта приведены в
[runtime contract](runtime-contract.md).

## Путь запроса

~~~text
Client
  -> Armeria Server
  -> TrafficAdmissionService
  -> MetricsService
  -> TracingService
  -> PiiShadowProxyService
       -> descriptor validation
       -> config-driven identity extraction and trust check
       -> bounded request ingest
       -> ShadowInspectionWorkflow
            -> Chat Completions parser
            -> normalized request policy context and scoped handoff
            -> policy selection and fast-pii inspection
            -> safe aggregate shadow audit
            -> Forward | Reject
       -> ReplayReadyRequest one-shot transport handoff
  -> BypassProxyService
       -> header normalization
       -> pooled Armeria WebClient
  -> Upstream

Upstream response
  -> hop-by-hop header stripping
  -> streaming pass-through
  -> Client
~~~

### 1. Admission, metrics и tracing

`/healthz` и `/readyz` зарегистрированы отдельными routes и не попадают в
proxy decorators. Остальные запросы проходят через
`TrafficAdmissionService`: во время graceful shutdown новый traffic получает
`503`, а уже принятые exchanges продолжают drain.

`MetricsService` считает запросы, ответы, transport errors, timeouts,
cancellations и длительности. `TracingService` создаёт SERVER span, принимает
или генерирует W3C trace context и session ID, а затем возвращает effective
context клиенту и передаёт его upstream.

### 2. Проверка descriptor, identity и bounded ingest

`PiiShadowProxyService` до чтения body проверяет method, path и media type.
Неподдерживаемый descriptor получает stable `400 unsupported_schema` и не
создаёт shadow audit.

Затем `IdentityExtractor` применяет ровно один startup mode. `ANONYMOUS` не
потребляет headers. `TRUSTED_HEADERS` читает только настроенные user/groups
headers и доверяет им только когда immediate socket peer входит в настроенный
literal IPv4/IPv6 CIDR; `Forwarded` и `X-Forwarded-For` не влияют на boundary.
`BASIC` strict-decodes `Authorization`, сохраняет только нормализованный ASCII
username и не преобразует password bytes в retained string. Ошибка identity
отклоняется до body demand и upstream call.

Для поддерживаемого запроса `RequestSourceQuota` резервирует owner. Body
принимается с backpressure и полностью сохраняется в bounded in-memory source.
Ограничения задаются отдельно для одного запроса, всего процесса, числа
одновременных owners и числа storage segments. Объявленный `Content-Length`
проверяется до body demand, а фактический размер - во время ingest.

Complete source имеет последовательные leases: сначала read-only view для
parser, затем demand-driven replay. После ingest HTTP-адаптер атомарно передаёт
owner в `ShadowInspectionWorkflow`. При expected reject, exception или
cancellation workflow закрывает owner; при `Forward` ownership переходит в
`ReplayReadyRequest`. Cancellation закрывает owner, отменяет
ingest/inspection/replay и освобождает квоты.

### 3. Lossless parsing

`ChatCompletionsRequestParser` читает отдельный view и строит только
нормализованное представление model-visible text. Исходные bytes не
пересериализуются и не заменяются DTO.

Parser извлекает модель и независимые text fragments из поддержанных message,
tool, function, reasoning, prediction, response-format и related полей.
Неизвестные несемантические поля сохраняются в исходном JSON. Известные
non-text части, например image, audio, file или opaque reasoning, отмечаются
как inspection gaps. Malformed или неоднозначная content-bearing structure
отклоняется fail-closed.

Текстовые поля проверяются независимо и не конкатенируются. Поэтому detector
offsets относятся к конкретному logical fragment, а данные из разных protocol
fields не образуют ложный общий контекст.

### 4. Policy context и engine

Context содержит:

- canonical effective upstream URL без query, fragment и credentials;
- `model` из request body;
- phase `REQUEST`;
- normalized user/groups выбранного identity mode либо явную anonymous identity.

Один immutable request snapshot сохраняется typed attribute в соответствующем
Armeria `ServiceRequestContext`. Public handoff создаёт response context,
изменяя только phase на `RESPONSE`; reported model из upstream ответа не
участвует. Snapshot очищается при completion, error или cancellation и не
использует thread-local или global map. Сам response inspection пока не
подключён.

`PolicySelector` выбирает enabled policies по точному значению или wildcard,
затем одновременно применяет явные overrides. `PolicyEngine` дедуплицирует
detector execution между policies, соблюдает deadline каждой policy и строит
детерминированное объяснение решения.

Policy domain поддерживает `ALLOW`/`BLOCK` и transformation plans, но startup
validation текущего shadow increment разрешает только `ALLOW` без
transformations для всех outcomes. Поэтому runtime всегда пересылает исходный
request, включая случаи `DETECTED`, `CLEAN` и `INSPECTION_GAP`. Detector error
фиксируется как audit decision `ERROR`, но при валидной shadow policy также не
включает enforcement.

### 5. Fast PII detector

Встроенный detector `fast-pii` детерминированно распознаёт:

- email;
- российские телефонные номера;
- номера платёжных карт;
- IPv4 и IPv6;
- IBAN;
- российские ИНН, СНИЛС, внутренний паспорт и полис ОМС.

Finding содержит тип, UTF-8 offsets, evidence strength и versioned recognizer
metadata, но не matched text. Фрагменты больше одновызовного лимита detector
разбиваются на перекрывающиеся UTF-8-safe windows; результаты переводятся в
координаты исходного fragment и дедуплицируются.

### 6. Audit и exact replay

Complete-source orchestration выполняет синхронный
`ShadowInspectionWorkflow` на существующем blocking-safe inspection executor.
Он последовательно открывает единственный parser view, собирает и сохраняет
request context, оценивает каждый независимый text fragment, получает replay
ровно один раз и возвращает взаимоисключающий `Forward` или expected `Reject`.
Unexpected exception и cancellation не превращаются в expected result и
остаются outer failures HTTP-адаптера.

После policy evaluation `ShadowAuditLogger` пишет один aggregate
`policy.shadow_decision`. В нём есть coverage, policy/detector identities,
числа fragments/findings и безопасные агрегаты по типам. Payload, matched text,
locators и headers в audit не попадают.

Эта safe aggregate schema не является durable acceptance: current
`ShadowAuditLogger` публикует INFO record через discardable async stdout.
Обязательная WAL-backed acceptance boundary определена отдельным
[minimum audit trail contract](../spec/MINIMUM_AUDIT_TRAIL_CONTRACT.md), ещё не
реализована и принадлежит
[EPIC-22](../spec/epics/epic_22_durable_minimum_audit_trail.md).

`ReplayReadyRequest` инкапсулирует demand-driven publisher и immutable strip
set. Его `transferTo` допускает ровно один transport handoff. `close()` до
handoff и synchronous callback failure освобождают source; после принятого
handoff owner освобождается только terminal signal replay. Это исключает окно
без владельца между workflow и transport.

Во время handoff из request headers удаляется только strip set, возвращённый
identity extractor: configured Vigilant-only headers для `TRUSTED_HEADERS` или
consumed `Authorization` для `BASIC`. Затем исходные bytes передаются в
`BypassProxyService`. Этот transport слой:

- переписывает scheme, authority и base path под upstream URL;
- удаляет стандартные hop-by-hop headers и headers из `Connection`;
- добавляет effective session/trace context;
- использует application-owned connection pool;
- преобразует connection/transport failure в stable `502`, timeout - в
  stable `504`;
- не агрегирует response body и сохраняет streaming/backpressure, включая SSE.

## Выполнение и resource ownership

Netty event loops не выполняют blocking parser или detector work. Request
orchestration запускается на virtual threads. Собственно CPU work `fast-pii`
идёт через bounded platform-thread pool размером не больше числа CPU и
configured concurrent-source limit. Policy detector waits и deadlines также
остаются за blocking-safe execution boundaries.

Основные owned resources:

| Resource | Owner | Ограничение или lifecycle |
|---|---|---|
| Request bodies | `RequestSourceQuota` | byte/owner/segment limits из configuration |
| Inspection orchestration | `InspectionResources` | virtual-thread executor |
| Fast PII CPU | `InspectionResources` | bounded fixed-size pool и bounded queue |
| Upstream connections | `UpstreamClientResources` | dedicated Armeria `ClientFactory` |
| Traces и metrics | `SdkTracerProvider`, `SdkMeterProvider` | process-wide providers, stdout exporters |

## Startup и shutdown

`MainKt` через Metro создаёт dependency graph и до старта server eagerly
проверяет application configuration и immutable policy snapshot. Ошибка
configuration печатается в stderr и завершает процесс с code `2`.

При `SIGTERM` shutdown hook выполняет порядок:

1. readiness становится `503`, admission нового traffic прекращается;
2. Armeria server выполняет bounded graceful drain;
3. закрываются inspection executors;
4. закрывается upstream connection factory;
5. traces и metrics flush-ятся и закрываются.

Quiet period и force timeout настраиваются. Полный операторский контракт
описан в [deployment guide](deployment.md), telemetry - в
[observability reference](observability.md).

## Карта исходного кода

Пути в таблице указаны относительно `src/main/kotlin/io/vigilant/`.

| Область | Основные файлы |
|---|---|
| Composition и lifecycle | `gateway/Main.kt`, `gateway/AppComponent.kt` |
| Application configuration | `gateway/config/AppConfig.kt` |
| Admission и probes | `gateway/health/*` |
| Request inspection | `gateway/proxy/PiiShadowProxyService.kt`, `ShadowInspectionWorkflow.kt`, `ReplayReadyRequest.kt`, `InspectionResources.kt` |
| Transport proxy | `gateway/proxy/BypassProxyService.kt`, `UpstreamClientResources.kt` |
| OpenAI normalization | `protocol/openai/*` |
| Bounded request source | `source/*` |
| Policy loading и engine | `policy/config/*`, `policy/selection/*`, `policy/execution/*`, `policy/engine/*` |
| PII detector и windowing | `detectors/pii/*`, `windowing/*` |
| Logs, traces и metrics | `gateway/proxy/ShadowAuditLogger.kt`, `gateway/tracing/*`, `gateway/metrics/*` |
