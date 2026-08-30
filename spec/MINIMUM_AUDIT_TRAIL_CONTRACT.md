# Minimum durable audit trail contract

Этот документ является единственным нормативным источником detailed lifecycle,
schema, resource и failure semantics минимального обязательного audit trail.
EPIC-22 владеет delivery outcome и декомпозицией, а дочерние issues владеют
своими independently grabbable acceptance criteria.

Связанные требования: `MVP-18`, `MVP-19`, `OUT-06`, `CONC-01`, `CONC-03`.

## Durability guarantee

Минимальный обязательный audit trail хранится в application-owned
сегментированном write-ahead log на явно настроенном durable volume. Текущий
`policy.shadow_decision` в async stdout может оставаться безопасной
observability-проекцией, но его публикация, очередь и доставка не являются
durable acceptance.

Для каждого supported request, который создал policy decision или
типизированный supported-request error, действует гарантия:

> Vigilant не передаёт первый request byte upstream и не возвращает normal
> supported-request outcome клиенту, пока соответствующая safe audit record не
> стала `DURABLY_RETAINED`.

Если durable acceptance невозможно, request не достигает upstream и получает
`503 {"error":"audit_unavailable"}`, пока соединение позволяет доставить HTTP
response. Сам `audit_unavailable` не обязан иметь durable record, потому что
этот outcome означает недоступность обязательного audit boundary. Он
наблюдается через readiness и best-effort operational telemetry.

External Collector не находится в request critical path. Он асинхронно
переносит immutable WAL segments во внешнее хранилище и подтверждает только
durably stored contiguous segment prefix. Request success не ждёт external
delivery. Не подтверждённые segments не удаляются; если они исчерпали
настроенный storage bound, новые supported requests fail closed.

## Lifecycle states

Четыре состояния различаются нормативно:

1. `DECISION_CREATED` - immutable safe record собрана в памяти из policy
   decision или supported-request error. Durability и ownership store ещё не
   подтверждены.
2. `STORE_OWNED` - WAL проверил record, назначил persistent sequence, принял
   ownership и зарезервировал место в bounded writer queue и на диске.
   Состояние остаётся volatile и не разрешает forwarding или normal response.
3. `DURABLY_RETAINED` - полный framed record и необходимые recovery metadata
   записаны, а `FileChannel.force(true)` успешно завершился для диапазона,
   содержащего record. Только этот переход завершает durable acceptance,
   разрешает upstream handoff или исходный supported-request error.
4. `EXTERNALLY_DELIVERED` - Collector durably сохранил весь immutable segment и
   атомарно подтвердил его ID, terminal sequence и digest. Это состояние
   разрешает reclaim segment, но не влияет на уже завершённый request.

`STORE_OWNED` и `DURABLY_RETAINED` намеренно не объединяются: постановка в
очередь, write без force и `logger.atInfo()` не являются durability evidence.

## Durability boundary и assumptions

Гарантия переживает process crash, container restart и host restart при
условии, что настроенный volume сохраняется и filesystem/storage корректно
выполняет successful `force(true)`. Она не обещает восстановление после утраты
volume, storage corruption, operator deletion или оборудования, которое
нарушает подтверждённые flush semantics.

WAL имеет ровно одного process owner, подтверждённого exclusive lock. Startup
с отсутствующим, недоступным или уже занятым audit directory завершается с
exit code `2`. После restart owner сканирует segments по persistent sequence,
проверяет framing и checksum, сохраняет каждый complete valid frame, видимый на
volume, и отбрасывает только незавершённый tail после последней valid record.
Complete frame, записанный до force, но переживший process crash, может
восстановиться как conservative orphan: это допустимая лишняя audit record, но
не evidence того, что original request получил durable acknowledgement.
Recovery никогда не превращает partial tail в record.

Crash после `DURABLY_RETAINED`, но до forwarding или client response, может
оставить audit record для request, успех которого клиент не наблюдал. Обратный
случай запрещён: forwarded request или normal supported-request response без
durably retained record не допускается.

## Safe versioned schema

Record body является versioned UTF-8 JSON, окружённым length-delimited frame с
checksum. Он содержит только:

- schema version, globally unique event ID и persistent monotonic sequence;
- creation timestamp и trace ID для correlation;
- `protocol`, `phase`, `decision`, `disposition` и inspection `coverage`;
- sorted policy ID/version и detector ID/version;
- inspected-fragment count;
- total finding count и sorted bounded counts по PII type и evidence strength;
- evaluation duration;
- один bounded stable `error.code` для supported-request failure.

Encoded record не превышает `64 KiB`. Startup отклоняет policy snapshot, чей
worst-case safe record не помещается в этот bound. Record не содержит и не
производит:

- request/response payload, content preview или matched text;
- offsets, locators, filenames, media URLs, raw URI или query;
- identity values, session values, headers, cookies или credentials;
- raw exception messages или stack traces;
- payload-, identity- или credential-derived hashes, включая reversible
  hashes;
- individual findings или другие unbounded collections.

Event ID является deduplication key external consumer. Trace ID используется
только для correlation и не заменяет event ID.

## Resource и execution contract

Audit storage имеет следующие startup settings:

```text
directory              required, persistent and exclusively locked
maxEventBytes          65_536
maxPendingEvents       128
maxRetainedBytes       1_073_741_824
maxSegmentBytes        16_777_216
maxSegmentAge          5 seconds
```

Все bounds положительны, `maxEventBytes <= maxSegmentBytes <=
maxRetainedBytes`. In-memory ownership не превышает
`maxPendingEvents * maxEventBytes` плюс bounded queue metadata. Disk accounting
включает active и sealed, но ещё не reclaimed segments вместе с framing и
manifest metadata.

Supported descriptor резервирует audit admission token до identity extraction
и body demand. Token покрывает один pending event и worst-case disk record.
Reservation failure не требует body и даёт `audit_unavailable`. Cancellation
до `DECISION_CREATED` освобождает token без record. После
`DECISION_CREATED` workflow сохраняет reservation и обязан передать record
store независимо от HTTP cancellation. Ownership переходит к store только в
`STORE_OWNED` и после этого сохраняется до durable outcome.

File operations, force, recovery, sealing и reclaim выполняются одним
store-owned blocking-safe worker. Netty event loop только создаёт reservation,
передаёт immutable record и ожидает asynchronous completion без file или
network I/O. Group commit допустим: один successful force может перевести
несколько records в `DURABLY_RETAINED`, но completion каждой record происходит
только после force, покрывающего её frame.

Segments seal-ятся при достижении byte bound, age bound и graceful shutdown.
Store никогда не удаляет unacknowledged segment. Collector outage сам по себе
не меняет request outcome, пока остаётся reserved capacity.

## Exact outcome matrix

| Сценарий | Audit outcome | HTTP/upstream outcome | External observation |
|---|---|---|---|
| Normal `CLEAN`, `DETECTED`, `INSPECTION_GAP` или detector `ERROR` decision | Record достигает `DURABLY_RETAINED` до transport handoff | Original request только после durable acceptance; upstream response сохраняется | stdout mirror может быть потерян; WAL record остаётся authoritative |
| Supported identity, source, parser, context или inspection failure | `ERROR` record достигает `DURABLY_RETAINED` | Исходный stable `4xx`, `5xx` или safe inspection outcome возвращается только после durable acceptance; upstream не вызывается для fail-closed case | WAL содержит bounded `error.code` |
| Admission queue или retained-byte capacity exhausted | Новая record не принимается | `503 {"error":"audit_unavailable"}`, body не demand-ится, upstream не вызывается | `/readyz=503` до восстановления capacity; operational telemetry best effort |
| Write, force, seal или recovery I/O failure | Record не объявляется durable | `503 audit_unavailable`, если HTTP delivery ещё возможна; upstream не вызывается | Readiness остаётся `503`; process startup failure даёт exit code `2` |
| Collector недоступен | Durable records остаются в sealed segments | Requests продолжаются только пока есть capacity | Ack не продвигается; при исчерпании capacity применяется предыдущая строка |
| Graceful shutdown | Новые admissions запрещены; pending `STORE_OWNED` records force-ятся, active segment seal-ится | Readiness сначала `503`; request без durable acceptance не forward-ится | Normal exit только после bounded drain и close; sealed segments доступны Collector |
| Process crash до successful force | Durable acknowledgement отсутствует; partial tail удаляется, а complete checksum-valid frame может восстановиться как orphan | Успешный forwarding или normal response для этой record запрещён | После restart видны только complete valid records; наличие orphan не доказывает original acceptance |
| Process crash после successful force | Record восстанавливается; может ещё не иметь external ack | Request мог не получить response, но record не теряется | Collector повторяет segment; event ID обеспечивает deduplication |
| Client cancellation до decision | Reservation освобождается, record не требуется | Upstream не вызывается | Durable audit отсутствует, потому что decision не создан |
| Client cancellation после decision | Workflow завершает handoff в `STORE_OWNED`, затем store завершает durable append независимо от HTTP cancellation | Upstream не вызывается после cancellation | Record остаётся с тем же event ID и trace correlation |

## Collector ownership и delivery semantics

Sealed segment публикуется атомарным rename вместе с manifest, содержащим
segment ID, first/last sequence и digest. Collector читает segments в sequence
order и может повторять передачу после crash. Delivery является at-least-once;
external consumer обязан deduplicate по event ID.

Collector создаёт acknowledgement только после durable retention всего
segment во внешнем destination. Ack содержит segment ID, terminal sequence и
digest. Vigilant принимает только contiguous valid ack prefix и после этого
может удалить соответствующие segment и ack metadata. Invalid, forged,
out-of-order или mismatched ack не продвигает reclaim и создаёт safe
operational error без содержимого records.

Collector, его credentials, destination availability, external retention,
queryability и disaster recovery находятся за границей Vigilant. Deployment
обязан защитить local volume permissions и encryption at rest. Vigilant не
становится SIEM и не хранит traces, metrics или произвольные application logs.

## Non-goals

- Собственная SIEM, audit query UI, dashboard, alerting или search index.
- Хранение traces, metrics, raw application logs или traffic payload.
- Выбор конкретного external vendor, Collector distribution или destination.
- Synchronous network delivery в request critical path.
- Application-level encryption/key management поверх защищённого deployment
  volume.
- Response inspection, SSE spooling, enforcement reactions и новые protocols.
- Перезапись или masking исходного request.
