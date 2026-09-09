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
| `MVP-01` | Работает | Request ALLOW/MASK/BLOCK применяется до upstream; ordinary JSON/SSE response проходит atomic enforcement до client disclosure. |
| `MVP-02` | Работает | `fast-pii` с полным detector set и UTF-8-safe windowing подключён к request, ordinary-response и SSE-response fragments. |
| `MVP-03` | Работает | Request и ordinary/SSE response применяют ALLOW/MASK/BLOCK. Request structural MASK блокирует целиком, markers сокращаются до decoded UTF-8 budget, technical failure выше policy BLOCK. |
| `MVP-04` | Работает | Immutable strict startup snapshot сохраняет URL/model/phase/USER/GROUP matching и overrides. Empty/disabled/unmatched selection не запускает detector; implicit global policy отсутствует. |
| `MVP-05` | Работает | Startup-selectable `DUMMY`, offline `JWT` и trusted Bridge `EXTERNAL` реализуют общий async cancellation-aware contract. EXTERNAL использует Caffeine с process-local HMAC keys, configurable write TTL/size, bounded coalescing, независимой cancellation и fail-closed refresh по следующему request. |
| `MVP-06` | Работает | REQUEST и ordinary JSON/SSE RESPONSE analysis публикуют safe best-effort started/completed pair через existing non-blocking stdout без application-owned persistence. |
| `MVP-07` | Работает | OpenAI-compatible Chat Completions request и response JSON/SSE parser и enforcement contracts реализованы; другие OpenAI APIs остаются вне MVP. |

## Нефункциональные требования MVP

| ID | Статус | Текущий факт |
|---|---|---|
| `PERF-01` | Не реализовано | Есть bypass/shadow benchmark, но нет отдельного request/response enforcement latency evidence с новым profile. |
| `PERF-02` | Частично | Existing reports фиксируют warmup и hardware; новый non-streaming profile и warm/mock identity setup отсутствуют. |
| `PERF-03` | Работает | Per-policy request и ordinary/SSE response deadlines дают fail-closed 503 без reaction fallback. |
| `CONC-01` | Частично | Request source и windowing bounded; retained response source использует one-item upstream demand и terminal cleanup, но по принятому MVP contract не имеет application-level limit или shared quota. Heap sizing и runtime OOM policy принадлежат deployment. |
| `CONC-02` | Частично | Existing request capacity даёт typed failure; response capacity намеренно отсутствует, response source освобождает ownership на всех terminal paths. |
| `CONC-03` | Работает | CPU inspection, response parsing и identity orchestration изолированы от event loop; External HTTP остаётся async, bounded и cancellation-aware. |
| `CONC-04` | Работает | Request и ordinary/SSE response ingest, analysis и handoff cancellation, graceful/forced shutdown lifecycle и terminal cleanup имеют bounded causal evidence. |
| `PROXY-01` | Работает | Ordinary JSON и SSE удерживаются до EOF/standalone `[DONE]` и final policy decision, после чего атомарно применяют `ALLOW`/`MASK`/`BLOCK`. |
| `PROXY-02` | Работает | Request и ordinary JSON/SSE поддерживают byte-identical `ALLOW` и exact-span source-patched `MASK` с lossless preservation незатронутых bytes и header rewrite. |
| `PROXY-03` | Работает | Все пять VIG-29 outcomes подключены: request policy/structural BLOCK и technical refusal запрещают handoff; response errors исключают partial disclosure. |
| `OBS-01` | Частично | Base HTTP metrics/tracing, REQUEST и RESPONSE audit pairs, inspection spans и External lookup counter/duration/CLIENT span используют bounded safe outcomes. EXTERNAL cache публикует точные hit/miss, coalesced и actual expiry/size removal counters с finite attributes; hit/join не создают дополнительных Bridge spans. |
| `OBS-02` | Работает | REQUEST и ordinary/SSE RESPONSE pairs, VIG-29 errors, logs, metrics и traces исключают payload, PII values/spans, credentials, identity, query values и raw exception details. Session/query-free path и valid propagation в operational MDC сохраняются по tracing contract; audit/client errors не содержат session/path/raw propagation. Safe policy/detector references разрешены только в stdout. |

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
