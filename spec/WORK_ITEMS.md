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
  [completion protocol](../docs/agent-workflow.md#work-item-completion). Архивные копии не создаются.

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
| `Ready for implementation` | Issue однозначна и прошла применимый [risk-based readiness](#risk-based-readiness); зависимости могут быть ещё не завершены |
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
Scope lock не заменяет эти условия: даже согласованное High-risk решение
декомпозируется до independently executable leaves.

<a id="risk-based-readiness"></a>

## Risk-based readiness

Автор новой или содержательно пересматриваемой issue явно указывает
архитектурный риск `Low` или `High`; reviewer проверяет классификацию.
Это отдельная оценка, не значение `Приоритет`. Правило действует после
завершённой migration к [permanent owners](requirements/README.md).
Frozen legacy work items не мигрируются только ради формата; обновление
fulfilled prerequisite links само по себе не является пересмотром scope.

| Признак изменения | Классификация |
|---|---|
| Добавляет долгоживущий resource owner | `High` |
| Добавляет обязательную конфигурацию | `High` |
| Добавляет persistence | `High` |
| Вводит или меняет внешний/public contract | `High` |
| Добавляет deployment responsibility | `High` |
| Добавляет packaging responsibility | `High` |
| Связывает несколько runtime subsystems | `High` |
| Классификация неоднозначна | `High` |
| Локальное изменение без любого из признаков выше | `Low` |

Достаточно одного High-признака. Названия bug, refactoring или docs-only сами
по себе не определяют риск. Для `Low` достаточно уровня и одной короткой
причины; scope lock, пустые разделы альтернатив и формальное approval не нужны.

`High` не может документально считаться `Ready for implementation`, пока
полностью не заполнен и не утверждён [scope lock](ISSUE_TEMPLATE.md#scope-lock).
`Draft` может содержать незакрытый lock. Автор и reviewer проверяют содержание
и подтверждение решения; green validator не доказывает approval и не заменяет
эту проверку. Автоматическая семантическая оценка риска не выполняется.

Канонический [issue template](ISSUE_TEMPLATE.md) владеет форматом scope lock и
context sources. Само решение хранится один раз в owning issue или epic;
остальные consumers ссылаются на точный раздел. `CLAUDE.md` и runtime docs не
копируют этот authoring contract. Scope lock фиксирует границу результата,
не предписывая имена классов, внутренний layout или будущие extension points.

Если жизнеспособность механизма неизвестна, lock может разрешить отдельный
ограниченный tracer bullet с одним вопросом и наблюдаемым выходом. Его результат
не становится production implementation автоматически. Согласованное решение
сохраняет действующие TDD, semantic review, security и final verification gates.

Два [authoring examples](examples/issue-authoring.md) показывают применение
правил к локальному исправлению и persistence/public-contract предложению.

## Реестр

| Work item | Статус | Прогресс | Оценка |
|---|---|---:|---:|
| [EPIC-06: OpenAI Responses protocol scope](epics/epic_06_llm_message_parsing.md) | `Draft` | 0/0 | Responses вне MVP; нет implementation-ready leaves |
| [VIG-43: Восстановить выполнение GitHub CI](issues/issue_43_restore_github_ci.md) | `Ready for implementation` | не начато | 0.5-1 дня |

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

Текущий следующий шаг: выполнить
[VIG-43](issues/issue_43_restore_github_ci.md).
[EPIC-06](epics/epic_06_llm_message_parsing.md) остаётся `Draft`;
новый scope требует согласования и executable leaves.
Эксплуатация Docker и дальнейший production review принадлежат
[operations contract](requirements/operations.md) и
[operator reference](../docs/operations.md); текущие app observations находятся в
[operations evidence](../docs/operations-evidence.md).
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

Canonical [completion protocol](../docs/agent-workflow.md#work-item-completion) и
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
