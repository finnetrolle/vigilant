# VIG-22-04: Packaged durability qualification

**Статус:** Done

> Historical evidence only: qualification surface superseded and removed by
> [EPIC-32](../../epics/epic_32_best_effort_stdout_audit.md); `Done` is unchanged.
**Epic:** [EPIC-22](../../epics/epic_22_durable_minimum_audit_trail.md)
**Ветка:** Production evidence > packaged crash, exhaustion and lifecycle matrix
**Зависит от:** [VIG-22-02](issue_22_02_request_path_audit_acceptance.md), [VIG-22-03](issue_22_03_collector_handoff_reclaim.md), [VIG-22-05](issue_22_05_audit_exhaustion_admission_mapping.md)
**Блокирует:** завершение EPIC-22
**Оценка:** 3-5 инженерных дней
**Уверенность:** Medium

## Результат

Versioned packaged-process и OCI evidence причинно доказывает EPIC-22
durability guarantee для normal decisions, supported failures, capacity/I/O
exhaustion, graceful shutdown, process crash, recovery и external Collector
acknowledgement. Report отделяет proven process/volume semantics от hardware
failure assumptions.

VIG-22-05 разделила lifecycle admission и composite readiness. Повторный
packaged/OCI run прошёл полный fail-closed matrix без production-правок внутри
qualification leaf.

## Текущее evidence

[Versioned PASS report](../../../docs/durability-qualification-2026-08-31.md)
сохраняет полную матрицу, environment, OCI image ID и force assumptions.
Команда `./gradlew durabilityQualification` завершилась успешно: каждый
decision, exhaustion, crash, recovery, shutdown, Collector, installed и OCI
row имеет GREEN evidence.

## Требования

`MVP-18`, `MVP-19`, `OUT-06`, `CONC-01`, `CONC-03`; все строки
[minimum audit trail contract](../../MINIMUM_AUDIT_TRAIL_CONTRACT.md).

## Критерии готовности

- [x] Installed distribution и OCI image запускаются с fixed JVM settings,
  persistent mounted audit directory, exact audit bounds, real Armeria upstream
  и отдельным fake Collector process. Environment, filesystem и command
  versioned в report.
- [x] Decision matrix включает `CLEAN`, `DETECTED`, `INSPECTION_GAP`, detector
  `ERROR` и supported fail-closed parser/source/identity/inspection failures.
  Для каждого case проверены exact client response, upstream body demand/bytes,
  one durable record и safe schema.
- [x] Admission queue, event-size, retained-byte и filesystem write/force
  exhaustion дают exact `503 audit_unavailable`, no upstream observation и
  `/readyz=503` без payload leakage или unbounded retry.
- [x] Causal crash matrix завершает gateway до write, после write до force,
  после force до upstream handoff, после handoff до client response, после
  external store до ack и после ack до reclaim. Ни один case не доказывается
  timestamps или sleep-only assertions.
- [x] Restart на том же volume проверяет exact recovered sequence, valid record
  set, partial-tail removal, no acknowledged-record loss и допустимые orphan
  records для complete pre-force frame и force-before-response crash.
- [x] Graceful SIGTERM сначала наблюдает readiness `503`, затем завершает
  admitted append, seal и close в bounded deadline. Forced termination не
  превращает unforced tail в accepted record.
- [x] Collector-outage phase заполняет configured retained capacity, проверяет
  fail-closed request, затем публикует valid ack и причинно наблюдает reclaim,
  readiness recovery и успешный новый request.
- [x] At-least-once phase подтверждает duplicate event ID после Collector crash
  и deduplication external consumer без duplicate local sequence.
- [x] Synthetic fixtures не содержат production payload или raw PII. Body,
  matched-text, identity, session, credentials, query, locators и reversible
  hashes отсутствуют в WAL, manifests, acks, stdout, errors и report.
- [x] Report явно фиксирует, что successful `force(true)` на persistent volume
  не покрывает volume loss, storage corruption, operator deletion или broken
  hardware flush semantics.
- [x] Runtime, configuration, deployment, observability и architecture docs
  описывают фактический durable boundary, `audit_unavailable`, required volume,
  Collector ownership и remaining non-goals без объявления Vigilant SIEM.
- [x] Production code не меняется внутри qualification leaf. Обнаруженный
  runtime defect получает отдельную RED-first issue с exact failing case.
- [x] Qualification command, `./gradlew validateWorkItems` и
  `./gradlew build` проходят; versioned report сохраняется в `docs/`.

## Test/demo seam

Packaged `MainKt` и OCI container, real upstream, persistent test volume,
forked fake Collector и deterministic control channel. Barriers публикуются
после фактического write/force/handoff/store/ack/reclaim observation. Каждый
wait bounded и печатает только safe last-known process and sequence state.

## Не входит

Performance SLO redesign, production optimization без failing gate, disk-loss
simulation, vendor SIEM integration, Kubernetes/Helm, response inspection,
enforcement reactions и исправление найденного production defect без
отдельного TDD issue.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   packaged evidence закрывает полный durability matrix
  Acceptance:   0.10  exact process barriers и outcomes перечислены
  Boundaries:   0.0   qualification не скрывает production fixes
  Alternatives: 0.10  control-channel mechanics остаются test-local
  Assumptions:  0.20  persistent volume force semantics фиксируются как environment
  Aggregate:    0.08  below threshold (0.3 issue)
```
