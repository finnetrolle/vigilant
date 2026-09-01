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
| `MVP-06` | Частично | Current durable audit WAL безопасен по содержимому, но блокирует request admission и не соответствует best-effort writer contract. |
| `MVP-07` | Частично | Поддержан только Chat Completions request path; response inspection и enforcement отсутствуют. |

## Нефункциональные требования MVP

| ID | Статус | Текущий факт |
|---|---|---|
| `PERF-01` | Не реализовано | Есть bypass/shadow benchmark, но нет отдельного request/response enforcement latency evidence с новым profile. |
| `PERF-02` | Частично | Existing reports фиксируют warmup и hardware; новый non-streaming profile и warm/mock identity setup отсутствуют. |
| `PERF-03` | Частично | Current policies имеют deadline, но новый per-policy aggregation/fail-closed contract не реализован. |
| `CONC-01` | Частично | Request source и windowing bounded; `16 MiB` text / `20 MiB` request-response limits и response spool отсутствуют. |
| `CONC-02` | Частично | Existing request capacity gives typed failure; shared request/response capacity contract ещё не реализован. |
| `CONC-03` | Частично | CPU inspection isolation реализована; external identity integration отсутствует. |
| `CONC-04` | Частично | Request cancellation и graceful shutdown реализованы; response enforcement lifecycle отсутствует. |
| `PROXY-01` | Не реализовано | Response/SSE currently pass through; bounded hold-before-release enforcement отсутствует. |
| `PROXY-02` | Не реализовано | Exact-span JSON mutation отсутствует. |
| `PROXY-03` | Частично | Capacity errors существуют, но новый `503`/`Retry-After` matrix и policy `BLOCK` contract принадлежат VIG-29. |
| `OBS-01` | Частично | Base metrics/tracing существуют; new outcome, audit-drop и identity metrics отсутствуют. |
| `OBS-02` | Частично | Logs/audit безопасны для payload и credentials, но новый tracing/audit contract не реализован. |

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
