# VIG-22-01: Локально durable audit store

**Статус:** Done
**Epic:** [EPIC-22](../../epics/epic_22_durable_minimum_audit_trail.md)
**Ветка:** Local durability module seam > bounded WAL, force and recovery
**Зависит от:** [VIG-21-01](../epic_21/issue_21_01_minimum_audit_trail_contract.md)
**Блокирует:** [VIG-22-02](issue_22_02_request_path_audit_acceptance.md), [VIG-22-03](issue_22_03_collector_handoff_reclaim.md)
**Оценка:** 4-5 инженерных дней
**Уверенность:** Medium

## Результат

Stable audit-store module принимает bounded safe record через reservation,
возвращает durable acknowledgement только после covering `force(true)` и
восстанавливает все acknowledged records после process crash. Queue, event,
segment и total-retained exhaustion имеют typed outcomes без silent loss,
unbounded allocation или I/O на caller thread.

Это допустимый module-seam slice: он предоставляет complete durability
capability с public lifecycle contract, которую независимо используют HTTP
integration и external-delivery adapter.

## Требования

`MVP-18`, `MVP-19`, `CONC-01`, `CONC-03`;
[minimum audit trail contract](../../MINIMUM_AUDIT_TRAIL_CONTRACT.md).

## Критерии готовности

- [x] Audit configuration содержит required persistent directory и exact
  normative contract defaults для event, pending-event, retained-byte, segment-byte и
  segment-age bounds; invalid, unavailable или already locked directory даёт
  startup exit code `2` без raw path details в safe operational event.
- [x] Public seam предоставляет one-shot reservation, immutable safe record,
  durable acknowledgement и typed `CAPACITY_EXHAUSTED`, `EVENT_TOO_LARGE`,
  `IO_FAILURE`, `CLOSED` outcomes. Illegal reuse и close идемпотентны.
- [x] Reservation atomically покрывает один pending event и worst-case framed
  record bytes до передачи ownership store. Cancellation до record creation
  освобождает всё ровно один раз.
- [x] Record codec реализует versioned UTF-8 JSON внутри length-delimited
  checksum frame; encoded size не превышает `65_536` bytes и не использует
  truncation для достижения bound.
- [x] Safe schema принимает все `CLEAN`, `DETECTED`, `INSPECTION_GAP`, `ERROR`
  decisions, empty/non-empty sorted policy references и каждый bounded
  aggregate class без payload, matched text, identity, credential, locator,
  raw exception или derived hash fields.
- [x] Persistent sequence и event ID однозначно идентифицируют record. Sequence
  восстанавливается без reuse после normal restart и valid crash recovery.
- [x] Durable future завершается успешно только после `force(true)`,
  покрывающего полный frame и recovery metadata. Write completion, queue
  acceptance и stdout logging не завершают future.
- [x] Group commit, если используется, подтверждает каждую record только после
  covering force и сохраняет deterministic completion для success и failure.
- [x] Segment seal выполняется по exact byte bound, age bound и store close;
  active плюс sealed accounting никогда не превышает configured retained bound.
- [x] Recovery matrix покрывает clean close, crash before first byte, partial
  header, partial body, checksum mismatch, complete pre-force frame и crash
  after force. Complete acknowledged records сохраняются, invalid tail не
  публикуется. Complete pre-force frame может сохраниться только как allowed
  orphan и не объявляется evidence original acknowledgement.
- [x] Exclusive process ownership, writer shutdown и repeated recovery не
  удаляют unacknowledged sealed segments.
- [x] Все file operations, blocking waits и force выполняются на store-owned
  blocking-safe worker; caller/event-loop seam получает asynchronous result.
- [x] Для всех добавленных и изменённых Kotlin/Java declarations и test methods
  написан актуальный KDoc/Javadoc.
- [x] Focused module tests, forked crash-helper tests и `./gradlew build`
  проходят.

## Evidence

- `AuditStoreSettingsTest`, `AuditRecordContractTest` и `LocalAuditStoreTest`
  покрывают exact defaults, schema/outcome matrix, independent maximum-width
  frame bound, rotation, lifecycle и best-effort terminal resource cleanup с
  сохранением primary/suppressed failures, включая abandoned initialized
  ownership после interrupted startup; все file-resource cleanup paths
  сериализованы на store worker.
- `LocalAuditStoreCrashTest` с forked `AuditCrashHelper` покрывает causal
  barriers до write, после write и после force, invalid tails и recovery.
- Focused audit/request/process suite и полный `./gradlew build` проходят.

## Test/demo seam

Public audit-store API над temporary real filesystem плюс forked JVM helper.
Parent process управляет causal barriers до write, после write до force и после
force, завершает helper принудительно и повторно открывает тот же directory.
Все waits bounded и выводят reservation, sequence, segment и last recovery
state без record body.

## Не входит

HTTP request integration, Armeria readiness, external Collector process,
segment acknowledgement/reclaim, network I/O, SIEM, response inspection и
изменение policy semantics.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   module возвращает только force-backed acknowledgement
  Acceptance:   0.10  lifecycle и crash matrix перечислены
  Boundaries:   0.0   HTTP и Collector вынесены в отдельные leaves
  Alternatives: 0.10  codec/framing implementation ограничен contract
  Assumptions:  0.20  real filesystem helper проверяет process-crash guarantee
  Aggregate:    0.08  below threshold (0.3 issue)
```
