# VIG-30: External Bearer identity extractor

- **ID:** `VIG-30`
- **Тип:** Issue
- **Статус:** Done
- **Приоритет:** High
- **Зависит от:** [VIG-35](issue_35_production_identity_mode.md)
- **Блокирует:** [VIG-31](issue_31_identity_lookup_cache.md)
- **Оценка:** 5 дней
- **Уверенность:** Medium

## Цель

Добавить production identity extractor, который по Bearer token получает у
внешней системы пользователя и его группы для policy matching.

## Известный контекст

- VIG-35 выбрал три реализации общего `BearerIdentityExtractor`: `DUMMY`,
  `JWT` и `EXTERNAL`. Обязательный startup selector выбирает ровно одну;
  aliases, fallback, runtime switching и композиция modes отсутствуют.
- Общий extraction contract становится async и cancellation-aware. `DUMMY` и
  JWT завершают его локально, а External завершает результатом lookup. Точный
  JVM type и HTTP-client integration принадлежат этой issue.
- `EXTERNAL` разрешён в `development`, `test` и `production`. Его настройки
  обязательны только при выбранном `EXTERNAL`; settings любого невыбранного
  identity mode дают startup error с exit code `2`.
- Bearer token принадлежит конечному LiteLLM/LLM service и передаётся upstream
  byte-for-byte.
- Vigilant временно использует token только для identity lookup; token не
  попадает в audit, logs, metrics, traces или errors.
- Request без доступной identity при cache miss получает `503` и не доходит до
  upstream.
- Extractor возвращает user/groups; policy groups match exact name или `*` по
  правилу `ANY`.

## Принятый provider protocol

VIG-30 определяет минимальный versioned HTTP contract для trusted Bridge
service и не зависит от Keycloak, vendor SDK или discovery. После успешной
проверки общего single Bearer header contract External extractor отправляет
один запрос:

```http
POST <configured identity endpoint>
Authorization: Bearer <original token>
Accept: application/json
Content-Length: 0
```

Request body отсутствует. `<original token>` означает credential из входного
Bearer header без декодирования или преобразования token bytes. Остальные
client headers, query, body и request context внешнему identity service не
передаются.

Успешный provider response использует status `200`, media type
`application/json` и следующий minimal shape:

```json
{"user":"normalized-user","groups":["group-a","group-b"]}
```

Root обязан быть JSON object без duplicate keys. `user` является обязательным
string, `groups` является обязательным array of strings; пустой array разрешён.
Оба поля проходят существующие normalization и grammar `NormalizedIdentity`.
Blank или invalid value, non-string group, больше 128 unique groups и duplicate
после normalization дают provider protocol failure. Неизвестные top-level
fields игнорируются для additive compatibility. Отсутствующий `groups` не
преобразуется молча в empty set.

Валидированные `user` и `groups` преобразуются в один immutable
`NormalizedIdentity` перед policy matching. Точная public failure matrix
зафиксирована ниже.

## Transport trust

Bridge является trusted service внутри deployment boundary. Его endpoint
задаётся одним absolute `http://` или `https://` URL; plain HTTP разрешён во
всех environments, включая `production`. Vigilant не добавляет обязательный
TLS, mTLS, certificate settings или отдельную service credential. Защита
соединения и сетевой периметр принадлежат deployment.

Extractor обращается только к exact configured endpoint. Redirects не
follow-ятся: любой `3xx` обрабатывается как provider failure. Так Bearer token
не получает второго неявного routing path.

## Failure contract

Общий Bearer header boundary выполняется до Bridge lookup. Missing или
non-Bearer Authorization возвращает существующий `401 authentication_required`
с Bearer challenge; duplicate, malformed или пустой Bearer token возвращает
существующий `400 invalid_identity`. В этих случаях Bridge не вызывается.

Только valid `200 application/json` с корректными `user` и `groups` даёт
identity success. Любой `3xx`, `4xx` или `5xx`, включая Bridge `401`/`403`,
malformed JSON, wrong media type, invalid identity shape, DNS/connect failure,
timeout или premature connection close возвращает один public outcome:

```http
HTTP/1.1 503 Service Unavailable
Retry-After: 1
Content-Type: application/json
```

```json
{"error":{"message":"Identity service unavailable.","type":"server_error","code":"identity_unavailable"}}
```

Bridge status, response headers, body и exception details клиенту не
раскрываются. При любом identity failure request body не читается и LLM
upstream не вызывается. Client cancellation остаётся cancellation и не
преобразуется в HTTP response.

## Attempt and timeout contract

Один extraction выполняет ровно одну Bridge attempt; automatic retries
отсутствуют. Один общий `identity-external-timeout` с environment override
`VIGILANT_IDENTITY_EXTERNAL_TIMEOUT` имеет default `1s` и обязан быть positive
duration в существующем scheduler bound.

Deadline начинается перед получением client connection и охватывает pool
acquisition, connect, request write, response headers и полный response body.
При истечении deadline незавершённый Bridge exchange отменяется и возвращает
`503 identity_unavailable`. Default `1s` является изменяемым safety bound, а не
performance SLO. Retries, circuit breaker и cache/coalescing не входят в
VIG-30; cache и coalescing принадлежат VIG-31.

Bridge считается trusted и обязан возвращать малый identity document, поэтому
VIG-30 не вводит identity-specific response-size setting, custom `64 KiB`
limit или streaming JSON parser. Aggregation использует standard bounded
Armeria client `maxResponseLength` default (`10 MiB` в используемой Armeria
`1.41.0`). Превышение library limit является provider failure и маппится на
`503 identity_unavailable`. Compressed response отдельно не запрещается.

## Startup configuration

```hocon
vigilant {
  identity-mode = "EXTERNAL"
  identity-external-url = "http://bridge.internal/v1/identity"
  identity-external-timeout = 1s
}
```

Environment overrides используют `VIGILANT_IDENTITY_EXTERNAL_URL` и
`VIGILANT_IDENTITY_EXTERNAL_TIMEOUT`. URL обязателен только в `EXTERNAL`, не
имеет default и должен быть absolute `http://` или `https://` URI с host. Path
и query разрешены; fragment и user info запрещены. Timeout имеет default `1s`
и проходит общую positive-duration validation.

`identity-external-*` settings запрещены в `DUMMY`/`JWT`; Dummy и JWT settings
запрещены в `EXTERNAL`. Любое нарушение даёт safe startup failure с exit code
`2`. Startup не обращается к Bridge и не выполняет health check.

## Async lifecycle contract

Общий interface принимает exact форму:

```kotlin
fun interface BearerIdentityExtractor {
    fun extract(headers: RequestHeaders): CompletableFuture<IdentityExtractionResult>
}
```

Gateway инициирует `extract` на существующем blocking-safe request executor.
`DUMMY` завершает `completedFuture`; JWT выполняет текущую локальную validation
и завершает тот же future; `EXTERNAL` связывает future с asynchronous Armeria
Bridge response. До successful completion request body не читается.

Client cancellation отменяет extraction future и активный Bridge exchange.
Timeout отменяет Bridge exchange и завершает lookup как
`identity_unavailable`. Graceful shutdown позволяет уже принятому lookup
завершиться в пределах его `1s` deadline; forced shutdown отменяет его. Гонка
success, timeout, client cancellation и shutdown публикует ровно один terminal
result. После любого terminal path raw token не остаётся в application-owned
state.

## Safe observability

External lookup не создаёт отдельный application log на каждый request. Один
CLIENT span `vigilant.identity.external.lookup` является child существующего
request inspection span. Он и metrics используют только finite outcome:
`success`, `provider_status`, `invalid_response`, `timeout`,
`transport_error` или `cancelled`. Failure span получает `ERROR`, кроме client
cancellation.

Metrics содержат counter `vigilant.identity.external.lookups` и histogram
`vigilant.identity.external.lookup.duration`. Разрешённые attributes:
`identity.mode=EXTERNAL`, finite outcome и Bridge response status class `2xx`,
`3xx`, `4xx` или `5xx`, когда headers получены. Active-lookups gauge не
добавляется; итоговый client `503` остаётся виден в existing gateway metrics.

Endpoint, token, Authorization, `user`, groups, Bridge response body, content
preview и raw exception запрещены в logs, metrics и traces. Provider failure
не вызывает `Span.recordException`; наружу и в telemetry выходит только safe
typed category.

## Outbound client ownership

Existing `UpstreamClientResources` расширяется и переименовывается в
application-scoped `OutboundClientResources`. Он владеет одним Armeria
`ClientFactory`, LLM upstream `WebClient` и, только в `EXTERNAL` mode, отдельным
Bridge `WebClient`. Connection pools разделены Armeria по endpoint, но network
threads и shutdown lifecycle имеют одного owner.

Bridge client применяет свой общий `identity-external-timeout`, а LLM client
сохраняет существующие upstream timeout semantics. Отдельный Bridge executor
и второй `ClientFactory` не создаются. После server drain сначала завершаются
или отменяются request/identity/inspection tasks, затем общий factory
закрывается ровно один раз; `DUMMY`/JWT startup не создаёт Bridge client.

## Concurrent lookup admission

External extractor имеет fair-free immediate semaphore, размер которого равен
существующему `inspection-max-concurrent-request-sources`; отдельная identity
setting не добавляется. Permit берётся непосредственно перед Bridge call. Если
permit недоступен, request не попадает в unbounded queue, Bridge не вызывается,
а gateway сразу возвращает `503 identity_unavailable`.

Permit освобождается ровно один раз при success, provider failure, invalid
response, timeout, client cancellation и shutdown. `DUMMY` и JWT semaphore не
используют. После VIG-31 cache hit не требует permit, а cache miss проходит
обычный Bridge admission. Safe observability получает дополнительный finite
outcome `overloaded`.

## Component boundaries

`ExternalIdentityExtractor` является третьей реализацией общего
`BearerIdentityExtractor`. Он применяет shared `BearerHeaderParser`, отклоняет
local header failures и передаёт только transient non-empty token во внутренний
async seam:

```kotlin
fun interface ExternalIdentityLookup {
    fun lookup(token: String): CompletableFuture<ExternalIdentityLookupResult>
}
```

Lookup result является safe sealed result: `Resolved(NormalizedIdentity)` либо
`Unavailable(ExternalIdentityFailureCode)`. Finite failure codes соответствуют
`provider_status`, `invalid_response`, `timeout`, `transport_error` и
`overloaded`; cancellation завершает future cancellation, а не
`Unavailable`. `BridgeIdentityClient` реализует lookup и владеет HTTP
protocol, response parsing, deadline, semaphore и lookup observability.

VIG-31 позже вставляет `CachingExternalIdentityLookup` decorator между
`ExternalIdentityExtractor` и `BridgeIdentityClient`. Cache не получает
`RequestHeaders`. Generic provider registry, несколько Bridge implementations
и plugin API не создаются.

## Primary test seam

Основной public seam использует real Armeria client, Vigilant gateway, trusted
Bridge test server и LLM upstream server на `http(0)`. Controlled request-body
publisher независимо сообщает первый demand, Bridge/upstream независимо
записывают принятые method/path/headers/body, а latches удерживают timeout и
cancellation transitions без `sleep`.

E2E success доказывает exact Bridge request, normalized policy identity,
отсутствие client-body demand до identity success, последующий LLM handoff и
byte-for-byte original Authorization upstream. Каждая Bridge failure family
доказывает exact public `503`, отсутствие body demand/upstream call и safe
telemetry. Cancellation подтверждается наблюдением отмены на Bridge server;
metrics/spans читаются из in-memory OTel SDK.

Pure tests отдельно покрывают полный Bridge JSON и startup configuration
matrix. Regression cases `DUMMY`/JWT используют тот же gateway boundary. Один
packaged `MainKt` E2E выбирает `EXTERNAL` через startup config, вызывает real
Bridge и достигает LLM upstream после success. После этих автоматических
evidence ручной проверки не остаётся.

## Acceptance matrix

Каждая строка ниже является обязательным отдельным test case. Параметризованный
test разрешён, только если отчёт сохраняет имя каждого входа. `Bridge calls`
считает начатые HTTP exchanges, `body demand` наблюдается controlled inbound
publisher, а `upstream calls` записывает отдельный LLM test server.

### A. Startup configuration и selection

| ID | Stimulus | Обязательный результат |
|---|---|---|
| `CFG-01` | `development` + `EXTERNAL` + valid `http://` URL | Startup успешен, выбран только External extractor |
| `CFG-02` | `test` + `EXTERNAL` + valid `https://` URL | Startup успешен, выбран только External extractor |
| `CFG-03` | `production` + `EXTERNAL` + valid `http://` URL | Startup успешен; trusted deployment допускает plain HTTP |
| `CFG-04` | `production` + `JWT` | Existing JWT startup остаётся успешным |
| `CFG-05` | `production` + `DUMMY` | Safe startup error, process exit code `2` |
| `CFG-06` | mode отсутствует, имеет alias, неверный case или неизвестное значение | Safe startup error, process exit code `2`; default/fallback отсутствует |
| `CFG-07` | `EXTERNAL` без URL | Safe startup error, process exit code `2` |
| `CFG-08` | URL relative, без host, не `http`/`https`, с user info или fragment | Каждый вариант даёт safe startup error, process exit code `2` |
| `CFG-09` | Absolute HTTP(S) URL с path и query | Значение принимается и используется без rewrite |
| `CFG-10` | Timeout отсутствует | Effective timeout равен `1s` |
| `CFG-11` | Valid timeout задан в HOCON, затем переопределён env | Env имеет существующий приоритет и задаёт effective timeout |
| `CFG-12` | Timeout malformed, zero или negative | Каждый вариант даёт safe startup error, process exit code `2` |
| `CFG-13` | Любая `identity-external-*` setting в `DUMMY` или `JWT` | Safe startup error, process exit code `2` |
| `CFG-14` | Любая Dummy/JWT-specific setting в `EXTERNAL` | Safe startup error, process exit code `2` |
| `CFG-15` | `DUMMY` или `JWT` startup | Bridge `WebClient` и External semaphore не создаются |
| `CFG-16` | `EXTERNAL` startup при недоступном Bridge | Startup успешен; health check отсутствует |

### B. Bearer boundary

| ID | Stimulus | HTTP result | Bridge/body/upstream |
|---|---|---|---|
| `AUTH-01` | Authorization отсутствует | Existing exact `401 authentication_required` и Bearer challenge | `0 / 0 / 0` |
| `AUTH-02` | Well-formed non-Bearer scheme | Existing exact `401 authentication_required` и Bearer challenge | `0 / 0 / 0` |
| `AUTH-03` | Duplicate Authorization | Existing exact `400 invalid_identity` | `0 / 0 / 0` |
| `AUTH-04` | Malformed scheme separator | Existing exact `400 invalid_identity` | `0 / 0 / 0` |
| `AUTH-05` | `Bearer` без credential или только с whitespace credential | Existing exact `400 invalid_identity` | `0 / 0 / 0` |
| `AUTH-06` | Mixed-case Bearer scheme и non-empty opaque token | Lookup получает exact token после единственного scheme separator | `1 / 0 до success / 1 после success` |

`AUTH-05` является правилом External extractor. Существующий token-agnostic
Dummy contract и offline JWT validation не расширяются и не ослабляются этой
issue.

### C. Bridge request и success

| ID | Stimulus | Обязательный результат |
|---|---|---|
| `REQ-01` | Один valid External request | Bridge получает exact configured path/query, `POST`, exact `Authorization: Bearer <token>`, `Accept: application/json`, `Content-Length: 0` и empty body |
| `REQ-02` | Client прислал дополнительные headers, query и body | Ни один из них не копируется в Bridge request; до identity success нет body demand |
| `OK-01` | `200 application/json` с valid user/groups | Создан один immutable normalized identity, выбрана соответствующая policy, затем исходный Authorization byte-for-byte передан LLM upstream |
| `OK-02` | `200 application/json; charset=utf-8` | Та же success semantics |
| `OK-03` | Valid empty `groups` | Success с empty immutable group set |
| `OK-04` | Valid values, требующие canonical normalization | User/group values нормализованы существующим `NormalizedIdentity` contract |
| `OK-05` | Valid identity плюс unknown top-level fields | Success; unknown fields игнорируются |

### D. Provider failures

Каждая строка возвращает exact `503 identity_unavailable` с
`Retry-After: 1`, не начинает client body demand и LLM upstream handoff, не
раскрывает Bridge status, headers, body или exception.

| ID | Stimulus | Safe failure code/outcome |
|---|---|---|
| `FAIL-01` | Каждый final status `201..599` | `provider_status`; параметризованные cases покрывают каждый допустимый code, включая все 3xx/4xx/5xx; status-class attribute появляется только после headers |
| `FAIL-02` | Missing или non-JSON media type | `invalid_response` |
| `FAIL-03` | Invalid UTF-8, malformed JSON, duplicate key или non-object root | Каждый вариант: `invalid_response` |
| `FAIL-04` | Missing, non-string, blank или grammar-invalid `user` | Каждый вариант: `invalid_response` |
| `FAIL-05` | Missing/non-array `groups` или хотя бы один non-string/blank/invalid group | Каждый вариант: `invalid_response` |
| `FAIL-06` | Groups duplicate после normalization | `invalid_response` |
| `FAIL-07` | 129 unique valid normalized groups | `invalid_response` |
| `FAIL-08` | Response превышает standard Armeria `10 MiB` aggregate limit | `invalid_response` |
| `FAIL-09` | Premature close после incomplete response | `transport_error` |
| `FAIL-10` | Deterministic connect failure через canonical reserved unreachable-port fixture | `transport_error` |
| `FAIL-11` | Whole-exchange deadline истёк на acquisition/connect/write/headers/body phase | Каждый удержанный phase: `timeout`; exchange отменён |

### E. Admission, cancellation и lifecycle

| ID | Stimulus | Обязательный результат |
|---|---|---|
| `LIFE-01` | `N` lookups удерживают все permits; приходит `N+1` | `N+1` немедленно получает `503`, outcome `overloaded`, Bridge не вызывается и очередь не создаётся |
| `LIFE-02` | Permit holder завершается success, каждым provider failure, timeout, transport failure, client cancellation или forced shutdown | Permit освобождён ровно один раз; следующий lookup admitted |
| `LIFE-03` | Client отменяет request во время Bridge exchange | Extraction и Bridge exchange отменены, public HTTP response не синтезирован, outcome `cancelled` |
| `LIFE-04` | Graceful shutdown при уже admitted lookup | Lookup может завершиться только в пределах своего deadline, затем общий factory закрывается один раз |
| `LIFE-05` | Forced shutdown при active lookup | Lookup/exchange отменены до закрытия общего factory |
| `LIFE-06` | Контролируемая гонка success, timeout, cancellation и shutdown | Публикуется ровно один terminal result, downstream side effect не дублируется, application-owned state не удерживает token |
| `LIFE-07` | Lookup завершён любым terminal path | Raw token отсутствует в result, queued task, exception, metric, span и application log |

`N` равен effective `inspection-max-concurrent-request-sources`. Fixtures
синхронизируются на захвате permit и наблюдаемом terminal event через bounded
waits, без `sleep` и без повторного использования освобождённого ephemeral
port.

### F. Safe observability

| ID | Stimulus | Обязательный результат |
|---|---|---|
| `OBS-01` | Каждый outcome `success`, `provider_status`, `invalid_response`, `timeout`, `transport_error`, `overloaded`, `cancelled` | Ровно один counter sample, один duration record и один CLIENT span с `identity.mode=EXTERNAL` и exact finite outcome |
| `OBS-02` | Bridge headers получены с 2xx/3xx/4xx/5xx | Span/metrics содержат только соответствующий finite status class; transport/cancel/overload не изобретают status class |
| `OBS-03` | Любой failure кроме cancellation | Span status `ERROR`; cancellation не помечается как error |
| `OBS-04` | Success и все failure bodies содержат sentinels | Ни token, Authorization, endpoint, user, groups, response body/preview, raw exception, ни sentinels не встречаются в logs, metrics или traces; `recordException` не вызывается |
| `OBS-05` | Identity failure становится client `503` | Existing gateway metrics также наблюдают итоговый `503`; active-lookups gauge отсутствует |

### G. Packaged и regression evidence

| ID | Stimulus | Обязательный результат |
|---|---|---|
| `E2E-01` | Installed `MainKt` с `EXTERNAL`, real Bridge и LLM servers | Config выбирает External; successful lookup достигает LLM upstream с exact Authorization |
| `REG-01` | Existing Dummy gateway matrix после async migration | Все прежние Dummy HTTP/policy/upstream outcomes неизменны |
| `REG-02` | Existing offline JWT valid/invalid/rotation gateway matrix после async migration | Все прежние JWT HTTP/policy/upstream outcomes неизменны; сеть для JWT отсутствует |

## TDD delivery slices

Срезы выполняются последовательно в RED-GREEN-REFACTOR. В каждом срезе сначала
добавляется один минимальный public или component-level failing test, затем
минимальный production code. Полные matrix cases добавляются рядом с первым
срезом, который вводит их behavior, а не постфактум.

1. **Async common boundary.** RED: delayed test extractor доказывает no body
   demand и cancellation propagation. GREEN: изменить общий interface,
   orchestration и локально completed Dummy/JWT implementations. Сразу вернуть
   `REG-01`/`REG-02` в green.
2. **Strict startup selection.** RED: `CFG-01..16`. GREEN: External settings,
   URI/timeout/mode validation и DI branch без startup I/O.
3. **Bridge success tracer bullet.** RED: `AUTH-01..06`, `REQ-01..02` и
   `OK-01`. GREEN: `ExternalIdentityExtractor`, internal lookup seam,
   application-owned client и minimal HTTP exchange.
4. **Strict response parser.** RED: `OK-02..05` и `FAIL-02..08`. GREEN:
   duplicate-detecting aggregate JSON parser и canonical identity validation.
5. **Stable transport failure mapping.** RED: `FAIL-01`, `FAIL-09..11`.
   GREEN: typed failures, one whole-exchange deadline и exact public `503`.
6. **Admission and cancellation.** RED: `LIFE-01..07`. GREEN: immediate
   semaphore, once-only completion/release and shutdown wiring.
7. **Observability.** RED: `OBS-01..05`. GREEN: finite metrics/span adapter
   without per-lookup application logging or sensitive attributes.
8. **Packaged evidence.** RED: `E2E-01`. GREEN: final `MainKt` composition,
   installed distribution configuration and lifecycle. Затем выполняется
   contract sweep по Dummy/JWT, docs и diagrams.

## План изменений по файлам

Имена новых файлов являются целевыми. Допустимо объединить private helper с
указанным owner, но нельзя менять component boundaries или создавать generic
provider registry.

### Production

- `gateway/identity/DummyIdentityExtractor.kt`: async
  `BearerIdentityExtractor`, shared header parser и completed Dummy result.
- `gateway/identity/OfflineJwtIdentityExtractor.kt`: тот же async contract без
  нового executor или network I/O.
- `gateway/identity/ExternalIdentityExtractor.kt`: External Bearer validation
  и преобразование safe lookup result в общий extraction result.
- `gateway/identity/ExternalIdentityLookup.kt`: internal async seam, result и
  finite failure codes.
- `gateway/identity/BridgeIdentityClient.kt`: exact HTTP request, aggregate
  parser, timeout, cancellation, semaphore и observability terminal ownership.
- `gateway/config/IdentityConfig.kt` и `gateway/config/AppConfig.kt`: External
  settings, env mappings, validation и effective default.
- `gateway/AppComponent.kt`: strict three-way composition; Bridge objects
  существуют только для `EXTERNAL`.
- `gateway/proxy/PiiShadowProxyService.kt`: compose async extraction before
  body demand and propagate cancellation/one terminal result.
- `gateway/proxy/InspectionHttpResponses.kt` и
  `gateway/proxy/OpenAiErrorResponses.kt`: exact safe identity `503`.
- Переименовать `gateway/proxy/UpstreamClientResources.kt` в
  `gateway/proxy/OutboundClientResources.kt`; сохранить один `ClientFactory`,
  отдельные upstream/Bridge `WebClient` и once-only close.
- `gateway/proxy/UpstreamWebClients.kt`: reuse existing upstream construction;
  добавить Bridge client construction без изменения upstream timeouts.
- `gateway/metrics/MetricsService.kt` и существующий tracing owner: добавить
  только согласованные identity instruments/span lineage.
- `gateway/Main.kt`: сохранить shutdown order server drain, task completion,
  inspection resources, общий outbound factory.

### Tests and fixtures

- `gateway/identity/DummyIdentityExtractorTest.kt` и
  `OfflineJwtIdentityExtractorTest.kt`: async regression contract.
- Новый `gateway/identity/ExternalIdentityExtractorTest.kt`: `AUTH-*` и safe
  lookup-result mapping без HTTP.
- Новый `gateway/identity/BridgeIdentityClientTest.kt`: `REQ-*`, `OK-*`,
  `FAIL-*`, `LIFE-*` и `OBS-*` через real Armeria Bridge.
- `gateway/config/AppConfigLoadingTest.kt`: полная `CFG-*` matrix.
- `gateway/AppComponentIdentityTest.kt`: exact selected implementation и
  отсутствие Bridge resources в Dummy/JWT.
- `gateway/proxy/PiiShadowProxyServiceTest.kt` или отдельный focused
  `ExternalIdentityGatewayTest.kt`: основной public gateway/Bridge/upstream,
  no-demand, policy, error и cancellation seam.
- `gateway/GatewayTestFixture.kt`: переиспользовать controlled demand,
  in-memory OTel и bounded observation helpers, не дублировать ordering rules.
- `gateway/GatewayProcessFixture.kt` и `gateway/MainTest.kt`: `E2E-01`, existing
  reserved non-ephemeral port strategy и startup exit-code cases.

### Documentation and work-item closure

- Обновить `README.md`, `CLAUDE.md`, `docs/configuration.md`,
  `docs/runtime-contract.md`, `docs/observability.md` и
  `docs/requirements-coverage.md` только после реализации behavior.
- Обновить `docs/diagrams/runtime-components.puml` и
  `docs/diagrams/request-inspection-sequence.puml`; если tracing diagram
  перечисляет child spans, добавить External CLIENT span и регенерировать SVG.
- При завершении синхронно обновить эту issue, VIG-31 dependency notes,
  `spec/MVP_FUNCTIONS.md` и `spec/WORK_ITEMS.md`. Не ставить `Done` без всех
  dynamic evidence, `./gradlew build` и `./gradlew validateWorkItems`.

## Definition of Done

- [x] Все `CFG-*`, `AUTH-*`, `REQ-*`, `OK-*`, `FAIL-*`, `LIFE-*`, `OBS-*`,
  `E2E-*` и `REG-*` cases имеют автоматическое evidence с независимым oracle.
- [x] External использует ровно один Bridge attempt, exact trusted HTTP
  protocol и один whole-exchange timeout; client body/upstream остаются
  нетронутыми до identity success.
- [x] Async cancellation, shutdown и permit ownership завершаются ровно один
  раз на каждом lifecycle path без retention token.
- [x] Safe failure и observability contracts не раскрывают credentials,
  identity, endpoint, Bridge payload, exact status или raw exceptions; telemetry
  содержит только разрешённый status class.
- [x] Dummy/JWT semantics и LLM upstream timeout/authorization behavior не
  изменились; VIG-31 extension seam подтверждён без реализации cache.
- [x] Все изменённые Kotlin declarations, test methods и lifecycle helpers
  имеют актуальный KDoc, перечитанный после финального ownership refactor.
- [x] Architecture/configuration/runtime/observability docs и generated UML
  отражают фактическую реализацию без будущего behavior.
- [x] `./gradlew build` и `./gradlew validateWorkItems` проходят после полного
  pre-verification contract sweep и closure summary.

## Не входит

- Cache результатов lookup, JWT verification, token exchange, refresh и
  изменение upstream authorization.
- Удаление или изменение semantics существующих `DUMMY` и JWT modes,
  автоматический fallback между implementations и hot reload mode.
- Policy reaction, PII detection или enforcement transport.
- Provider discovery, redirects, health checks, retries, circuit breaker,
  multiple providers, plugin API, hot reload или runtime mode switching.
- Identity-specific response-size setting, streaming response parser, второй
  `ClientFactory`, отдельный Bridge executor или новая concurrency setting.
- TLS/mTLS/certificate/service-credential management внутри Vigilant.

## Решение о готовности

Provider protocol, failure/configuration matrix, component ownership,
concurrency/cancellation lifecycle, safe observability и primary E2E seam
реализованы. Все acceptance cases имеют dynamic evidence, contract sweep не
оставил stale current-runtime claims, а финальные `build` и
`validateWorkItems` проходят.

## Ambiguity Report

```text
Goals: 0.0; Acceptance: 0.0; Boundaries: 0.0; Alternatives: 0.0;
Assumptions: 0.0; Aggregate: 0.0. Done.
```
