# VIG-38: Risk-based scope lock для implementation-ready work items

- **ID:** `VIG-38`
- **Тип:** Issue
- **Статус:** Ready for implementation
- **Приоритет:** High
- **Зависит от:** нет
- **Выполненная предпосылка:** [completion protocol](../../CLAUDE.md#work-item-completion) и [current requirements ownership](../requirements/README.md).
- **Блокирует:** [VIG-39](issue_39_compact_task_context.md)
- **Оценка:** 1-2 инженерных дня
- **Уверенность:** High

## Результат

Архитектурно дорогая issue не получает статус `Ready for implementation`, пока
в одном коротком scope lock не зафиксированы минимальный продуктовый результат,
обязательные свойства, явные non-goals, отклонённые альтернативы и условие
пересмотра. Локальная issue с низким архитектурным риском сохраняет текущий
короткий путь без дополнительного документационного ритуала.

## Контекст и решение

Техническая полнота acceptance criteria не доказывает, что выбранный subsystem
нужен продукту. В истории проекта durable audit был полностью реализован и
квалифицирован, после чего заменён stdout-аудитом и удалён. Новый gate должен
останавливать такую реализацию до кода, не ослабляя TDD и verification после
принятого решения.

Scope lock является разделом owning issue или epic, а не отдельным historical
документом. Он фиксирует границу результата, но не предписывает имена классов,
внутренний layout или заранее не требуемые extension points.

## Классификация риска

Автор новой или существенно уточняемой issue явно выбирает один уровень:

- `Low` - изменение локально, не добавляет долгоживущий resource owner,
  обязательную конфигурацию, persistence, внешний контракт, deployment или
  packaging responsibility. Достаточна одна короткая причина классификации.
- `High` - присутствует хотя бы одна из перечисленных границ либо изменение
  связывает несколько runtime subsystems. До `Ready for implementation`
  требуется утверждённый scope lock.

Неоднозначная классификация считается `High`. Существующие frozen legacy work
items не мигрируются только ради нового формата; правило применяется к новым и
содержательно пересматриваемым issues после завершения VIG-36.

## Обязательный scope lock для High risk

Один раздел содержит ровно следующие смысловые части:

1. Наблюдаемый продуктовый результат.
2. Минимальное достаточное решение.
3. Обязательные свойства результата.
4. Явные non-goals.
5. Рассмотренные более сложные альтернативы и причина отказа от каждой.
6. Условие пересмотра решения.
7. Явное подтверждение владельца решения или ссылка на согласованный источник.

Если жизнеспособность механизма неизвестна, scope lock разрешает отдельный
ограниченный tracer bullet с одним вопросом и наблюдаемым выходом. Его результат
не становится production implementation автоматически.

## Изменения

- Дополнить правила readiness и размера issue в `spec/WORK_ITEMS.md`.
- Добавить один канонический компактный шаблон issue под `spec/`, включающий
  risk classification, conditional scope lock, точные context sources и
  optional papercut tags для последующей VIG-39.
- Обновить только те текущие authoring examples или проверки ссылок, которые
  должны демонстрировать новый формат.
- Не добавлять автоматическую семантическую оценку риска в validator: решение
  остаётся явной ответственностью автора и reviewer.

## Критерии готовности

- [ ] Канонические правила однозначно разделяют `Low` и `High`; каждый признак
  риска выше имеет явную классификацию, а неоднозначный случай fail-closed
  направляется в `High`.
- [ ] `High` issue без утверждённого scope lock не может документально считаться
  `Ready for implementation`; `Draft` может содержать незакрытый lock.
- [ ] `Low` issue требует только уровня и одной причины, без пустых разделов про
  альтернативы или формального approval.
- [ ] Шаблон содержит один normative owner для scope lock и context sources;
  epic, issue, `CLAUDE.md` и runtime docs не должны копировать один контракт.
- [ ] На двух независимых примерах проверено authoring behavior: локальное
  исправление проходит короткий `Low` path, а persistence/public-contract
  изменение остаётся `Draft` до полного `High` scope lock.
- [ ] `rtk proxy ./gradlew validateWorkItems` и `rtk proxy git diff --check`
  завершаются успешно. Production source, runtime behavior и build gates не
  меняются.

## Не входит

- Автоматическое архитектурное проектирование или выбор решения за пользователя.
- Новый универсальный approval service, база решений или отдельный workflow engine.
- Обязательный scope lock для всех локальных bugs, refactoring и docs-only задач.
- Изменение TDD, semantic review, security или final verification gates.
- Переписывание global user skills вне репозитория.

## Ambiguity Report

```text
Goals:        0.0   наблюдаемый readiness gate задан
Acceptance:   0.1   Low/High cases и проверки конечны
Boundaries:   0.0   authoring contract без production behavior
Alternatives: 0.1   отдельный документ и автоматический risk inference отклонены
Assumptions:  0.1   VIG-36 завершает текущую migration до нового правила
Aggregate:    0.06  Ready for implementation.
```
