# VIG-22-05: Exact audit exhaustion response через production admission chain

**Статус:** Done

> Historical evidence only: runtime behavior superseded and removed by
> [EPIC-32](../../epics/epic_32_best_effort_stdout_audit.md); `Done` is unchanged.
**Epic:** [EPIC-22](../../epics/epic_22_durable_minimum_audit_trail.md)
**Ветка:** Request-path defect > lifecycle admission и audit availability
**Зависит от:** [VIG-22-02](issue_22_02_request_path_audit_acceptance.md)
**Блокирует:** [VIG-22-04](issue_22_04_packaged_durability_qualification.md)
**Оценка:** 1-2 инженерных дня
**Уверенность:** High

## Результат

Production service chain различает graceful-shutdown admission и динамическую
недоступность mandatory audit boundary. После audit capacity/I/O exhaustion
`/readyz` остаётся `503`, но новый supported Chat Completions request достигает
audit admission seam и получает exact
`503 {"error":"audit_unavailable"}` без body demand и upstream observation.
Только начавшийся graceful shutdown продолжает отклонять весь новый proxy
traffic как `503 draining`.

## Обнаруженный RED case

VIG-22-04 запустила installed `build/install/vigilant/bin/vigilant` с fixed JVM
settings, real separate-process Armeria upstream и persistent audit directory.
Первый supported request создал forced record при малом валидном retained bound;
после этого `/readyz` причинно перешёл в `503`. Следующий streaming request
передал только headers и не передал ни одного body byte.

Ожидалось:

```text
HTTP 503
{"error":"audit_unavailable"}
```

Фактически получено:

```text
HTTP 503
draining
```

Причина локализована: `TrafficAdmissionService` использует composite
`ReadinessService.isReady()`. Этот predicate становится false как при shutdown,
так и при `AuditStore.isAvailableForAdmission() == false`, поэтому outer service
перехватывает request до `PiiShadowProxyService` и скрывает нормативный typed
audit outcome.

## Требования

`MVP-18`, `MVP-19`, `CONC-01`, `CONC-03`; строки admission exhaustion и
graceful shutdown из
[minimum audit trail contract](../../MINIMUM_AUDIT_TRAIL_CONTRACT.md).

## Принятое решение

- Lifecycle traffic admission и composite readiness получают разные явные
  predicates. `TrafficAdmissionService` проверяет только возможность принять
  новый exchange с точки зрения shutdown lifecycle.
- `ReadinessService` по-прежнему возвращает `503` как при shutdown, так и при
  недоступном audit admission. Existing probe contract и audit recovery
  сохраняются.
- При audit unavailability supported descriptor доходит до существующего
  `PiiShadowProxyService` reservation seam. Его текущий typed mapping остаётся
  единственным owner `audit_unavailable`.
- Во время graceful shutdown outer traffic admission продолжает отклонять
  новое proxy traffic локально, не demand-ит body и не вызывает upstream.
- Unsupported descriptor сохраняет существующий no-shadow-audit contract и не
  получает новый special case внутри этой issue.

## Критерии готовности

- [x] RED-first packaged regression запускает installed `MainKt`, real Armeria
  upstream и persistent audit directory, причинно исчерпывает retained
  admission, наблюдает `/readyz=503`, затем получает фактический
  `503 draining` вместо exact `audit_unavailable` до production change.
- [x] После GREEN тот же supported streaming request получает ровно
  `503 {"error":"audit_unavailable"}`, не передав ни одного body byte; upstream
  request count остаётся `0`, retry отсутствует.
- [x] `CAPACITY_EXHAUSTED`, `EVENT_TOO_LARGE`, `IO_FAILURE` и `CLOSED`, когда
  HTTP delivery ещё возможна и shutdown не начат, не перехватываются outer
  lifecycle admission и сохраняют существующий typed audit mapping.
- [x] `/readyz` возвращает `503` во всех audit-unavailable states и
  восстанавливается в `200` после valid ack/reclaim или другого подтверждённого
  восстановления audit admission.
- [x] Отдельный shutdown regression причинно вызывает lifecycle transition и
  подтверждает прежний `503 draining`, no body demand и no upstream для нового
  traffic во время drain.
- [x] Уже admitted exchange продолжает bounded drain; разделение predicates не
  меняет shutdown order, WAL force/seal или active exchange ownership.
- [x] Client response, logs и metrics не содержат audit path, raw exception,
  request body, identity, session, credential или locator sentinels.
- [x] Для всех добавленных и изменённых Kotlin/Java declarations и test methods
  написан актуальный KDoc/Javadoc.
- [x] Focused health/admission E2E tests, packaged RED/GREEN regression,
  `./gradlew validateWorkItems` и `./gradlew build` проходят.

## Evidence

- RED: focused real-Armeria `HealthEndpointsTest` получил для
  `CAPACITY_EXHAUSTED` expected `audit_unavailable`, actual `draining`.
- GREEN: тот же table-driven test пропускает `CAPACITY_EXHAUSTED`,
  `EVENT_TOO_LARGE`, `IO_FAILURE` и `CLOSED` к typed owner, сохраняя
  `/readyz=503`.
- `ShutdownLifecycleTest` отправляет header-only request после SIGTERM и
  подтверждает exact `503 draining`, no upstream и завершение уже admitted
  response.
- `./gradlew durabilityQualification` прошёл с verdict `PASS`; retained-byte
  row содержит `503 audit_unavailable`, body bytes before response `0`, upstream
  requests `0`, readiness `503`.
- Versioned evidence: [durability qualification report](../../../docs/durability-qualification-2026-08-31.md).

## Test/demo seam

Основной seam - installed production `MainKt` с real Armeria upstream,
persistent audit directory и streaming client, который после headers не
публикует body bytes. Retained capacity заполняется валидной durable record;
wait синхронизируется на `/readyz=503` и exact WAL/manifest state, а не на
таймстемп или sleep. Отдельный real-Armeria lifecycle test вызывает
`markNotReady`/SIGTERM и сохраняет `draining` contract.

## Не входит

Изменение audit bounds или WAL schema, новый error code, retry, Collector
protocol, response inspection, enforcement reactions, изменение probe status,
объединение `draining` и `audit_unavailable`, а также правки qualification
report за пределами повторного запуска VIG-22-04 после GREEN.

## Рассмотренные альтернативы

- Возвращать `audit_unavailable` прямо из `TrafficAdmissionService` отклонено:
  outer service не владеет descriptor classification и не должен дублировать
  audit outcome mapping.
- Считать `draining` допустимым при audit exhaustion отклонено: это нарушает
  exact normative matrix и скрывает причину fail-closed outcome от клиента.
- Оставить общий predicate и различать причину через новый config mode
  отклонено как лишняя configuration surface; runtime уже имеет независимые
  lifecycle и audit states.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   expected и actual packaged response зафиксированы
  Acceptance:   0.05  RED/GREEN, readiness, body demand и shutdown explicit
  Boundaries:   0.0   issue разделяет predicates без нового audit mapping
  Alternatives: 0.05  owner typed response и отклонённые варианты названы
  Assumptions:  0.05  existing PiiShadowProxyService mapping остаётся canonical
  Aggregate:    0.03  below threshold (0.3 issue)
```
