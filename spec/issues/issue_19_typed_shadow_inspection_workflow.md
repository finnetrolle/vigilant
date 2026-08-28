# VIG-19: Типизированный workflow request-side shadow inspection

- **ID:** `VIG-19`
- **Тип:** Issue
- **Статус:** Done
- **Приоритет:** Medium
- **Зависит от:** [VIG-13](issue_13_pii_shadow_request_tracer.md), [VIG-14](issue_14_strict_protocol_gap_outcomes.md), [VIG-15](issue_15_capacity_cancellation_outcomes.md), [VIG-17](issue_17_request_tracing_stdout_otlp.md)
- **Блокирует:** нет
- **Оценка:** 2-3 инженерных дня
- **Уверенность:** Medium

## Контекст

Текущий runtime уже использует внешний decorator chain:

```text
TrafficAdmissionService
  -> MetricsService
    -> TracingService
      -> PiiShadowProxyService
        -> BypassProxyService
```

Этот chain корректно отделяет admission, metrics, tracing, request-side
inspection и streaming transport. Усложнение сосредоточено внутри
`PiiShadowProxyService`: после complete ingest один метод одновременно
управляет parser view, сборкой и handoff policy context, evaluation всех
фрагментов, audit, получением replay lease, преобразованием ожидаемых ошибок и
передачей transport ownership.

Фактический путь исходных bytes остаётся коротким:

```text
Armeria request -> BoundedRequestSource -> exact replay publisher
                -> BypassProxyService -> pooled WebClient -> upstream
```

Identity, protocol parser, policy engine и audit работают с headers,
read-only view, нормализованными fragments и metadata, а не образуют
последовательные копии сетевого payload. Issue должна упростить orchestration,
не меняя этот data flow.

## Результат

`PiiShadowProxyService` становится тонким Armeria HTTP-адаптером, а complete
source inspection выполняет отдельный типизированный
`ShadowInspectionWorkflow`. Workflow явно возвращает `Forward` или `Reject`,
сохраняет единую точку ownership для retained request source и не зависит от
upstream transport.

Рефакторинг не меняет наблюдаемое HTTP, policy, audit, tracing, metrics,
backpressure, exact replay или streaming behavior.

## Принятое архитектурное решение

### Внешний HTTP chain

- `TrafficAdmissionService`, `MetricsService` и `TracingService` остаются
  существующими HTTP decorators в текущем порядке.
- `PiiShadowProxyService` остаётся request-side inspection decorator перед
  `BypassProxyService`.
- Дополнительные HTTP decorators для identity, parsing, policy evaluation или
  audit не вводятся: эти стадии зависят от типизированных результатов друг
  друга и совместно владеют source lifecycle.

### Граница HTTP-адаптера

`PiiShadowProxyService` продолжает владеть:

- созданием и завершением INTERNAL inspection span;
- descriptor validation до body demand;
- identity extraction до body demand;
- открытием `RequestSourceQuota` и complete ingest с backpressure;
- scheduling workflow на существующем blocking-safe inspection executor;
- propagation cancellation в inspection task и source owner;
- преобразованием `Reject` в существующий stable `HttpResponse`;
- удалением только `headersToStrip` и вызовом `BypassProxyService` для
  результата `Forward`;
- общим recovery неожиданного failure в существующий
  `500 {"error":"inspection_failed"}`.

### Граница workflow

`ShadowInspectionWorkflow` получает:

- `BoundedRequestSourceOwner` в состоянии complete и принимает его ownership;
- исходный `HttpRequest` как источник path и headers для существующей protocol
  и context semantics;
- успешный `IdentityExtractionResult.Success`;
- owning `ServiceRequestContext` и текущий inspection `Span` для существующих
  `PolicyContextHandoff`, MDC и audit semantics.

Workflow является gateway-specific application service. В рамках этой issue
для Armeria request scope и OpenTelemetry span не вводятся абстрактные порты с
единственной реализацией.

Workflow предоставляет синхронный `execute(...)` contract. Его вызывает
существующий inspection task на blocking-safe virtual thread. Существующий
локальный bridge от синхронной orchestration к suspend `PolicyEngine.evaluate`
переносится внутрь workflow; новый coroutine runtime, dispatcher или
параллельный execution mode не вводится. Synchronous API намеренно отражает
blocking detector coordination и не обещает неблокирующую suspend semantics.

Workflow последовательно выполняет:

1. открывает и закрывает единственный parser view через `PiiShadowProtocol`;
2. получает lossless normalized Chat Completions request;
3. собирает request `PolicyContext` и сохраняет его через
   `PolicyContextHandoff`;
4. вызывает `PolicyEngine` отдельно для каждого text fragment, а при их
   отсутствии сохраняет текущую evaluation пустого payload;
5. получает replay ровно один раз;
6. публикует ровно один aggregate shadow decision либо один safe workflow
   error audit;
7. возвращает типизированный `Forward` или `Reject`.

Workflow не вызывает `BypassProxyService`, не создаёт `HttpResponse`, не
переписывает HTTP headers и не управляет upstream WebClient.

## Типизированный результат

Итоговый контракт должен выражать две взаимоисключающие ветви без nullable
полей и без вывода статуса из пустых collections:

```kotlin
sealed interface ShadowInspectionOutcome {
    data class Forward(
        val replay: ReplayReadyRequest,
    ) : ShadowInspectionOutcome

    data class Reject(
        val error: ShadowInspectionRejection,
    ) : ShadowInspectionOutcome
}
```

Точные Kotlin names могут быть уточнены при реализации только без изменения
семантики контракта.

### Expected rejection

`Reject` используется только для ожидаемых safe outcomes complete-source
workflow:

- parser failure с существующим `ChatCompletionsParseFailureCode`;
- source lifecycle failure с существующим `RequestSourceOutcomeCode`;
- context assembly или handoff failure, публично отображаемый в существующий
  `inspection_failed`.

Неожиданные exceptions, programming faults и cancellation не маскируются под
`Reject`. Они выходят из workflow и обрабатываются существующим outer recovery
`PiiShadowProxyService`. Cancellation должна сохранять interrupt и cleanup
semantics, а не превращаться в успешный result.

## Ownership и replay transfer

- До вызова workflow complete owner принадлежит `PiiShadowProxyService`.
- При входе в workflow ownership атомарно переходит workflow.
- При любом `Reject` или exception до успешного replay transfer workflow
  закрывает owner и освобождает retained owners, bytes и segments.
- При `Forward` ownership переходит closeable `ReplayReadyRequest`.
- `ReplayReadyRequest` инкапсулирует demand-driven publisher и точный immutable
  `headersToStrip`; bare publisher наружу не выдаётся и пересериализованный body
  не создаётся.
- После успешной передачи publisher в `BypassProxyService` owner закрывается
  существующим replay lifecycle при completion, failure или cancellation.
- Если `PiiShadowProxyService` не начал `transferTo`, он явно закрывает
  abandoned `ReplayReadyRequest`; если transport callback бросил синхронное
  исключение, cleanup выполняет сама операция `transferTo`.
- `close()` transfer object должен быть безопасным при повторном вызове и не
  освобождать quota раньше завершения успешно переданного replay.

Эта issue не должна расширять окно, в котором replay owner остаётся без
владельца между workflow и transport.

### One-shot transfer API

`ReplayReadyRequest` предоставляет единственную операцию передачи:

```kotlin
fun <T> transferTo(
    forward: (
        publisher: Flow.Publisher<ByteBuffer>,
        headersToStrip: Set<String>,
    ) -> T,
): T
```

- `transferTo` можно вызвать ровно один раз; повторная передача завершается
  deterministic fail-fast ошибкой без второго replay.
- Успешный возврат callback означает, что transport принял publisher и теперь
  publisher lifecycle владеет освобождением source.
- Синхронное исключение callback закрывает owner и пробрасывается без
  преобразования в expected `Reject`.
- `close()` до transfer закрывает owner; последующий `transferTo` запрещён.
- `close()` после успешного transfer не закрывает активный replay раньше его
  terminal signal.
- Переходы `ready -> transferring -> transferred|closed` thread-safe;
  concurrent close/transfer не создаёт двойную передачу или утечку quota.

`PiiShadowProxyService` вызывает `transferTo` и внутри callback строит replay
request, удаляет `headersToStrip` и вызывает `BypassProxyService`. Workflow не
получает transport callback и не зависит от результата forwarding.

## Error и observability semantics

- Descriptor и identity failures до complete ingest остаются ответственностью
  `PiiShadowProxyService` и не переводятся в workflow.
- Workflow сохраняет существующее правило одного aggregate
  `policy.shadow_decision` для поддержанного request на своей стадии.
- Payload, matched text, offsets, locators, headers, credentials и identity
  values не добавляются в outcome, audit или exceptions.
- INTERNAL span по-прежнему охватывает validation, identity, ingest и workflow;
  workflow может использовать span для audit correlation, но не завершает его.
- SERVER и CLIENT span ownership, metrics callbacks, structured completion log
  и upstream failure log не меняются.
- Existing stable status, error code и JSON body для каждого failure остаются
  byte-identical.

## Не входит

- изменение HTTP routes, status codes или error bodies;
- изменение порядка descriptor validation, identity extraction и body demand;
- изменение policy matching, overrides, detector execution, deadlines,
  reactions или audit fields;
- response inspection, `BLOCK`, transformations или новые protocol adapters;
- изменение buffering limits, backpressure, exact replay или streaming
  response;
- изменение tracing span tree, metrics, MDC или stdout formats;
- новые configuration keys, modes, providers, modules или external
  dependencies;
- generic workflow framework или обобщение под будущие протоколы;
- изменение `BypassProxyService`, WebClient pooling или upstream transport
  semantics;
- performance optimization без отдельного измеренного regression.

## Рассмотренные альтернативы

- Отдельный HTTP decorator на каждую внутреннюю стадию отклонён: body
  subscription, typed intermediate state, cancellation и source ownership
  стали бы распределёнными и зависимыми от неявного порядка decorators.
- Полностью framework-neutral workflow с `ContextStore`, `AuditSink` и
  telemetry ports отклонён как преждевременная абстракция с единственными
  реализациями.
- Сохранение orchestration целиком в `PiiShadowProxyService` отклонено: HTTP
  boundary, complete-source use case и transport transfer имеют разные причины
  изменения и уже образуют самостоятельный типизированный seam.
- Возврат bare replay publisher отклонён: синхронная ошибка между `Forward` и
  transport subscription могла бы оставить owner без явного cleanup owner.
- Преобразование всех exceptions в `Reject` отклонено: оно смешивает expected
  domain outcomes с cancellation и programming faults.

## Предварительный план изменений

- `gateway/proxy/PiiShadowProxyService.kt`: оставить HTTP validation, ingest,
  scheduling, cancellation, response mapping и transport delegation; заменить
  complete-source orchestration вызовом workflow.
- Новый файл в `gateway/proxy/`: реализовать `ShadowInspectionWorkflow`,
  `ShadowInspectionOutcome`, expected rejection types и closeable replay
  transfer contract.
- `gateway/AppComponent.kt`: собрать workflow из существующих protocol,
  `PolicyEngine` и audit dependencies без нового module или runtime mode.
- Тесты workflow и существующие real-Armeria E2E tests: доказать result,
  ownership и полную неизменность observable proxy behavior.
- `docs/architecture.md`: показать новый workflow, сокращённую ответственность
  HTTP-адаптера и неизменный physical byte path.
- `CLAUDE.md`: обновить canonical architecture/source map и ownership wording
  для `PiiShadowProxyService` и `ShadowInspectionWorkflow`.
- `docs/runtime-contract.md`, `docs/configuration.md` и
  `docs/observability.md` не менять: их observable runtime, configuration и
  telemetry contracts этой issue не затрагиваются.

## Test strategy

Это behavior-preserving refactoring. До первого production edit необходимо
запустить существующие `PiiShadowProxyServiceTest` и
`InspectionCancellationTest` и получить GREEN characterization baseline.
После каждого шага extraction тот же focused набор остаётся GREEN; искусственный
RED для уже существующего runtime behavior не создаётся.

### Focused workflow tests

Новый `ShadowInspectionWorkflowTest` проверяет все типизированные исходы:

- valid complete source возвращает `Forward`, сохраняет request context и
  публикует ровно один decision audit;
- malformed и ambiguous normalized content возвращают parser `Reject`,
  публикуют один safe error audit и закрывают owner;
- закрытый или недопустимый source state возвращает source `Reject` и не
  оставляет reservation;
- pre-existing request context создаёт context-handoff `Reject`, один safe
  error audit и cleanup owner;
- detector error или policy deadline в текущем shadow contract остаётся
  `Forward`, а не expected rejection;
- unexpected exception и interruption не превращаются в `Reject`, сохраняют
  исходный failure/cancellation contract и закрывают owner.

### Replay transfer tests

Новый `ReplayReadyRequestTest` проверяет:

- успешный one-shot `transferTo`, exact original bytes и освобождение source
  по terminal replay signal;
- deterministic fail-fast при повторном transfer без второго publisher;
- `close()` до transfer освобождает quota и запрещает последующую передачу;
- synchronous callback exception закрывает owner и пробрасывается без
  подмены;
- `close()` после успешного transfer не закрывает активный replay до его
  completion, failure или cancellation;
- concurrent close/transfer через explicit barriers приводит ровно к одному
  terminal ownership path без race на wall-clock sleeps.

### Обязательная E2E characterization

Существующий real-Armeria seam должен продолжать доказывать:

- byte-identical forwarding и один safe aggregate audit;
- Basic и trusted-header identity handoff и точный header stripping;
- anonymous mode без потребления identity-like headers;
- identity rejection и unsupported descriptor до body demand/upstream;
- fail-closed malformed/ambiguous content и explicit inspection gap для
  recognized non-text content;
- known и streamed per-request overflow, global exhaustion и полное
  освобождение reservations;
- cancellation активной inspection, shadow-ALLOW policy deadline и safe error
  observations;
- SSE response streaming до завершения upstream;
- replay cleanup при upstream connection failure;
- неизменный sibling span tree `SERVER -> INTERNAL + CLIENT`.

Новые и изменённые production/test methods и lifecycle helpers получают
актуальный KDoc в том же refactoring slice. Асинхронные наблюдения используют
существующий bounded polling или explicit barriers, а не sleeps.

## Закрытые решения диалога

- [x] Workflow имеет synchronous `execute(...)`; существующий
  `runSuspending` bridge становится его private implementation detail.
- [x] Focused workflow/transfer matrix и обязательная real-Armeria E2E
  characterization перечислены в `Test strategy`.
- [x] `ReplayReadyRequest.transferTo` является one-shot callback boundary,
  закрывает owner при synchronous callback failure и не раскрывает bare
  publisher до контролируемой передачи.
- [x] Обновляются только `docs/architecture.md` и canonical architecture map в
  `CLAUDE.md`; runtime, configuration и observability references остаются без
  изменений.

## Критерии готовности

Issue готова к реализации только как behavior-preserving refactoring по
следующим проверяемым критериям:

- [x] `PiiShadowProxyService` сохраняет внешний HTTP decorator contract и
  делегирует complete-source orchestration типизированному workflow.
- [x] Workflow возвращает только `Forward` или expected `Reject`; unexpected
  exceptions остаются outer failures.
- [x] Каждый terminal path имеет ровно одного owner retained source, а quota
  освобождается при reject, exception, cancellation, replay completion,
  replay failure и несостоявшемся transport handoff.
- [x] Existing HTTP status/body, upstream bytes, audit events, span tree,
  metrics и streaming behavior не изменяются.
- [x] Все перечисленные focused workflow, replay transfer и real-Armeria E2E
  cases проходят детерминированно; KDoc соответствует итоговому ownership и
  error contract.
- [x] Focused tests, real-Armeria E2E suite, `./gradlew validateWorkItems` и
  `./gradlew build` проходят.

## Проверка реализации

- Characterization baseline до production edits:
  `./gradlew test --tests 'io.vigilant.gateway.proxy.PiiShadowProxyServiceTest' --tests 'io.vigilant.gateway.proxy.InspectionCancellationTest' --no-daemon` - GREEN.
- RED transfer slice: `ReplayReadyRequestTest` сначала завершился compile
  failure из-за отсутствующего contract, затем после минимального scaffold
  упал на непереданных exact bytes. Последующие focused RED подтвердили
  повторный transfer, close-before-transfer, callback failure и immutable
  header snapshot.
- RED workflow slice: `ShadowInspectionWorkflowTest` сначала завершился
  compile failure из-за отсутствующего contract, затем после минимального
  scaffold упал на `UnsupportedOperationException`. Последующие focused RED
  подтвердили parser, source и context-handoff rejects.
- Final focused GREEN:
  `./gradlew test --tests 'io.vigilant.gateway.proxy.ShadowInspectionWorkflowTest' --tests 'io.vigilant.gateway.proxy.ReplayReadyRequestTest' --tests 'io.vigilant.gateway.proxy.PiiShadowProxyServiceTest' --tests 'io.vigilant.gateway.proxy.InspectionCancellationTest' --no-daemon`.
- Final project GREEN: `./gradlew build --no-daemon`, включая detekt, полный
  test suite, `validateWorkItems` и validator tests.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ workflow extraction outcome определён
  Acceptance:   0.0   ✓ focused и E2E matrix перечислены
  Boundaries:   0.0   ✓ behavior и non-goals исчерпывающие
  Alternatives: 0.25  ✓ основные варианты рассмотрены
  Assumptions:  0.0   ✓ execution, ownership и docs contracts подтверждены
  ──────────────────────────────
  Aggregate:    0.05  ✓ below threshold (0.3 ticket)

Push lightly on: preserve deterministic ownership evidence during implementation.
```
