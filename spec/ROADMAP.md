# Roadmap: guardrails gateway

**Тип:** cross-epic roadmap

## Назначение

Roadmap показывает достигнутый runtime milestone, открытые gaps и реальный
delivery frontier. Он не является нормативным owner и не дублирует завершённые
issues или epics. История реализации и прежние measurements остаются в Git.

- [55 stable requirement IDs](requirements/README.md) и detailed contracts
  определяют целевое поведение.
- [Coverage](../docs/requirements-coverage.md) отделяет target, фактический
  runtime и применимое evidence.
- [Work-item registry](WORK_ITEMS.md) владеет статусами, hard dependencies и
  порядком незавершённой работы.
- [Runtime documentation](../docs/README.md) объясняет текущую реализацию.

## Текущий runtime milestone

Vigilant является OpenAI-compatible guardrails gateway для Chat Completions:

- request проверяется до upstream и применяет `ALLOW`, exact non-expanding
  `MASK` или whole-request `BLOCK` по
  [REQUEST enforcement](requirements/request-enforcement.md);
- ordinary JSON и SSE response удерживаются до terminal protocol state и
  policy decision, затем атомарно применяют `ALLOW`, exact source-patched
  `MASK` или `BLOCK` по
  [RESPONSE enforcement](requirements/response-enforcement.md);
- original bytes и unknown fields сохраняются, а unsupported, malformed или
  ambiguous content-bearing structures получают stable fail-closed outcome по
  [protocol](requirements/chat-completions-protocol.md) и
  [HTTP gateway](requirements/http-gateway.md);
- request source bounded и quota-controlled; retained response source не имеет
  application-level quota и очищает owned references на terminal paths;
- startup выбирает ровно один `DUMMY`, offline `JWT` или trusted Bridge
  `EXTERNAL` identity mode по
  [identity/context contract](requirements/identity-and-context.md);
- immutable startup policy snapshot, deterministic selection, overrides,
  deadlines и aggregation принадлежат
  [policy engine](requirements/policy-engine.md);
- request и response analysis публикуют safe best-effort stdout pair, а
  application не владеет audit persistence или delivery по
  [observability contract](requirements/observability.md).

Другие OpenAI APIs, tool execution, non-PII detectors, plugin workers,
Kubernetes/Helm и application-owned observability storage не входят в текущий
MVP. Полная граница принадлежит
[Stage 1](STAGE_1_FUNCTIONS.md) и
[product non-goals](OUT_OF_SCOPE_FUNCTIONS.md).

## Evidence frontier

Documentation migration не создаёт runtime evidence и не превращает прежние
bypass, shadow, load или resource measurements в current guarantees.

- `PERF-01` не подтверждён для отдельного request/response enforcement profile;
  `PERF-02` подтверждён частично. Новая qualification должна использовать
  target profile и фиксировать warmup, duration, hardware, JVM, connections,
  payload sizes и percentiles.
- Product targets `16 MiB` inspectable text и `20 MiB` raw request не
  подтверждаются runtime default `8 MiB` или старым smaller-profile run.
- Dedicated inspection OTel instruments и complete all-channel privacy
  evidence имеют открытые gaps.
- PII/window, identity, protocol и lifecycle matrices содержат перечисленные в
  [coverage](../docs/requirements-coverage.md) conformance gaps. Похожий
  internal API не повышает статус Stage 1 или out-of-scope capability.

Reproducible commands и обязательные test matrices находятся в
[development guide](../docs/development.md). Current test topology использует
serial `processTest`, четыре non-process forks и exact 3 + 3 + 10 local
qualification без fallback. Historical durations или percentage improvements
не являются current measurement.

## Текущий roadmap frontier

Delivery order определяется
[active registry](WORK_ITEMS.md#active-todo-порядок-следующей-работы):

1. [VIG-38](issues/issue_38_risk_based_scope_lock.md) добавляет risk-based scope
   lock для архитектурно дорогих implementation-ready work items.
2. [VIG-39](issues/issue_39_compact_task_context.md) создаёт компактный
   детерминированный task packet после VIG-38.
3. [VIG-40](issues/issue_40_reusable_verification_evidence.md) закрепляет один
   verification snapshot и reusable evidence после VIG-39.

[VIG-33](issues/issue_33_availability_slo_and_operations.md) остаётся `Draft`:
численный SLI/SLO, ownership внешних failures и production evidence period ещё
не выбраны. Отсутствие application-owned audit file не закрывает эти решения.

[EPIC-06](epics/epic_06_llm_message_parsing.md) остаётся `Draft` для future
OpenAI Responses scope и не имеет implementation-ready leaves. Текущий Chat
Completions protocol не задаёт Responses, Realtime или Batch semantics.

## Проверяемые seams

- Protocol: public parser над complete immutable request/response source и
  independent literal corpus.
- Policy: immutable startup snapshot, public selector/executor и controlled
  deadline/cancellation observations.
- Request/response ownership: public source counters, exact replay и every
  terminal cleanup path.
- Gateway: real Armeria client, gateway и upstream; raw HTTP/1 fixture для
  wire-level malformed/header cases.
- Streaming: upstream final chunk освобождается только после first non-empty
  client body observation.
- Health/shutdown: readiness, admission, drain и resource termination
  наблюдаются на owning boundary с bounded causal barriers.
- Process/package: shared mandatory startup settings, never-reused loopback
  ports, child/output-reader ownership, installed distribution и explicit OCI
  checks when required by an issue.
- Performance: generated reports относятся только к одному зафиксированному
  machine/HEAD/tree snapshot и не коммитятся как universal guarantee.

## Границы roadmap

Roadmap не выбирает availability SLO, не ослабляет product targets под текущий
runtime, не объявляет future capabilities реализованными и не хранит completed
planning records. Новый executable scope появляется только как согласованный
work item в registry; permanent requirement получает один normative owner.
