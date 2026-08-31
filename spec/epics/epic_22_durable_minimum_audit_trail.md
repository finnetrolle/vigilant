# Epic 22: Durable minimum audit trail

**ID:** `EPIC-22`
**Тип:** Epic
**Статус:** Done
**Приоритет:** High
**Суммарная оценка:** 15-22 инженерных дня
**Связанные требования:** `MVP-18`, `MVP-19`, `OUT-06`, `CONC-01`, `CONC-03`

## Контекст и целевой результат

Источник delivery epic - завершённая
[VIG-21-01](../issues/epic_21/issue_21_01_minimum_audit_trail_contract.md).
Epic реализует application-owned WAL boundary, включает его в supported
request path, добавляет acknowledged Collector handoff и публикует packaged
durability evidence.

Подробные lifecycle states, schema, resource bounds, exact outcome matrix и
Collector semantics принадлежат отдельному нормативному
[minimum audit trail contract](../MINIMUM_AUDIT_TRAIL_CONTRACT.md). Дочерние
issues ссылаются на этот contract и содержат только acceptance criteria своего
implementation slice. Этот parent epic владеет outcome, boundaries,
dependencies и общим completion state, но не копирует leaf-level criteria.

## Требования

- Каждый supported normal outcome или supported-request error проходит
  нормативную durable acceptance boundary до transport/result publication.
- Невозможность mandatory audit acceptance не допускает silent loss или
  forwarding без record.
- External delivery не входит в request critical path и сохраняет bounded
  ownership semantics.
- Implementation и qualification leaves независимо проверяемы после
  завершения перечисленных hard dependencies.

## Карта декомпозиции

```text
EPIC-22 Durable minimum audit trail
├── local durability module seam
│   └── bounded WAL, framing, force, recovery and typed outcomes
├── request-path tracer bullet
│   ├── admission, durable-before-forwarding, errors and shutdown
│   └── exact audit-unavailable mapping through production admission
├── external-delivery tracer bullet
│   └── immutable segments, acknowledged prefix and reclaim
└── production evidence
    └── packaged and OCI crash/exhaustion/lifecycle matrix
```

## Дочерние issues

- [x] [VIG-22-01: Локально durable audit store](../issues/epic_22/issue_22_01_local_durable_audit_store.md) - `Done`
- [x] [VIG-22-02: Mandatory audit acceptance в request path](../issues/epic_22/issue_22_02_request_path_audit_acceptance.md) - `Done`
- [x] [VIG-22-03: Acknowledged Collector handoff и reclaim](../issues/epic_22/issue_22_03_collector_handoff_reclaim.md) - `Done`
- [x] [VIG-22-05: Exact audit exhaustion response через production admission chain](../issues/epic_22/issue_22_05_audit_exhaustion_admission_mapping.md) - `Done`
- [x] [VIG-22-04: Packaged durability qualification](../issues/epic_22/issue_22_04_packaged_durability_qualification.md) - `Done`

VIG-22-01..05 завершены. Versioned packaged/OCI qualification подтверждает
полный decision, exhaustion, crash, recovery, shutdown и Collector matrix,
включая exact separation `audit_unavailable` и lifecycle `draining`.

## Не входит

- Собственная SIEM, audit query UI, dashboard, alerting или search index.
- Хранение traces, metrics, raw application logs или traffic payload.
- Выбор конкретного external vendor, Collector distribution или destination.
- Synchronous network delivery в request critical path.
- Application-level encryption/key management поверх защищённого deployment
  volume.
- Response inspection, SSE spooling, enforcement reactions и новые protocols.
- Перезапись или masking исходного request.

## Критерии готовности

- VIG-22-01..05 имеют status `Done`, а checklist и `WORK_ITEMS.md` обновлены в
  тех же change sets.
- Каждый outcome и boundary из
  [minimum audit trail contract](../MINIMUM_AUDIT_TRAIL_CONTRACT.md) имеет
  dynamic evidence в owning child issue.
- Runtime, configuration, deployment и observability documentation обновлены
  по фактически завершённой implementation.
- Для всех добавленных и изменённых Kotlin/Java declarations написан актуальный
  KDoc/Javadoc.
- Project validators, build и versioned packaged/OCI qualification проходят.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   durable minimum decision trail выбран
  Acceptance:   0.10  child completion и contract evidence заданы
  Boundaries:   0.0   OUT-06 и safe schema ограничивают storage
  Alternatives: 0.05  WAL выбран, external ack не находится в request path
  Assumptions:  0.15  force semantics и persistent volume являются deployment contract
  Aggregate:    0.06  below threshold (0.2 epic)
```
