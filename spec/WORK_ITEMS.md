# Vigilant: epics и issues

Этот каталог хранит продуктовые требования и исполняемые work items проекта.
Декомпозиция следует подходу из видео
[«The Pet: Таинственный MM»](https://www.youtube.com/watch?v=YfE5v11-rYM):
работа представляется как mind map, где epic является корнем, крупные
capabilities образуют ветви, а отдельные проверяемые issues являются листьями.

## Размещение

```text
spec/
  epics/
    epic_NN_name.md
  issues/
    issue_NN_name.md
    epic_NN/
      issue_NN_MM_name.md
```

- `spec/epics/` содержит цель, полный нормативный scope, карту ветвей,
  итоговые критерии и сводный checklist.
- `spec/issues/issue_*.md` содержит самостоятельные issues, не являющиеся
  частью epic.
- `spec/issues/epic_NN/` содержит небольшие исполняемые issues одного epic.
- Файл не перемещается после завершения. Это сохраняет стабильные ссылки и
  историю. Меняются только статус и checklist родительского epic.

## Идентификаторы и связи

- Epic имеет ID `EPIC-NN`.
- Дочерняя issue имеет ID `VIG-NN-MM`.
- Самостоятельная issue сохраняет ID `VIG-NN` или `VIG-NNA`.
- Каждая дочерняя issue содержит ссылки `Epic`, `Зависит от` и при
  необходимости `Блокирует`.
- У одной issue ровно один родительский epic. Между разными epics разрешены
  явные зависимости.
- Epic перечисляет дочерние issues в порядке зависимостей, но не копирует их
  подробные acceptance criteria.

Статус в заголовке дочерней issue является источником истины. Checklist epic
является сводным представлением и обновляется в том же change set.

## Статусы

Допустимы пять значений:

| Статус | Значение |
|---|---|
| `Draft` | Существенные решения или acceptance criteria ещё не закрыты |
| `Ready for implementation` | Issue однозначна, зависимости могут быть ещё не завершены |
| `In progress` | Реализация начата |
| `Blocked` | Продолжение невозможно; рядом указаны причина и блокирующая issue |
| `Done` | Acceptance criteria выполнены и обязательные проверки прошли |

`Blocked` используется только для реального внешнего или dependency blocker,
а не вместо `Draft`.

Epic получает статус:

- `Draft`, пока его границы или обязательные дочерние issues не определены;
- `Ready for implementation`, когда дерево достаточно полное и хотя бы одна
  issue готова к реализации;
- `In progress`, когда начата или завершена хотя бы одна дочерняя issue;
- `Blocked`, когда заблокирован критический путь epic;
- `Done`, только когда все обязательные issues имеют статус `Done` и проверены
  собственные критерии готовности epic.

В checklist epic `[x]` означает только `Done`. Для `Draft`, `Ready for
implementation`, `In progress` и `Blocked` используется `[ ]` с явным текстом
статуса рядом.

## Правило размера issue

Issue является листом mind map, если:

1. даёт один наблюдаемый результат;
2. имеет один основной тестовый seam;
3. может быть реализована независимо после завершения перечисленных
   зависимостей;
4. содержит явные non-goals;
5. обычно занимает от одного до пяти инженерных дней.

Если оценка превышает пять дней или в названии естественно появляется союз
«и» между независимыми результатами, issue нужно раскрыть ещё на один уровень.

## Реестр

| Work item | Статус | Прогресс | Оценка |
|---|---|---:|---:|
| [VIG-01: Асинхронные JSON-логи](issues/issue_01_logging.md) | `Done` | завершена | 0 дней осталось |
| [VIG-01A: Benchmark логирования](issues/issue_01A_benchmark.md) | `Ready for implementation` | не начата | 4-7 дней |
| [EPIC-02: Быстрый PII-detector](epics/epic_02_fast_pii_detector.md) | `Done` | 16/16 | 0 дней осталось |
| [EPIC-03: Policy context extraction](epics/epic_03_policy_context_extraction.md) | `Draft` | 0/6 | 17-27 дней после решений |
| [EPIC-04: Policy engine](epics/epic_04_policy_engine.md) | `In progress` | 2/11 | 33-47 дней |
| [EPIC-05: v0 hardening](epics/epic_05_v0_hardening.md) | `Done` | 9/9 | 0 дней осталось |
| [EPIC-06: Разбор LLM-сообщений и извлечение payload](epics/epic_06_llm_message_parsing.md) | `Draft` | 0/1 | после закрытия протокольного контракта |
| [EPIC-07: Windowed payload processing](epics/epic_07_windowed_payload_processing.md) | `Draft` | 0/1 | после закрытия windowing-контракта |
| [EPIC-08: Lossless message spooling and replay](epics/epic_08_message_spooling_replay.md) | `Draft` | 0/1 | после закрытия spool-контракта |
| [EPIC-09: Закрытие архитектурных рисков v0](epics/epic_09_v0_architecture_closure.md) | `Ready for implementation` | 0/8 | 19-29 дней |
| [EPIC-10: Повышение качества детерминированного PII-распознавания](epics/epic_10_pii_detection_quality.md) | `Ready for implementation` | 0/8 | 21-29 дней |

## Как закрывать work item

При завершении дочерней issue в одном change set:

1. изменить её статус на `Done`;
2. отметить `[x]` соответствующую строку в epic;
3. обновить счётчик прогресса в этом реестре;
4. если закрыта последняя обязательная issue, проверить критерии готовности epic
   и только затем изменить статус epic на `Done`.

Статус не выводится автоматически из наличия кода: `Done` означает, что
пройдены именно проверки, перечисленные в issue и epic.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ структура и назначение work items ясны
  Acceptance:   0.25  ✓ completion protocol задан явно
  Boundaries:   0.0   ✓ epic и issue responsibilities разделены
  Alternatives: 0.25  ✓ flat list отклонён в пользу hierarchy
  Assumptions:  0.25  ✓ ручная синхронизация статусов зафиксирована
  ──────────────────────────────
  Aggregate:    0.15  ✓ below threshold (0.2 spec)

Push lightly on: automation of registry counters after workflow stabilizes.
```
