# Покрытие требований документацией и реализацией

## Назначение

Эта карта отделяет согласованный MVP contract от текущей runtime реализации.
Нормативные требования принадлежат [MVP functions](../spec/MVP_FUNCTIONS.md),
[MVP NFR](../spec/MVP_NON_FUNCTIONAL_REQUIREMENTS.md), [Stage 1](../spec/STAGE_1_FUNCTIONS.md)
и [product non-goals](../spec/OUT_OF_SCOPE_FUNCTIONS.md).

Статусы: `Работает` означает полный runtime contract, `Частично` означает
полезную основу без полного contract, `Не реализовано` означает отсутствие
возможности.

## Функциональные требования MVP

| ID | Статус | Текущий факт |
|---|---|---|
| `MVP-01` | Частично | Request Chat Completions полностью принимается и проверяется до upstream в shadow mode. Ordinary JSON и SSE response проходят atomic enforcement до client disclosure; request enforcement ещё не завершено. |
| `MVP-02` | Работает | `fast-pii` с полным detector set и UTF-8-safe windowing подключён к request, ordinary-response и SSE-response fragments. |
| `MVP-03` | Частично | Ordinary JSON и SSE response применяют exact `ALLOW`, source-patched `MASK` и whole-response `BLOCK` с closed VIG-29 errors. Request остаётся shadow-only. |
| `MVP-04` | Частично | Startup policy snapshot и group matching существуют, но новая direction/group contract и startup policy logging отсутствуют. |
| `MVP-05` | Частично | Startup-selectable `DUMMY`, offline `JWT` и trusted Bridge `EXTERNAL` реализуют общий async cancellation-aware identity contract. External lookup fail-closed и bounded; VIG-31 cache ещё отсутствует. |
| `MVP-06` | Работает | REQUEST и ordinary JSON/SSE RESPONSE analysis публикуют safe best-effort started/completed pair через existing non-blocking stdout без application-owned persistence. |
| `MVP-07` | Работает | OpenAI-compatible Chat Completions request и response JSON/SSE parser и enforcement contracts реализованы; другие OpenAI APIs остаются вне MVP. |

## Нефункциональные требования MVP

| ID | Статус | Текущий факт |
|---|---|---|
| `PERF-01` | Не реализовано | Есть bypass/shadow benchmark, но нет отдельного request/response enforcement latency evidence с новым profile. |
| `PERF-02` | Частично | Existing reports фиксируют warmup и hardware; новый non-streaming profile и warm/mock identity setup отсутствуют. |
| `PERF-03` | Частично | Per-policy deadlines действуют; ordinary и SSE response timeout даёт fail-closed `503`. Request остаётся shadow `ALLOW`. |
| `CONC-01` | Частично | Request source и windowing bounded; retained response source использует one-item upstream demand и terminal cleanup, но по принятому MVP contract не имеет application-level limit или shared quota. Heap sizing и runtime OOM policy принадлежат deployment. |
| `CONC-02` | Частично | Existing request capacity даёт typed failure; response capacity намеренно отсутствует, response source освобождает ownership на всех terminal paths. |
| `CONC-03` | Работает | CPU inspection, response parsing и identity orchestration изолированы от event loop; External HTTP остаётся async, bounded и cancellation-aware. |
| `CONC-04` | Работает | Request и ordinary/SSE response ingest, analysis и handoff cancellation, graceful/forced shutdown lifecycle и terminal cleanup имеют bounded causal evidence. |
| `PROXY-01` | Работает | Ordinary JSON и SSE удерживаются до EOF/standalone `[DONE]` и final policy decision, после чего атомарно применяют `ALLOW`/`MASK`/`BLOCK`. |
| `PROXY-02` | Работает | Ordinary JSON и SSE поддерживают byte-identical `ALLOW` и exact-span source-patched `MASK` с lossless preservation незатронутых bytes и header rewrite. |
| `PROXY-03` | Частично | Все пять VIG-29 rows зафиксированы; ordinary и SSE response реально используют exact `403`, `502` и `503` без partial disclosure. Request `BLOCK` ещё не подключён. |
| `OBS-01` | Частично | Base HTTP metrics/tracing, REQUEST и RESPONSE audit pairs, inspection spans и External lookup counter/duration/CLIENT span используют bounded safe outcomes. Обязательные identity cache hit/miss metrics появятся с VIG-31. |
| `OBS-02` | Работает | REQUEST и ordinary/SSE RESPONSE pairs, VIG-29 errors, logs, metrics и traces исключают payload, PII values/spans, credentials, identity/session, upstream details и raw inbound propagation values. Разрешены только safe policy/detector references в stdout и tracing identifiers. |

## Stage 1 и non-goals

Stage 1 requirements остаются future scope. Tool execution, tool middleware,
other LLM APIs, non-PII detectors, hot reload и plugin workers не входят в
обновлённый MVP. Полный перечень находится в
[STAGE_1_FUNCTIONS.md](../spec/STAGE_1_FUNCTIONS.md) и
[OUT_OF_SCOPE_FUNCTIONS.md](../spec/OUT_OF_SCOPE_FUNCTIONS.md).

## Правило обновления

Любая production задача, меняющая requirement coverage, обновляет этот документ,
нормативную спецификацию-владельца, work item и runtime documentation в одном
change set. Dynamic evidence публикуется только после фактического прогона.
