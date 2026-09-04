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
| `MVP-01` | Частично | Request Chat Completions полностью принимается и проверяется до upstream, но только shadow mode; response enforcement отсутствует. |
| `MVP-02` | Частично | `fast-pii` реализован с указанным detector set и windowing, но request-only integration. |
| `MVP-03` | Не реализовано | Runtime разрешает только `ALLOW`; `MASK` и `BLOCK` transport enforcement отсутствуют. Закрытый VIG-29 encoder уже фиксирует safe `BLOCK` HTTP contract, но не выполняет enforcement. |
| `MVP-04` | Частично | Startup policy snapshot и group matching существуют, но новая direction/group contract и startup policy logging отсутствуют. |
| `MVP-05` | Не реализовано | Есть Dummy и offline JWT extractors; external Bearer lookup и cache отсутствуют. |
| `MVP-06` | Частично | REQUEST analysis публикует safe best-effort started/completed pair через existing non-blocking stdout без application-owned persistence; RESPONSE pair остаётся future EPIC-20 behavior. |
| `MVP-07` | Частично | Chat Completions request и response JSON/SSE parser contracts реализованы; runtime удерживает и protocol-validates response, но policy inspection и enforcement отсутствуют. |

## Нефункциональные требования MVP

| ID | Статус | Текущий факт |
|---|---|---|
| `PERF-01` | Не реализовано | Есть bypass/shadow benchmark, но нет отдельного request/response enforcement latency evidence с новым profile. |
| `PERF-02` | Частично | Existing reports фиксируют warmup и hardware; новый non-streaming profile и warm/mock identity setup отсутствуют. |
| `PERF-03` | Частично | Current policies имеют deadline, но новый per-policy aggregation/fail-closed contract не реализован. |
| `CONC-01` | Частично | Request source и windowing bounded; retained response source использует one-item upstream demand и terminal cleanup, но по принятому MVP contract не имеет application-level limit или shared quota. Heap sizing и runtime OOM policy принадлежат deployment. |
| `CONC-02` | Частично | Existing request capacity даёт typed failure; response capacity намеренно отсутствует, response source освобождает ownership на всех terminal paths. |
| `CONC-03` | Частично | CPU inspection и response protocol parsing изолированы от event loop; external identity integration отсутствует. |
| `CONC-04` | Частично | Request/response cancellation и graceful/forced shutdown lifecycle реализованы; response policy enforcement отсутствует. |
| `PROXY-01` | Частично | Ordinary JSON и SSE полностью удерживаются до EOF/standalone `[DONE]` и protocol validation; policy `ALLOW`/`MASK`/`BLOCK` decision ещё не подключён. |
| `PROXY-02` | Частично | Lossless exact response replay реализован; exact-span JSON mutation для `MASK` отсутствует. |
| `PROXY-03` | Частично | Все пять VIG-29 status/body/header rows зафиксированы закрытым production encoder; request failures используют exact `503`, malformed/incomplete/interrupted response использует exact `502 invalid_upstream_response`, но `BLOCK` и response inspection unavailable ещё не подключены. |
| `OBS-01` | Частично | Base metrics/tracing и REQUEST stdout outcome pair существуют; response outcome и identity metrics отсутствуют. Audit-drop metrics intentionally не требуются. |
| `OBS-02` | Частично | REQUEST pair и VIG-29 errors исключают payload, PII values/spans, credentials, identity/session, policy references, upstream details и raw inbound propagation values. Ordinary request logs/traces сохраняют разрешённые tracing identifiers, configured session и accepted W3C propagation metadata по tracing contract; RESPONSE audit пока отсутствует. |

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
