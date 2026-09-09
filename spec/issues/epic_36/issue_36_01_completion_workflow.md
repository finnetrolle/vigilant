# VIG-36-01: Закрытие задач с переносом требований

**Статус:** Ready for implementation
**Epic:** [EPIC-36](../../epics/epic_36_current_requirements_and_work_item_lifecycle.md)
**Ветка:** expand для VIG-36-02..08
**Зависит от:** нет
**Блокирует:** [VIG-36-02](issue_36_02_pii_windowing_contracts.md), [VIG-36-03](issue_36_03_identity_context_contract.md), [VIG-36-04](issue_36_04_protocol_error_contracts.md), [VIG-36-05](issue_36_05_request_enforcement_contracts.md), [VIG-36-07](issue_36_07_observability_contract.md)
**Связанные требования:** Work-item consistency; ownership всех 55 current requirement IDs
**Оценка:** 1-2 инженерных дня
**Уверенность:** High

## Результат

Project guide, registry convention и existing validation поддерживают
completion с переносом требований и удалением выполненной работы. Это
исполняемая основа для VIG-36-02..08, без переноса чужих domain contracts.

## Public seam и evidence contract

Основной seam: `WorkItemValidator.validate(Path)` на temporary repository
fixtures и existing `workItemValidatorTest` / `validateWorkItems` tasks.
Проверка documentation references подключается к существующему work-item
verification path, без нового standalone CLI/framework/dependency.

- Stimulus: minimal valid repository fixture, затем ровно одна завершённая
  migration или одна намеренная ошибка ссылки/dependency/ownership.
- Observable result: valid migrated catalog принимается; invalid catalog
  возвращает deterministic diagnostics с file/location и причиной.
- Independent oracle: fixture files, expected remaining IDs/links и заранее
  заданные diagnostics; не повторять production parser calculation в oracle.
- Actual repository после изменения проходит тот же публичный seam.

Перед изменением validator behavior добавить focused fixture test и получить
ожидаемый behavioral RED; затем минимальное GREEN. Прежние проверки нельзя
ослабить ради прохождения миграции. При уже поддержанном case достаточно
зафиксировать characterization GREEN без искусственного изменения кода.

## Scope файлов

- `CLAUDE.md`, `AGENTS.md`, `spec/WORK_ITEMS.md`: заменить прежний запрет
  удаления completed work items согласованным completion protocol.
- `docs/development.md`: описать воспроизводимую процедуру и проверки.
- `spec/requirements/README.md`: создать индекс agreed owners. Ссылки на
  detailed files появляются только вместе с содержательным переносом; до
  этого индекс ссылается на existing top-level current requirements и runtime
  references, не создаёт пустых contracts или duplicate specification.
- `src/workItemValidator/java/io/vigilant/spec/` и existing tests:
  минимальные изменения graph/reference validation под fixtures ниже.
- `RoadmapFrontierContractTest`: заменить brittle assertions о давно закрытой
  VIG-32-02/historical headings проверкой актуального navigation contract.
  Не закреплять текущий ID VIG-36-01 как вечный frontier.

Текущий Java validator проверяет registry membership/status, epic
checklist/backlinks и unchecked acceptance у Done issues. Он пока не читает
все `Зависит от` edges и не проверяет все local Markdown anchors; эти
пробелы нельзя объявлять уже закрытыми.

## Completion contract

1. До удаления получить все обязательные implementation/verification evidence
   из issue и соответствующие epic criteria. Availability source в Git
   обеспечивается до удаления: нельзя терять единственный uncommitted текст
   согласованной задачи; архивных копий в рабочей ветке не создавать.
2. Перенести current requirements из epic/issue к постоянным owners, relevant
   implementation details в runtime docs, applicable observations в evidence;
   обновить coverage. Obsolete/planning/history не переносить как requirements.
3. Выполненные prerequisite edges заменить requirement/capability references.
   На незавершённые work items сохранять формальные hard dependencies.
   Missing file не является признаком Done; self/cyclic/dangling edges ошибочны.
4. Удалить issue, её registry/checklist row и все входящие references или
   перенаправить их к постоянному owner. Незавершённый parent сохраняет
   remaining scope/children. Полностью завершённый parent тоже удалить.
5. Проверить resulting catalog и ссылки после terminal удаления, а не только
   до него. Нельзя ослаблять checks ради уже удалённого источника.
6. ID не переиспользуются: выделение нового ID учитывает active catalog и Git
   history, а не только максимальный номер оставшегося файла.
7. Во время миграции legacy Done files из frozen EPIC-36 batches ещё могут
   присутствовать до своей owning issue. Это не новый archival mode: leaf
   закрывает свой batch атомарно, а VIG-36-08 проверяет отсутствие всего набора.
   Новые completed leaves EPIC-36 проходят новый protocol без исключений.

Registry остаётся каталогом открытой работы. Формат progress `done/total`
может сохраниться как счётчик файлов текущего checklist, без claims об
историческом проценте: удалённые children в total не входят. Epic с remaining
future scope, но без executable children, имеет Draft с явной причиной.
Совершенно пустые каталоги после удаления поддерживаются корректно.

## Конечная fixture matrix

| Case | Stimulus | Expected observable result |
|---|---|---|
| C1 standalone completion | Contract опубликован; Done issue и её row удалены; references перенаправлены | Valid catalog; required owner доступен |
| C2 child completion | Удалён один child/row, parent и ещё один child остаются | Valid membership/backlinks/progress; remaining child не потерян |
| C3 last child / epic completion | Удалены last child, completed parent, registry entries; requirements остаются | Valid catalog без historical records |
| C4 open prerequisites | Valid edge между двумя active issues, затем dangling, self и cycle отдельными fixtures | Valid edge проходит; каждый invalid case даёт deterministic error |
| C5 satisfied prerequisite | Удалён выполненный blocker, вместо hard edge ссылка на published requirement | Valid reference; потерянная prerequisite ссылка не считается выполнением |
| C6 empty catalogs | По отдельности: no epics, no issues, ни одного work item, отсутствующие пустые directories | Empty catalog допустим; referenced missing item остаётся error |
| C7 local paths | Relative file, directory, root-relative path, image, reference-style Markdown link | Existing target проходит; missing target выдаёт location/reason |
| C8 anchors | Same-file и cross-file headings, UTF-8/percent-encoded fragment, repeated-heading suffix | Existing exact anchor проходит; missing anchor выдаёт error |
| C9 non-local/literals | External HTTP/mail links, fenced/inline code examples | Не выполняются network checks; code literals не являются links |
| C10 post-removal references | Оставшийся current document ведёт к удалённой task | Error; перенаправление к existing owner даёт GREEN |
| C11 existing invariants | Duplicate IDs/rows, status drift, bad Epic backlink, checklist mismatch | Прежние regression fixtures остаются GREEN |

Local reference scope: root README/CLAUDE/AGENTS, tracked Markdown в `spec/`
и `docs/`, local UML `[[...]]` link targets. Heading anchors соответствуют
используемому GitHub-style Markdown: Unicode headings, punctuation removal,
spaces-to-hyphens, repeated-heading suffixes; explicit HTML IDs также targets.
Diagnostics перечисляются в stable file/location order. Network I/O, чтение
внешних secret files и generic documentation crawl не нужны.

## Критерии готовности

- [ ] Новый completion contract записан в canonical project guide и registry,
  включая fulfilled prerequisites, ID non-reuse, empty catalog и self-cleanup
  migration tasks; текущие TDD/KDoc/privacy/verification требования сохранены.
- [ ] Индекс permanent owners создан без broken links/empty contract files;
  current 55 top-level IDs и их документы не переименованы.
- [ ] C1..C11 имеют отдельное наблюдение через указанный public seam; invalid
  cases не подменены label-only параметрами. Expected RED/GREEN зафиксированы
  для действительно нового validator behavior.
- [ ] Actual root/spec/docs/UML reference sweep проходит; network checks
  не запускаются. Historical filenames внутри literal evidence не выдают
  false link errors, actual links на missing files отклоняются.
- [ ] Required KDoc/Javadoc обновлён у добавленных/изменённых Java/Kotlin
  declarations, test methods и fixture helpers.
- [ ] `rtk proxy ./gradlew workItemValidatorTest validateWorkItems` и
  `rtk proxy git diff --check` проходят; одна Gradle invocation обязательно
  завершена перед следующей.
- [ ] Эта issue закрыта новым protocol; current guide/requirements/evidence,
  а не её файл, становятся fulfilled prerequisite для следующих leaves.

## Проверки

Focused RED/GREEN выбирается через:
`rtk proxy ./gradlew workItemValidatorTest --tests 'io.vigilant.spec.WorkItemValidatorTest'`
или добавленную focused class для local references в том же source set.
Final tooling GREEN:
`rtk proxy ./gradlew workItemValidatorTest validateWorkItems`.
Полный production build/OCI/load run не требуется: gateway code не меняется.

## Не входит

- Миграция 121 old work-item files, подробных product contracts или removal
  historical reports других leaves.
- Изменение runtime/config/build task semantics, новый CLI, tracker, plugin,
  Markdown framework или дополнительный постоянный retirement registry.
- Удаление старых regression checks вместо их адаптации к новому contract.

## Ambiguity Report

```text
Goals:        0.0   independently reviewable migration outcome explicit
Acceptance:   0.25  finite cases and public evidence; semantic review required
Boundaries:   0.0   current-only transfer, no new runtime behavior
Alternatives: 0.0   lifecycle and permanent owners agreed
Assumptions:  0.0   existing validator/test seam inspected
Aggregate:    0.05  Ready for implementation.
```
