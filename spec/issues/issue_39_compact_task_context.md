# VIG-39: Компактный task packet и маршрутизация agent context

- **ID:** `VIG-39`
- **Тип:** Issue
- **Статус:** Ready for implementation
- **Приоритет:** High
- **Зависит от:** [VIG-38](issue_38_risk_based_scope_lock.md)
- **Блокирует:** [VIG-40](issue_40_reusable_verification_evidence.md)
- **Оценка:** 3-5 инженерных дней
- **Уверенность:** Medium

## Результат

Одна локальная команда по ID активной issue выдаёт компактный детерминированный
task packet, достаточный для начала работы без чтения всего реестра, полного
papercuts journal и несвязанных разделов project guide. Project instructions
хранят общие инварианты и маршрутизацию, а подробные TDD, verification и delivery
правила имеют по одному каноническому владельцу.

## Публичный контракт

Основной seam:

```bash
./scripts/task-context VIG-NN
```

Успешный stdout является компактным machine-readable TOON/text packet и содержит:

- ID, status, path, dependencies и blockers;
- полный результат/цель, scope lock или Low-risk reason, criteria и non-goals;
- содержимое только явно указанных repository context sections по точным
  `path#anchor`, без соседних разделов;
- validation commands, если они принадлежат issue;
- не более пяти papercuts, выбранных по явно указанным exact tags, с ID,
  симптомом, status и resolution note;
- список каждого включённого source, чтобы полноту можно было независимо
  проверить.

Команда возвращает non-zero и краткую безопасную диагностику для неизвестного
или неоднозначного ID, отсутствующего файла/anchor, malformed metadata и
нечитаемого papercuts journal. Она не угадывает похожий ID, requirement owner,
tag или anchor.

## Источники и владение

- Active work-item graph и status остаются в `spec/WORK_ITEMS.md` и issue files.
- Issue template из VIG-38 владеет явными context-source anchors и optional
  papercut tags.
- `CLAUDE.md` владеет только стабильными project invariants и project-specific
  overrides.
- Подробные команды и воспроизводимые процедуры остаются в
  `docs/development.md` или специализированном owning document.
- Установленные `tdd`, `verify-changes`, `two-axis-review`, `judge-changes` и
  `no-mistakes` не копируются в repository и не переписываются этой issue.

После миграции `AGENTS.md` должен только направлять агента к каноническому owner.
Нельзя переместить обязательное правило в optional task packet, если оно должно
применяться до определения ID задачи.

## Детерминированный выбор контекста

1. Issue определяется exact ID через активный каталог; duplicate или missing
   entry является ошибкой.
2. Собственные result/scope/criteria/non-goals включаются полностью, без
   генеративного пересказа.
3. External context включается только по exact repository-relative path и
   explicit Markdown anchor из issue.
4. Papercuts фильтруются exact пересечением tags, затем устойчиво сортируются;
   лимит применяется после сортировки. Без tags journal не читается в packet.
5. Никакой source не обрезается молча. Недопустимый или слишком широкий source
   исправляется в issue, а не скрывается эвристическим summarization.

## Изменения

- Добавить `scripts/task-context` и изолированные tests под `scripts/tests/`.
- Дополнить authoring contract точными context-source anchors и papercut tags.
- Сократить дублирование между `AGENTS.md`, `CLAUDE.md` и
  `docs/development.md`, сохранив один нормативный экземпляр каждого правила.
- Заменить рекомендацию читать все papercuts на exact-tag retrieval с явным
  fallback к полному journal только при неизвестном симптоме.
- Добавить воспроизводимый отчёт baseline для 3-5 активных или восстановленных
  из Git representative issues: bytes/words исходного обязательного контекста,
  packet output и перечень вручную подтверждённых потерянных обязательств.
  Численный release threshold не задаётся до baseline.

## Критерии готовности

- [ ] Exact ID возвращает стабильный packet с полным собственным контрактом и
  только объявленными external sections; повторный запуск на неизменных inputs
  byte-for-byte идентичен.
- [ ] Missing ID, duplicate ID, missing anchor, malformed context declaration и
  malformed papercuts journal имеют отдельные non-zero cases и не печатают
  частичный packet как успех.
- [ ] Exact-tag выборка papercuts покрывает open и resolved entries, deterministic
  ordering, лимит five и отсутствие чтения journal при пустом наборе tags.
- [ ] Fixture-oracle строится из независимых expected literals, а не вызывает
  production extractor для вычисления ожидаемого packet.
- [ ] После сокращения guides каждое удалённое правило либо имеет один
  канонический destination, либо доказано устаревшим; mandatory startup rules не
  зависят от успешного запуска `task-context`.
- [ ] Baseline report различает input context, tool output и экономию; для всех
  sampled issues вручную подтверждено `lost obligations: 0`.
- [ ] Script tests, `rtk proxy ./gradlew validateWorkItems` и
  `rtk proxy git diff --check` GREEN. Production behavior не меняется.

## Не входит

- Embedding/RAG, vector database, LSP, Serena или AST index.
- Генеративное summarization нормативных требований.
- Автоматический выбор architecture, affected tests или verification verdict.
- Синхронизация и переписывание global user skills.
- Изменение product runtime, Gradle test topology или security gates.

## Ambiguity Report

```text
Goals:        0.0   command и наблюдаемый packet заданы
Acceptance:   0.15  success/error/determinism cases конечны
Boundaries:   0.05  repository context отделён от global skills
Alternatives: 0.1   embedding и generative summary явно отклонены
Assumptions:  0.2   baseline определит будущий численный target
Aggregate:    0.10  Ready for implementation.
```
