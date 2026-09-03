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

### Safe request analysis pair

Каждый supported REQUEST, где после parse, identity/context assembly и
policy selection реально начинается detector execution, best-effort
публикует в JSONL stdout ровно одну lifecycle pair:

```text
event.name=policy.analysis_started | policy.analysis_completed
protocol=openai.chat_completions
phase=REQUEST
outcome=CLEAN|DETECTED|INSPECTION_GAP|ERROR
```

Оба event содержат correlation одного inspection span: span IDs генерируются
server-side, а trace ID может продолжать валидный W3C parent по current tracing
contract. Events также содержат canonical policy references и detector
ID/version. Terminal event содержит coverage, aggregate
fragment/finding counts, duration и `reaction=ALLOW` либо stable `error.code`
без reaction. Payload, PII values/spans, path/query, headers, credentials,
identity, user/groups, session и raw inbound propagation values запрещены.

Existing Logback `AsyncAppender` с `neverBlock=true` остаётся единственной
queue. Request path не ждёт logging delivery, stdout write или durable
acknowledgement. RESPONSE pair остаётся future behavior owning leaves EPIC-20.

### Historical durable subsystem

Исторический нормативный [contract](MINIMUM_AUDIT_TRAIL_CONTRACT.md) и completed
[EPIC-22](epics/epic_22_durable_minimum_audit_trail.md) фиксируют прежний
application-owned WAL, Collector handoff и qualification. VIG-32-01 отключил
request analysis от record reservation/submission/acknowledgement, а VIG-32-02
удалил подсистему и её readiness/startup/shutdown/packaging consumers из
current runtime. Historical work items и versioned evidence сохраняются только
как запись прежнего контракта.

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
|   +-- one safe request-analysis event pair
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
   stable failures и boundary с spool/windowing. Combined Chat Completions
   JSON/SSE response parser реализован в VIG-06-03; OpenAI Responses API
   implementation leaves остались future scope.
2. [VIG-03-01](issues/epic_03/issue_03_01_context_contract.md) зафиксировал
   anonymous request context для global `ANY` policy без identity extraction.
3. [VIG-07-01](issues/epic_07/issue_07_01_windowing_contract.md) зафиксировал
   detector capability, overlap, offset translation,
   deduplication, cancellation и error aggregation.
4. [VIG-08-01](issues/epic_08/issue_08_01_spool_contract.md) зафиксировал
   request-only in-memory source, quotas, replay, backpressure и
   cleanup. Retained in-memory response source lifecycle для ordinary и SSE
   responses перенесён в
   [EPIC-20](epics/epic_20_atomic_in_memory_response_analysis.md), который
   сейчас имеет статус `In progress`.

Contract issues обязаны опубликовать implementation leaves с estimate не более
пяти инженерных дней, отдельным observable result и pre-agreed public seam.

### Stage 2: реализовать независимые module seams

Следующие planned leaves получают реальные IDs только при публикации в своих
parent epics:

| Parent | Work item | Observable result | Hard blockers | Estimate | Confidence |
|---|---|---|---|---:|---|
| EPIC-06 | [VIG-06-02: Chat Completions JSON request parser](issues/epic_06/issue_06_02_chat_completions_request_parser.md) | Original JSON даёт normalized model, ordered text fragments и coverage/failure result без reconstruction | VIG-06-01 Done | 3-5 дней | Medium |
| EPIC-06 | [VIG-06-03: Chat Completions JSON/SSE response parser](issues/epic_06/issue_06_03_chat_completions_response_parser.md) | Immutable byte segments дают terminal normalized response либо safe typed outcome независимо от transport chunking | VIG-06-02 Done | 4-5 дней | Medium |
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
| [VIG-17: Сквозной tracing context и OTLP JSON через stdout](issues/issue_17_request_tracing_stdout_otlp.md) | Observability tracer bullet | Configurable session header и W3C trace context проходят client -> SERVER -> INTERNAL/CLIENT -> response, а application и OTLP JSON records выходят через stdout без прямого Collector connection | VIG-01, VIG-05-06, VIG-05-07, VIG-13 | 3-5 дней | High |

Каждая behavior change выполняется TDD vertical slices: один focused RED через
указанный public seam, минимальный GREEN, затем следующий behavior. Tests не
мокают собственные production classes и не проверяют private implementation.

### Stage 4: production milestone

[VIG-18](issues/issue_18_inspection_load_report.md) владеет последним release
gate: воспроизводимым inspection-load baseline и опубликованным production
report. Он не превращает advisory latency targets в blockers, но обязан
подтвердить safety и resource bounds на реальном packaged process.

Gate закрыт 2026-08-27. [Versioned report](../docs/inspection-load-result.md)
фиксирует `PASS`: `240 000/240 000` measured requests при `2 000 RPS`, HTTP
p50/p95/p99 `2/3/4 ms`, total inspection `64 KiB` p99 `1.670 ms`, bounded RSS,
byte-identical replay и полный набор matched safe aggregate events. Production
milestone достигнут.

Граница evidence VIG-18 - ровно измеренный profile от `2026-08-27`:
synthetic request `64 KiB` с single PII-bearing fragment, hardware Apple M3 Max
и gateway heap `512 MiB`. Этот run не доказывает throughput или
memory envelope для всей accepted `8 MiB` request surface.

Отдельный test-only [VIG-21-02](issues/epic_21/issue_21_02_adversarial_inspection_resource_qualification.md)
от `2026-08-30` расширяет claims только на свой measured profile:
three exact `8 MiB` accepted shapes, fragment-overflow rejection и concurrent
raw-source capacity boundary на Mac OS X 26.3.1/aarch64 с heap `1 GiB` и
direct-memory limit `512 MiB`. Его [versioned report](../docs/inspection-resource-qualification-2026-08-30.md)
фиксирует exact HTTP, audit, replay, cleanup и memory observations, но не
заменяет VIG-18 throughput baseline и не создаёт universal capacity promise.

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
- session, trace, span и parent lineage сохраняется от клиента через gateway
  до upstream и обратно, включая корректный context текущего log event;
- application logs и OTLP trace/metric records выходят отдельными атомарными
  JSON Lines через stdout без прямого Collector connection;
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

После production milestone quality leaves EPIC-10 завершены. VIG-10-01..07
добавили frozen evaluation split, safe diagnostics, product-aligned report,
boundary hardening, format-preserving email/phone surfaces и contextual
SNILS/OMS fallback. Финальная
[VIG-10-08](issues/epic_10/issue_10_08_quality_qualification.md) подтвердила
source-aligned floors, улучшение frozen evaluation, canonical contract и
paired JMH performance gate. Эти метрики характеризуют detector, но сами по
себе не включают PII enforcement.

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
VIG-09-01..09 Done --------------------> packaged OCI evidence ------+
VIG-01 + VIG-05-06/07 + VIG-13 -------> VIG-17 tracing/OTLP stdout -+
                                                                    |
                                                                    v
                                                      VIG-18 load baseline
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
  request и JSON/SSE response corpus.
- EPIC-07 windowing: transport-neutral fragment inspection с exact original
  UTF-8 offsets и boundary corpus.
- EPIC-08 spool: public source ingest/read/replay contract с controlled
  subscriber demand.
- EPIC-03 context: pure normalized inputs to immutable `PolicyContext`.
- Fast PII adapter: public `policy.domain.Detector.detect`.
- Proxy behavior: real Armeria client, gateway и upstream на ephemeral ports.
- Logs после response completion: deadline-bounded polling через
  `GatewayTestFixture.awaitUntil`.
- Tracing: real HTTP custom headers плюс in-memory SDK проверяют session,
  W3C trace/span/parent propagation и span tree.
- Telemetry stdout: captured line writer проверяет отдельные application JSONL
  и OTLP `resourceSpans`/`resourceMetrics` records без byte interleaving.
- Distribution evidence: packaged `MainKt` child process и OCI container.

## Текущий roadmap frontier

Все implementation issues Stage 0 завершены: VIG-09-01..09 и EPIC-09 имеют
status `Done`, а project work-item validator входит в `check`. VIG-10-01 и
VIG-10-02 также имеют status `Done`.

Implementation issues Stage 2 закрыты: VIG-03-02, VIG-03-07, VIG-06-02, VIG-06-03,
VIG-07-02 и VIG-08-02 имеют status `Done`; независимые module seams готовы к
integration. VIG-18 подтверждает только `64 KiB` single-fragment profile;
VIG-21-02 отдельно подтверждает опубликованную max-shape matrix. EPIC-07 и
request-only EPIC-08 также имеют status `Done`. Identity
scope исторического first production increment был anonymous-only. После
него VIG-03-03..06 закрыли прежний identity path. VIG-27 заменил его
development/test-only mode `DUMMY`. VIG-28 добавил production-capable offline
JWT Bearer extractor с pinned RSA public JWK set и без runtime identity I/O.

Все standalone integration issues Stage 3 закрыты: VIG-11..17 имеют status
`Done`. Production `MainKt` и OCI image выполняют bounded PII shadow inspection
для Chat Completions request, exact replay, safe aggregate audit и lifecycle
gates. Configurable session header и W3C trace context сохраняются через
SERVER, INTERNAL и CLIENT spans; application и OTLP JSON records передаются
через stdout. VIG-18 и Stage 4 закрыты: versioned load report опубликован,
обязательные safety gates пройдены, `Production PII shadow proxy` достигнут.

Post-milestone evidence также синхронизировано: VIG-21-03 и VIG-21-04 имеют
status `Done`; upstream-error и streaming tests используют bounded causal
observation seams. Их исторические issue records и reports сохраняют
исходные dates и evidence. EPIC-20 остаётся единственным owner retained
in-memory response source lifecycle и enforcement для ordinary и SSE
responses, а EPIC-06 владеет protocol parsing; EPIC-21 закрыт без
дублирующего future scope.

Post-milestone closure EPIC-21 завершён. В EPIC-22 local durable store
[VIG-22-01](issues/epic_22/issue_22_01_local_durable_audit_store.md), mandatory
request-path acceptance
[VIG-22-02](issues/epic_22/issue_22_02_request_path_audit_acceptance.md) и
acknowledged Collector handoff
[VIG-22-03](issues/epic_22/issue_22_03_collector_handoff_reclaim.md) имеют status
`Done`. Exact admission mapping
[VIG-22-05](issues/epic_22/issue_22_05_audit_exhaustion_admission_mapping.md)
и packaged qualification
[VIG-22-04](issues/epic_22/issue_22_04_packaged_durability_qualification.md)
тоже имеют status `Done`; EPIC-22 завершён с versioned PASS evidence.
VIG-01A, EPIC-10 и VIG-10-08 имеют status `Done` и не входят в current
frontier.

EPIC-32 завершил миграцию current audit на best-effort stdout. VIG-32-01
публикует REQUEST lifecycle pair без durable acknowledgement; VIG-32-02 удалил
WAL, Collector handoff, audit-driven readiness/configuration и packaging
consumers.

## Не входит в первый production increment

- OpenAI Responses API, Realtime и Batch.
- Response content inspection и atomic retained in-memory response source
  lifecycle для SSE, принадлежащие future
  [EPIC-20](epics/epic_20_atomic_in_memory_response_analysis.md).
- `BLOCK`, `MASK`, `REMOVE` и изменение protocol source.
- historical scope первого milestone не включал user/group identity
  extraction и trusted ingress model. Это temporal boundary, а не current
  exclusion: current runtime поддерживает development/test Dummy и production
  offline JWT Bearer identity с локально pinned trust snapshot.
- External object storage.
- Kubernetes manifests, Helm, autoscaling и centralized log storage.
- Backend-specific Langfuse/MLflow exporters и authentication внутри Vigilant.
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
- Disk spill отклонён для MVP в EPIC-20: retained in-memory response source не
  сохраняет raw PII на disk.
- Hardcoded default policy отклонён: operator обязан явно предоставить
  validated global coverage policy.
- Прямой OTLP network exporter из Vigilant отклонён: application logs и
  OTLP/JSON telemetry передаются через stdout, а delivery выполняет Collector.
- Proprietary request correlation header отклонён: configurable header несёт
  стандартное W3C `traceparent`, а session остаётся отдельным opaque ID.

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
