# Epic 20: Atomic in-memory response analysis

**ID:** `EPIC-20`
**Тип:** Epic
**Статус:** Draft
**Приоритет:** High
**Предварительная оценка:** не оценён; 0/4 опубликованных issues завершено,
SSE leaves ещё не опубликованы
**Связанные требования:** `PROXY-01`, `PROXY-02`, `CONC-01`, `CONC-02`, `CONC-03`

## Контекст

Первый production increment реализовал и проверил bounded in-memory request
source в завершённом [EPIC-08](epic_08_message_spooling_replay.md). Response,
включая SSE, пока остаётся streaming pass-through без content inspection.

Этот epic принимает future response/SSE scope из EPIC-08. Он не
переоткрывает завершённый request contract и не обобщает request abstraction
до выбора самостоятельных response lifecycle и storage contracts.

## Целевой результат

Guardrail-enabled response полностью удерживается в in-memory source до
terminal event и итогового policy decision. При `ALLOW` exact original
response раскрывается клиенту с backpressure, при `BLOCK` клиент не получает
upstream status, headers или body. Для MVP source использует доступный JVM heap
и не имеет quota, disk spill path или persistent storage.

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
- Disk spill, temporary files, encryption, recovery и persistent response
  storage исключены из MVP.
- Response source lifecycle требует отдельных готовых issues до
  начала production implementation.

## Discovery map

```text
EPIC-20 Atomic in-memory response analysis
├── response memory contract (Ready)
│   ├── retained in-memory terminology
│   ├── no application-level response quota
│   └── deployment-owned heap sizing and OOM policy
├── response source contract (Ready after memory contract)
│   ├── ordinary response lifecycle
│   ├── status/header disclosure boundary
│   └── cancellation and upstream failure
├── non-stream response enforcement (Draft)
│   ├── complete JSON parse and policy decision
│   ├── byte-identical ALLOW replay
│   ├── exact MASK rewrite
│   └── zero-byte BLOCK disclosure plus response audit pair
├── SSE response enforcement (Draft; leaves not published)
│   ├── framing and standalone terminal-event parser
│   ├── text assembly by choice.index
│   ├── cross-chunk MASK rewrite
│   └── atomic ALLOW/BLOCK disclosure plus response audit pair
└── reusable masking seam (Ready)
    ├── typed masking instructions
    ├── deterministic overlap handling
    └── all-or-nothing invalid-input failure
```

## Дочерние issues

- [ ] [VIG-20-04: Retained in-memory response contract](../issues/epic_20/issue_20_04_retained_memory_response_contract.md) - `Ready for implementation`
- [ ] [VIG-20-01: In-memory response source](../issues/epic_20/issue_20_01_bounded_memory_response_source.md) - `Ready for implementation`
- [ ] [VIG-20-03: Reusable text masker](../issues/epic_20/issue_20_03_reusable_text_masker.md) - `Ready for implementation`
- [ ] [VIG-20-02: Non-stream response inspection and enforcement](../issues/epic_20/issue_20_02_response_inspection_enforcement.md) - `Draft`

VIG-20-02 сохранён как первый узкий enforcement leaf. Его прежний полный
response/SSE contract поднят в этот epic. До перевода epic в
`Ready for implementation` необходимо опубликовать отдельные bounded leaves
для SSE framing/terminal parsing и SSE enforcement.

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

### Non-stream Chat Completions enforcement

- Каждый `choices[].message.content`, являющийся string, образует независимый
  inspection fragment. Findings не пересекают границы choices.
- `null`, recognized image/audio/file/tool-call и другие recognized non-text
  shapes дают `INSPECTION_GAP` и `ALLOW` без изменения response.
- Missing `choices`, invalid content type, malformed или ambiguous
  content-bearing shape дают safe `502 invalid_upstream_response` по VIG-29.
- `ALLOW` раскрывает exact original bytes. `MASK` изменяет только выбранные
  spans, сохраняет unknown fields и пересчитывает framing headers. Любой
  applicable `BLOCK` блокирует весь response до первого client byte.

### SSE Chat Completions enforcement

- Supported stream завершается только отдельным standalone `data: [DONE]`
  event. Missing или malformed terminal event является safe protocol failure.
- Text собирается независимо по `choice.index` из string
  `choices[].delta.content`. Non-content event fields и порядок events
  сохраняются.
- Finding может пересекать transport chunks и несколько `delta.content`
  fragments одного choice. `MASK` не раскрывает клиенту часть исходного PII
  span.
- До terminal event, полного анализа и final reaction клиент не получает
  upstream status, headers или event bytes.

### Response audit

- Реально проанализированный response публикует ровно одну stdout pair из
  VIG-32-01: `policy.analysis_started` непосредственно до detector execution и
  `policy.analysis_completed` после final outcome.
- Audit содержит только safe aggregate fields и tracing correlation. Payload,
  spans, headers, credentials и identity запрещены.

### In-memory source

- Response не имеет application-level heap или concurrent-source quota; он
  использует доступный JVM heap. После terminal lifecycle source освобождает
  owned buffers и references, а память возвращает JVM GC.
- Raw source, content preview и reversible payload hash не попадают в logs,
  metrics, traces или errors.

## Открытые решения

- JVM heap sizing и runtime OOM policy deployment выбирает вне application.
- Точные public seams и границы двух будущих SSE leaves.
- Принадлежность response JSON/SSE parser contracts между EPIC-06 и EPIC-20.

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
- Упрощение или удаление существующего policy engine, его match dimensions,
  overrides либо detector registry.
- `REMOVE` reaction, новые detector types и protocols кроме Chat Completions.
- External object storage или доступность raw source вне процесса.
- Realtime и Batch source lifecycle.
- Реализация SSE enforcement до публикации готовых bounded leaves.

## Предварительные критерии готовности epic

- Unmodified response replay-ится byte-for-byte для memory path.
- Slow upstream или client создаёт backpressure, а не unbounded queue.
- Каждый terminal lifecycle освобождает response buffers и references.
- Cancellation/error на каждой lifecycle phase освобождает все ресурсы.
- Temporary source недоступен через logs/errors и освобождается после lifecycle.
- SSE replay начинается только после terminal event и полного `ALLOW`; любой
  `BLOCK` оставляет upstream SSE полностью нераскрытым клиенту.
- Non-stream JSON и SSE protocol/enforcement доказаны отдельными bounded
  leaves, каждый с одним основным E2E или public parser seam.
- Каждый реально проанализированный response публикует одну safe stdout audit
  pair по VIG-32-01 без влияния logging failure на HTTP outcome.
- Для добавленных и изменённых Kotlin declarations написан KDoc.
- `./gradlew validateWorkItems` проходит после каждого изменения epic tree.
- `./gradlew build` проходит после реализации всех дочерних issues.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   atomic response outcome fixed
  Acceptance:   0.35  SSE branches still need published leaves
  Boundaries:   0.25  response parser ownership must be assigned
  Alternatives: 0.10  retained memory and no disk spill selected
  Assumptions:  0.20  deployment owns heap sizing and OOM policy
  Aggregate:    0.18  Draft: publish bounded SSE leaves before implementation.
```
