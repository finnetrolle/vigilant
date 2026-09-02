# VIG-32-02: Durable audit removal

**Статус:** Ready for implementation
**Epic:** [EPIC-32](../../epics/epic_32_best_effort_stdout_audit.md)
**Ветка:** Contract old durable subsystem > remove all current consumers
**Зависит от:** [VIG-32-01](issue_32_01_stdout_request_audit_migration.md)
**Блокирует:** [VIG-36](../issue_36_superseded_requirements_cleanup.md)
**Оценка:** 4-5 инженерных дней
**Уверенность:** Medium

## Результат

После работающей stdout request migration приложение и distribution больше не
содержат или не требуют application-owned durable audit: WAL, recovery,
segments, manifests, Collector handoff, reservation/acknowledgement,
audit-driven admission/readiness, persistent directory и durability
qualification удалены. Logging failure не меняет traffic, readiness, startup,
shutdown или upstream handoff.

Это contract phase схемы expand, migrate, contract. Она удаляет только старый
audit path и его consumers, не меняя stdout schema VIG-32-01 и не добавляя
заменяющий delivery subsystem.

## Public seam и TDD slices

Основной seam - installed distribution и OCI image, запускаемые без audit
directory/settings против real Armeria upstream. Первый RED case доказывает,
что current packaged process ещё требует persistent audit configuration; GREEN
запускает его без неё и проводит supported request до upstream при живом stdout
audit contract.

Последующие vertical slices по одному удаляют behavioral consumers:
audit-based admission/readiness, shutdown/storage lifecycle, packaging volume
и durability qualification surface. Static absence checks дополняют, но не
заменяют runtime seam.

## Полный removal inventory

- Production audit domain/store/codec/file I/O/WAL/segment/recovery/Collector
  handoff и durable reservation/acknowledgement API.
- Audit store wiring в request workflow, Metro graph и startup/shutdown;
  `audit_unavailable`, audit capacity/health admission и readiness conditions.
- Audit directory и все audit bounds в HOCON/env loading, validation, example
  config, shared process launch fixtures и local run instructions.
- Docker persistent audit directory/volume and related ownership setup.
- Audit-only unit/process/crash/Collector tests, fake Collector, qualification
  sources/configs, Gradle source sets/tasks и generated current report surface.
- Current runtime, architecture, configuration, deployment, development,
  observability and README references, including audit lifecycle/component/
  request-sequence diagrams and Collector handoff as an active adapter.

Completed EPIC-21/EPIC-22 work items, the normative historical contract and the
versioned durability qualification report are not deleted. They receive a clear
superseded/historical pointer to EPIC-32 where needed and are excluded from
current runtime navigation or claims.

## Требования

`MVP-06`, `OBS-01`, `OBS-02`; current startup, readiness, shutdown, packaging
and documentation contracts; [EPIC-32](../../epics/epic_32_best_effort_stdout_audit.md).

## Критерии готовности

- [ ] `src/main` не содержит `AuditStore`, `LocalAuditStore`, durable record
  codec, WAL/segment/recovery/manifest/ack/Collector file I/O или
  reservation/force-backed acknowledgement path. Request stdout audit остается
  единственным application-owned audit behavior.
- [ ] Real-Armeria request matrix подтверждает, что clean/detected/gap/error
  outcomes и upstream handoff не зависят от audit capacity, filesystem, force
  или Collector. `audit_unavailable` больше не является runtime response.
- [ ] `/readyz` отражает только remaining readiness contract и shutdown, а не
  audit directory/capacity/health. Graceful shutdown не останавливает audit
  admissions, не ждёт append/force/seal/reclaim и не управляет Collector
  lifecycle.
- [ ] Env-only, HOCON, installed-distribution и OCI startup работают без audit
  directory и `VIGILANT_AUDIT_*`; неизвестные legacy settings не создают
  compatibility mode. Shared launch fixtures больше не создают audit paths.
- [ ] Dockerfile/image не объявляет persistent audit directory или volume.
  Build не содержит durability qualification source set/task, audit-only test
  task или fake Collector; обычные current verification tasks сохраняются.
- [ ] Current docs и UML sources описывают stdout-only audit и stateless
  replica. Collector file handoff и durability qualification удалены из current
  navigation; historical contract/work items/report сохранены и явно помечены
  superseded без изменения их `Done` status или прошлого evidence.
- [ ] Repository-wide search подтверждает отсутствие active references на
  removed symbols, settings, `audit_unavailable`, persistent audit volume,
  Collector handoff и durability task вне явно historical artifacts.
- [ ] Нет нового sink, queue, worker, exporter, retry, metric, alert,
  compatibility setting или unrelated policy/identity/enforcement behavior.
- [ ] Все добавленные и изменённые Kotlin/Java declarations, lifecycle helpers
  и test methods имеют актуальный KDoc/Javadoc. Зафиксированы expected RED,
  focused GREEN, affected startup/readiness/shutdown/process/OCI suites,
  `./gradlew validateWorkItems` и `./gradlew build`.

## Не входит

- Изменение stdout schema или REQUEST event timing из VIG-32-01.
- RESPONSE audit pair, response source/parser/SSE и request/response
  enforcement.
- Удаление или изменение статуса completed epics/issues и historical evidence.
- Полная cleanup всех superseded requirements/architecture snapshots beyond
  direct durable-audit consumers. Более широкий inventory принадлежит VIG-36.

## Ambiguity Report

```text
Goals:        0.0   durable subsystem и его consumers удаляются полностью
Acceptance:   0.10  runtime, packaging, tests и docs inventory explicit
Boundaries:   0.05  historical evidence и unrelated cleanup сохранены
Alternatives: 0.0   replacement subsystem явно запрещен
Assumptions:  0.15  known current consumers исчерпываются repository search
Aggregate:    0.06  Ready for implementation.
```
