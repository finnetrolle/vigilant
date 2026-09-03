# Epic 32: Best-effort stdout audit migration

**ID:** `EPIC-32`
**Тип:** Epic
**Статус:** In progress
**Приоритет:** High
**Суммарная оценка:** 7-10 инженерных дней
**Связанные требования:** `MVP-06`, `OBS-01`, `OBS-02`

## Контекст и целевой результат

Этот epic является decomposition successor первоначального Draft
`VIG-32`. Он заменяет обязательный application-owned durable audit минимальным
best-effort audit через существующий JSONL stdout pipeline, а затем удаляет
ставшие ненужными WAL, Collector handoff и их runtime consumers.

После завершения epic audit не участвует в admission, policy decision,
readiness или traffic forwarding. Невозможность поставить event в logging
queue либо записать его в stdout не меняет HTTP outcome и не запрещает
upstream handoff. Durable storage, rotation и delivery stdout принадлежат
container runtime и deployment.

## Нормативные решения

- Единственный application-level audit sink и queue - существующий Logback
  `AsyncAppender` с `neverBlock=true`, направленный в stdout.
- Custom audit queue, worker, batching, drop-oldest algorithm, file sink, WAL,
  manifest, acknowledgement, Collector protocol и audit configuration не
  создаются и не сохраняются.
- Queue overload или stdout failure допускает потерю event. Application не
  публикует audit queue depth/drop metrics, callback, rate-limited alert или
  отдельный technical event.
- Для каждого реально начатого направления анализа публикуется одна пара:
  `policy.analysis_started` непосредственно перед первым detector execution и
  `policy.analysis_completed` после terminal outcome.
- `phase` имеет значение `REQUEST` или `RESPONSE`. REQUEST pair принадлежит
  VIG-32-01; RESPONSE pair использует тот же schema contract в owning
  enforcement leaves EPIC-20.
- Events содержат только safe aggregate data и correlation одного inspection
  span. Span IDs генерируются server-side; trace ID либо генерируется
  server-side, либо продолжает валидный W3C parent по existing tracing contract.
  Payload, content preview, PII value/span, headers, credentials, user ID,
  groups, session ID и raw user-controlled correlation values запрещены.
- `analysis_completed` использует outcome `CLEAN`, `DETECTED`,
  `INSPECTION_GAP` или `ERROR`. Для успешного анализа указывается final
  reaction; для `ERROR` reaction отсутствует и присутствует только stable
  `error.code`.
- Response source не является audit storage. Его временный in-memory lifecycle
  принадлежит EPIC-20 и не меняется этим epic.

## Карта декомпозиции

```text
EPIC-32 Best-effort stdout audit migration
+-- migrate request audit contract
|   +-- VIG-32-01 stdout REQUEST started/completed pair
|       +-- operator-visible JSONL schema
|       +-- no durable acknowledgement before outcome or handoff
|       +-- causal and privacy evidence
+-- contract old durable subsystem
    +-- VIG-32-02 durable audit removal
        +-- WAL, recovery, segment and Collector handoff
        +-- admission, readiness and shutdown integration
        +-- configuration, packaging and qualification consumers
        +-- current runtime documentation and diagrams
```

## Delivery graph

```text
VIG-32-01 Stdout request audit migration
    |
    +-- blocks VIG-32-02 Durable audit removal
    +-- publishes schema for VIG-20-02 and VIG-34
```

## Дочерние issues

- [x] [VIG-32-01: Stdout request audit migration](../issues/epic_32/issue_32_01_stdout_request_audit_migration.md) - `Done`
- [ ] [VIG-32-02: Durable audit removal](../issues/epic_32/issue_32_02_durable_audit_removal.md) - `Ready for implementation`

## Не входит

- Response parsing, source ownership, SSE и enforcement. Owning response
  leaves только используют опубликованный stdout schema.
- Новые policy decisions, detectors, protocols, identity behavior или
  enforcement reactions.
- Прямой network exporter, vendor client, application-owned persistence или
  юридически значимая delivery guarantee.
- Удаление completed EPIC-21/EPIC-22 work items и versioned historical evidence.
- Устранение временного response buffer до policy decision.

## Критерии готовности epic

- VIG-32-01 и VIG-32-02 имеют status `Done`, а checklist и
  `spec/WORK_ITEMS.md` обновлены в тех же change sets.
- REQUEST inspection публикует ровно одну safe non-blocking stdout pair только
  для реально начатого analysis; logging failure не меняет HTTP outcome или
  upstream handoff.
- WAL, recovery, Collector handoff, durable reservation/acknowledgement,
  audit-driven admission/readiness, audit configuration, packaging volume и
  durability-only runtime consumers отсутствуют.
- VIG-20-02 и VIG-34 используют VIG-32-01 как schema dependency; current docs
  описывают stdout-only behavior, а исторические completed work items и
  qualification evidence явно отделены от current runtime.
- Новые и изменённые Kotlin/Java declarations, test methods и lifecycle helpers
  имеют актуальный KDoc/Javadoc. Focused tests, `./gradlew validateWorkItems` и
  `./gradlew build` проходят.

## Ambiguity Report

```text
Goals:        0.0   stdout-only outcome и removal boundary определены
Acceptance:   0.10  complete criteria распределены между двумя leaves
Boundaries:   0.05  response behavior и historical evidence явно исключены
Alternatives: 0.0   Logback AsyncAppender выбран как единственная queue
Assumptions:  0.10  migration должна завершиться до удаления subsystem
Aggregate:    0.05  Ready for implementation.
```
