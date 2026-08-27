# Epic 08: Lossless message spooling and replay

**ID:** `EPIC-08`  
**Тип:** Epic  
**Статус:** In progress  
**Приоритет:** High  
**Предварительная оценка:** 0 дней до request source первого increment; future response/disk scope не оценён
**Связанные требования:** `PROXY-01`, `PROXY-02`, `CONC-01`, `CONC-02`, `CONC-03`, `SEC-01`, `SEC-02`

## Подтверждённое решение

Original message source и normalized parse result имеют разное ownership.
Protocol parser не возвращает копию raw body и не пересобирает сообщение из
DTO. Отдельная integration capability сохраняет точную последовательность
bytes/events и предоставляет lossless replay после policy decision.

Capability оформляется отдельным epic, потому что объединяет memory и disk
resource management, backpressure, security, cancellation, cleanup и replay.

В future response-inspection increment SSE является одной атомарной
policy-транзакцией: spool принимает весь upstream stream с backpressure и не
раскрывает клиенту status, headers или body до terminal event и итогового
decision. Это решение не активно в первом production increment, где response,
включая SSE, остаётся существующим streaming pass-through без inspection.

## Карта декомпозиции

```text
EPIC-08 Lossless spooling and replay
├── source/spool contract (Done)
├── first production increment
│   └── bounded in-memory request source (Done)
│       ├── ingest and global quota
│       ├── read-only parser view
│       ├── replay with backpressure
│       └── cancellation and cleanup
└── future Draft
    ├── response/SSE source lifecycle
    └── secure disk spill
```

## Дочерние issues

- [x] [VIG-08-01: Контракт source, spool и replay](../issues/epic_08/issue_08_01_spool_contract.md) - `Done`
- [x] [VIG-08-02: Bounded in-memory request source](../issues/epic_08/issue_08_02_bounded_request_source.md) - `Done`

## Контекст

[EPIC-06](epic_06_llm_message_parsing.md) строит normalized view из read-only
source. Если request guardrail должен принять решение до forwarding, original
bytes нельзя отправить upstream заранее, но их нужно сохранить без
ресериализации. Большие сообщения нельзя безусловно удерживать целиком в heap.

Для будущей response phase ordinary response и SSE используют отдельные
lifecycle. Для SSE уже принято атомарное enforcement-поведение, но mechanics,
bounds и implementation issue остаются Draft. Первый production increment не
изменяет response path.

## Цель

Определить и реализовать bounded source abstraction, которая:

- принимает original bytes/events с backpressure;
- позволяет parser читать source без изменения;
- сохраняет точное исходное представление до policy decision;
- replay-ит неизменённый source byte-for-byte;
- предоставляет future rewriter доступ к original source и locators;
- гарантированно освобождает memory, file handles и temporary storage при
  success, error, timeout и cancellation.

Первый production increment реализует только request direction и только
in-memory storage. Response, SSE и file handles относятся к future scope и не
являются acceptance готовой VIG-08-02.

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
содержит source; future rewriter не получает mutable access без отдельного
contract.

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
state descriptions и metrics не содержат bytes, preview, filename, media URL,
temporary path или reversible payload hash.

## Нормативные ограничения

- Unmodified forwarding использует original source, а не DTO serialization.
- Parser result не содержит raw body или вторую полную копию source.
- Heap usage, open file count, temporary storage и concurrent spool count
  имеют явные bounds.
- Spill storage недоступно другим пользователям процесса и не переживает
  normal cleanup lifecycle.
- Raw source, path временного файла, payload и content preview не логируются.
- Backpressure применяется на ingest и replay.
- До итогового SSE decision клиент не получает upstream status, headers или
  body; partial release запрещён в MVP.
- Blocking file I/O не выполняется на Netty event loop.
- Cancellation прекращает ingest/replay и инициирует cleanup.
- Cleanup идемпотентен и выполняется для partial source.
- Hard exhaustion имеет stable safe outcome и не приводит к unbounded memory
  growth или silent truncation.

## Связи с соседними epics

- [EPIC-06](epic_06_llm_message_parsing.md) читает source и возвращает
  normalized attributes, fragments и locators.
- [EPIC-07](epic_07_windowed_payload_processing.md) обрабатывает большие
  decoded fragments, но не хранит original encoded message.
- [EPIC-04](epic_04_policy_engine.md) возвращает policy decision, после
  которого integration выбирает replay, rewrite или block.

## Отложенные решения

- Response и atomic SSE получают отдельные lifecycle/source issues после
  активации response inspection; request abstraction не обобщается заранее.
- Disk spill требует отдельного security contract для directory ownership,
  permissions, encryption, disk/file-descriptor quota и crash cleanup.
- Future rewriter требует patch API поверх original source и protocol locators.

## Не входит в epic

- Разбор LLM protocol schemas.
- Выбор policies и detector execution.
- Sliding-window обработка decoded text.
- Формат reaction и правила masking.
- Container log collection или external object storage.
- Realtime и Batch source lifecycle в текущем MVP.

## Предварительные критерии готовности epic

- Unmodified message replay-ится byte-for-byte для memory и spill paths.
- Slow parser, upstream или client создаёт backpressure, а не unbounded queue.
- Heap, disk, file descriptors и concurrent spools имеют проверяемые bounds.
- Cancellation/error на каждой lifecycle phase освобождает все ресурсы.
- Temporary source не доступен через logs/errors и удаляется после lifecycle.
- Blocking storage I/O отсутствует на Netty event loop.
- Hard exhaustion даёт stable safe outcome без partial silent forwarding.
- SSE replay начинается только после terminal event и полного `ALLOW`; любой
  `BLOCK` оставляет upstream SSE полностью нераскрытым клиенту.
- Resource thresholds подтверждены benchmark/baseline с зафиксированным
  hardware и workload.
- Для добавленных и изменённых Kotlin declarations написан KDoc.
- `./gradlew build` проходит после реализации всех дочерних issues.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ request lifecycle and replay explicit
  Acceptance:   0.15  ✓ exact quotas, errors and cleanup matrix fixed
  Boundaries:   0.05  ✓ response and disk scope deferred explicitly
  Alternatives: 0.10  ✓ in-memory request strategy selected
  Assumptions:  0.20  ✓ default sizes remain measured profiling baselines
  Aggregate:    0.10  ✓ below threshold (0.3 epic)
```
