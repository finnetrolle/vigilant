# Epic 20: Atomic response spooling and secure disk spill

**ID:** `EPIC-20`
**Тип:** Epic
**Статус:** Draft
**Приоритет:** High
**Предварительная оценка:** не оценён; дочерние issues не определены
**Связанные требования:** `PROXY-01`, `PROXY-02`, `CONC-01`, `CONC-02`, `CONC-03`

## Контекст

Первый production increment реализовал и проверил bounded in-memory request
source в завершённом [EPIC-08](epic_08_message_spooling_replay.md). Response,
включая SSE, пока остаётся streaming pass-through без content inspection.

Этот epic принимает future response/SSE и disk-spill scope из EPIC-08. Он не
переоткрывает завершённый request contract и не обобщает request abstraction
до выбора самостоятельных response lifecycle и storage contracts.

## Целевой результат

Guardrail-enabled response полностью удерживается в bounded source до
terminal event и итогового policy decision. При `ALLOW` exact original
response раскрывается клиенту с backpressure, при `BLOCK` клиент не получает
upstream status, headers или body. Если memory threshold недостаточен, source
может использовать secure bounded disk spill без утечки raw content и без
blocking file I/O на Netty event loop.

## Подтверждённые решения

- Guardrail-enabled SSE является одной атомарной policy-транзакцией.
- До terminal event и итогового decision клиент не получает upstream status,
  headers или body; partial release запрещён.
- Обычный bypass и первый request-only production increment сохраняют текущее
  streaming response behavior.
- Request source EPIC-08 остаётся in-memory only и не получает скрытый spill
  threshold или file lifecycle.
- Unmodified `ALLOW` replay использует exact original source, а не protocol DTO
  serialization.
- Response source lifecycle и secure spill требуют отдельных готовых issues до
  начала production implementation.

## Discovery map

```text
EPIC-20 Atomic response spooling and secure disk spill
├── response source contract (Draft)
│   ├── ordinary response lifecycle
│   ├── SSE terminal-event lifecycle
│   ├── status/header disclosure boundary
│   └── cancellation and upstream failure
├── secure bounded storage contract (Draft)
│   ├── memory-to-disk transition
│   ├── private directory and file permissions
│   ├── encryption and key ownership decision
│   ├── disk and file-descriptor quotas
│   └── normal and crash cleanup
├── atomic response inspection tracer bullet (Draft)
│   ├── complete parse and policy decision
│   ├── byte-identical ALLOW replay
│   └── zero-byte BLOCK disclosure
└── qualification evidence (Draft)
    ├── backpressure and lifecycle matrix
    ├── resource exhaustion outcomes
    └── storage and latency baseline
```

## Дочерние issues

Не опубликованы. Epic остаётся `Draft`, пока не закрыты решения, влияющие на
границы response lifecycle и secure storage, и не подготовлены независимо
проверяемые implementation leaves размером не более пяти инженерных дней.

## Нормативный future scope

### Atomic response lifecycle

- Source принимает ordinary response или SSE events с upstream backpressure.
- Terminal response/SSE state должен быть однозначно определён protocol
  adapter-ом до итогового policy decision.
- До полного `ALLOW` status, headers и body остаются нераскрыты клиенту.
- При `ALLOW` exact original source replay-ится по client demand.
- При `BLOCK`, parse failure или policy failure не происходит partial client
  forwarding; внешний outcome остаётся stable и safe.
- Client cancellation, upstream cancellation/error, timeout и shutdown
  прекращают ingest/replay и запускают idempotent cleanup.

### Secure bounded spill

- Heap, disk bytes, file descriptors, retained segments и concurrent response
  sources имеют явные configurable bounds.
- Spill storage недоступно другим пользователям процесса и не переживает
  normal cleanup lifecycle.
- Directory ownership, permissions, encryption/key ownership и crash cleanup
  должны быть зафиксированы до реализации.
- Blocking file I/O не выполняется на Netty event loop.
- Hard exhaustion даёт stable safe outcome без unbounded growth, silent
  truncation или partial disclosure.
- Raw source, temporary path, filename, content preview и reversible payload
  hash не попадают в logs, metrics, traces или errors.

## Открытые решения

- Отдельные или общие lifecycle contracts для ordinary response и SSE.
- Terminal-event и provider-error semantics каждого поддерживаемого protocol
  adapter.
- Memory-to-disk threshold, allocation order и precedence одновременного
  исчерпания memory, disk и file-descriptor quota.
- Private directory ownership, permission model, encryption requirement и key
  lifecycle.
- Crash cleanup strategy и допустимое время жизни orphaned files.
- Представление задержанных status/headers и момент принятия transport
  ownership при replay.
- Bounded storage и latency thresholds, которые должны стать release gates.

## Связи с соседними epics

- [EPIC-06](epic_06_llm_message_parsing.md) определяет future response и SSE
  protocol adapters, terminal events и normalized fragments.
- [EPIC-04](epic_04_policy_engine.md) возвращает итоговый policy decision до
  раскрытия response source.
- [EPIC-08](epic_08_message_spooling_replay.md) предоставляет завершённый
  request-side ownership и exact-replay precedent, но не общий storage API.
- [EPIC-05](epic_05_v0_hardening.md) и
  [EPIC-09](epic_09_v0_architecture_closure.md) сохраняют baseline streaming,
  response backpressure и transport lifecycle для режима без inspection.

## Не входит в epic

- Изменение завершённого in-memory request source EPIC-08.
- Protocol parsing rules, policy selection или detector implementation.
- Source rewriting, `MASK`/`REMOVE` patch API и protocol locator mapping.
- External object storage или доступность raw source вне процесса.
- Realtime и Batch source lifecycle.
- Реализация до публикации готовых дочерних issues.

## Предварительные критерии готовности epic

- Unmodified response replay-ится byte-for-byte для memory и spill paths.
- Slow upstream или client создаёт backpressure, а не unbounded queue.
- Heap, disk, file descriptors и concurrent response sources имеют
  проверяемые bounds.
- Cancellation/error на каждой lifecycle phase освобождает все ресурсы.
- Temporary source недоступен через logs/errors и удаляется после lifecycle.
- Blocking storage I/O отсутствует на Netty event loop.
- Hard exhaustion даёт stable safe outcome без partial disclosure.
- SSE replay начинается только после terminal event и полного `ALLOW`; любой
  `BLOCK` оставляет upstream SSE полностью нераскрытым клиенту.
- Resource thresholds подтверждены benchmark/baseline с зафиксированными
  hardware и workload.
- Для добавленных и изменённых Kotlin declarations написан KDoc.
- `./gradlew build` проходит после реализации всех дочерних issues.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.10  ✓ atomic response outcome fixed
  Acceptance:   0.45  △ protocol and resource matrices not decomposed
  Boundaries:   0.15  ✓ request scope remains in completed EPIC-08
  Alternatives: 0.65  △ storage and encryption strategy unresolved
  Assumptions:  0.55  △ thresholds and crash lifecycle need evidence
  Aggregate:    0.38  △ Draft; resolve before implementation
```
