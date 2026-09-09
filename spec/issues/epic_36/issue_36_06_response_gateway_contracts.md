# VIG-36-06: Постоянные требования response и gateway

**Статус:** Ready for implementation
**Epic:** [EPIC-36](../../epics/epic_36_current_requirements_and_work_item_lifecycle.md)
**Ветка:** migrate по RESPONSE/transport contract
**Зависит от:** [VIG-36-04](issue_36_04_protocol_error_contracts.md), [VIG-36-05](issue_36_05_request_enforcement_contracts.md)
**Блокирует:** [VIG-36-08](issue_36_08_current_catalog_closure.md)
**Связанные требования:** `MVP-01`, `MVP-03`, `PROXY-01..03`, `CONC-01`, `CONC-03`, `CONC-04`, `PERF-03`
**Оценка:** 2-3 инженерных дня
**Уверенность:** Medium

## Результат

Постоянные contracts описывают atomic RESPONSE enforcement и низкоуровневый HTTP gateway, включая разные streaming/retention boundaries и lifecycle cleanup.

## Обязательный контекст

До редактирования прочитать [EPIC-36](../../epics/epic_36_current_requirements_and_work_item_lifecycle.md), permanent owners, если они
уже опубликованы, current top-level requirements и **весь source batch ниже**.
Baseline - Git `ca8ec90`; полный текст текущего source сравнить с baseline,
чтобы не удалить новую незавершённую работу. Source titles и Done сами по себе
не доказывают актуальность содержащихся в них правил.

Protocol parsing/terminal definitions принадлежат VIG-36-04, policy engine VIG-36-05. Здесь их application enforcement и transport ownership.

## Source batch: 23 issues и 3 epics

Paths ниже относительно repository root. Каждая строка назначена этому leaf
как единственному removal owner; это конечный список, а не glob на все файлы
с подходящим именем. Current clauses из shared sources других leaves можно
читать, но их файлы здесь не удаляются.

- `spec/issues/epic_05/issue_05_01_upstream_error_mapping.md`
- `spec/issues/epic_05/issue_05_02_health_endpoints.md`
- `spec/issues/epic_05/issue_05_03_streaming_e2e.md`
- `spec/issues/epic_05/issue_05_04_cancellation_e2e.md`
- `spec/issues/epic_05/issue_05_05_upstream_timeouts.md`
- `spec/issues/epic_05/issue_05_06_trace_id_otlp.md`
- `spec/issues/epic_05/issue_05_07_otlp_metrics.md`
- `spec/issues/epic_05/issue_05_08_load_test.md`
- `spec/issues/epic_05/issue_05_09_oci_image.md`
- `spec/issues/epic_09/issue_09_01_memory_stability.md`
- `spec/issues/epic_09/issue_09_02_perf01_latency.md`
- `spec/issues/epic_09/issue_09_03_request_backpressure.md`
- `spec/issues/epic_09/issue_09_04_response_backpressure.md`
- `spec/issues/epic_09/issue_09_05_connection_pooling.md`
- `spec/issues/epic_09/issue_09_06_malformed_upstream.md`
- `spec/issues/epic_09/issue_09_07_response_connection_headers.md`
- `spec/issues/epic_09/issue_09_08_shutdown_lifecycle.md`
- `spec/issues/epic_09/issue_09_09_work_item_validator.md`
- `spec/issues/epic_20/issue_20_01_retained_memory_response_source.md`
- `spec/issues/epic_20/issue_20_02_response_inspection_enforcement.md`
- `spec/issues/epic_20/issue_20_03_reusable_text_masker.md`
- `spec/issues/epic_20/issue_20_04_retained_memory_response_contract.md`
- `spec/issues/epic_20/issue_20_05_sse_response_enforcement.md`
- `spec/epics/epic_05_v0_hardening.md`
- `spec/epics/epic_09_v0_architecture_closure.md`
- `spec/epics/epic_20_atomic_in_memory_response_analysis.md`

## Destination и current consumers

Permanent destinations:

- `spec/requirements/response-enforcement.md`
- `spec/requirements/http-gateway.md`: transport/headers/timeouts/health/shutdown sections

Current documents для синхронизации:

- `docs/runtime-contract.md`
- `docs/response-masking-headers.md`
- `docs/architecture.md`
- `docs/deployment.md`
- `docs/configuration.md`
- `docs/requirements-coverage.md`
- `spec/MVP_FUNCTIONS.md`
- `spec/MVP_NON_FUNCTIONAL_REQUIREMENTS.md`

Также обновить requirements index, свой registry/checklist и **все входящие
ссылки на удаляемый batch** во всём tracked root/spec/docs. Другие epics/issues
можно менять только для affected references или перенесённых shared clauses.
Нельзя оставить broken links ради соблюдения списка основных consumers.
Source consumers для фактической сверки: RetainedResponseSource/RetainedResponseHandler/ResponseInspectionWorkflow, JsonResponseRewriter/SseResponseRewriter, BypassProxyService, traffic admission/health/shutdown и existing source/HTTP/process tests.

## Обязательные contract cases

1. Ordinary JSON и SSE: complete source до первого client status/header/body byte, protocol-valid terminal state до policy decision, exact ALLOW, exact-source MASK, policy BLOCK и technical failure без partial disclosure.

2. Retained response не имеет application-level byte limit/shared quota/spill. Cleanup owned references обязателен; heap sizing, GC и OOM policy не объявляются application memory-safety guarantee.

3. JSON/SSE source maps, cross-event spans, reusable TextMasker contract, validation of masked representation, unknown fields/order/trailers и exact transport header rewrite для original/masked/error outcomes.

4. Response lifecycle: ingest, parse/analysis, original/masked replay, rejection, upstream failure/timeout, client cancellation, peer/caller close, normal/forced shutdown и one-shot ownership transfer.

5. Bypass transport остаётся streaming; request/response backpressure, cancellation, connection pooling, malformed upstream, connect/response/write timeouts и stable failures описываются по current contract.

6. End-to-end headers, static и Connection-listed hop-by-hop stripping, Host/authority/path/Content-Length, preserved upstream statuses/bodies и VIG-29 errors из existing error owner.

7. Health/readiness/admission/shutdown order, bounded drain/forced cancellation и cleanup failures для всех owned resources; упаковка/non-root OCI и обязательные current startup settings описаны без нового smoke claim.

Для каждого named case переносить полную уже согласованную матрицу source,
а не representative examples. Если current target ещё не подтверждён,
сохранить target и явно назвать gap в coverage. Не менять семантику code или
requirements, чтобы сделать документацию внешне согласованной.

## Особые границы

Действующие resource/streaming/test-determinism правила из EPIC-09 переходят в contract или current verification method, но старые PERF-01 результаты не становятся подтверждением обновлённого SLO. VIG-36-08 удаляет сами historical load/result docs. docs/response-masking-headers.md остаётся коротким runtime reference со ссылкой на normative rule, без второй полной normative matrix. Error sections http-gateway.md, опубликованные VIG-36-04, сохраняются.

Узнать actual source/test ownership перед переносом ссылки. Не переименовывать
production symbols и не добавлять будущие reactions/schema/config extensions.
Новый owner получает current contract, а не историю изменений или полный plan
исходной issue. Если соседний detailed file ещё не существует, использовать
существующий top-level/current reference и обновить его при публикации owner;
пустые placeholders и dangling future links запрещены.

## Public seam и evidence contract

- **Stimulus:** прочитать весь конечный batch, применить согласованный
  current-only transfer и удалить его completed files после переноса.
- **Public seam:** путь от root README/requirements index к конкретному rule,
  current runtime description и coverage; tracked filesystem и repository
  validation для removal/references.
- **Observable result:** каждый current clause найден у одного permanent
  owner; implementation detail или verification method находится в своём
  document; obsolete/planning/history не сохранились как requirements; batch
  отсутствует, ссылки и remaining work-item graph разрешаются.
- **Independent oracle:** original source clause из baseline, approved
  superseding contract и existing source/test observations. Новые headings,
  количество deleted files или green checker не заменяют semantic review.

Временная review matrix:
`source section/clause -> disposition -> destination anchor -> evidence`.
Использовать dispositions из EPIC-36, проверить каждую строку и отсутствие
unsupported current clauses. Matrix хранится для review вне final working
tree; historical mirror/archive в repository не добавляется.

## UML ownership

При найденном расхождении синхронно менять source и owning text document:

- `runtime-components.puml`, `runtime-classes.puml`,
  `request-inspection-sequence.puml` -> `docs/architecture.md`;
- `policy-selection-activity.puml` -> `docs/policies.md`;
- `tracing-sequence.puml` -> `docs/observability.md`.

Все sources находятся в `docs/diagrams/`. Менять только относящиеся к этому
leaf claims, сохранять UML 2.0; новые diagrams ради migration не нужны.
Для изменённого UML выполнить syntax/render check и просмотреть результат,
не добавляя generated SVG/PNG в Git.

## Критерии готовности

- [ ] Весь source batch прочитан и clause matrix полна; каждый current rule
  перенесён ровно к одному owner, каждая obsolete disposition обоснована.
- [ ] Все numbered contract cases выше и все их source subcases сохранены;
  нет label-only coverage, потерянных terminal paths или обобщений вместо
  exhaustive matrices.
- [ ] Permanent destinations, relevant root/spec/docs и affected UML описывают
  один согласованный contract; target/runtime/evidence gaps явно отражены,
  никакой coverage status не повышен без applicable evidence.
- [ ] Удалён exact source batch; все входящие paths/anchors/task dependencies
  удалены или перенаправлены к actual owner. Новая незавершённая работа,
  возникшая после baseline, не удалена автоматически.
- [ ] Registry, remaining epic checklists/backlinks/progress и fulfilled
  prerequisites обновлены одним consistency change по VIG-36-01.
- [ ] `rtk proxy ./gradlew workItemValidatorTest validateWorkItems` и
  `rtk proxy git diff --check` GREEN; modified UML проверен. Поиск stale
  claims и removed references имеет полный scope и объяснённые совпадения.
- [ ] Production behavior/config/tasks не изменены. Эта migration issue
  завершена и удалена по новому completion protocol, без historical record.

## Проверки и граница evidence

Сначала semantic review source/destination; затем actual catalog checks через
существующие commands VIG-36-01. После terminal removal выполнить их снова,
если removal был сделан после предыдущего прогона. Не запускать перекрывающиеся
Gradle processes. Full production build, OCI/load/process qualification не
требуется для documentation-only переноса и не заявляется выполненной.

## Не входит

Новый response limit/quota/spill, streaming redesign, изменение rewriting/errors/headers, новый runtime/performance evidence или Docker/config changes.

Не менять generated reports, CHANGELOG или .papercuts.jsonl. Не публиковать
новые независимые product issues и не объявлять неподтверждённые capabilities
готовыми в рамках этого leaf.

## Ambiguity Report

```text
Goals:        0.0   independently reviewable migration outcome explicit
Acceptance:   0.25  finite cases and public evidence; semantic review required
Boundaries:   0.0   current-only transfer, no new runtime behavior
Alternatives: 0.0   lifecycle and permanent owners agreed
Assumptions:  0.25   baseline verified; source drift must be checked
Aggregate:    0.10  Ready for implementation.
```
