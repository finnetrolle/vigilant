# Epic 08: Lossless message spooling and replay

**ID:** `EPIC-08`  
**Тип:** Epic  
**Статус:** Draft  
**Приоритет:** High  
**Предварительная оценка:** после закрытия spool-контракта  
**Связанные требования:** `PROXY-01`, `PROXY-02`, `CONC-01`, `CONC-02`, `CONC-03`, `SEC-01`, `SEC-02`

## Подтверждённое решение

Original message source и normalized parse result имеют разное ownership.
Protocol parser не возвращает копию raw body и не пересобирает сообщение из
DTO. Отдельная integration capability сохраняет точную последовательность
bytes/events и предоставляет lossless replay после policy decision.

Capability оформляется отдельным epic, потому что объединяет memory и disk
resource management, backpressure, security, cancellation, cleanup и replay.

SSE response в OpenAI MVP является одной атомарной policy-транзакцией. Spool
принимает весь upstream stream с backpressure и не раскрывает клиенту status,
headers или body до terminal event и итогового decision. При полном `ALLOW`
original SSE replay-ится lossless, при любом `BLOCK` исходные upstream bytes
не отправляются клиенту и integration формирует stable safe proxy error.

## Предварительная карта декомпозиции

```text
EPIC-08 Lossless spooling and replay
├── source/spool contract
├── bounded in-memory buffering
├── secure spill storage
├── replay with backpressure
├── quotas and hard exhaustion
├── completion, error and cancellation cleanup
└── lossless E2E behavior
```

Исполняемые issues создаются после выбора source lifecycle и spill strategy.

## Дочерние issues

- [ ] [VIG-08-01: Контракт source, spool и replay](../issues/epic_08/issue_08_01_spool_contract.md) - `Draft`

## Контекст

[EPIC-06](epic_06_llm_message_parsing.md) строит normalized view из read-only
source. Если request guardrail должен принять решение до forwarding, original
bytes нельзя отправить upstream заранее, но их нужно сохранить без
ресериализации. Большие сообщения нельзя безусловно удерживать целиком в heap.

Для response phase требования отличаются: ordinary response и SSE используют
разные lifecycle, но оба сохраняют source до policy decision. Для SSE принято
атомарное enforcement-поведение: TTFB ожидает terminal event и итоговый
decision. Это осознанный MVP tradeoff, а не разрешение на unbounded heap;
ingest и последующий replay сохраняют backpressure и bounded spill.

## Цель

Определить и реализовать bounded source abstraction, которая:

- принимает original bytes/events с backpressure;
- позволяет parser читать source без изменения;
- сохраняет точное исходное представление до policy decision;
- replay-ит неизменённый source byte-for-byte;
- предоставляет future rewriter доступ к original source и locators;
- гарантированно освобождает memory, file handles и temporary storage при
  success, error, timeout и cancellation.

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

## Открытые решения

1. Единый source contract либо отдельные request, ordinary response и SSE
   abstractions для активного OpenAI MVP scope.
2. Кто читает source первым и как parser и spool не создают две полные копии.
3. In-memory threshold и переход к spill storage.
4. Temporary storage type, permissions, encryption requirement и directory
   ownership.
5. Per-request, per-replica и global quotas.
6. Stable outcome при hard memory/disk/file-descriptor exhaustion.
7. Replay ordering, backpressure и повторное чтение.
8. Cleanup при success, block, parse failure, upstream error, timeout,
   cancellation и process shutdown.
9. API между spool, protocol parser и future rewriter.

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
  Goals:        0.15  общий lifecycle понятен
  Acceptance:   0.50  thresholds и exhaustion semantics не выбраны
  Boundaries:   0.35  source abstractions и response scope открыты
  Alternatives: 0.60  memory/disk strategy не выбрана
  Assumptions:  0.60  resource model требует baseline
  Aggregate:    0.44  выше порога implementation-ready issue
```

Оставить `Draft` до закрытия spool-контракта.
