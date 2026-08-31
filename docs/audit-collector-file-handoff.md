# Audit Collector file handoff

Этот документ задаёт публичный vendor-neutral adapter между Vigilant и внешним
Audit Collector. Adapter использует общий persistent filesystem. Он не задаёт
SIEM, network protocol, credentials или формат внешнего destination.

## Владение файлами

Vigilant является единственным writer WAL и владельцем audit directory lock.
Collector может читать опубликованные WAL segments и создавать только
acknowledgement files. Collector не изменяет, не переименовывает и не удаляет
WAL, manifests или metadata Vigilant.

Collector игнорирует `.active`, `.tmp`, lock и sequence metadata. Ready segment
считается опубликованным только когда существует manifest с именем
`segment-<20 decimal digits>.ready.json`. Соответствующий immutable WAL имеет
имя `segment-<20 decimal digits>.wal`. Segment появляется atomic rename после
successful `force(true)`; manifest force-ится и публикуется atomic rename
последним. Поэтому WAL без ready manifest ещё не является delivery work item.

## Ready manifest version 1

Manifest является UTF-8 JSON object не больше 512 bytes и содержит ровно:

~~~json
{
  "version": 1,
  "segment_id": "segment-00000000000000000001",
  "first_sequence": 1,
  "last_sequence": 42,
  "record_count": 42,
  "byte_size": 123456,
  "digest": "lowercase-sha256-of-complete-wal"
}
~~~

`segment_id` выводится только из first sequence и не является hash или preview
record. `digest` является SHA-256 complete safe WAL bytes и используется только
для integrity. Manifest не содержит payload, matched text, identity, session,
credentials, headers, cookies, locators, URI/query или производные от них
hashes.

Collector выбирает manifests по `first_sequence` в возрастающем порядке,
проверяет exact `byte_size` и `digest`, затем переносит весь immutable segment.
Каждый WAL record содержит globally unique `event_id`. Delivery является
at-least-once, поэтому external consumer обязан дедуплицировать records по
`event_id`.

## Acknowledgement version 1

Ack означает только одно: весь segment durably retained внешним destination,
а destination acknowledgement уже получен. Успешная запись в stdout, local
Collector queue или network send без destination acknowledgement не разрешает
ack.

Collector создаёт UTF-8 JSON object не больше 512 bytes с ровно четырьмя
полями:

~~~json
{
  "version": 1,
  "segment_id": "segment-00000000000000000001",
  "terminal_sequence": 42,
  "digest": "same-lowercase-sha256-as-ready-manifest"
}
~~~

Публикация выполняется в том же audit directory:

1. создать `segment-<id>.ack.json.tmp`;
2. записать complete JSON и успешно выполнить `force(true)`;
3. atomic rename в `segment-<id>.ack.json`;
4. force directory entry согласно guarantees выбранного filesystem.

Vigilant читает только final `.ack.json`. Он принимает только exact ack для
старейшего unreclaimed ready segment. Valid contiguous prefix сначала
force-backed переводится в `EXTERNALLY_DELIVERED`, затем WAL, manifest и ack
удаляются. Unknown, duplicate, out-of-order, missing-segment, terminal- или
digest-mismatched ack, как и local segment integrity mismatch, не продвигает
prefix и создаёт bounded safe operational error без filename, path или record
contents.

Crash до ack приводит к redelivery. Crash после ack и в любой точке reclaim
восстанавливается через force-backed delivered high-water mark: unacknowledged
records не удаляются, а уже подтверждённый prefix очищается идемпотентно.

## Operational ownership

Deployment отвечает за:

- ACL/permissions audit volume для gateway и Collector service accounts;
- encryption at rest всего local и external storage;
- безопасное хранение и ротацию Collector/destination credentials вне
  Vigilant configuration и WAL;
- availability, retention, queryability и deduplication external destination;
- backup, restore и disaster recovery local volume и external destination;
- соответствие filesystem/storage semantics успешному `force(true)` и atomic
  same-directory rename.

Vigilant не предоставляет Collector distribution, vendor client, external
retention policy, query UI или disaster-recovery automation.
