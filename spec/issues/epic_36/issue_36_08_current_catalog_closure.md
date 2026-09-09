# VIG-36-08: Завершение миграции каталога требований

**Статус:** Ready for implementation
**Epic:** [EPIC-36](../../epics/epic_36_current_requirements_and_work_item_lifecycle.md)
**Ветка:** contract после VIG-36-02..07
**Зависит от:** [VIG-36-02](issue_36_02_pii_windowing_contracts.md), [VIG-36-03](issue_36_03_identity_context_contract.md), [VIG-36-04](issue_36_04_protocol_error_contracts.md), [VIG-36-05](issue_36_05_request_enforcement_contracts.md), [VIG-36-06](issue_36_06_response_gateway_contracts.md), [VIG-36-07](issue_36_07_observability_contract.md)
**Блокирует:** нет
**Связанные требования:** `PERF-01`, `PERF-02`; completeness всех 55 current IDs и verification workflow
**Оценка:** 2-3 инженерных дня
**Уверенность:** Medium

## Результат

Все 121 baseline completed files удалены после переноса contracts, current verification описана без исторических guarantees, registry/roadmap показывают только оставшуюся работу.

## Обязательный контекст

До редактирования прочитать [EPIC-36](../../epics/epic_36_current_requirements_and_work_item_lifecycle.md), permanent owners, если они
уже опубликованы, current top-level requirements и **весь source batch ниже**.
Baseline - Git `ca8ec90`; полный текст текущего source сравнить с baseline,
чтобы не удалить новую незавершённую работу. Source titles и Done сами по себе
не доказывают актуальность содержащихся в них правил.

Source EPIC-21 удаляется только здесь: audit subsection/child VIG-21-01 уже обработаны VIG-36-07. Никакие незавершённые задачи не удаляются ради пустого backlog.

## Source batch: 11 issues и 2 epics baseline; затем VIG-36-08 и EPIC-36 как completed migration work

Paths ниже относительно repository root. Каждая строка назначена этому leaf
как единственному removal owner; это конечный список, а не glob на все файлы
с подходящим именем. Current clauses из shared sources других leaves можно
читать, но их файлы здесь не удаляются.

- `spec/issues/epic_21/issue_21_02_adversarial_inspection_resource_qualification.md`
- `spec/issues/epic_21/issue_21_03_upstream_error_test_determinism.md`
- `spec/issues/epic_21/issue_21_04_streaming_evidence_determinism.md`
- `spec/issues/epic_21/issue_21_05_roadmap_frontier_reconciliation.md`
- `spec/issues/epic_37/issue_37_01_test_timing_report.md`
- `spec/issues/epic_37/issue_37_02_gateway_e2e_split.md`
- `spec/issues/epic_37/issue_37_03_process_test_isolation.md`
- `spec/issues/epic_37/issue_37_04_four_worker_qualification.md`
- `spec/issues/epic_37/issue_37_05_health_endpoints_determinism.md`
- `spec/issues/issue_01A_benchmark.md`
- `spec/issues/issue_18_inspection_load_report.md`
- `spec/epics/epic_21_post_milestone_architecture_closure.md`
- `spec/epics/epic_37_predictable_test_throughput.md`

## Destination и current consumers

Permanent destinations:

- `docs/development.md`
- `docs/requirements-coverage.md`
- `spec/WORK_ITEMS.md`
- `spec/ROADMAP.md`
- `spec/requirements/README.md`

Current documents для синхронизации:

- `docs/README.md`
- `README.md`
- `CLAUDE.md`
- `AGENTS.md`
- `docs/request-enforcement-evidence.md`
- `docs/perf-01-load-test.md`
- `spec/issues/issue_33_availability_slo_and_operations.md`
- `spec/epics/epic_06_llm_message_parsing.md`

Также обновить requirements index, свой registry/checklist и **все входящие
ссылки на удаляемый batch** во всём tracked root/spec/docs. Другие epics/issues
можно менять только для affected references или перенесённых shared clauses.
Нельзя оставить broken links ради соблюдения списка основных consumers.
Source consumers для фактической сверки: Actual tracked Markdown/UML catalog, migrated requirement owners, current Gradle/tasks/process fixture definitions и existing work-item/document checks; baseline Git source remains independent completeness oracle.

## Обязательные contract cases

1. EPIC-37: current serial process lane, four non-process workers, timing-report/qualification methodology, deterministic startup/shutdown observations и required fixture settings описаны в development docs. Historical counts/percent improvements не объявляются current measurements.

2. VIG-01A/18 и remaining EPIC-21: сохранить действующие verification methods/causal observations, исключить заменённые audit milestones и historical load guarantees. PERF-01/02 enforcement profile остаётся неподтверждённым без нового evidence.

3. Удалить docs/architecture-review-2026-08-23.md, docs/architecture-review-2026-08-29.md, docs/inspection-resource-qualification-2026-08-30.md, docs/inspection-load-result.md, docs/perf-01-result.md. Старый 8 MiB resource report не является qualification нового request enforcement; сохранившиеся causal test obligations перенести, а новый safety claim не создавать.

4. docs/request-enforcement-evidence.md: сохранить current observations/commands/results, убрать obsolete task/history narrative и references на удалённые files; не менять измеренные числа/исходы и не копировать full VIG-34 issue как заменяющий архив.

5. Проверить каждый из 55 current IDs отдельно: уникальный top-level owner/anchor, detailed owner либо explicit future/non-goal, current implementation/evidence status и remaining gap. У ST1/OUT нет автоматического 'implemented' из существования похожего internal API.

6. Ни одного baseline Done issue/epic, dead local link/anchor, current requirement reference на removed task, shadow-only/global-coverage/durable/bounded-response claim как current contract. Разрешённые совпадения negative boundaries и actual production symbol names перечислить в temporary sweep evidence.

7. VIG-33 сохраняет Draft и открытые SLI/SLO decisions; удалить audit-file claim. EPIC-06 сохраняет только ещё не реализованный future scope, без автоматического Done/новых protocol leaves.

8. Проверить union manifests VIG-36-02..08 из согласованной версии в Git: 108 issues + 13 epics, disjoint ownership и полное отсутствие после migration. Завершённые migration leaves к этому моменту уже удалены из working tree. Закрыть VIG-36-08 и EPIC-36 по новому protocol, удалив их planning records и remaining links; roadmap указывает на реальный frontier.

Для каждого named case переносить полную уже согласованную матрицу source,
а не representative examples. Если current target ещё не подтверждён,
сохранить target и явно назвать gap в coverage. Не менять семантику code или
requirements, чтобы сделать документацию внешне согласованной.

## Особые границы

Этот leaf завершает evidence applicability: старые reviews/load/resource results удаляются, current methods и реально применимые VIG-34 observations сохраняются. docs/perf-01-load-test.md остаётся описанием существующего benchmark с явной границей к current enforcement SLO; tasks не удаляются. Не создавать archive index, retirement registry или постоянную source-to-destination historical таблицу. Before final deletion краткое review evidence хранится вне final working tree; history доступна в Git.

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

Новый benchmark/OCI/process qualification, выбор availability SLO, реализация Responses или resource targets, изменения runtime/build task behavior, удаление generated reports/CHANGELOG/journal.

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
