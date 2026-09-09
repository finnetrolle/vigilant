# VIG-36-05: Постоянные требования request enforcement

**Статус:** Ready for implementation
**Epic:** [EPIC-36](../../epics/epic_36_current_requirements_and_work_item_lifecycle.md)
**Ветка:** migrate по полному REQUEST contract
**Зависит от:** [VIG-36-01](issue_36_01_completion_workflow.md), [VIG-36-04](issue_36_04_protocol_error_contracts.md)
**Блокирует:** [VIG-36-06](issue_36_06_response_gateway_contracts.md), [VIG-36-08](issue_36_08_current_catalog_closure.md)
**Связанные требования:** `MVP-01`, `MVP-03`, `MVP-04`, `PERF-03`, `CONC-01..04`, `PROXY-02`
**Оценка:** 2-4 инженерных дня
**Уверенность:** Medium

## Результат

Current policy schema, bounded request source и полный REQUEST enforcement contract опубликованы в постоянных owners. Старые shadow-only startup/coverage правила не конкурируют с текущим контрактом.

## Обязательный контекст

До редактирования прочитать [EPIC-36](../../epics/epic_36_current_requirements_and_work_item_lifecycle.md), permanent owners, если они
уже опубликованы, current top-level requirements и **весь source batch ниже**.
Baseline - Git `ca8ec90`; полный текст текущего source сравнить с baseline,
чтобы не удалить новую незавершённую работу. Source titles и Done сами по себе
не доказывают актуальность содержащихся в них правил.

Полный request field map принадлежит VIG-36-04; здесь сохраняется exhaustive classification/behavior через ссылки на эти fields. Error matrix не дублируется.

## Source batch: 19 issues и 2 epics

Paths ниже относительно repository root. Каждая строка назначена этому leaf
как единственному removal owner; это конечный список, а не glob на все файлы
с подходящим именем. Current clauses из shared sources других leaves можно
читать, но их файлы здесь не удаляются.

- `spec/issues/epic_04/issue_04_01_domain_contracts.md`
- `spec/issues/epic_04/issue_04_02_config_parser.md`
- `spec/issues/epic_04/issue_04_03_policy_validation.md`
- `spec/issues/epic_04/issue_04_04_snapshot_provider.md`
- `spec/issues/epic_04/issue_04_05_matching_overrides.md`
- `spec/issues/epic_04/issue_04_06_detector_executor.md`
- `spec/issues/epic_04/issue_04_07_parallel_execution.md`
- `spec/issues/epic_04/issue_04_08_deadlines_cancellation.md`
- `spec/issues/epic_04/issue_04_09_fail_fast.md`
- `spec/issues/epic_04/issue_04_10_reaction_aggregation.md`
- `spec/issues/epic_04/issue_04_11_decision_observability.md`
- `spec/issues/epic_08/issue_08_01_spool_contract.md`
- `spec/issues/epic_08/issue_08_02_bounded_request_source.md`
- `spec/issues/issue_12_global_shadow_coverage_validation.md`
- `spec/issues/issue_13_pii_shadow_request_tracer.md`
- `spec/issues/issue_15_capacity_cancellation_outcomes.md`
- `spec/issues/issue_16_packaged_shadow_proxy_evidence.md`
- `spec/issues/issue_19_typed_shadow_inspection_workflow.md`
- `spec/issues/issue_34_request_pii_enforcement.md`
- `spec/epics/epic_04_policy_engine.md`
- `spec/epics/epic_08_message_spooling_replay.md`

## Destination и current consumers

Permanent destinations:

- `spec/requirements/policy-engine.md`
- `spec/requirements/request-source.md`
- `spec/requirements/request-enforcement.md`

Current documents для синхронизации:

- `docs/policies.md`
- `docs/openai-chat-completions.md`
- `docs/runtime-contract.md`
- `docs/configuration.md`
- `docs/architecture.md`
- `docs/requirements-coverage.md`
- `README.md`
- `spec/MVP_FUNCTIONS.md`
- `spec/MVP_NON_FUNCTIONAL_REQUIREMENTS.md`

Также обновить requirements index, свой registry/checklist и **все входящие
ссылки на удаляемый batch** во всём tracked root/spec/docs. Другие epics/issues
можно менять только для affected references или перенесённых shared clauses.
Нельзя оставить broken links ради соблюдения списка основных consumers.
Source consumers для фактической сверки: PolicyConfiguration/PolicyValidator/PolicySelector/PolicyEngine, RequestSourceQuota/BoundedRequestSourceOwner, ShadowInspectionWorkflow, RequestRewritePlanner, RequestMaskingFormatter и current source/HTTP/process tests.

## Обязательные contract cases

1. Полная startup schema: id/version/enabled/match/detectors/deadline/reactions/overrides; strict parsing/validation, immutable snapshot, URL/model/phase/USER/GROUP/wildcard matching, simultaneous overrides и canonical ordering.

2. Selection cases: empty snapshot, disabled-only, unmatched, selected policy, overrides. policies file обязателен, implicit global coverage отсутствует; без applied policy detector/audit не запускаются, identity/protocol/source gates сохраняются.

3. REQUEST detected ALLOW/MASK/BLOCK, clean=ALLOW и error=BLOCK без transformations, в том числе validation disabled policies; technical error/deadline выше policy BLOCK/structural MASK. Domain engine и RESPONSE не получают новых ограничений из REQUEST-specific rule.

4. Полный recognized field classification VIG-34: каждый free-text и structural field, modern/deprecated forms; structural MASK блокирует весь request, structural ALLOW finding не блокирует. Не заменить exhaustive field list несколькими примерами.

5. Marker budget: полный marker, shortened bracketed marker, 1-byte '*', 2-byte '**', UTF-8 boundaries, полное удаление matched PII и non-expanding decoded/raw-source patches. Все source validation и overlap/order constraints сохраняются.

6. Source lifecycle: owner/byte/segment quotas, ingest/backpressure, read lease, original/patched one-shot replay, invalid interleavings, success/reject/failure/timeout/cancellation/close/shutdown и release на фактическом terminal point.

7. Полный forward/reject/handoff contract, stable errors по VIG-36-04, audit timing/privacy по current stdout owner. REQUEST и RESPONSE representations не смешиваются.

Для каждого named case переносить полную уже согласованную матрицу source,
а не representative examples. Если current target ещё не подтверждён,
сохранить target и явно назвать gap в coverage. Не менять семантику code или
requirements, чтобы сделать документацию внешне согласованной.

## Особые границы

VIG-12 целиком проверяется как заменённый startup contract; его старые ALLOW/error/global-coverage правила не переносятся. VIG-13/15/16/19 используются только для сохранившихся source/workflow/packaging obligations. Preserve numeric target/runtime gap: CONC-01 target 16 MiB text/20 MiB raw и configurable 8 MiB default не исправляются подменой чисел. Current policy API capabilities не расширяются/не урезаются ради удаления старого prose. Применимые VIG-34 observations остаются в docs/request-enforcement-evidence.md; ссылки на её удаляемые task owners перенаправляются на requirements, без копирования issue в report.

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

Изменение code, policy schema/defaults, reaction semantics, source limits, marker algorithm, new resource qualification или OCI/process runs.

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
