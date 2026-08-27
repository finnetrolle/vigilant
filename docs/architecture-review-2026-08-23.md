# Архитектурное ревью Vigilant v0 от 2026-08-23

> Исторический документ. Он фиксирует состояние bypass-only v0 на дату ревью,
> а не текущую архитектуру. Находки AR-01..09 были закрыты в EPIC-09; актуальный
> production path уже выполняет bounded request-side PII shadow inspection.
> Текущее устройство описано в [architecture.md](architecture.md), а
> подтверждённый PERF-01 run после исправлений - в
> [perf-01-result.md](perf-01-result.md).

## Вердикт

Архитектурное ядро bypass proxy построено здраво: data path остаётся потоковым,
HTTP-нормализация сосредоточена в одном service, конфигурация валидируется до
старта, а metrics/traces и probes добавлены декораторами и отдельными routes.
Главный риск находится не в выбранных границах компонентов, а в доказанной
эксплуатационной несостоятельности текущей реплики под нормативной нагрузкой:
полный PERF-01 прогон завершился `OutOfMemoryError`, после чего все 240 000
измеряемых proxy-запросов упали. Кроме того, несколько обязательных свойств v0
следуют из устройства Armeria pipeline, но не подтверждены E2E-тестами. Поэтому
v0 годится как функциональный фундамент, но пока не как подтверждённый
production baseline для следующего guardrail-enabled этапа.

## Что сделано хорошо

- `BypassProxyService` передаёт исходные `HttpRequest`/`HttpResponse` publishers
  без JSON parsing и без явной агрегации body. Это правильная база для
  `PROXY-01` и будущего lossless spool.
- Переписывание authority/base path и удаление hop-by-hop headers, включая
  имена из `Connection`, сосредоточены в одном месте, а не размазаны по
  transport и observability слоям.
- Stable `502`/`504` proxy errors отделены от корректных upstream HTTP statuses;
  безопасная классификация ошибки переиспользуется metrics.
- Metrics и tracing оформлены внешними `HttpService` decorators. Они не
  заставляют proxy агрегировать body и не связывают transport с будущим policy
  engine.
- `AppConfig` задаёт явный `env > file > defaults` contract и валидирует URL,
  port, durations и OTLP endpoint до запуска server.
- Probes зарегистрированы раньше catch-all route, readiness переключается до
  остановки server, а OCI image закрепляет JRE digest и non-root execution.
- EPIC-03/06/07/08 заранее разделяют HTTP context, protocol parsing, windowing
  и lossless spooling. Эти границы стоит сохранить: они защищают schema
  tolerance, original bytes и detector offsets от смешения ответственностей.

## Находки по критичности

| ID | Severity | Class | Что и почему | Где | Нормативный источник |
|---|---|---|---|---|---|
| AR-01 | Critical | `code-defect` | Реплика исчерпывает память под полным PERF-01 профилем, после чего proxy перестаёт принимать соединения; следующий этап добавит CPU и memory pressure поверх уже неустойчивого baseline. | `docs/perf-01-result.md:5`, `docs/perf-01-result.md:43` | `PERF-01`, `PERF-06` |
| AR-02 | High | `missing-behavior` | После устранения отказа всё ещё требуется получить измеряемый `proxy_overhead p99 <= 2 ms`; текущий прогон не содержит ни одного успешного proxy sample. | `docs/perf-01-result.md:10`, `docs/perf-01-result.md:54` | `PERF-01` |
| AR-03 | High | `unverified` | Request body передаётся как streaming publisher, но единственный обычный POST test агрегирует request upstream; отсутствие request aggregation и request-side backpressure не доказаны. | `src/main/kotlin/io/vigilant/gateway/proxy/BypassProxyService.kt:49`, `src/test/kotlin/io/vigilant/gateway/proxy/BypassProxyServiceTest.kt:45`; E2E streaming-request test отсутствует | `PROXY-01`, раздел «Входит в v0»: streaming requests/backpressure |
| AR-04 | High | `unverified` | Response streaming test запрашивает `Long.MAX_VALUE`, поэтому доказывает ранний первый byte, но не propagation обратного давления от медленного client к upstream. | `src/test/kotlin/io/vigilant/gateway/proxy/BypassProxyStreamingTest.kt:125`, `src/test/kotlin/io/vigilant/gateway/proxy/BypassProxyStreamingTest.kt:130` | `PROXY-01`, `CONC-01` |
| AR-05 | Medium | `unverified` | Dedicated `ClientFactory` заявляет pooling и idle policy, но connection reuse и повторное соединение после idle eviction не проверены через реальный upstream. | `src/main/kotlin/io/vigilant/gateway/proxy/UpstreamWebClients.kt:17`; pooling E2E test отсутствует | раздел «Входит в v0»: connection pooling |
| AR-06 | Medium | `unverified` | `PROXY-03` требует stable error для некорректного upstream HTTP, однако E2E tests покрывают connection failure и timeout, но не malformed response от raw upstream. | `src/test/kotlin/io/vigilant/gateway/proxy/BypassProxyServiceTest.kt:84`, `src/test/kotlin/io/vigilant/gateway/proxy/BypassProxyServiceTest.kt:95`; malformed-upstream test отсутствует | `PROXY-03` |
| AR-07 | Medium | `unverified` | Production code удаляет response headers, перечисленные upstream в `Connection`, но response test проверяет только фиксированный список; регрессия dynamic response stripping останется незамеченной. | `src/main/kotlin/io/vigilant/gateway/proxy/BypassProxyService.kt:137`, `src/test/kotlin/io/vigilant/gateway/proxy/BypassProxyServiceTest.kt:270` | `PROXY-02`, project instruction о `Connection` |
| AR-08 | Medium | `unverified` | Shutdown tests наблюдают `503` readiness и exit процесса без активного обмена, но не доказывают успешный drain активного request, force bound для зависшего request и полный lifecycle owned resources. | `src/test/kotlin/io/vigilant/gateway/health/HealthEndpointsTest.kt:89`, `src/main/kotlin/io/vigilant/gateway/Main.kt:31`, `src/main/kotlin/io/vigilant/gateway/proxy/UpstreamWebClients.kt:18` | раздел «Поставка»: graceful shutdown с bounded wait |
| AR-09 | Medium | `untracked` | PERF report прямо требует отдельный profiling/fix work item, но реестр не содержит задачи, привязанной к наблюдаемому OOM и восстановлению throughput. | `docs/perf-01-result.md:54`, `spec/WORK_ITEMS.md` | `PERF-01`, completion discipline `WORK_ITEMS.md` |

## Nits

| ID | Class | Наблюдение | Где | Решение |
|---|---|---|---|---|
| N-01 | `nit` | Две issues имеют status `Done`, но их собственные acceptance checkboxes остались пустыми. Реализация в основном подтверждается code/tests, однако документальный audit trail противоречив. | `spec/issues/issue_01_logging.md:404`, `spec/issues/epic_05/issue_05_03_streaming_e2e.md:25` | Осознанно не включено в EPIC-09: выполнить отдельную backlog hygiene правку без смешения с runtime remediation. |
| N-02 | `nit` | README утверждает, что CI запускает mutation testing, тогда как workflow содержит build и conditional dependency check. | `README.md:223`, `.github/workflows/ci.yml:15` | Осознанно не включено в EPIC-09: исправить как documentation-only cleanup. |

## Process gaps

Единственная архитектурно значимая дыра реестра - AR-09. VIG-05-08 честно
закрыл delivery результата с `DEVIATION`, но отдельная remediation task,
которую требует сам отчёт, не была создана. EPIC-09 добавляет эту работу и
разделяет stability от p99 optimization. Остальные verification gaps также
получают отдельные issues, чтобы `Done` больше не зависел от предположений о
поведении library pipeline.

## Готовность к следующему этапу

Сохранятся без переделки: Metro composition root, transport-neutral streaming
publishers, отдельные observability decorators, централизованная HTTP header
normalization и принятые границы parser/window/spool. Эти seams позволяют
заменить прямой bypass execution на enforcement flow без переноса protocol
semantics в detector или policy engine.

Не выдержат следующий этап без закрытия EPIC-09: memory posture при 2 000 RPS,
непроверенные обе стороны backpressure и неполный shutdown lifecycle. Добавлять
spool, parser и detectors поверх OOM baseline нельзя: иначе performance и
resource regressions невозможно будет локализовать между proxy foundation и
новым guardrail path.

## Рекомендации

1. Сначала закрыть VIG-09-01 и получить устойчивую память/throughput на полном
   PERF-01 профиле.
2. После этого отдельно закрыть VIG-09-02 и измерить либо оптимизировать p99;
   только затем выполнять logging-specific VIG-01A.
3. Параллельно добавить request/response backpressure E2E tests, потому что их
   seams станут базой для EPIC-08 spooling.
4. Затем закрыть pooling, malformed upstream, dynamic response hop-by-hop и
   shutdown lifecycle. Эти tasks независимы и могут выполняться параллельно.
5. Не подключать EPIC-06/07/08 к production request path до зелёного PERF-01 и
   завершения streaming verification branch EPIC-09.

## Finding-to-issue coverage

| Finding | Покрытие |
|---|---|
| AR-01, AR-09 | VIG-09-01 |
| AR-02 | VIG-09-02 |
| AR-03 | VIG-09-03 |
| AR-04 | VIG-09-04 |
| AR-05 | VIG-09-05 |
| AR-06 | VIG-09-06 |
| AR-07 | VIG-09-07 |
| AR-08 | VIG-09-08 |
| N-01, N-02 | Явно downgraded to nits, вне EPIC-09 |
