# VIG-22-03: Acknowledged Collector handoff и reclaim

**Статус:** Done
**Epic:** [EPIC-22](../../epics/epic_22_durable_minimum_audit_trail.md)
**Ветка:** External-delivery tracer bullet > immutable segment acknowledgement
**Зависит от:** [VIG-22-01](issue_22_01_local_durable_audit_store.md)
**Блокирует:** [VIG-22-04](issue_22_04_packaged_durability_qualification.md)
**Оценка:** 3-5 инженерных дней
**Уверенность:** Medium

## Результат

External Collector получает immutable, self-verifying WAL segments, может
безопасно повторить delivery после crash и подтверждает только durably stored
contiguous prefix. Vigilant reclaim-ит segment bytes только после valid ack;
Collector outage не блокирует requests до исчерпания retained capacity.

## Требования

`MVP-18`, `MVP-19`, `OUT-06`;
[minimum audit trail contract](../../MINIMUM_AUDIT_TRAIL_CONTRACT.md).

## Критерии готовности

- [x] Active segment публикуется только после successful force and seal через
  atomic rename. Ready manifest содержит version, segment ID, first/last
  sequence, record count, byte size и digest без record-derived preview.
- [x] Segment seal срабатывает для exact max bytes, max age и graceful close;
  low-volume record становится доступна Collector не позднее age bound плюс
  bounded scheduling tolerance.
- [x] Documented file handoff является vendor-neutral public adapter:
  Collector читает ready segments в sequence order и не изменяет WAL files.
- [x] Ack публикуется атомарно и содержит version, segment ID, terminal
  sequence и digest. Ack означает, что весь segment durably retained external
  consumer; stdout write или network send без destination acknowledgement не
  считается delivery.
- [x] Vigilant принимает только valid contiguous ack prefix. Unknown,
  duplicate, out-of-order, missing-segment или digest-mismatched ack не
  продвигает reclaim и создаёт bounded safe operational error.
- [x] Valid ack переводит все records segment в `EXTERNALLY_DELIVERED`, после
  чего segment bytes и ack metadata удаляются идемпотентно. Crash до/после ack
  и до/после deletion восстанавливает один эквивалентный state без потери
  unacknowledged records.
- [x] Fake Collector crash после external store, но до ack, приводит к
  повторной delivery того же event ID. Contract явно at-least-once; consumer
  deduplicate-ит по event ID.
- [x] Collector outage не меняет request outcome, пока audit store имеет
  capacity. При достижении retained bound request path получает typed capacity
  failure и применяет `audit_unavailable` из VIG-22-02.
- [x] Ack watcher, seal и reclaim выполняются на store-owned blocking-safe
  worker и не добавляют network client или file I/O на Netty event loop.
- [x] Directory permissions, volume encryption, Collector credentials,
  destination retention/queryability и disaster recovery документированы как
  deployment/Collector responsibility.
- [x] Tests используют только synthetic safe records и проверяют отсутствие
  payload, identity, credentials, locators и reversible hashes в manifest,
  ack, errors и filenames.
- [x] Для всех добавленных и изменённых Kotlin/Java declarations, callbacks и
  test methods написан актуальный KDoc/Javadoc.
- [x] Focused segment/ack tests, fake-Collector process suite и
  `./gradlew build` проходят.

## Evidence

- `AuditSegmentHandoffTest` покрывает exact schema и filenames, force/seal для
  close, exact byte и age boundaries, worker ownership, contiguous reclaim,
  invalid acknowledgement matrix, self-verification и safe error metadata.
- `LocalAuditStoreCrashTest` причинно завершает процесс после ready rename,
  forced reclaimed high-water и segment deletion, затем проверяет
  эквивалентное восстановленное состояние без потери unacknowledged records.
- `AuditCollectorProcessTest` использует отдельный fake Collector process,
  crash после durable external store до ack, повторную delivery того же event
  ID, consumer deduplication и последующий acknowledged reclaim.
- `PiiShadowProxyServiceTest` с real Armeria доказывает, что Collector outage
  не меняет outcome до retained bound, capacity exhaustion даёт
  `audit_unavailable`, а valid ack восстанавливает admission.
- Focused audit/real-Armeria suite, project validators и полный
  `./gradlew build` проходят.

## Test/demo seam

Real shared filesystem между store и отдельным fake Collector process.
Causal barriers управляют external durable write, ack rename и Vigilant
reclaim. Process kills до и после каждого barrier плюс repeated recovery
проверяют at-least-once delivery и contiguous deletion без timing races.

## Не входит

Конкретный SIEM/vendor SDK, network protocol, Collector credentials, external
query UI, external retention policy, synchronous delivery перед request
completion, traces/metrics storage и изменение audit record schema.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   acknowledged segment handoff позволяет safe reclaim
  Acceptance:   0.10  seal, ack, crash и outage cases explicit
  Boundaries:   0.0   destination и Collector implementation external
  Alternatives: 0.15  atomic file adapter выбран без vendor protocol
  Assumptions:  0.20  Collector честно подтверждает durable destination write
  Aggregate:    0.09  below threshold (0.3 issue)
```
