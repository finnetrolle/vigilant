# Архитектура Vigilant

## Текущий runtime

Vigilant - однопроцессный HTTP gateway на Kotlin и Armeria. Текущий
production increment поддерживает request-side PII inspection для OpenAI Chat
Completions в shadow mode. Он проверяет запрос и best-effort публикует
безопасную audit lifecycle pair, но не блокирует и не изменяет payload.
Upstream response полностью удерживается в памяти и проверяется existing
Chat Completions JSON/SSE parser до раскрытия клиенту.

Поддерживаемый маршрут:

- `POST /v1/chat/completions`;
- `Content-Type: application/json`, параметры media type допускаются;
- schema-tolerant Chat Completions JSON, который можно однозначно разобрать для
  проверки.

Response policy evaluation, response audit pair, `MASK`/`BLOCK`, OpenAI
Responses API и другие enforcement reactions пока не подключены к runtime.
Подробные границы HTTP-контракта приведены в
[runtime contract](runtime-contract.md).

## Путь запроса

Архитектурные схемы оформлены в нотации UML 2.0:

- [диаграмма компонентов исполняемой системы](diagrams/runtime-components.puml)
  показывает границу процесса, внутренние компоненты, вышестоящий сервер и
  container-managed stdout;
- [последовательность проверки запроса](diagrams/request-inspection-sequence.puml)
  показывает отсутствие audit до detector execution, stdout lifecycle pair,
  точное воспроизведение и атомарно удержанный response;

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

Затем выбранная реализация общего `BearerIdentityExtractor` выполняется на
blocking-safe request executor. Development/test `DummyIdentityExtractor`
проверяет только representation и возвращает configured normalized identity.
`OfflineJwtIdentityExtractor` локально выбирает pinned RSA public key по exact
`kid`, проверяет RS256 signature, issuer, audience и time claims, а только
после этого нормализует `sub`/`groups`. Он не выполняет discovery, key fetch
или identity lookup. Любой reject предшествует body demand и upstream call.
Raw token не сохраняется, а принятый Authorization передаётся upstream без
изменения.

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

Полная карта полей, непроверяемые части, структурные ограничения и категории
fail-closed описаны в
[контракте запросов Chat Completions](openai-chat-completions.md).

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
использует thread-local или global map. Retained response protocol validation
пока не запускает response policy context или detector execution.

`PolicySelector` выбирает enabled policies по точному значению или wildcard,
затем одновременно применяет явные overrides. `PolicyEngine` дедуплицирует
detector execution между policies, соблюдает deadline каждой policy и строит
детерминированное объяснение решения.

Строгая структура HOCON, точное сопоставление и ограничения теневого режима при
запуске описаны в [руководстве по политикам](policies.md).

Policy domain поддерживает `ALLOW`/`BLOCK` и transformation plans, но startup
validation текущего shadow increment разрешает только `ALLOW` без
transformations для всех outcomes. Поэтому runtime всегда пересылает исходный
request, включая случаи `DETECTED`, `CLEAN` и `INSPECTION_GAP`. Detector error
фиксируется как audit outcome `ERROR`, но при валидной shadow policy также не
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
metadata, но не matched text. PII-free `WindowedInspectionExecutor` разбивает
фрагменты больше одновызовного лимита на перекрывающиеся UTF-8-safe windows,
переводит результаты в координаты исходного fragment и детерминированно
дедуплицирует их. `WindowedFastPiiExecutor` остаётся тонким adapter-ом: он
предоставляет capability, exhaustive вызов `FastPiiDetector`, semantic identity,
сравнение PII metadata и канонический порядок.

Поддерживаемые типы, семантика свидетельств, возможности оконной обработки,
свидетельства качества и явные ограничения описаны в
[руководстве по обнаружению PII](pii-detection.md).

### 6. Best-effort audit, request replay и retained response

Complete-source orchestration выполняет синхронный
`ShadowInspectionWorkflow` на существующем blocking-safe inspection executor.
Он последовательно открывает единственный parser view, собирает и сохраняет
request context и оценивает каждый независимый text fragment. После selection
и непосредственно перед первым detector execution `ShadowAuditLogger`
best-effort публикует `policy.analysis_started`. После terminal outcome и до
transport handoff он публикует `policy.analysis_completed` с safe aggregate
coverage/counts и stable ERROR code либо successful `reaction=ALLOW`.

Оба event идут через existing Logback `AsyncAppender` с `neverBlock=true`.
Payload, matched text, locators, identity, session, headers и raw
user-controlled correlation values в них не попадают. Эти request-analysis records
остаются только в stdout; workflow не ждёт queue delivery или stdout write.
Slow/full/throwing logging sink не меняет response, readiness или upstream
handoff. Application-owned audit storage, delivery worker и persistence
configuration отсутствуют; retention, rotation и delivery stdout принадлежат
container runtime и deployment.

`ReplayReadyRequest` инкапсулирует demand-driven publisher. Его `transferTo`
допускает ровно один transport handoff. `close()` до
handoff и synchronous callback failure освобождают source; после принятого
handoff owner освобождается только terminal signal replay. Это исключает окно
без владельца между workflow и transport.

Во время handoff исходные end-to-end headers, включая accepted Authorization,
и исходные bytes передаются в `BypassProxyService`. Этот transport слой:

- переписывает scheme, authority и base path под upstream URL;
- удаляет стандартные hop-by-hop headers и headers из `Connection`;
- добавляет effective session/trace context;
- использует application-owned connection pool;
- преобразует connection/transport failure в stable `502`, timeout - в
  stable `504`;
- не агрегирует response body и сохраняет streaming/backpressure, включая SSE.

`RetainedResponseHandler` принимает transport response в отдельный
`RetainedResponseSource`. Source требует у upstream по одному body item,
копирует exact bytes только в RAM и скрывает upstream status, headers и body
до protocol completion. Ordinary JSON завершается по end-of-stream, а SSE
только отдельным standalone `data: [DONE]`. Existing response parser работает
на blocking-safe executor. Valid response любого upstream status, включая
`4xx` и `5xx`, replay-ится byte-for-byte по client demand. Malformed JSON/SSE,
missing или malformed terminal event и upstream interruption очищают source и
возвращают exact VIG-29 `502 invalid_upstream_response` без partial disclosure.

Retained response не имеет application-level byte limit, shared quota, disk
spill, temporary file или persistent representation. Success, protocol
failure, client cancellation, replay cancellation и forced shutdown очищают
owned buffers и references. JVM heap sizing и OOM policy остаются
deployment-owned. Этот increment не запускает response detectors, не применяет
response policy reaction и не публикует response audit pair.

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
| Response bodies | `RetainedResponseSource` | available JVM heap, one-item upstream demand, terminal cleanup |
| Inspection orchestration | `InspectionResources` | virtual-thread executor |
| Fast PII CPU | `InspectionResources` | bounded fixed-size pool и bounded queue |
| Upstream connections | `UpstreamClientResources` | dedicated Armeria `ClientFactory` |
| Traces и metrics | `SdkTracerProvider`, `SdkMeterProvider` | process-wide providers, stdout exporters |

## Startup и shutdown

`MainKt` через Metro создаёт dependency graph и до старта server eagerly
проверяет application configuration и immutable policy snapshot. Ошибка
печатается безопасно в stderr и завершает процесс с code `2`.

При `SIGTERM` shutdown hook выполняет порядок:

1. readiness становится `503`, новый traffic и новый response-analysis handoff запрещаются;
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
| Response retention и lifecycle | `gateway/proxy/RetainedResponseHandler.kt`, `gateway/proxy/ResponseAnalysisLifecycle.kt`, `source/RetainedResponseSource.kt` |
| Transport proxy | `gateway/proxy/BypassProxyService.kt`, `UpstreamClientResources.kt` |
| OpenAI normalization | `protocol/openai/*` |
| Bounded request source | `source/*` |
| Policy loading и engine | `policy/config/*`, `policy/selection/*`, `policy/execution/*`, `policy/engine/*` |
| Generic windowing core | `windowing/WindowedInspectionModels.kt`, `windowing/WindowedInspectionExecutor.kt` |
| PII detector и windowing adapter | `detectors/pii/*`, `windowing/WindowedFastPiiExecutor.kt`, `windowing/WindowedPiiModels.kt` |
| Logs, traces и metrics | `gateway/proxy/ShadowAuditLogger.kt`, `gateway/tracing/*`, `gateway/metrics/*` |
