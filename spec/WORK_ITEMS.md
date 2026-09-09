# Vigilant: epics и issues

Этот реестр хранит открытую работу проекта. Действующие требования имеют
[постоянных владельцев](requirements/README.md); история реализации остаётся в Git.
Декомпозиция следует подходу из видео
[«The Pet: Таинственный MM»](https://www.youtube.com/watch?v=YfE5v11-rYM):
работа представляется как mind map, где epic является корнем, крупные
capabilities образуют ветви, а отдельные проверяемые issues являются листьями.

Cross-epic delivery path к первому промышленному guardrail increment описан в
[ROADMAP.md](ROADMAP.md). Roadmap связывает существующие и planned work items,
но не заменяет их статусы, hard dependencies или нормативный scope epics.

## Размещение

```text
spec/
  ROADMAP.md
  epics/
    epic_NN_name.md
  issues/
    issue_NN_name.md
    epic_NN/
      issue_NN_MM_name.md
```

- `spec/requirements/` содержит индекс и постоянные detailed contracts.
- `spec/epics/` содержит цель и границы незавершённого изменения, карту ветвей,
  итоговые критерии и сводный checklist.
- `spec/issues/issue_*.md` содержит самостоятельные issues, не являющиеся
  частью epic.
- `spec/issues/epic_NN/` содержит небольшие исполняемые issues одного epic.
- После выполнения обязательных проверок требования переносятся к постоянным
  owners, а completed issue и полностью completed epic удаляются по
  [completion protocol](../CLAUDE.md#work-item-completion). Архивные копии не создаются.

## Идентификаторы и связи

- Epic имеет ID `EPIC-NN`.
- ID никогда не переиспользуются: новый номер проверяется по active catalog
  и Git history, включая удалённые файлы и прежние title/ID metadata.
- Дочерняя issue имеет ID `VIG-NN-MM`.
- Самостоятельная issue сохраняет ID `VIG-NN` или `VIG-NNA`.
- Каждая дочерняя issue содержит ссылки `Epic`, `Зависит от` и при
  необходимости `Блокирует`.
- У одной issue ровно один родительский epic. Между разными epics разрешены
  явные зависимости на незавершённую работу. Выполненные предпосылки становятся
  ссылками на published requirement/capability; missing file не означает Done.
  Dangling, self и cyclic hard dependencies ошибочны.
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

- `Draft`, пока его границы или обязательные дочерние issues не определены,
  либо remaining future scope ещё не имеет executable children; причина явная;
- `Ready for implementation`, когда дерево достаточно полное и хотя бы одна
  issue готова к реализации;
- `In progress`, когда начата или завершена хотя бы одна дочерняя issue;
- `Blocked`, когда заблокирован критический путь epic;
- `Done`, только когда вся обязательная работа выполнена, её требования и
  evidence перенесены, а собственные критерии epic проверены; затем completed
  epic удаляется по completion protocol. Статус хранится только у legacy batches.

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
| [EPIC-06: OpenAI Responses protocol scope](epics/epic_06_llm_message_parsing.md) | `Draft` | 0/0 | Responses вне MVP; нет implementation-ready leaves |
| [VIG-33: Availability SLO and operational evidence](issues/issue_33_availability_slo_and_operations.md) | `Draft` | production SLO не определён | не оценено |
| [VIG-38: Risk-based scope lock для implementation-ready work items](issues/issue_38_risk_based_scope_lock.md) | `Ready for implementation` | не начата | 1-2 инженерных дня |
| [VIG-39: Компактный task packet и маршрутизация agent context](issues/issue_39_compact_task_context.md) | `Ready for implementation` | не начата | 3-5 инженерных дней |
| [VIG-40: Один snapshot и переиспользуемая verification evidence](issues/issue_40_reusable_verification_evidence.md) | `Ready for implementation` | не начата | 3-5 инженерных дней |

## Active TODO: порядок следующей работы

Этот список задаёт delivery order для агентов. Статус в issue-файле остаётся
источником истины: пункт ниже не разрешает реализацию `Draft` issue. Для Draft
разрешены только discovery, фиксация решений и декомпозиция до
`Ready for implementation`.

Принципы порядка:

1. Сначала удалять ненужный runtime и только затем добавлять новые возможности.
2. Внутри delivery phase сначала завершать доступные `Ready for implementation`
   и `In progress` work items, затем уточнять `Draft` work items.
3. Исключение допустимо только для минимальной декомпозиции, необходимой, чтобы
   превратить removal work или parent epic в independently executable Ready
   leaves.
4. Не начинать новый пункт, пока предыдущий hard gate не завершён и
   `./gradlew validateWorkItems` не подтверждает согласованность реестра.

### Phase 6: operations

- [ ] После стабилизации identity, enforcement и observability уточнить
  [VIG-33](issues/issue_33_availability_slo_and_operations.md).

### Phase 7: сократить стоимость agent workflow

- [ ] Реализовать [VIG-38](issues/issue_38_risk_based_scope_lock.md): добавить
  risk-based scope lock перед архитектурно дорогими implementation-ready issues,
  сохранив короткий путь для локальных изменений.
- [ ] После VIG-38 реализовать
  [VIG-39](issues/issue_39_compact_task_context.md): выдавать по exact issue ID
  компактный task packet и убрать дублирование обязательного agent context.
- [ ] После VIG-39 реализовать
  [VIG-40](issues/issue_40_reusable_verification_evidence.md): выполнять один
  дорогой mechanical gate на неизменный snapshot и передавать reusable evidence
  последующим verification consumers.

Текущий следующий шаг: реализовать
[VIG-38](issues/issue_38_risk_based_scope_lock.md),
`Ready for implementation`.
[Policy engine](requirements/policy-engine.md),
[request source](requirements/request-source.md) и
[REQUEST enforcement](requirements/request-enforcement.md) опубликованы.
[RESPONSE enforcement](requirements/response-enforcement.md) и
[HTTP gateway](requirements/http-gateway.md) опубликованы.
Применимые HTTP/process/OCI/validator/build observations находятся в
[closure ledger](../docs/request-enforcement-evidence.md).
[Identity/context](requirements/identity-and-context.md) и полный
[observability contract](requirements/observability.md) опубликованы;
[coverage](../docs/requirements-coverage.md#observability-evidence) сохраняет
conformance/evidence gaps без нового performance claim.
Текущая serial process lane, four-worker non-process topology и qualification
methodology описаны в [development guide](../docs/development.md#test-timing-report)
без переноса historical timings как current measurement.
Ordinary JSON и SSE response используют
[exact inspection matrix](requirements/http-gateway.md#inspection-error-matrix).

## Как закрывать work item

Canonical [completion protocol](../CLAUDE.md#work-item-completion) и
[воспроизводимая процедура](../docs/development.md#завершение-work-item)
применяются к standalone issues, дочерним issues и epics.

В одном consistency change получить все обязательные evidence, обеспечить
доступность согласованного source в Git, перенести current clauses из issue
и epic к permanent owners, обновить runtime docs/coverage и fulfilled
prerequisites. Затем удалить completed issue, её row/checklist entry и
входящие ссылки либо перенаправить их к owner. Полностью завершённый parent
удаляется; незавершённый сохраняет remaining scope и children.

`done/total` считает только файлы текущего checklist, а не исторический процент.
Удалённые children не входят в total. Epic без executable children, но с future
scope имеет `Draft` с явной причиной. Пустой реестр, отдельно пустые issues/epics
и отсутствующие пустые directories допустимы. Missing referenced item остаётся
ошибкой. Проверки выполняются повторно после terminal удаления.

Completed planning records не сохраняются в рабочем каталоге: их current
clauses переходят к permanent owners, а история остаётся в Git. Архивный
каталог, retirement registry и historical wrappers не создаются.

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
