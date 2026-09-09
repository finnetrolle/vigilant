# VIG-36-07: Постоянный контракт observability

**Статус:** Ready for implementation
**Epic:** [EPIC-36](../../epics/epic_36_current_requirements_and_work_item_lifecycle.md)
**Ветка:** migrate по telemetry/audit contract
**Зависит от:** [VIG-36-01](issue_36_01_completion_workflow.md)
**Блокирует:** [VIG-36-08](issue_36_08_current_catalog_closure.md)
**Связанные требования:** `MVP-06`, `OBS-01`, `OBS-02`, `OUT-06`
**Оценка:** 1-2 инженерных дня
**Уверенность:** Medium

## Результат

Единый permanent stdout/observability contract заменяет completed audit/logging tasks. Исторический application-owned durable contract исчезает из working tree.

## Обязательный контекст

До редактирования прочитать [EPIC-36](../../epics/epic_36_current_requirements_and_work_item_lifecycle.md), permanent owners, если они
уже опубликованы, current top-level requirements и **весь source batch ниже**.
Baseline - Git `ca8ec90`; полный текст текущего source сравнить с baseline,
чтобы не удалить новую незавершённую работу. Source titles и Done сами по себе
не доказывают актуальность содержащихся в них правил.

VIG-36-08 удаляет historical architecture reviews и прочие load/resource reports. Здесь только durable-specific contract/report и current observability.

## Source batch: 10 issues и 2 epics; два obsolete contract/report файла дополнительно

Paths ниже относительно repository root. Каждая строка назначена этому leaf
как единственному removal owner; это конечный список, а не glob на все файлы
с подходящим именем. Current clauses из shared sources других leaves можно
читать, но их файлы здесь не удаляются.

- `spec/issues/epic_21/issue_21_01_minimum_audit_trail_contract.md`
- `spec/issues/epic_22/issue_22_01_local_durable_audit_store.md`
- `spec/issues/epic_22/issue_22_02_request_path_audit_acceptance.md`
- `spec/issues/epic_22/issue_22_03_collector_handoff_reclaim.md`
- `spec/issues/epic_22/issue_22_04_packaged_durability_qualification.md`
- `spec/issues/epic_22/issue_22_05_audit_exhaustion_admission_mapping.md`
- `spec/issues/epic_32/issue_32_01_stdout_request_audit_migration.md`
- `spec/issues/epic_32/issue_32_02_durable_audit_removal.md`
- `spec/issues/issue_01_logging.md`
- `spec/issues/issue_17_request_tracing_stdout_otlp.md`
- `spec/epics/epic_22_durable_minimum_audit_trail.md`
- `spec/epics/epic_32_best_effort_stdout_audit.md`

## Destination и current consumers

Permanent destinations:

- `spec/requirements/observability.md`

Current documents для синхронизации:

- `docs/observability.md`
- `docs/architecture.md`
- `docs/deployment.md`
- `docs/configuration.md`
- `docs/development.md`
- `docs/README.md`
- `docs/requirements-coverage.md`
- `README.md`
- `spec/MVP_FUNCTIONS.md`
- `spec/MVP_NON_FUNCTIONAL_REQUIREMENTS.md`
- `spec/OUT_OF_SCOPE_FUNCTIONS.md`

Также обновить requirements index, свой registry/checklist и **все входящие
ссылки на удаляемый batch** во всём tracked root/spec/docs. Другие epics/issues
можно менять только для affected references или перенесённых shared clauses.
Нельзя оставить broken links ради соблюдения списка основных consumers.
Source consumers для фактической сверки: ShadowAuditLogger, current Logback configuration, tracing/metrics services, identity telemetry и existing logging/HTTP/process observation tests; runtime source inspection confirms removed durable subsystem.

## Обязательные contract cases

1. Existing JSONL stdout pipeline и bounded non-blocking AsyncAppender/neverBlock behavior: no own persistence, queue/worker/exporter/retry/Collector handoff; logging failure не меняет traffic/readiness/startup/handoff.

2. REQUEST и ordinary/SSE RESPONSE analysis_started/completed: точный trigger, phase/outcome/reaction, detector/policy references, aggregate counts/latency/correlation, отсутствие pair при не начавшемся analysis.

3. Privacy по каналам: body, PII values/spans, credentials, user/groups и raw exceptions запрещены; различия audit/client errors против operational tracing session/path/MDC сохраняются, blanket claim не заменяет channel matrix.

4. Session/trace/span/parent lineage, request/response INTERNAL и upstream CLIENT spans; External cold-miss initiating CLIENT, отсутствие новых spans на hit/join и original lineage при cancellation.

5. Current HTTP/inspection/External cache metric names, units, finite attributes/outcomes и lifecycle publication. Generic telemetry owner не перезаписывает identity section, уже перенесённый VIG-36-03.

6. Container/deployment владеет stdout retention/rotation/delivery; нет application durability acknowledgement, audit capacity/readiness gate или гарантии потери zero events.

Для каждого named case переносить полную уже согласованную матрицу source,
а не representative examples. Если current target ещё не подтверждён,
сохранить target и явно назвать gap в coverage. Не менять семантику code или
requirements, чтобы сделать документацию внешне согласованной.

## Особые границы

Удалить spec/MINIMUM_AUDIT_TRAIL_CONTRACT.md и docs/durability-qualification-2026-08-31.md вместе с входящими active links. EPIC-22 и VIG-21-01 являются obsolete durability/governance sources: их прежние obligations не переезжают в requirements. EPIC-21 остаётся для VIG-36-08, но ссылка на удалённый VIG-21-01 и старый audit scope убираются в этом leaf. Generated output и .papercuts.jsonl не чистить.

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

Новый telemetry sink/metric/alert/delivery subsystem, schema/timing change, повторное удаление runtime WAL code, пересчёт logging benchmark.

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
