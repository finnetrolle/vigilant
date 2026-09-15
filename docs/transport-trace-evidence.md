# Transport trace evidence

Нормативный owner: [transport failure tracing](../spec/requirements/observability.md#transport-failure-tracing).
Runtime wiring: [tracing](observability.md#tracing). Эти проверки относятся к
SERVER и HTTP CLIENT upstream; остальные telemetry channels проверяются отдельно.

## Наблюдаемые границы

Локальный прогон 2026-09-15: focused regression прошёл 240 tests, включая
133 новых HTTP/SDK/OTLP cases; installed suite отдельно прошёл 15 cases.
Failures, errors и skips отсутствовали в обоих JUnit reports. Это наблюдение
синтетического local fixture, а не production deployment measurement.

Проверки выполняют настоящий входящий HTTP exchange через production decorators.
SDK observer получает finished `SpanData`; отдельные executions подключают
`buildSdkTracerProvider` и декодируют production OTLP stdout. Installed lane
запускает Main через `GatewayProcessFixture.launchInstalled`, с явным empty
policy file и synthetic DUMMY identity. Ручное создание span не используется
как HTTP или packaging evidence.

| Boundary / cases | Public observations | Suite |
|---|---|---|
| Bypass и retained JSON/SSE: success, обычные 400/500, refused connection, controlled DNS, timeout до/после headers, truncated response, active upstream cancellation | Exact response bytes/status/headers; terminal upstream cancellation; два finished HTTP spans с независимыми outcomes | `TransportTraceHttpE2eTest` |
| Request BLOCK; response BLOCK отдельно JSON/SSE | Exact safe 403; request BLOCK имеет ноль upstream calls и только SERVER; response BLOCK сохраняет completed CLIENT | `TransportTraceLifecycleE2eTest` |
| Cancellation до handoff, после upstream completion во время inspection и большого replay | Held detector cancellation, отсутствие преждевременного disclosure, finite HTTP/2 window/demand, неизменный finished CLIENT, released request quota/retained source | `TransportTraceLifecycleE2eTest` |
| SERVER-only timeout и diagnostic failure после headers | Client действительно получил 200/prefix до `timeoutNow()` или закрытия writer; один failed SERVER | `TransportTraceServerE2eTest` |
| Все десять timeout types, четыре cancellation types, DNS/connect/unknown cause; три wrapper types; 16/17 transitions, missing cause, identity cycle, suppressed и misleading messages | Literal category/status и прежний HTTP recovery; SDK и production OTLP schema независимо | `TransportTraceCauseE2eTest` |
| Timeout потом cancel; cancel потом timeout; simultaneous release | Первый terminal record опубликован до позднего stimulus; actual upstream terminal signal; после полного drain records неизменны; race допускает только timeout/cancelled | `TransportTraceRaceE2eTest` |
| Transport error потом cleanup cancel; success потом cancel; cancel до handoff | Published span barrier и неизменность результата; отсутствие несуществующего CLIENT | HTTP и lifecycle suites выше |
| Synthetic exception message/cause/suppressed/stack через внешний dependency seam | Реальный production CLIENT OTLP первоначально содержал запрещённый exception event; тот же HTTP 502 path после исправления не содержит diagnostics | `TransportTracePrivacyE2eTest` |
| Installed JSON/SSE success/refused/timeout/truncation/cancel/replay; disabled timeout | Main stdout после bounded export observation и закрытия fixture; enabled trace schema/tree, disabled отсутствие trace/metric envelopes при том же safe HTTP response | `TransportTracePrivacyProcessTest` |

Schema oracle задаёт keys, categories, statuses и HTTP bodies как независимые
literals, без вызова production classifier/error mapper. Проверяются IDs,
parent tree, session, query-free method/path, status при доступных headers,
неотрицательные durations, один finished record, пустые description/events/links.
Отдельные synthetic sentinels покрывают wire body/query/auth/header/cookie,
identity user/groups и injected exception class/message/cause/suppressed/stack;
scan включает OTLP resource/scope. Allowed session имеет другое значение.

Для отменённой доставки final Armeria RequestLog может содержать подготовленные
headers, которых клиент не получил. Lifecycle tests независимо читают этот
public log и одновременно проверяют отсутствие client disclosure; `UNKNOWN`
не становится HTTP status attribute. Это не изменение HTTP error mapping.

## Повторение проверок

Все Gradle invocations запускать последовательно через
[durable runner](development.md#устойчивый-запуск-проверок), с актуальными
source/build/toolchain inputs. Focused regression сохраняет старых consumers:

```bash
./gradlew detekt test -x processTest --tests 'io.vigilant.gateway.tracing.*' --tests 'io.vigilant.gateway.proxy.BypassProxyServiceTest' --tests 'io.vigilant.gateway.proxy.UpstreamTimeoutsTest' --tests 'io.vigilant.gateway.metrics.MetricsServiceTest' --tests 'io.vigilant.gateway.proxy.RequestInspectionE2eTest' --tests 'io.vigilant.gateway.proxy.JsonResponseEnforcementE2eTest' --tests 'io.vigilant.gateway.proxy.SseResponseEnforcementE2eTest' --tests 'io.vigilant.gateway.ProcessTestInventoryTest'
./gradlew processTest --tests 'io.vigilant.gateway.TransportTracePrivacyProcessTest'
./gradlew build
```

JUnit XML находится в `build/test-results/test` и `build/test-results/processTest`.
Production exporter observations сохраняются в
`build/reports/transport-traces/http-*.jsonl`, installed stdout в
`build/reports/transport-traces/process-*.jsonl`. `resourceSpans`,
`resourceMetrics` и application JSONL разбираются как разные signal types.
Короткий enabled exchange не обязан дождаться periodic metric envelope.

## Граница выводов

Dependency injection воспроизводит происхождение запрещённых diagnostics в
gateway, а network cases отдельно доказывают runtime wiring. Java exception
удалённого upstream не передаётся по HTTP. Эти synthetic observations не
утверждают реальный инцидент или эксплуатацию пользовательским секретом.

HTTP error mapper, metric classifier, application log schema, identity и
INTERNAL spans сохраняют самостоятельные contracts. Не проверяются внешний
Collector, retention/delivery, historical storage cleanup, OCI deployment или
performance. `OBS-02` остаётся частичным; arbitrary metric error class,
отсутствующие inspection instruments и другие
[channel/deployment gaps](requirements-coverage.md#observability-evidence)
не объявляются закрытыми.
