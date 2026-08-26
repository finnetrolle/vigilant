# VIG-09-09: Project work-item validator

**Статус:** Done
**Epic:** [EPIC-09](../../epics/epic_09_v0_architecture_closure.md)
**Ветка:** Operations > deterministic work-item completion gate
**Зависит от:** нет
**Блокирует:** закрытие [EPIC-09](../../epics/epic_09_v0_architecture_closure.md)
**Оценка:** 1-2 инженерных дня
**Уверенность:** High

## Результат

Команда `./gradlew validateWorkItems` детерминированно проверяет, что registry,
epics и issues образуют согласованный work-item graph. Проверка входит в
`check`, поэтому завершённый work item нельзя оставить с рассинхронизированным
статусом, progress или checklist.

## Требования

Completion discipline из `spec/WORK_ITEMS.md`; последний completion gate
EPIC-09 и рекомендация Stage 0 из `spec/ROADMAP.md`.

## Критерии готовности

- [x] Validator обнаруживает все epic, standalone issue и epic-scoped issue,
  проверяет уникальность ID, допустимый статус и существование Markdown-файла.
- [x] Registry содержит ровно по одной записи для каждого epic и standalone
  issue; link, ID и статус совпадают с целевым work item.
- [x] Для каждого epic child checklist полностью совпадает с его issue-каталогом:
  link, ID, status label, `Done` checkbox и обратная ссылка `Epic` согласованы.
- [x] Epic progress в registry равен фактическому числу `Done` children и их
  общему числу; epic со статусом `Done` не содержит незавершённых children.
- [x] У issue со статусом `Done` нет незакрытых acceptance checkboxes.
- [x] Ошибки агрегируются в стабильном порядке, содержат путь и причину, а
  validator ничего не изменяет в `spec/`.
- [x] Fixture tests доказывают GREEN для согласованного graph и RED для status,
  progress, checklist и membership drift.
- [x] `validateWorkItems` подключён к `check`; focused tests и
  `./gradlew build` проходят.

## Test/demo seam

Public build seam `./gradlew validateWorkItems`; pure validator tests создают
минимальные Markdown graph fixtures во временном каталоге и проверяют bounded
diagnostics без обращения к production runtime.

## Не входит

Автоматическое исправление Markdown, генерация новых work items, semantic
оценка качества требований, проверка roadmap dependency graph и изменение
production code или gateway behavior.
