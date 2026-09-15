# VIG-42: Безопасная диагностика transport failures в traces

- **ID:** `VIG-42`
- **Тип:** Issue
- **Статус:** Ready for implementation
- **Приоритет:** P1
- **Зависит от:** нет
- **Блокирует:** нет
- **Оценка:** 3-4 инженерных дня с HTTP/process evidence
- **Уверенность:** средняя
- **Архитектурный риск:** High - внешний telemetry contract и несколько span owners.

## Контекст и объяснение риска

`BypassProxyService.exchange` передаёт исходный transport cause в CLIENT
`Span.recordException`; `TracingService.completeExchange` делает это для
SERVER при некоторых failure paths. OpenTelemetry exception event может
содержать exception type, исходное message и stack trace с цепочкой causes.
Сообщение исключения не является безопасным по происхождению: нижний HTTP
слой или иной dependency может включить URL/query, headers, фрагмент данных
либо внутренние подробности. Очищенные application logs не очищают этот
отдельный telemetry channel. OTLP stdout export включён по умолчанию.

На `d2e272c` подтверждены вызовы без sanitization. Реальная утечка секрета и
полный HTTP путь, позволяющий вызвать её пользовательскими данными, пока не
воспроизведены. Нельзя подменять эту границу утверждением о доказанной утечке.
[Privacy owner](../requirements/observability.md#privacy-by-channel) прямо
запрещает raw exception event/message/stack в traces.

## Scope lock

1. **Наблюдаемый продуктовый результат:** экспортируемые SERVER/CLIENT spans сохраняют диагностику transport failure в разрешённых безопасных полях.
2. **Минимальное достаточное решение:** удалить raw exception recording и заменить его одним finite attribute на span затронутого соединения: `timeout`, `cancelled` или `transport_error`. `timeout` и `transport_error` получают span status `ERROR`, `cancelled` получает `UNSET`. CLIENT описывает upstream exchange, SERVER описывает обмен с клиентом; восстановленный safe HTTP response не переносит upstream failure на SERVER.
3. **Обязательные свойства результата:** никакого message/stack/cause-chain или произвольного exception class; сохранить trace lineage, duration, session/correlation в пределах owner и существующие HTTP status/body/headers. Span status меняется по согласованной семантике категории; отмена не помечается `ERROR`.
4. **Явные non-goals:** новый exporter/collector, хранение traces, deployment chain, sampling redesign, изменения retry/timeout/identity, новые inspection metrics, очистка стороннего telemetry storage. Не менять существующую HTTP error mapping, metrics classification и application log schema; известные gaps других каналов не объявлять закрытыми.
5. **Более сложные альтернативы:** regex redaction raw exception отклоняется как ненадёжная для неизвестных dependency messages; отключение tracing лишает разрешённой диагностики; новый asynchronous pipeline не нужен. Полное отсутствие failure attributes не сохраняет выбранное различие между timeout, cancellation и transport error. Отдельная классификация DNS, отказа подключения и обрыва ответа не требуется для согласованной глубины диагностики.
6. **Условие пересмотра:** нужная диагностика не выражается безопасными finite полями; сначала согласовать exact schema, не возвращать raw exception как fallback.
7. **Подтверждение:** пользователь 2026-09-10 поручил создать исходный Draft. В четырёх последовательных решениях диалога 2026-09-15 подтвердил три категории, `ERROR` для timeout/transport error, `UNSET` для cancellation, разделение SERVER/CLIENT и правило первого terminal-события. Подтверждены отсутствие transport-категории у SERVER при доставленном safe HTTP response, неизменность завершённого CLIENT при поздней отмене и сохранение HTTP-поведения, lineage и durations. Техническое имя attribute и fixtures ниже конкретизируют эту согласованную границу. Реализация и её runtime evidence этой подготовкой issue не объявляются выполненными.

## Принятые решения

Глубина transport-диагностики согласована 2026-09-15:

| Категория | Смысл | Span status |
|---|---|---|
| `timeout` | Превышено время ожидания transport operation. | `ERROR` |
| `cancelled` | Операция отменена. | `UNSET`, без `ERROR` |
| `transport_error` | Остальные transport failures, включая DNS, отказ подключения и обрыв ответа. | `ERROR` |

Категория не содержит exception message, stack trace, cause chain или имя
exception class. HTTP status/body/headers, trace lineage и durations сохраняются.
Техническое имя единственного нового string attribute: `vigilant.transport.failure`.
Значение присутствует только при transport failure/cancellation данного span;
значения `none`, `success`, `unknown` и пустая строка не используются. Новых
events нет; `exception` events и `exception.*` attributes запрещены.
Status description остаётся пустым, в том числе при `ERROR`. Имена spans,
остальные attributes и links не используются как альтернативный канал для cause.

Семантика статусов подтверждена отдельно 2026-09-15. Отмена остаётся видимой
через категорию, но не считается техническим сбоем. Это намеренное изменение
нынешнего CLIENT поведения, где любой exceptional completion получает `ERROR`.
Статус выбирается из terminal outcome до завершения span; поздний cleanup не
должен пытаться сбросить ранее опубликованный `ERROR` в `UNSET`.

### Владение результатом

Разделение подтверждено 2026-09-15:

- CLIENT описывает только одно начатое обращение к LLM upstream. Завершается
  по terminal upstream response; категория и status публикуются до `end()`.
- SERVER описывает принятый gateway HTTP exchange. Завершается по final
  `RequestLog`; наличие response headers не доказывает успешное завершение.
  Сбой после headers также получает категорию и согласованный span status.
- Успешно доставленный safe `502`/`504` несёт HTTP status на SERVER без
  transport-категории. Исходный upstream failure остаётся на CLIENT.
- Если upstream interruption обрывает уже раскрываемый bypass response,
  SERVER также получает категорию фактической причины обрыва. Служебная
  отмена подписки при cleanup не заменяет исходный timeout/transport failure.
- Отмена клиентом при активном upstream помечает оба затронутых span
  `cancelled` / `UNSET`. Отмена до upstream handoff не создаёт CLIENT.
  После завершения upstream меняется только ещё активный SERVER.
- Обычный завершённый HTTP response, в том числе upstream `4xx`/`5xx`,
  policy BLOCK и safe protocol/inspection rejection, сам по себе не является
  transport failure. HTTP-код не используется для вывода категории; существующая
  status-разметка завершённых exchanges остаётся прежней.
- INTERNAL inspection и External identity CLIENT сохраняют собственные
  contracts; новый attribute на них не добавляется.

### Распознавание причины

Classifier предназначен только для tracing; применение к metrics или HTTP
error mapping изменило бы самостоятельные contracts и не входит в VIG-42.
Разрешены проверки типов исключений и уже известной причины terminal
operation. Запрещены matching message, `toString`, class-name strings и
формирование diagnostics из cause/suppressed details.

При разборе одной причины timeout проверяется раньше cancellation:
`com.linecorp.armeria.common.TimeoutException` сам наследуется от
`com.linecorp.armeria.common.CancellationException` в pinned Armeria 1.41.0.
Поэтому факт cancellation callback без типа причины не означает `cancelled`.

| Вход одной transport failure | Категория |
|---|---|
| Armeria `ResponseTimeoutException`, `WriteTimeoutException`, `DnsTimeoutException`, `RequestTimeoutException`, `StreamTimeoutException` | `timeout` |
| Netty `ConnectTimeoutException`, `ReadTimeoutException`, `WriteTimeoutException`; JDK `SocketTimeoutException`, `java.util.concurrent.TimeoutException` | `timeout` |
| Armeria cancellation без timeout, `CancelledSubscriptionException`, JDK `CancellationException` | `cancelled` |
| Подтверждённая отмена HTTP exchange клиентом, включая закрытие его stream/connection при активной операции | `cancelled` для отменённых операций |
| DNS resolution failure без timeout, connection refused, premature upstream close, неизвестное transport exception | `transport_error` |

`CompletionException`, `ExecutionException` и `UnprocessedRequestException`
являются прозрачными wrappers: underlying category сохраняется. Suppressed
exceptions не участвуют в выборе категории и не экспортируются. Непрозрачный
неизвестный wrapper получает `transport_error`; его вложенная diagnostic cause
не становится основанием угадывать другую причину. Допускается раскрыть не
более 16 последовательных прозрачных wrappers; terminal cause после 16-го
ещё классифицируется. Нужен 17-й переход, отсутствует cause или встретился
цикл по object identity - `transport_error`. Это внутренний защитный предел,
без новой настройки; suppressed graph не обходится.

Для OTel нельзя рассчитывать на поздний сброс `ERROR` в `UNSET`: статус
выбирается после определения terminal outcome. Это следует из
[OTel Set Status](https://opentelemetry.io/docs/specs/otel/trace/api/#set-status).

### Конкурирующие terminal causes

Подтверждено 2026-09-15: первая причина, фактически завершившая операцию,
фиксируется для её span. Поздний timeout, cancellation или cleanup не
переписывает категорию/status и не завершает span повторно. «Первая» относится
к terminal transition владельца операции, а не к порядку вызова export/log
callbacks. Если SERVER и CLIENT завершились по разным причинам, каждый
сохраняет свой результат. Реальные одновременно поступившие причины могут
иметь любого из двух победителей; после выбора результат однозначен.

Обязательные управляемые порядки для тестов: timeout затем cancel; cancel
затем timeout; transport error затем cleanup cancel; success затем cancel;
cancel до upstream handoff. Отдельная race допускает только один из
контрактных terminal outcomes и ровно один finished span на operation.

## Context sources

- `spec/requirements/observability.md#privacy-by-channel`
- `spec/requirements/observability.md#tracing-and-propagation`
- `spec/requirements/observability.md#otlp-output-and-lifecycle`
- `docs/runtime-contract.md#upstream-errors`
- `docs/runtime-contract.md#upstream-timeout-model`
- `spec/requirements/http-gateway.md#inspection-error-matrix`
- `spec/requirements/http-gateway.md#low-level-transport`
- `spec/requirements/http-gateway.md#upstream-failures-and-timeouts`
- `docs/development.md#observability-contract-checks`
- `docs/agent-workflow.md#behavior-first-development-and-selective-tdd`

## Согласованный план реализации

Оператор согласовал план и его запись в issue 2026-09-15. Агент-исполнитель
должен руководствоваться этим планом, [принятыми решениями](#принятые-решения)
и [scope lock](#scope-lock). Существенное отступление от подхода требует
обсуждения с оператором. Имена внутренних helpers, размещение функций внутри
указанных компонентов и конкретный механизм once-only фиксации результата
остаются на усмотрение исполнителя при соблюдении terminal contract.

**Цель:** сохранить безопасную transport-диагностику SERVER/CLIENT spans,
исключив исходные исключения из экспортируемых traces.

**Исходное поведение:**
[BypassProxyService.exchange](../../src/main/kotlin/io/vigilant/gateway/proxy/BypassProxyService.kt)
вызывает `recordException` и ставит `ERROR` при любом exceptional completion,
включая отмену.
[TracingService.completeExchange](../../src/main/kotlin/io/vigilant/gateway/tracing/TracingService.kt)
обрабатывает failure только при отсутствии response headers и пропускает
обрыв уже начавшегося ответа.

### Шаги и порядок

1. **Зафиксировать дефект через настоящий HTTP.** В новом
   `TransportTracePrivacyE2eTest` в tracing package воспроизвести CLIENT failure
   с synthetic sentinels в exception message/cause/suppressed через существующие
   gateway fixtures и внешний `WebClient` seam. Подключить production exporter
   из `buildSdkTracerProvider`: начальный RED D06/E01 должен показывать
   запрещённые diagnostic fields в экспортированном span. Дополнить существующий
   `TracingServiceTest`; естественно GREEN cases сохранить.
2. **Добавить небольшой tracing-only classifier и запись результата.** В
   `src/main/kotlin/io/vigilant/gateway/tracing/` реализовать finite категории,
   один attribute `vigilant.transport.failure` и согласованные statuses с пустым
   description. Следовать [правилам распознавания](#распознавание-причины):
   проверки типов, timeout раньше cancellation, только три transparent wrappers,
   максимум 16 раскрытий и защита от циклов. Отдельный classifier необходим:
   `ProxyRequestOutcome`/`UpstreamFailureObservation` обслуживают самостоятельные
   HTTP/metrics contracts, включая class-derived metric dimension. Их алгоритм
   сохранить. Новый module, injectable service, exporter wrapper, executor или
   очередь не нужны.
3. **Заменить raw exception recording в `BypassProxyService`.** Определять
   CLIENT outcome по terminal upstream operation и публиковать безопасную
   категорию/status до единственного `end()`. Поздняя отмена или cleanup
   сохраняют уже завершённый результат. Routing, HTTP recovery, upstream failure
   log и metrics observations сохраняют текущее поведение.
4. **Разделить HTTP status и transport outcome в `TracingService`.** Завершать
   SERVER по final `RequestLog`, учитывая request/response failure и причину
   отмены также после headers. Удалить `recordException`; сохранить HTTP status,
   durations, correlation и один completion log. Доставленный safe `502/504`
   остаётся без transport-категории; оборванный bypass response получает
   фактическую причину. Для необходимой причинной связи через request-scoped
   tracing context передавать только finite outcome, без diagnostic strings
   или cause graph. Фиксировать причину, завершившую операцию, согласно
   [terminal contract](#конкурирующие-terminal-causes), независимо от порядка
   export/log callbacks. Завершённый CLIENT не изменять при поздней отмене SERVER.
5. **Закрепить экспортный contract и evidence.** Добавить новый
   `TransportTracePrivacyProcessTest` в gateway package через существующий
   `GatewayProcessFixture.launchInstalled`; пометить `process-e2e` и
   зарегистрировать в `ProcessTestInventoryTest`. После получения runtime evidence
   перенести действующий contract в
   [observability owner](../requirements/observability.md#tracing-and-propagation),
   обновить [runtime reference](../../docs/observability.md#tracing),
   [trace coverage](../../docs/requirements-coverage.md#observability-evidence),
   [observability checks](../../docs/development.md#observability-contract-checks)
   и применимый terminal flow в
   [tracing-sequence.puml](../../docs/diagrams/tracing-sequence.puml).
   Добавить актуальный KDoc изменённым и новым Kotlin declarations.

Первый production fix опирается на RED шага 1; общий classifier шага 2
используется обоими span owners в шагах 3-4. Каждый небольшой behavior slice
проверяется вместе со своими примерами до следующего по
[project testing mode](../../docs/agent-workflow.md#behavior-first-development-and-selective-tdd).

**Ожидаемая проверка:** выполнить всю
[acceptance matrix](#acceptance-evidence-contract) H01-H15, D01-D08 и E01-E03,
включая заданные bypass/retained JSON/SSE variants, пять управляемых terminal
порядков и timeout/cancel race. Независимые literals должны подтвердить exact
HTTP bytes/status/headers, один finished span, lineage/session/durations и
безопасную schema. SDK, production exporter и installed stdout наблюдать
раздельно. После focused regression и process suite выполнить один полный
`build` и независимые Standards/Spec review по [проверкам ниже](#проверки).
Это план будущих проверок, не отчёт об их прохождении; устранение trace gap
не закрывает gaps metrics, logs или внешнего Collector.

## Acceptance evidence contract

Основной public seam - finished SERVER/CLIENT spans, созданные production
decorators при настоящем HTTP-запросе. Наблюдения SDK, production OTLP exporter
и установленного процесса независимы. Созданный тестом вручную span не
доказывает ни HTTP reachability, ни wiring приложения.

Обозначения таблиц: `T` = `timeout` / `ERROR`, `C` = `cancelled` / `UNSET`,
`E` = `transport_error` / `ERROR`, `N` = attribute отсутствует, status `UNSET`.
`-` означает, что span вообще не создавался. Это только обозначения oracle;
в экспорт уходят literal values из принятого контракта.

### HTTP и terminal lifecycle

В `TransportTracePrivacyE2eTest` использовать `GatewayTestFixture.startTracedGateway`
для bypass (`B`) и production guardrail composition из `GatewayE2eTestSupport`
для retained route (`G`). Для `G` отправлять валидный
`POST /v1/chat/completions`; успешные JSON/SSE responses тоже валидны.
Каждая строка с `B,G` исполняется отдельно в обоих режимах. HTTP expectations
являются literals из owners, а не результатом production error mapper.

| ID | Stimulus и causal barrier | Observable CLIENT / SERVER | HTTP oracle |
|---|---|---|---|
| H01 B,G | Завершённый upstream `200` с известными bytes | N / N | Exact ALLOW bytes/status и разрешённые headers сохранены. |
| H02 B,G | По отдельности корректный upstream `400` и `500`, normal EOF | N / N | Исходный HTTP status/body сохранён; код сам по себе не создаёт transport failure. |
| H03 G | Request policy BLOCK, fixture считает upstream calls | - / N | Exact request `403 policy_blocked`, upstream calls = 0. |
| H04 G | Response policy BLOCK после complete upstream | N / N | Exact response `403 policy_blocked`, без исходных bytes. |
| H05 B,G | Connection refused на reserved non-ephemeral unused loopback port | E / N | B: `502 {"error":"upstream_unavailable"}`; G: safe invalid-upstream `502`. |
| H06 B,G | Controlled DNS resolver возвращает `UnknownHostException`, без внешнего DNS | E / N | Те же literal B/G outcomes, что H05. |
| H07 B,G | Upstream принял запрос, не отправил headers; configured response timeout | T / N | B: `504 {"error":"upstream_timeout"}`; G: safe invalid-upstream `502`. |
| H08 B,G | Upstream headers и один body item приняты, затем stall до response timeout | B: T / T; G: T / N | B: уже наблюдённый `200` и prefix, затем failed stream без replacement body; G: safe invalid-upstream `502` без upstream disclosure. |
| H09 B,G | `RawHttp1TestUpstream`: объявленный Content-Length больше body, затем закрыть socket | B: E / E; G: E / N | B: headers/prefix, затем failed stream; G: safe invalid-upstream `502`. Для B закрывать только после client observation prefix. |
| H10 G | Отмена клиента до handoff, test barrier удерживает identity/request inspection | - / C | Нет upstream call и response disclosure; удержанная работа завершена отменой. |
| H11 B,G | Upstream request принят, response ещё активен и headers клиенту не раскрыты; client abort | C / C | Отмена доходит до upstream, полноценного response нет. |
| H12 B | Client отменяет subscription после первого body item продолжающегося upstream | C / C | Наблюдён только prefix; upstream cancellation подтверждена собственным terminal signal. |
| H13 G | Upstream завершён и CLIENT уже exported; response inspection barrier удерживает disclosure, затем client abort | N / C | Ранее экспортированный CLIENT остаётся N; response bytes отсутствуют. |
| H14 G | Upstream завершён, replay большого валидного response начат; client abort после первого body item | N / C | SERVER имеет исходный HTTP status и C; CLIENT не меняется. Ограничить client receive window и держать response больше окна, чтобы SERVER не успел завершиться. |
| H15 SERVER | Real HTTP service под production `TracingService` выдал `200` и prefix; после их client observation вызвать public `ctx.timeoutNow()` | - / T | Уже раскрытый `200`/prefix, затем failed stream; отсутствие CLIENT задано fixture. |

Safe invalid-upstream `502` в H05-H09: `Content-Type: application/json`,
без `Retry-After`, exact body
`{"error":{"message":"Invalid upstream response.","type":"upstream_error","code":"invalid_upstream_response"}}`.
Два BLOCK body берутся как независимые literals из inspection error matrix.
H01, H04, H08, H09, H11, H13 и H14 для G исполнить отдельно с ordinary JSON
и SSE. H02 для G использует ordinary JSON. В B fixture headers/body observation
обязательна перед mid-response stimulus; запись в upstream writer сама по себе
не доказывает disclosure клиенту.

Для каждой строки отдельно проверить finished span count/IDs, parent tree,
`session.id`, query-free method/path, HTTP status attribute при наличии headers,
неотрицательные durations и `endEpochNanos >= startEpochNanos`. У каждого
созданного SERVER/CLIENT ровно один finished record; поля schema ниже безопасны.
Сохраняются существующие tracing propagation tests с default/custom headers.

Races: H08 + поздний client abort, H11 + поздний timeout, H09 + cleanup cancel,
H13 и H10 покрывают пять управляемых порядков. Barrier подтверждает terminal
первой операции до второго stimulus; ожидание только HTTP future недостаточно.
Дополнительно отпустить timeout и client abort одновременно при активном
exchange: после полного drain outcome каждого span принадлежит `{T,C}`,
category/status согласованы, нет второго record и изменения опубликованного
CLIENT. Oracle - разрешённое множество и завершивший операцию public terminal
signal, без чтения production classifier/coordinator state.

Ожидания через `GatewayTestFixture.awaitUntil`/causal futures с deadline 5 s;
проверять exporter и actual upstream cancellation, а не только client future.
Это bound тестового ожидания, не новый production SLO. Без sleep как доказательства
порядка. Закрыть servers, clients, SDK и test resources существующим lifecycle.

### Типы причин, wrappers и privacy

Внешний transport seam позволяет подставить failure без вызова private
методов: `WebClient` decorator, переданный реальному gateway, возвращает
управляемый failed response. Production `BypassProxyService` и `TracingService`
обрабатывают настоящий входящий HTTP request. Отдельный SERVER fixture под
`TracingService` закрывает свой response с cause после наблюдённых headers/body.
Такая injection проверяет diagnostic provenance; H05-H09 проверяют настоящий
network transport. Не утверждать, что Java cause удалённого upstream передаётся
по HTTP или что injection воспроизвела эксплуатацию пользовательским payload.

| ID | Stimulus | Public observation и independent oracle |
|---|---|---|
| D01 | Каждый из 10 именованных timeout types в таблице распознавания, отдельный case | CLIENT = T; recovery не меняется относительно existing HTTP mapping. SERVER fixture с `RequestTimeoutException` = T. |
| D02 | `RequestCancellationException`, `ResponseCancellationException`, `CancelledSubscriptionException`, JDK `CancellationException`, отдельные cases | Owning span = C, status description пустой. |
| D03 | `UnknownHostException`, `ConnectException`, неизвестный test exception с именем, содержащим sentinel, отдельные cases | Owning span = E; class name не экспортируется. |
| D04 | Representative response timeout, cancellation и unknown error: по одному под каждым из трёх transparent wrappers | 9 cases сохраняют соответственно T/C/E. Unknown nontransparent wrapper с timeout cause = E. |
| D05 | Цепочки 16 и 17 transparent wrappers вокруг response timeout; wrapper без cause; двухобъектный cycle | Соответственно T, E, E, E; каждый HTTP exchange и экспорт завершаются в deadline. |
| D06 | Unknown exception с message, nested cause и suppressed exception; разные synthetic sentinels в каждом месте | E; нулевые exception events/attributes, пустой status description, ни один sentinel не встречается в trace schema. |
| D07 | Timeout с suppressed cancellation и cancellation с suppressed timeout | Соответственно T и C: suppressed не меняет категорию. |
| D08 | Unknown exception с message `timeout cancelled`, затем тот же type с нейтральным message | Оба E; strings не влияют на классификацию. |

D03, D06 и D08 выполнить и через CLIENT transport injection, и через SERVER
response failure после headers. SERVER failure до headers наблюдается через
H10/H11; отсутствие response headers не заменяет проверку actual terminal cause.

Privacy oracle задаёт literal allowlist SERVER attributes:
`http.request.method`, `url.path`, `http.response.status_code`,
`upstream.duration_ms`, `gateway.duration_ms`, `session.id`,
`session.id.generated`, `trace.context.generated`, `trace.context.replaced`,
`vigilant.transport.failure`. Для CLIENT allowlist:
`http.request.method`, `url.path`, `http.response.status_code`, `session.id`,
`vigilant.transport.failure`. Conditional presence проверяется по HTTP matrix.
Оба span не имеют events/links и имеют пустой status description; единственное
новое значение принадлежит exact set из трёх категорий. Проверить также span
name и OTLP resource/scope/attribute values: raw cause не уходит в другой field.

Использовать разные заведомо синтетические sentinels для request body, response
body, query, Authorization, произвольного header, cookies, normalized identity
user/groups, exception class/message/cause/suppressed/stack-frame marker.
Последние вводятся только D-fixtures. Session ID имеет отдельное допустимое
значение; channel contract разрешает его в traces, поэтому oracle не запрещает
session целиком. Generated trace/span IDs и допустимый parent остаются.
Ожидаемые keys, strings, statuses и HTTP bodies не импортировать из production
sanitizer/classifier/error mapper; sentinel scan дополняет schema assertions.

### Экспорт OTLP и packaging

1. **E01, production exporter в HTTP E2E:** H07, H08, H11, D04-D08 выполнить
   с `buildSdkTracerProvider` и его production OTLP stdout exporter на захваченном
   `PrintStream`. Force flush с deadline 10 s должен завершиться успешно.
   Разобрать все `resourceSpans.scopeSpans.spans` JSON records, сопоставить
   trace/span IDs, применить тот же независимый literal oracle. D-fixtures
   проверяют nested/suppressed details на реальном exporter path.
2. **E02, installed process:** `GatewayProcessFixture.launchInstalled`,
   `VIGILANT_OTLP_ENABLED=true`, production Main/composition, локальный upstream,
   валидный request и явный `policies = []` для транспортных сценариев.
   Исполнить G-версии H01, H05, H07, H08, H09, H11 и H14; H08/H09/H11/H14
   отдельно для JSON и SSE. Sentinels wire/identity полей задать в request,
   response и test DUMMY configuration. Наблюдать records из `stdout()` после
   bounded ожидания и штатного завершения через fixture, затем проверить
   schema/categories/status, IDs/parentage и отсутствие sentinels.
   Metrics `resourceMetrics` и application JSONL разбирать как отдельные records;
   они не являются trace evidence. Этот fixture не подставляет Java exceptions
   в process и не доказывает D-cases самостоятельно.
3. **E03, disabled export:** повторить installed H07 с
   `VIGILANT_OTLP_ENABLED=false`: тот же HTTP outcome, нет `resourceSpans` и
   `resourceMetrics`; действующие in-memory context/collection tests остаются.

Ни E01, ни E02 не заменяются ручным `span.recordException`. Начальный RED
получить на текущем production CLIENT failure path: D06/E01 либо настоящий
network failure с raw exception event. Причина RED - запрещённые diagnostic
fields, а не fixture/compilation failure. Дополнительный SERVER privacy case,
который уже GREEN, сохранить GREEN. В issue не требуется доказать реальный
инцидент или эксплуатацию секретом перед устранением запрещённого канала.

## Критерии готовности

- [ ] Один attribute и span statuses соответствуют literal schema; production SERVER/CLIENT не записывают raw exception ни в events, ни в другие trace fields.
- [ ] H01-H15 и перечисленные G JSON/SSE variants пройдены; подтверждены HTTP invariants, lineage, отмена до/после handoff и до/после headers, один terminal record.
- [ ] D01-D08 пройдены через owning HTTP/dependency seams; finite classification, wrappers, bounded malformed cases и privacy имеют independent oracle.
- [ ] Все пять управляемых terminal порядков и одновременная timeout/cancel race имеют причинные наблюдения без позднего изменения результата.
- [ ] E01-E03 пройдены; in-memory SDK, production exporter и installed stdout evidence записаны раздельно. Initial RED и его GREEN относятся к одному воспроизводимому production path.
- [ ] Existing HTTP mapping, metrics/log contracts, propagation, request/response enforcement и source cleanup tests проходят; новые Kotlin declarations имеют KDoc.
- [ ] Normative observability owner, runtime docs и coverage обновлены по фактическому evidence. OBS-02 не объявлен полностью закрытым из-за одной trace-проверки; известные gaps других каналов сохранены.

## Проверки

Команды ниже описывают будущую реализацию, сейчас runtime tests не выполнялись.
Каждый Gradle check запускать последовательно через `scripts/check-run` с
preflight отсутствия другой Gradle invocation, одним session snapshot и
реальными source/build/toolchain inputs по development owner.

~~~bash
./gradlew detekt test -x processTest --tests 'io.vigilant.gateway.tracing.*' --tests 'io.vigilant.gateway.proxy.BypassProxyServiceTest' --tests 'io.vigilant.gateway.proxy.UpstreamTimeoutsTest' --tests 'io.vigilant.gateway.metrics.MetricsServiceTest' --tests 'io.vigilant.gateway.proxy.RequestInspectionE2eTest' --tests 'io.vigilant.gateway.proxy.JsonResponseEnforcementE2eTest' --tests 'io.vigilant.gateway.proxy.SseResponseEnforcementE2eTest' --tests 'io.vigilant.gateway.ProcessTestInventoryTest'
./gradlew processTest --tests 'io.vigilant.gateway.TransportTracePrivacyProcessTest'
./gradlew build
./scripts/task-context VIG-42
git diff --check
~~~

`processTest` сам зависит от `installDist`. Focused runs нужны при разработке
slices; один final `build` закрывает полный regression gate. Перед handoff
выполнить project pre-verification closure и независимые Standards/Spec review
по действующим owners. Сохранить JUnit XML обеих lanes, OTLP observations с
synthetic fixtures и criterion/evidence map. Внешние Collector/OCI deployment
проверки, performance threshold и mutation testing в эту issue не добавляются.

## Ambiguity Report

Goals: 0.0; Acceptance: 0.1; Boundaries: 0.0; Alternatives: 0.0;
Assumptions: 0.1; Aggregate: 0.04. Продуктовые решения подтверждены;
literal schema, terminal semantics, fixtures и independent oracles заданы.
Ready означает готовность к реализации. Runtime RED/GREEN и экспортные
наблюдения ещё предстоит получить; текущий privacy gap остаётся в coverage.
