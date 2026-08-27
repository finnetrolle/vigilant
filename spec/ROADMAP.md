# Roadmap: промышленный PII shadow proxy

**Тип:** cross-epic roadmap

**Целевой результат:** устанавливаемый OpenAI-compatible proxy, который проверяет
request payload через встроенный PII-detector, пересылает разрешённый запрос без
изменений и пишет безопасное структурированное audit event.

**Целевой milestone:** Production PII shadow proxy

## Назначение roadmap

Roadmap связывает work items нескольких epics в один delivery path к первому
промышленно применимому guardrail increment. Он не является epic, не владеет
нормативным scope дочерних компонентов и не меняет источник истины для статусов:

- нормативные требования остаются в соответствующих epic-файлах;
- status и hard dependencies остаются в issue-файлах;
- roadmap задаёт cross-epic порядок, milestones и недостающие delivery leaves;
- planned leaf без опубликованного issue-файла не считается work item и не
  получает ID в этом документе;
- новый ID присваивается только при публикации issue в соответствии с
  [WORK_ITEMS.md](WORK_ITEMS.md).

## Зафиксированный первый production increment

### Поддерживаемая поверхность

- Только `POST /v1/chat/completions` с JSON request body.
- Проверяется только request direction.
- Upstream response, включая SSE при `stream=true`, остаётся существующим
  streaming pass-through без content inspection.
- Другие OpenAI endpoints получают явный stable `unsupported` outcome и не
  проходят через молчаливый bypass.
- Detector работает в shadow-only режиме: finding не блокирует forwarding и не
  изменяет body.

### Inspectability и forwarding

- Client body полностью принимается в bounded in-memory spool до первого
  upstream byte.
- Parser получает read-only original source и создаёт отдельные normalized
  attributes, text fragments, provenance и inspection gaps.
- Разрешённый request replay-ится upstream byte-for-byte из original source, а
  не сериализуется обратно из DTO.
- Unknown non-content properties сохраняются без изменения.
- Malformed JSON, unknown content discriminator, ambiguous content-bearing
  structure и unsupported Chat Completions schema дают stable safe error и не
  отправляются upstream.
- Schema-recognized non-text content разрешает forwarding, но создаёт явный
  `PARTIALLY_INSPECTABLE` или `UNINSPECTABLE` coverage result.
- PII finding даёт `DETECTED`; отсутствие findings при inspection gap даёт
  `INSPECTION_GAP`, а не `CLEAN`.
- Detector error или deadline не блокирует request в shadow-only режиме, но
  создаёт safe error observation.

### Resource model

- Первая версия использует только in-memory spool, без temporary files.
- Стартовый configurable per-request limit равен `8 MiB`.
- Стартовая configurable global spool quota равна `64 MiB`.
- Значения являются profiling baseline, а не неизменяемым product contract.
- Per-request limit даёт stable `413 request_too_large`.
- Global quota exhaustion даёт stable
  `503 inspection_capacity_exhausted`.
- Ingest, parsing, inspection и replay сохраняют backpressure и не создают
  unbounded queues.
- Blocking или CPU-bound работа не выполняется на Netty event loop.

### Windowed PII inspection

- Logical fragment больше detector limit `1 MiB` не обрезается и не
  отклоняется только из-за внутреннего limit.
- Window size не превышает detector payload limit.
- Overlap выводится из доказанного maximum supported PII candidate span.
- Window-local UTF-8 offsets переводятся в coordinates исходного fragment.
- Findings из соседних окон дедуплицируются детерминированно.
- Detectors без доказанной bounded match length не получают выдуманный overlap.

### Policy coverage

- `politics.conf` обязан содержать enabled global REQUEST policy с
  `url=*`, `model=*`, `subject=ANY` и detector `fast-pii`.
- Все reactions первого increment остаются `ALLOW`.
- Startup validation проверяет global `fast-pii` coverage policy.
- Отсутствующая или неверная coverage policy завершает startup с exit code `2`.
- Hardcoded hidden default policy не добавляется.

### Safe audit

На каждый поддерживаемый HTTP request создаётся один aggregated event:

```text
event.name=policy.shadow_decision
protocol=openai.chat_completions
phase=REQUEST
decision=DETECTED|CLEAN|INSPECTION_GAP|ERROR
disposition=ALLOW
coverage=FULLY_INSPECTABLE|PARTIALLY_INSPECTABLE|UNINSPECTABLE
```

Event также содержит trace ID, sorted policy ID/version, detector ID/version,
inspected fragment count, total finding count, sorted counts по `PiiType`,
sorted `EvidenceStrength` и total evaluation duration.

Event и связанные errors не содержат payload, matched text, offsets, protocol
locator, media URL, filename, raw headers, identity values, credentials или
reversible hashes. Detector errors и policy deadlines остаются отдельными
structured error events.

## Discovery map

```text
Production PII shadow proxy
+-- Runtime foundation
|   +-- bounded memory and throughput
|   +-- request/response backpressure evidence
|   +-- pooling and protocol error evidence
|   +-- graceful shutdown and owned resources
+-- OpenAI protocol
|   +-- Chat Completions JSON request field map
|   +-- strict inspectability and stable failures
|   +-- normalized model attribute and text fragments
+-- Source lifecycle
|   +-- bounded in-memory ingest
|   +-- read-only parser view
|   +-- byte-identical replay
|   +-- quotas, cancellation and cleanup
+-- Large fragments
|   +-- detector window capability
|   +-- overlap and boundary correctness
|   +-- offset translation and deduplication
+-- Policy integration
|   +-- anonymous request PolicyContext
|   +-- FastPiiDetector policy adapter
|   +-- global coverage validation
|   +-- bounded execution outside event loop
+-- Operator-visible tracer bullet
|   +-- PII request forwarded unchanged
|   +-- one safe shadow decision event
|   +-- fail-closed protocol outcomes
|   +-- explicit inspection gaps
|   +-- stable resource exhaustion
+-- Release evidence
    +-- packaged MainKt E2E
    +-- OCI container smoke
    +-- safety and memory gates
    +-- advisory latency/load report
```

## Delivery path

### Stage 0: стабилизировать runtime foundation

[VIG-09-01](issues/epic_09/issue_09_01_memory_stability.md) локализовал и
устранил OOM finding, а
[VIG-09-02](issues/epic_09/issue_09_02_perf01_latency.md) подтвердил PERF-01.
Оба hard gate имеют status `Done`.

[VIG-09-03](issues/epic_09/issue_09_03_request_backpressure.md) -
[VIG-09-08](issues/epic_09/issue_09_08_shutdown_lifecycle.md) доказали через E2E
seams streaming/backpressure, connection reuse, malformed upstream, dynamic
response hop-by-hop stripping и shutdown lifecycle.
[VIG-09-09](issues/epic_09/issue_09_09_work_item_validator.md) добавил
детерминированный completion gate `./gradlew validateWorkItems` в `check`.
Все дочерние issues и EPIC-09 имеют status `Done`.

Existing PERF-01 contract остаётся нормативным отдельно от advisory inspection
baseline.

### Stage 1: закрыть contract decisions

Stage завершён. Все четыре documentation-only issues имеют status `Done`, их
parent epics содержат normative decisions, а implementation leaves опубликованы
с estimate не более пяти инженерных дней и pre-agreed public seams.

1. [VIG-06-01](issues/epic_06/issue_06_01_protocol_contract.md) зафиксировал
   versioned Chat Completions JSON request field map, inspectability results,
   stable failures и boundary с spool/windowing. Response, SSE inspection и
   Responses API implementation leaves остались future `Draft`.
2. [VIG-03-01](issues/epic_03/issue_03_01_context_contract.md) зафиксировал
   anonymous request context для global `ANY` policy без identity extraction.
3. [VIG-07-01](issues/epic_07/issue_07_01_windowing_contract.md) зафиксировал
   detector capability, overlap, offset translation,
   deduplication, cancellation и error aggregation.
4. [VIG-08-01](issues/epic_08/issue_08_01_spool_contract.md) зафиксировал
   request-only in-memory source, quotas, replay, backpressure и
   cleanup. Response/SSE storage и disk spill остаются `Draft`.

Contract issues обязаны опубликовать implementation leaves с estimate не более
пяти инженерных дней, отдельным observable result и pre-agreed public seam.

### Stage 2: реализовать независимые module seams

Следующие planned leaves получают реальные IDs только при публикации в своих
parent epics:

| Parent | Work item | Observable result | Hard blockers | Estimate | Confidence |
|---|---|---|---|---:|---|
| EPIC-06 | [VIG-06-02: Chat Completions JSON request parser](issues/epic_06/issue_06_02_chat_completions_request_parser.md) | Original JSON даёт normalized model, ordered text fragments и coverage/failure result без reconstruction | VIG-06-01 Done | 3-5 дней | Medium |
| EPIC-07 | [VIG-07-02: Windowed fast PII execution](issues/epic_07/issue_07_02_windowed_fast_pii_execution.md) | Fragment до configured request limit проверяется без boundary false negatives и duplicate findings | VIG-07-01 Done, VIG-06-01 Done, EPIC-02 Done | 3-5 дней | Medium |
| EPIC-08 | [VIG-08-02: Bounded in-memory request source](issues/epic_08/issue_08_02_bounded_request_source.md) | Client bytes принимаются с quotas/backpressure и replay-ятся byte-for-byte либо дают stable capacity error | VIG-08-01 Done, VIG-06-01 Done | 3-5 дней | Medium |
| EPIC-03 | [VIG-03-07: Anonymous request PolicyContext](issues/epic_03/issue_03_07_anonymous_request_context.md) | Normalized path и parser model создают REQUEST context с subject `ANY` без raw body или identity | VIG-03-01 Done, VIG-03-02, VIG-06-02 | 2-3 дня | Medium |

EPIC-03 обязан создать отдельный anonymous request leaf. Existing VIG-03-04
сохраняет dependency на identity extraction и не используется как скрытый
shortcut для первого milestone.

### Stage 3: связать готовые компоненты standalone issues

Integration leaves являются самостоятельными issues, потому что EPIC-02 и
EPIC-04 уже закрыты, а повторное открытие их normative scope не требуется.
Roadmap группирует эти leaves без создания нового epic.

| Planned leaf | Mode | Observable result | Hard blockers | Estimate | Confidence |
|---|---|---|---|---:|---|
| [VIG-11: Fast PII policy adapter](issues/issue_11_fast_pii_policy_adapter.md) | Module seam | `FastPiiDetector` доступен как `policy.domain.Detector` с lossless normalized finding metadata | EPIC-02 Done, EPIC-04 Done | 1-2 дня | High |
| [VIG-12: Global shadow coverage validation](issues/issue_12_global_shadow_coverage_validation.md) | Vertical config slice | Process стартует только с валидной global REQUEST policy для `fast-pii` | EPIC-04 Done | 2-3 дня | High |
| [VIG-13: PII shadow request tracer bullet](issues/issue_13_pii_shadow_request_tracer.md) | Tracer bullet | Реальный Chat Completions request с PII проходит через gateway byte-identical и создаёт один safe `DETECTED` event | parser, windowing, spool, context, adapter, coverage leaves | 3-5 дней | Medium |
| [VIG-14: Strict protocol and gap outcomes](issues/issue_14_strict_protocol_gap_outcomes.md) | Tracer bullet | Malformed/ambiguous input не достигает upstream, известный non-text content forwarding-ится с явным inspection gap | PII shadow request tracer bullet | 2-3 дня | Medium |
| [VIG-15: Capacity and cancellation outcomes](issues/issue_15_capacity_cancellation_outcomes.md) | Tracer bullet | Per-request/global limits, cancellation и deadlines дают bounded stable outcomes без retained source | PII shadow request tracer bullet | 2-4 дня | Medium |
| [VIG-16: Packaged shadow proxy evidence](issues/issue_16_packaged_shadow_proxy_evidence.md) | Tracer bullet | `MainKt` и OCI container проходят production-process E2E с real upstream stub, config и JSONL audit | все integration leaves, VIG-09-08 | 3-5 дней | Medium |

Каждая behavior change выполняется TDD vertical slices: один focused RED через
указанный public seam, минимальный GREEN, затем следующий behavior. Tests не
мокают собственные production classes и не проверяют private implementation.

### Stage 4: production milestone

`Production PII shadow proxy` достигнут только когда:

- все issues EPIC-09 имеют status `Done`;
- все опубликованные roadmap contract, module и integration leaves имеют
  status `Done`;
- OCI image запускается с mounted `politics.conf` и documented HOCON/env
  configuration;
- `/healthz` и `/readyz` отражают lifecycle;
- PII request forwarding byte-identical и создаёт expected safe audit event;
- malformed, ambiguous, unsupported и resource-exhausted requests не создают
  partial upstream forwarding;
- request, cancellation и shutdown освобождают spool, executor tasks и owned
  resources;
- logs и errors не содержат raw PII, body, credentials или reversible values;
- `./gradlew build`, packaged-process E2E и work-item validator проходят;
- load report публикует actual throughput, memory, p50/p95/p99 parsing,
  windowing, policy evaluation и total inspection.

Ориентиры `2 000 RPS`, request до `64 KiB` и total inspection p99 `50 ms` не
являются release blockers первого increment. Обязательны измерение, честный
отчёт и строгие safety gates: no OOM, no unbounded memory, no silent bypass,
no truncation, deterministic results и expected HTTP outcome для каждого
request.

### Stage 5: улучшать quality после первого deploy

После production milestone приоритет переходит к quality leaves EPIC-10.
[VIG-10-01](issues/epic_10/issue_10_01_quality_diagnostics.md) зафиксировала
frozen evaluation split и safe diagnostics, а
[VIG-10-02](issues/epic_10/issue_10_02_ip_candidate_boundaries.md) закрыла
boundary hardening для IP. Следующие format-preserving leaves - VIG-10-04 и
VIG-10-05. Текущий общий RedMadRobot exact recall остаётся недостаточным
основанием для PII enforcement.

## Delivery graph

```text
VIG-06-01 Done +--> VIG-06-02 request parser --------+
               +--> VIG-07-01 Done --> VIG-07-02 ----+
               +--> VIG-08-01 Done --> VIG-08-02 ----+
                                                       |
VIG-03-01 Done --> VIG-03-02 --> VIG-03-07 context ---+
                                      ^               |
                                      +-- VIG-06-02 --+
                                                   |
EPIC-02 Done --> Fast PII adapter -----------------+
EPIC-04 Done --> global coverage validation -------+
                                                   v
                                      PII shadow tracer bullet
                                                   |
                         +-------------------------+------------------+
                         v                         v                  v
                protocol/gap outcomes    capacity/cancellation    safe audit
                         +-------------------------+------------------+
                                                   v
VIG-09-01..09 Done --------------------> packaged OCI evidence
                                                   |
                                                   v
                                  Production PII shadow proxy
                                                   |
                                                   v
                                      EPIC-10 quality roadmap
```

Hard dependency у anonymous context на parser относится только к переносу
normalized `model`; URL normalization может реализовываться независимо. Safe
audit является частью каждого tracer bullet acceptance, а не отдельным
horizontal logging subsystem.

## Тестовые seams

- EPIC-06 parser: pure public parse result на versioned Chat Completions JSON
  examples.
- EPIC-07 windowing: transport-neutral fragment inspection с exact original
  UTF-8 offsets и boundary corpus.
- EPIC-08 spool: public source ingest/read/replay contract с controlled
  subscriber demand.
- EPIC-03 context: pure normalized inputs to immutable `PolicyContext`.
- Fast PII adapter: public `policy.domain.Detector.detect`.
- Proxy behavior: real Armeria client, gateway и upstream на ephemeral ports.
- Logs после response completion: deadline-bounded polling через
  `GatewayTestFixture.awaitUntil`.
- Distribution evidence: packaged `MainKt` child process и OCI container.

## Текущий roadmap frontier

Все implementation issues Stage 0 завершены: VIG-09-01..09 и EPIC-09 имеют
status `Done`, а project work-item validator входит в `check`. VIG-10-01 и
VIG-10-02 также имеют status `Done`.

Implementation issues Stage 2 закрыты: VIG-03-02, VIG-03-07, VIG-06-02,
VIG-07-02 и VIG-08-02 имеют status `Done`; независимые module seams готовы к
integration. EPIC-07 остаётся `In progress` до подтверждения
memory/concurrency bounds benchmark или baseline. Identity leaves EPIC-03
готовы по contract, но не входят в первый production increment.

Все standalone integration issues Stage 3 закрыты: VIG-11..16 имеют status
`Done`. Production `MainKt` и OCI image выполняют bounded PII shadow inspection
для Chat Completions request, exact replay, safe aggregate audit и lifecycle
gates. Stage 4 production milestone остаётся отдельным frontier до публикации
предусмотренного им load report.

Полный repository frontier также сохраняет VIG-01A и VIG-10-03..07. VIG-01A
проверяет logging-specific PERF-01, а VIG-10-03..07 относятся к Stage 5 и не
создают отсутствующий HTTP integration path.

## Не входит в первый production increment

- OpenAI Responses API, Realtime и Batch.
- Response content inspection и policy evaluation.
- SSE buffering для response enforcement.
- `BLOCK`, `MASK`, `REMOVE` и изменение protocol source.
- User/group identity extraction и trusted ingress model.
- Disk spill, encryption at rest и external object storage.
- Kubernetes manifests, Helm, autoscaling и centralized log storage.
- ML/NER и новые PII taxonomy values.
- Использование production payload или raw audit data для quality fixtures.

## Выбранные альтернативы

- Direct detector call из `BypassProxyService` отклонён: он обходит policy
  engine и смешивает transport, parsing и detection.
- Early upstream forwarding отклонён: parser failure может появиться после
  partial disclosure upstream.
- DTO reconstruction отклонён: unmodified forwarding обязан использовать
  original source.
- Fixed `1 MiB` rejection отклонён: detector limit закрывается windowing.
- Disk spill отложен: bounded in-memory path быстрее даёт безопасный первый
  increment без сохранения raw PII на disk.
- Hardcoded default policy отклонён: operator обязан явно предоставить
  validated global coverage policy.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ deployable request-side PII shadow proxy определён
  Acceptance:   0.25  ✓ behavior и safety gates проверяемы
  Boundaries:   0.0   ✓ response, enforcement и другие APIs исключены
  Alternatives: 0.25  ✓ shortcuts и storage alternatives зафиксированы
  Assumptions:  0.25  ✓ resource defaults provisional и требуют profile
  ──────────────────────────────
  Aggregate:    0.15  ✓ below threshold (0.2 roadmap)

Push lightly on: pinned OpenAI schema snapshot, measured spool defaults.
```
