# Действующие требования и владельцы

Этот индекс связывает нормативные требования, фактическую реализацию и evidence.
Один behavioral clause имеет одного permanent owner. Runtime docs объясняют
реализацию и ссылаются на требования; задачи описывают только незавершённое
изменение. История реализации остаётся в Git после
[закрытия work item](../../CLAUDE.md#work-item-completion).

## Верхний уровень: 55 stable IDs

| IDs | Количество | Единственный top-level owner |
|---|---:|---|
| `MVP-01..07` | 7 | [Функции MVP](../MVP_FUNCTIONS.md) |
| `PERF-01..03` | 3 | [MVP NFR: производительность](../MVP_NON_FUNCTIONAL_REQUIREMENTS.md#производительность) |
| `CONC-01..04` | 4 | [MVP NFR: ресурсы и cancellation](../MVP_NON_FUNCTIONAL_REQUIREMENTS.md#ресурсы-и-cancellation) |
| `PROXY-01..03` | 3 | [MVP NFR: proxy и protocol](../MVP_NON_FUNCTIONAL_REQUIREMENTS.md#proxy-и-protocol) |
| `OBS-01..02` | 2 | [MVP NFR: наблюдаемость и audit](../MVP_NON_FUNCTIONAL_REQUIREMENTS.md#наблюдаемость-и-audit) |
| `ST1-01..23` | 23 | [Функции Stage 1](../STAGE_1_FUNCTIONS.md) |
| `OUT-01..13` | 13 | [Границы продукта и MVP non-goals](../OUT_OF_SCOPE_FUNCTIONS.md) |

Имена этих документов и IDs сохраняются. Detailed clauses не получают task IDs
в качестве новых requirement IDs. Global SLOs остаются в MVP NFR; методики
проверок принадлежат [development guide](../../docs/development.md).

[Карта покрытия](../../docs/requirements-coverage.md) различает target,
implemented behavior и evidence gaps. Наличие документа или удаление задачи
не повышает статус реализации. Future/out-of-scope capabilities не считаются
доступными; прежние bypass/shadow measurements не подтверждают новый enforcement
SLO. Target request limits и runtime defaults также не уравниваются редактурой.

## Владение подробными контрактами

Содержательные detailed contracts опубликованы у permanent owners ниже.
Implementation facts принадлежат runtime references, а coverage отдельно
показывает target, фактическое поведение и evidence gaps. Новые области следуют
тому же правилу одного normative owner без task IDs и historical wrappers.

| Область | Согласованный detailed owner | Действующий runtime reference |
|---|---|---|
| PII recognition, findings, ordering, quality | [Fast PII](fast-pii.md#api) | [PII detection](../../docs/pii-detection.md) |
| Windowing, overlap, offsets, cancellation | [Windowed inspection](windowed-inspection.md#contract) | [Architecture](../../docs/architecture.md#5-fast-pii-detector) |
| Bearer modes, identity cache, context и handoff | [Identity и context](identity-and-context.md#startup-selection) | [Runtime](../../docs/runtime-contract.md), [configuration](../../docs/configuration.md) |
| Chat Completions descriptor, field maps, JSON/SSE terminal states | [Chat Completions](chat-completions-protocol.md#surface) | [Protocol](../../docs/openai-chat-completions.md) |
| HTTP descriptor/error matrix, transport, headers, health и shutdown | [HTTP gateway](http-gateway.md#descriptor) | [Runtime](../../docs/runtime-contract.md), [deployment](../../docs/deployment.md) |
| Startup policy schema, matching, overrides, execution, aggregation | [Policy engine](policy-engine.md#startup-snapshot-and-schema) | [Policies](../../docs/policies.md) |
| Bounded request ingest/read/replay, quota и ownership | [Request source](request-source.md#ownership-model) | [Architecture](../../docs/architecture.md) |
| REQUEST selection, reactions, masking и handoff | [REQUEST enforcement](request-enforcement.md#request-boundary) | [Runtime](../../docs/runtime-contract.md), [evidence](../../docs/request-enforcement-evidence.md) |
| RESPONSE retention, JSON/SSE reactions, masking и disclosure | [RESPONSE enforcement](response-enforcement.md#atomic-boundary) | [Runtime](../../docs/runtime-contract.md), [masking headers](../../docs/response-masking-headers.md) |
| Logs, audit pairs, metrics/traces, privacy и stdout ownership | [Observability](observability.md#stdout-topology-and-ownership) | [Runtime observability](../../docs/observability.md) |

## Требования к последующим изменениям

Для каждого current clause сохраняются наблюдаемое поведение, ограничения,
ошибки и каждый именованный case. Superseded semantics, planning/history и
полные копии завершённых issues не становятся постоянной спецификацией.
Product targets не ослабляются под текущий код; gap остаётся явным в coverage.
После изменения обновляются dependent capability references, navigation,
runtime docs и coverage одним consistency change, затем выполняются
[проверки результирующего каталога](../../docs/development.md#проверка-каталога-и-ссылок).
