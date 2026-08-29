# Epic 08: Bounded in-memory request source and replay

**ID:** `EPIC-08`
**Тип:** Epic
**Статус:** Done
**Приоритет:** High
**Предварительная оценка:** 0 дней осталось
**Связанные требования:** `PROXY-01`, `PROXY-02`, `CONC-01`, `CONC-02`, `CONC-03`

## Подтверждённое решение

Original request source и normalized parse result имеют разное ownership.
Protocol parser не возвращает копию raw body и не пересобирает сообщение из
DTO. Отдельная request-source capability сохраняет точную последовательность
bytes и предоставляет lossless replay после policy decision.

Первый production increment использует только bounded in-memory storage.
Response/SSE source lifecycle и secure disk spill вынесены в отдельный future
[EPIC-20](epic_20_response_spooling_secure_spill.md), чтобы завершённый request
increment не зависел от ещё не активного response-inspection scope.

## Карта декомпозиции

```text
EPIC-08 Bounded in-memory request source and replay
├── source/replay contract (Done)
└── bounded in-memory request source (Done)
    ├── ingest and global quota
    ├── read-only parser view
    ├── replay with backpressure
    └── cancellation and cleanup
```

## Дочерние issues

- [x] [VIG-08-01: Контракт source, spool и replay](../issues/epic_08/issue_08_01_spool_contract.md) - `Done`
- [x] [VIG-08-02: Bounded in-memory request source](../issues/epic_08/issue_08_02_bounded_request_source.md) - `Done`

## Контекст

[EPIC-06](epic_06_llm_message_parsing.md) строит normalized view из read-only
source. Если request guardrail должен принять решение до forwarding, original
bytes нельзя отправить upstream заранее, но их нужно сохранить без
ресериализации. Большие сообщения нельзя безусловно удерживать целиком в heap.

## Цель

Определить и реализовать bounded in-memory request source, который:

- принимает original request bytes с backpressure;
- позволяет parser последовательно читать complete source без изменения;
- сохраняет точное исходное представление до policy decision;
- replay-ит неизменённый source byte-for-byte по downstream demand;
- ограничивает retained bytes, segments и concurrent owners;
- освобождает все memory reservations при success, rejection, error, timeout,
  cancellation и shutdown.

## Нормативный request source contract

### Lifecycle и ownership

Integration layer создаёт ровно одного owner на supported request:

```text
NEW -> INGESTING -> COMPLETE -> CLOSED
          |             |
          +-> REJECTED <-+
```

Создание owner-а atomically резервирует один concurrent-source slot до первого
body demand. Отсутствие slot даёт `INSPECTION_CAPACITY_EXHAUSTED`; idempotent
owner close освобождает slot вместе с остальными reservations ровно один раз.

`COMPLETE` source предоставляет последовательные read-only segmented views:
parser сначала читает source, затем integration после полного `ALLOW` создаёт
replay publisher. View/reader не владеет retained bytes и не освобождает quota.
Только idempotent `close` owner-а переводит terminal state в `CLOSED` и
освобождает все reservations. Concurrent parse и replay запрещены первым
contract, потому что первый upstream byte появляется только после inspection.

Source сохраняет exact concatenated byte sequence. Transport chunk boundaries
не являются lossless contract и могут отличаться при replay. Parser result не
содержит source и не получает mutable access к retained bytes.

### Bounds и stable exhaustion

Configurable profiling defaults:

```text
perRequestLimitBytes = 8_388_608
globalRetainedLimitBytes = 67_108_864
maxConcurrentRequestSources = 128
maxRetainedSegmentsPerRequest = 128
```

Все значения положительны, global byte limit не меньше per-request byte limit.
Global byte quota считает только retained payload bytes всех non-closed
sources. Source coalesce/split-ит transport chunks в собственные segments и не
сохраняет отдельный bookkeeping node на каждый transport chunk. Поэтому число
retained segment nodes ограничено
`maxConcurrentRequestSources * maxRetainedSegmentsPerRequest` (`16_384` при
defaults), а одновременно удерживаемых demanded transport chunks и writable
segments - не более одного каждого на active owner. Object-size overhead
измеряется baseline-ом отдельно, но число объектов имеет exact contract bound.

После успешной owner-slot reservation source перед retain каждого chunk сначала
проверяет prospective per-request size и segment-count bound, затем atomically
резервирует exact retained bytes в global quota. Transport chunks coalesce-ятся
до segment limit без изменения byte sequence. Такой порядок даёт deterministic
precedence, если нарушены оба byte limit:

- per-request overflow: `REQUEST_TOO_LARGE`, HTTP integration отображает в
  `413 {"error":"request_too_large"}`;
- global reservation failure: `INSPECTION_CAPACITY_EXHAUSTED`, HTTP integration
  отображает в `503 {"error":"inspection_capacity_exhausted"}`.

Known `Content-Length` выше per-request limit отклоняется до body demand.
Header не заменяет accounting фактически принятых bytes и не позволяет
preallocate полную заявленную длину. Rejection/cancellation освобождает уже
reserved bytes до публикации terminal outcome.

### Backpressure и cleanup

Ingest запрашивает следующий client chunk только после успешных size check,
global reservation и retention текущего chunk. Replay выдаёт следующий
segment только по subscriber demand. In-memory path не выполняет blocking I/O;
parser/detector CPU work остаётся вне Netty event loop по своим contracts.

Owner close обязателен и идемпотентен для:

- successful replay completion;
- parse failure, inspection gap failure или detector/policy error;
- per-request/global exhaustion;
- request timeout и client cancellation;
- upstream failure во время replay;
- graceful/forced shutdown.

После close новые views/replay дают typed closed-state outcome. Logs, errors,
state descriptions и metrics не содержат bytes, preview, filename, media URL
или reversible payload hash.

## Нормативные ограничения

- Unmodified request forwarding использует original source, а не DTO
  serialization.
- Parser result не содержит raw body или вторую полную копию source.
- Heap usage, retained segment count и concurrent request sources имеют явные
  bounds.
- Raw source, payload и content preview не логируются.
- Backpressure применяется на ingest и replay.
- Cancellation прекращает ingest/replay и инициирует cleanup.
- Cleanup идемпотентен и выполняется для partial source.
- Hard exhaustion имеет stable safe outcome и не приводит к unbounded memory
  growth, silent truncation или partial upstream forwarding.

## Связи с соседними epics

- [EPIC-06](epic_06_llm_message_parsing.md) читает source и возвращает
  normalized attributes, fragments и locators.
- [EPIC-07](epic_07_windowed_payload_processing.md) обрабатывает большие
  decoded fragments, но не хранит original encoded message.
- [EPIC-04](epic_04_policy_engine.md) возвращает policy decision, после
  которого integration выбирает replay или reject.
- [EPIC-20](epic_20_response_spooling_secure_spill.md) владеет future
  response/SSE source lifecycle и secure disk spill.

## Не входит в epic

- Response и SSE source lifecycle или content inspection.
- Disk spill, file-descriptor quota, encryption at rest и crash cleanup.
- Разбор LLM protocol schemas.
- Выбор policies и detector execution.
- Sliding-window обработка decoded text.
- Source rewriting, формат reaction и правила masking.
- Container log collection или external object storage.
- Realtime и Batch source lifecycle.

## Критерии готовности epic

- [x] Unmodified request replay-ится byte-for-byte из in-memory source.
- [x] Slow ingest producer и replay subscriber создают backpressure, а не
  unbounded queue.
- [x] Heap bytes, retained segments и concurrent request sources имеют
  проверяемые bounds и stable exhaustion outcomes.
- [x] Cancellation/error на каждой request lifecycle phase освобождает все
  reservations ровно один раз.
- [x] Request source не доступен через logs, errors или state descriptions.
- [x] Resource bounds и exact replay подтверждены conformance suite VIG-08-02
  и packaged load evidence [VIG-18](../issues/issue_18_inspection_load_report.md).
- [x] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [x] Focused source tests и `./gradlew build` прошли при завершении VIG-08-02.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ request lifecycle and replay complete
  Acceptance:   0.05  ✓ exact quotas, errors and cleanup verified
  Boundaries:   0.0   ✓ response and disk scope moved to EPIC-20
  Alternatives: 0.10  ✓ in-memory request strategy selected
  Assumptions:  0.10  ✓ defaults remain measured profiling baselines
  Aggregate:    0.05  ✓ below threshold (0.3 epic)
```
