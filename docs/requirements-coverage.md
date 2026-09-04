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
| `MVP-01` | Частично | Request Chat Completions полностью принимается и проверяется до upstream в shadow mode. Ordinary JSON response проходит enforcement до client disclosure; request и SSE enforcement ещё не завершены. |
| `MVP-02` | Частично | `fast-pii` с полным detector set и windowing подключён к request и ordinary-response fragments; SSE enforcement отсутствует. |
| `MVP-03` | Частично | Ordinary response применяет exact `ALLOW`, source-patched `MASK` и whole-response `BLOCK` с closed VIG-29 errors. Request остаётся shadow-only, SSE reactions ещё не подключены. |
| `MVP-04` | Частично | Startup policy snapshot и group matching существуют, но новая direction/group contract и startup policy logging отсутствуют. |
| `MVP-05` | Не реализовано | Есть Dummy и offline JWT extractors; external Bearer lookup и cache отсутствуют. |
| `MVP-06` | Частично | REQUEST и ordinary JSON RESPONSE analysis публикуют safe best-effort started/completed pair через existing non-blocking stdout без application-owned persistence; SSE RESPONSE pair принадлежит VIG-20-05. |
| `MVP-07` | Частично | Chat Completions request и response JSON/SSE parser contracts реализованы; ordinary JSON response inspection/enforcement работает, SSE пока ограничен protocol gate. |

## Нефункциональные требования MVP

| ID | Статус | Текущий факт |
|---|---|---|
| `PERF-01` | Не реализовано | Есть bypass/shadow benchmark, но нет отдельного request/response enforcement latency evidence с новым profile. |
| `PERF-02` | Частично | Existing reports фиксируют warmup и hardware; новый non-streaming profile и warm/mock identity setup отсутствуют. |
| `PERF-03` | Частично | Per-policy deadlines действуют; ordinary response timeout даёт fail-closed `503`. Request остаётся shadow `ALLOW`, а SSE fail-closed enforcement принадлежит VIG-20-05. |
| `CONC-01` | Частично | Request source и windowing bounded; retained response source использует one-item upstream demand и terminal cleanup, но по принятому MVP contract не имеет application-level limit или shared quota. Heap sizing и runtime OOM policy принадлежат deployment. |
| `CONC-02` | Частично | Existing request capacity даёт typed failure; response capacity намеренно отсутствует, response source освобождает ownership на всех terminal paths. |
| `CONC-03` | Частично | CPU inspection и response protocol parsing изолированы от event loop; external identity integration отсутствует. |
| `CONC-04` | Частично | Request и ordinary-response ingest/analysis/handoff cancellation, graceful/forced shutdown lifecycle и terminal cleanup реализованы; SSE analysis cancellation остаётся VIG-20-05. |
| `PROXY-01` | Частично | Ordinary JSON и SSE удерживаются до EOF/standalone `[DONE]`; ordinary JSON применяет atomic `ALLOW`/`MASK`/`BLOCK`, SSE enforcement ещё не подключён. |
| `PROXY-02` | Частично | Ordinary JSON поддерживает byte-identical `ALLOW` и exact-span source-patched `MASK` с header rewrite; cross-event SSE mutation остаётся VIG-20-05. |
| `PROXY-03` | Частично | Все пять VIG-29 rows зафиксированы; ordinary response реально использует exact `403`, `502` и `503` без partial disclosure. Request `BLOCK` и SSE enforcement ещё не подключены. |
| `OBS-01` | Частично | Base HTTP metrics/tracing, REQUEST pair и ordinary RESPONSE outcome/latency pair с separate response inspection span существуют; identity metrics и SSE response outcome отсутствуют. |
| `OBS-02` | Частично | REQUEST/ordinary RESPONSE pairs и VIG-29 errors исключают payload, PII values/spans, credentials, identity/session, upstream details и raw inbound propagation values. Разрешены только safe policy/detector references в stdout и tracing identifiers; SSE audit пока отсутствует. |

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
