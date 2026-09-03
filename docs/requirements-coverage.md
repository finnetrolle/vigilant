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
| `MVP-03` | Не реализовано | Runtime разрешает только `ALLOW`; `MASK` и `BLOCK` transport enforcement отсутствуют. |
| `MVP-04` | Частично | Startup policy snapshot и group matching существуют, но новая direction/group contract и startup policy logging отсутствуют. |
| `MVP-05` | Не реализовано | Есть Dummy и offline JWT extractors; external Bearer lookup и cache отсутствуют. |
| `MVP-06` | Частично | REQUEST analysis публикует safe best-effort started/completed pair через existing non-blocking stdout без durable acknowledgement; RESPONSE pair остаётся future EPIC-20 behavior, а transitional WAL удаляется в VIG-32-02. |
| `MVP-07` | Частично | Поддержан только Chat Completions request path; response inspection и enforcement отсутствуют. |

## Нефункциональные требования MVP

| ID | Статус | Текущий факт |
|---|---|---|
| `PERF-01` | Не реализовано | Есть bypass/shadow benchmark, но нет отдельного request/response enforcement latency evidence с новым profile. |
| `PERF-02` | Частично | Existing reports фиксируют warmup и hardware; новый non-streaming profile и warm/mock identity setup отсутствуют. |
| `PERF-03` | Частично | Current policies имеют deadline, но новый per-policy aggregation/fail-closed contract не реализован. |
| `CONC-01` | Частично | Request source и windowing bounded; required response in-memory lifecycle и cleanup пока отсутствуют. Response quota intentionally не требуется. |
| `CONC-02` | Частично | Existing request capacity gives typed failure; response capacity intentionally не резервируется до будущего in-memory response lifecycle. |
| `CONC-03` | Частично | CPU inspection isolation реализована; external identity integration отсутствует. |
| `CONC-04` | Частично | Request cancellation и graceful shutdown реализованы; response enforcement lifecycle отсутствует. |
| `PROXY-01` | Не реализовано | Response/SSE currently pass through; bounded hold-before-release enforcement отсутствует. |
| `PROXY-02` | Не реализовано | Exact-span JSON mutation отсутствует. |
| `PROXY-03` | Частично | Capacity errors существуют, но новый `503`/`Retry-After` matrix и policy `BLOCK` contract принадлежат VIG-29. |
| `OBS-01` | Частично | Base metrics/tracing и REQUEST stdout outcome pair существуют; response outcome и identity metrics отсутствуют. Audit-drop metrics intentionally не требуются. |
| `OBS-02` | Частично | REQUEST pair и errors исключают payload, PII values/spans, credentials, identity/session и raw inbound propagation values. Ordinary request logs/traces сохраняют разрешённые tracing identifiers, configured session и accepted W3C propagation metadata по tracing contract; RESPONSE audit пока отсутствует. |

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
