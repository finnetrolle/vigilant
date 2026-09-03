# VIG-21-01: Контракт минимального обязательного audit trail

**Статус:** Done

> Историческое решение superseded [EPIC-32](../../epics/epic_32_best_effort_stdout_audit.md).
> Этот `Done` work item и его прошлое evidence сохранены без изменения статуса.
**Epic:** [EPIC-21](../../epics/epic_21_post_milestone_architecture_closure.md)
**Ветка:** Audit governance > minimum mandatory audit trail
**Зависит от:** нет
**Блокирует:** [VIG-21-05](issue_21_05_roadmap_frontier_reconciliation.md), [VIG-22-01](../epic_22/issue_22_01_local_durable_audit_store.md)
**Оценка:** 2-3 инженерных дня
**Уверенность:** High

## Результат

Нормативный [contract](../../MINIMUM_AUDIT_TRAIL_CONTRACT.md) однозначно
определяет минимальный audit trail Vigilant:
момент acceptance, durability boundary, retained safe fields, failure outcomes,
shutdown behavior и ответственность внешнего Collector. По выбранному contract
опубликован [EPIC-22](../../epics/epic_22_durable_minimum_audit_trail.md) с
четырьмя independently grabbable implementation issues размером не более пяти
инженерных дней.

Это documentation-only decision issue. Production logging и data path не
изменяются.

## Требования

`MVP-18`, `MVP-19`, `OUT-06`, finding AR-10.

## Выбранный contract

Выбрано application-owned segmented WAL решение. Полные lifecycle, schema,
resource, failure, shutdown, recovery и Collector semantics опубликованы ровно
в одном [normative contract](../../MINIMUM_AUDIT_TRAIL_CONTRACT.md). Этот issue
фиксирует decision history и completion evidence, но не копирует contract
matrix.

## Alternatives decision

| Alternative | Durability | Operability | Privacy | Failure semantics | Решение |
|---|---|---|---|---|---|
| Current async stdout | Event может быть отброшен до stdout | Уже работает, но общий sink не даёт audit acknowledgement | Safe aggregate event не содержит payload, однако sink общий для observability | Потеря не видна request path | Отклонена |
| Application-owned segmented WAL | Covering `force(true)` даёт локальную durable boundary | Требует persistent volume, recovery, bounds и Collector ack | Локально хранится только bounded safe schema | Request fail closed при невозможности acceptance; Collector outage изолирован до capacity bound | Выбрана |
| Synchronous acknowledged external delivery | Зависит от Collector и destination acknowledgement | Network/Collector входят в latency и availability каждого request | Safe record покидает process до request completion | Любой network outage останавливает data plane | Отклонена для minimum boundary |
| In-memory queue with shutdown drain | Не переживает process crash | Простая bounded queue без disk lifecycle | Дополнительной durable copy нет | Crash молча теряет принятые decisions | Отклонена |

## Implementation decomposition

```text
VIG-22-01 local durable store
├── VIG-22-02 request-path mandatory acceptance
├── VIG-22-03 Collector handoff and reclaim
└── VIG-22-04 packaged durability qualification
```

- [VIG-22-01](../epic_22/issue_22_01_local_durable_audit_store.md) реализует
  bounded WAL, force/recovery и public module seam, `4-5` дней.
- [VIG-22-02](../epic_22/issue_22_02_request_path_audit_acceptance.md) реализует
  durable-before-forwarding, exact HTTP errors, cancellation и shutdown,
  `4-5` дней; hard dependency VIG-22-01.
- [VIG-22-03](../epic_22/issue_22_03_collector_handoff_reclaim.md) реализует
  immutable segment adapter, acknowledged prefix и reclaim, `3-5` дней; hard
  dependency VIG-22-01.
- [VIG-22-04](../epic_22/issue_22_04_packaged_durability_qualification.md)
  публикует packaged/OCI crash and exhaustion evidence, `3-5` дней; hard
  dependencies VIG-22-02 и VIG-22-03.

Текущая frontier EPIC-22 содержит только VIG-22-01. После неё VIG-22-02 и
VIG-22-03 могут выполняться параллельно. Dependency graph acyclic.

## Критерии готовности

- [x] Различены четыре состояния: policy decision создан, store принял
  ownership, record durably retained, record доставлен external consumer.
- [x] Выбрано одно проверяемое значение минимальной durability guarantee;
  `logger.atInfo()` и best-effort stdout не объявляются durable acceptance.
- [x] Для normal success, supported-request failure, queue/storage exhaustion,
  process shutdown и crash перечислены exact externally observable outcomes.
- [x] Зафиксировано, может ли request завершиться успешно до audit acceptance и
  что происходит, если acceptance невозможно.
- [x] Safe schema сохраняет policy ID/version, decision, disposition, coverage,
  correlation и bounded aggregates без payload, matched text, identity values,
  credentials, locators или reversible hashes.
- [x] Resource ownership, byte/event bounds, backpressure и blocking boundary
  определены без file/network I/O на Netty event loop.
- [x] OUT-06 соблюдён: собственная SIEM, query UI и полное observability storage
  не добавлены.
- [x] Альтернативы, включая application-owned WAL и acknowledged external
  delivery, сравнены по durability, operability, privacy и failure semantics.
- [x] Выбранный contract разложен на готовые implementation leaves с hard
  dependencies, estimates, test seams и non-goals; placeholders без файла не
  считаются work items.
- [x] `./gradlew validateWorkItems` проходит после публикации decomposition.

## Test/demo seam

Normative contract matrix в
[единственном source-of-truth](../../MINIMUM_AUDIT_TRAIL_CONTRACT.md),
dependency graph и четыре опубликованных leaves. Каждая implementation issue
называет public process or module seam, на котором проверяется её durability
outcome.

## Не входит

Production implementation, выбор конкретного vendor/SIEM, raw audit payload,
traces/metrics retention, response inspection и enforcement reactions.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   minimum durable decision trail выбран
  Acceptance:   0.05  state, failure и dependency matrices complete
  Boundaries:   0.0   OUT-06 и safe schema сохранены
  Alternatives: 0.0   application-owned WAL выбран явно
  Assumptions:  0.15  persistent volume force semantics принадлежат deployment
  Aggregate:    0.04  decision issue complete
```
