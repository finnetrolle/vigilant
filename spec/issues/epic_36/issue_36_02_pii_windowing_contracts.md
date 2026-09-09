# VIG-36-02: Постоянные требования PII и windowing

**Статус:** Ready for implementation
**Epic:** [EPIC-36](../../epics/epic_36_current_requirements_and_work_item_lifecycle.md)
**Ветка:** migrate по detector/windowing contract
**Зависит от:** [VIG-36-01](issue_36_01_completion_workflow.md)
**Блокирует:** [VIG-36-08](issue_36_08_current_catalog_closure.md)
**Связанные требования:** `MVP-02`, `CONC-01`, `CONC-03`, `CONC-04`, `OBS-02`
**Оценка:** 2-3 инженерных дня
**Уверенность:** Medium

## Результат

Recognition и generic windowing имеют постоянные contracts; для понимания текущей PII-проверки больше не нужны EPIC-02/07/10, VIG-11 или VIG-26.

## Обязательный контекст

До редактирования прочитать [EPIC-36](../../epics/epic_36_current_requirements_and_work_item_lifecycle.md), permanent owners, если они
уже опубликованы, current top-level requirements и **весь source batch ниже**.
Baseline - Git `ca8ec90`; полный текст текущего source сравнить с baseline,
чтобы не удалить новую незавершённую работу. Source titles и Done сами по себе
не доказывают актуальность содержащихся в них правил.

Общие source quota/ingest правила принадлежат VIG-36-05; этот leaf описывает только detector/windowing contract.

## Source batch: 28 issues и 3 epics

Paths ниже относительно repository root. Каждая строка назначена этому leaf
как единственному removal owner; это конечный список, а не glob на все файлы
с подходящим именем. Current clauses из shared sources других leaves можно
читать, но их файлы здесь не удаляются.

- `spec/issues/epic_02/issue_02_01_public_contract.md`
- `spec/issues/epic_02/issue_02_02_payload_preflight.md`
- `spec/issues/epic_02/issue_02_03_recognizer_pipeline.md`
- `spec/issues/epic_02/issue_02_04_email_recognizer.md`
- `spec/issues/epic_02/issue_02_05_phone_recognizer.md`
- `spec/issues/epic_02/issue_02_06_payment_card_recognizer.md`
- `spec/issues/epic_02/issue_02_07_ip_address_recognizer.md`
- `spec/issues/epic_02/issue_02_08_iban_recognizer.md`
- `spec/issues/epic_02/issue_02_09_ru_inn_recognizer.md`
- `spec/issues/epic_02/issue_02_10_ru_snils_recognizer.md`
- `spec/issues/epic_02/issue_02_11_ru_passport_recognizer.md`
- `spec/issues/epic_02/issue_02_12_ru_oms_recognizer.md`
- `spec/issues/epic_02/issue_02_13_cross_recognizer_semantics.md`
- `spec/issues/epic_02/issue_02_14_quality_corpora.md`
- `spec/issues/epic_02/issue_02_15_jmh_baseline.md`
- `spec/issues/epic_02/issue_02_16_canonical_quality_corpora.md`
- `spec/issues/epic_07/issue_07_01_windowing_contract.md`
- `spec/issues/epic_07/issue_07_02_windowed_fast_pii_execution.md`
- `spec/issues/epic_10/issue_10_01_quality_diagnostics.md`
- `spec/issues/epic_10/issue_10_02_ip_candidate_boundaries.md`
- `spec/issues/epic_10/issue_10_03_product_aligned_report.md`
- `spec/issues/epic_10/issue_10_04_email_obfuscation.md`
- `spec/issues/epic_10/issue_10_05_phone_surfaces.md`
- `spec/issues/epic_10/issue_10_06_snils_contextual.md`
- `spec/issues/epic_10/issue_10_07_oms_contextual.md`
- `spec/issues/epic_10/issue_10_08_quality_qualification.md`
- `spec/issues/issue_11_fast_pii_policy_adapter.md`
- `spec/issues/issue_26_generic_windowing_core.md`
- `spec/epics/epic_02_fast_pii_detector.md`
- `spec/epics/epic_07_windowed_payload_processing.md`
- `spec/epics/epic_10_pii_detection_quality.md`

## Destination и current consumers

Permanent destinations:

- `spec/requirements/fast-pii.md`
- `spec/requirements/windowed-inspection.md`

Current documents для синхронизации:

- `docs/pii-detection.md`
- `docs/development.md`
- `docs/architecture.md`
- `docs/requirements-coverage.md`
- `spec/MVP_FUNCTIONS.md`
- `spec/MVP_NON_FUNCTIONAL_REQUIREMENTS.md`

Также обновить requirements index, свой registry/checklist и **все входящие
ссылки на удаляемый batch** во всём tracked root/spec/docs. Другие epics/issues
можно менять только для affected references или перенесённых shared clauses.
Нельзя оставить broken links ради соблюдения списка основных consumers.
Source consumers для фактической сверки: FastPiiDetector, WindowedInspectionExecutor, WindowedFastPiiExecutor, FastPiiPolicyAdapter и их current tests; canonical corpus/quality report definitions.

## Обязательные contract cases

1. Полный fixed taxonomy: EMAIL_ADDRESS, PHONE_NUMBER, PAYMENT_CARD, IP_ADDRESS, IBAN, RU_INN, RU_SNILS, RU_PASSPORT, RU_OMS; текущие recognized formats, candidate/context boundaries и validation rules для каждого type.

2. Decoded UTF-8 offsets, findings/provenance metadata, normalization, deterministic ordering, duplicates/overlaps, supported RU_INN scope, false-positive boundaries и privacy. enabledTypes/internal detector API не объявляется новым administrator policy selector.

3. Payload preflight и limits, invalid input, thread safety, cancellation; exact invariants общих source tests сохраняются без переноса прежних product SLO IDs как current.

4. Generic capability/overlap contract, UTF-8 window boundaries, global offset translation, deduplication/aggregation, stop-on-first и cancellation; semantic distinction domain core / Fast PII adapter сохраняется.

5. Canonical synthetic quality corpus и external benchmark получают разные назначения. Quality thresholds и обязательные corpus cases сохраняются, historical benchmark measurements не становятся новым enforcement PERF-01 evidence.

Для каждого named case переносить полную уже согласованную матрицу source,
а не representative examples. Если current target ещё не подтверждён,
сохранить target и явно назвать gap в coverage. Не менять семантику code или
requirements, чтобы сделать документацию внешне согласованной.

## Особые границы

EPIC-10 уточняет ранний EPIC-02: переносить действующие recognition rules с учётом обоих источников. Методики текущих quality/JMH tasks остаются в development docs; результаты прошлого прогона не копируются в normative contract. Не менять corpora, recognizers, counts или report calculations.

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

Изменение recognition algorithms, taxonomy, quality corpus, windowing behavior, performance targets или запуск нового JMH/quality qualification.

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
