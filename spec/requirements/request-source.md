# Bounded request source

Нормативный owner bounded in-memory ingest, read/replay, quota и ownership для
[CONC-01..04 и PROXY-02](../MVP_NON_FUNCTIONAL_REQUIREMENTS.md#ресурсы-и-cancellation).
Protocol parser читает source по
[Chat Completions contract](chat-completions-protocol.md), а выбор original или
patched replay принадлежит [request enforcement](request-enforcement.md).
Response retention является отдельной capability и не использует эти limits.

## Ownership model

Один supported request получает ровно одного `BoundedRequestSourceOwner`.
Создание atomically резервирует concurrent owner slot до первого body demand.
При отсутствии slot возвращается `INSPECTION_CAPACITY_EXHAUSTED` без owner.

Public lifecycle:

```text
NEW -> INGESTING -> COMPLETE -> CLOSED
          |             |
          +-> REJECTED <-+
```

Owner хранит exact concatenated bytes. Transport chunk boundaries не являются
частью replay contract. Только idempotent owner close освобождает slot, bytes,
segments, compact patch plan и scratch. View/parser не получают ownership и не
освобождают quota.

Complete owner допускает только один active sequential access lease. Read-only
view single-use и не раскрывает mutable storage. Original или patched replay
single-subscriber и demand-driven. Concurrent view/replay, два replay,
повторная subscription и использование после close отклоняются stable typed
outcome и не крадут ownership у первого consumer.

## Resource bounds

Runtime defaults configurable и равны:

| Limit | Default |
|---|---:|
| bytes одного request | `8_388_608` |
| retained bytes process | `67_108_864` |
| concurrent owners | `128` |
| storage segments одного owner | `128` |

Все limits положительны, global bytes не меньше per-request bytes. Current
runtime default 8 MiB не заменяет product target
[16 MiB aggregate text / 20 MiB raw](../MVP_NON_FUNCTIONAL_REQUIREMENTS.md#conc-01-request-bounds-и-response-heap-lifecycle).
Эта разница остаётся явным coverage gap.

Global byte quota считает только original retained payload всех non-closed
owners. Максимум storage nodes равен
`maxConcurrentRequestSources * maxRetainedSegmentsPerRequest` (`16_384` для
defaults). Ingest coalesce/split-ит transport chunks в bounded storage segments;
one-byte input chunks не создают unbounded node count.

## Admission and ingest

Known negative `Content-Length` даёт `INCORRECT_CONTENT_LENGTH`, а known length
выше per-request limit даёт `REQUEST_TOO_LARGE` до body demand. Header не
заменяет accounting actual bytes и не разрешает full-size preallocation.
Declared и фактическая lengths обязаны совпасть.

Для каждого demanded chunk source выполняет проверки в точном порядке:

1. prospective per-request size и declared length;
2. segment-count feasibility и bounded coalescing/splitting;
3. atomic reservation exact bytes в global quota;
4. retention текущего chunk;
5. demand ровно следующего chunk.

Если один chunk одновременно превышает per-request и global availability,
per-request outcome имеет приоритет:

- `REQUEST_TOO_LARGE` отображается request boundary в
  `413 {"error":"request_too_large"}`;
- owner-slot или global-byte exhaustion даёт
  `INSPECTION_CAPACITY_EXHAUSTED`, отображаемый в safe request `503` по
  [HTTP matrix](http-gateway.md#inspection-error-matrix).

Producer failure даёт `SOURCE_ERROR`; caller cancellation даёт `CANCELLED`.
Rejection освобождает уже зарезервированные bytes/segments до публикации
terminal ingest outcome. Silent truncation, partial upstream forwarding и
unbounded waiting запрещены.

## Read view

После complete ingest parser получает immutable segmented stream без второй
полной копии. Один view открывает ровно один stream; close stream/view
освобождает только access lease. Parser result не содержит source, raw body или
mutable buffer. Parser и replay не работают конкурентно, потому что inspection
заканчивается до первого upstream byte.

## Original and patched replay

Original replay выдаёт exact source bytes по downstream demand. Patched replay
получает immutable ordered replacements raw byte ranges и до первого output
проверяет:

- owner находится в COMPLETE и active view/replay отсутствует;
- каждый range positive, находится внутри source и не пересекает предыдущий;
- ranges отсортированы, overlap/duplicate/backward ordering отсутствуют;
- replacement не длиннее заменяемого raw range;
- exact output length вычислим без overflow.

Plan содержит только ranges и immutable short replacements. Full rewritten
body, второй owner и дополнительная quota admission не создаются. Equal
replacement values могут делить immutable storage. Output использует bounded
scratch не больше storage segment size.

Demand cases обязательны для original и actual patched output: no demand,
`request(1)`, bounded batch, unbounded demand и invalid `request(0/-1)`.
Patch cases: внутри segment, на start/end boundary, через два и три segments,
adjacent patches, whole source и suffix после последней replacement. Expected
output задаётся literal source/patch fixtures, а не production rewriter.

Borrowed source/scratch bytes живут до возврата соответствующего `onNext`.
Close во время callback запрашивает terminal stop, но quota освобождается
только после callback return. Последний input byte может быть уже прочитан,
однако source остаётся retained, пока последний output не выдан или не отменён.

## Terminal paths

Owner и все reservations освобождаются ровно один раз на каждом terminal path:

| Phase | Required outcomes |
|---|---|
| Admission/ingest | owner exhaustion, known/streamed size overflow, global byte exhaustion, wrong length, publisher failure, cancellation |
| Read | normal parser close, parser failure, cancellation during view, invalid concurrent access |
| Preparation | valid original/patched plan, invalid range/order/expansion/state, close before replay |
| Replay | complete success, no-demand cancellation, partial-output cancellation, invalid demand, subscriber callback failure, duplicate subscribe |
| Transport | synchronous handoff failure, upstream write/peer close, request timeout, caller close |
| Process | shutdown before handoff, drain cancellation during active replay, repeated close |

For races оба явно удержанных порядка contenders обязательны: cancellation до
и после публикации handle, close до и после последнего output callback,
successful access до и после competing invalid access. Wall-clock sleeps не
являются ownership evidence.

После terminal outcome public counters `activeOwners`, `retainedBytes` и
`retainedSegments` возвращаются к recorded baseline. Cleanup очищает source,
patch metadata и scratch. Public state/errors/logs не содержат bytes, preview,
filename, media URL, PII, locator или reversible payload hash.

## Backpressure and execution class

Ingest запрашивает не больше одного следующего client item после retention.
Replay выдаёт не больше demand и сериализует callbacks. Source не выполняет
blocking I/O. Parser/detector CPU work выполняется вне Netty event loop по
[CONC-03](../MVP_NON_FUNCTIONAL_REQUIREMENTS.md#conc-03-execution-classes).

## Boundaries

Request source не знает OpenAI schema, PII, policy, audit или marker semantics.
Disk spill, file descriptors, encryption at rest, response/SSE retention и
crash cleanup не входят в contract. Source не обещает сохранение transport
chunk boundaries.

## Conformance cases

Public source/quota conformance использует controlled Reactive Streams
publisher/subscriber и наблюдает bytes, demand, states, counters и terminal
outcomes. Обязательны empty body, exact limit, limit + 1 byte, simultaneous
per-request/global overflow, concurrent final byte/owner slot, tiny chunks,
каждый demand/patch case и все terminal paths выше. Gateway/process evidence
для handoff, peer close и shutdown дополняет source tests по
[request enforcement](request-enforcement.md#lifecycle-and-handoff).

[Coverage](../../docs/requirements-coverage.md#policy-request-source-and-request-enforcement)
не считает default limits подтверждением 16/20 MiB target и не превращает
documentation migration в новый runtime/load run.
