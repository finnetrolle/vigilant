# Политики Vigilant

Нормативные schema, validation, selection, execution и aggregation принадлежат
[policy engine](../spec/requirements/policy-engine.md). REQUEST-specific
ограничения и итоговый priority принадлежат
[REQUEST enforcement](../spec/requirements/request-enforcement.md).

## Текущий контракт

Vigilant загружает один неизменяемый снимок политик при запуске. Путь к файлу
задаёт `VIGILANT_POLITICS_CONFIG`; без этой переменной используется
`./politics.conf`. Горячая перезагрузка, удалённый поставщик политик и скрытая
политика по умолчанию отсутствуют.

REQUEST и ordinary JSON/SSE RESPONSE применяют явно настроенные политики:

- `policies = []`, disabled-only и unmatched snapshots допустимы; global
  REQUEST coverage и implicit default policy отсутствуют;
- detected допускает `ALLOW`, `ALLOW+MASK` или `BLOCK`;
- REQUEST `clean` требует ALLOW без transformations, `error` требует BLOCK без
  transformations, даже у disabled или overridden policies;
- RESPONSE clean/error сохраняют прежние legal ALLOW/BLOCK combinations;
- `REMOVE` отвергается semantic validation.

Без applied policy соответствующей фазы `fast-pii` не запускается и analysis
pair отсутствует. Identity, strict protocol validation, source admission и
request-to-response context handoff сохраняются.

Диаграмма деятельности UML 2.0:
[policy-selection-activity.puml](diagrams/policy-selection-activity.puml).

## Полная структура HOCON

Синтаксический анализатор работает строго: неизвестное поле на любом уровне
останавливает запуск. Все варианты `include` в HOCON запрещены, внешние
системные свойства не участвуют в подстановке, а ошибка содержит только поле и
причину без настроенного значения.

```hocon
policies = [
  {
    id = "default-request-pii"
    version = "1"
    enabled = true
    match {
      url = "*"
      model = "*"
      phase = "REQUEST"
      subject {
        type = "*"
        id = "*"
      }
    }
    detectors = ["fast-pii"]
    deadline = 50ms
    reactions {
      detected { disposition = "ALLOW", transformations = [] }
      clean { disposition = "ALLOW", transformations = [] }
      error { disposition = "BLOCK", transformations = [] }
    }
    overrides = []
  }
]
```

Это явная REQUEST policy с detected ALLOW. Для response enforcement
к тому же snapshot добавляется политика с `match.phase = "RESPONSE"`. Например,
`detected { disposition = "ALLOW", transformations = ["MASK"] }` маскирует
findings, а `detected { disposition = "BLOCK", transformations = [] }` блокирует
весь request или response. `REMOVE` и transformations для `clean`/`error` невалидны.

Рабочий пример находится в
[`politics.conf.example`](../politics.conf.example).

## Поля политики

| Поле | Обязательность | Семантика |
|---|---|---|
| `id` | обязательно | Непустой и уникальный стабильный идентификатор политики в снимке |
| `version` | обязательно | Непустая версия, которая попадает в ссылку решения и аудита |
| `enabled` | обязательно | В сопоставлении и переопределениях участвует только включённая политика |
| `match` | обязательно | Полное совпадение `url`, `model`, `phase` и `subject` |
| `detectors` | обязательно | Непустой список уникальных известных идентификаторов детекторов; сейчас доступен `fast-pii` |
| `deadline` | необязательно | Положительная длительность HOCON; по умолчанию `50ms` |
| `reactions` | обязательно | Ровно три реакции: `detected`, `clean` и `error` |
| `overrides` | обязательно | Уникальные идентификаторы других политик; ссылки на себя, неизвестные идентификаторы и циклы запрещены |

Частичные шаблонные значения запрещены. Для `url`, `model` и `subject.id`
разрешено только точное значение или полный шаблон `*`.

## Контекст сопоставления

Политика сопоставляется сразу по всем измерениям:

| Измерение | Источник | Правило |
|---|---|---|
| `url` | Нормализованный итоговый URL вышестоящего сервера | Точное совпадение без учёта регистра или `*`; строка запроса, фрагмент и учётные данные отсутствуют |
| `model` | Непустое поле `model` из тела запроса | Точное совпадение без учёта регистра или `*` |
| `phase` | Фаза обработки | `REQUEST` для request enforcement или `RESPONSE` для ordinary JSON и SSE enforcement |
| `subject.type` | Режим идентификации | `USER`, `GROUP` или глобальное значение `*` |
| `subject.id` | Нормализованные пользователь и группы | Точное совпадение без учёта регистра или `*` |

Семантика субъекта:

- `type="*", id="*"` совпадает с любым контекстом, включая анонимный;
- `type="USER", id="*"` требует присутствующего пользователя;
- `type="GROUP", id="*"` требует хотя бы одну группу;
- точный `USER` или `GROUP` сравнивается с нормализованным идентификатором без
  учёта регистра;
- `type="*"` с точным идентификатором запрещён.

Идентификация выполняется до выбора политик. Общий Bearer contract передаёт
только normalized user/groups: development/test Dummy использует configured
values, JWT локально проверяет pinned RS256 token, а External получает identity
от trusted Bridge через local cache и применяет ту же canonical normalization.
Raw token не входит в policy context.
[Identity/context owner](../spec/requirements/identity-and-context.md#assembly-и-handoff)
определяет immutable handoff; REQUEST и RESPONSE независимо выбирают policies
с теми же URL, request model, user/groups. Runtime wiring описан в
[контракте исполнения](runtime-contract.md#bearer-identity).

## Переопределения

Переопределения разрешаются одновременно после сопоставления:

1. выбираются включённые политики, совпавшие со всем контекстом;
2. совпавшие политики сортируются по `id`;
3. объединяются списки `overrides` всех совпавших политик;
4. совпавшая политика с идентификатором из этого объединения удаляется из
   применяемого набора;
5. оставшиеся политики выполняются независимо от порядка в файле.

Переопределение не является числовым приоритетом и не зависит от порядка
завершения. Отключённая или несовпавшая политика не удаляет другие политики во
время выбора. Снимок с циклом или ссылкой на неизвестный идентификатор
отклоняется при запуске.

Scoped policy может переопределять global policy по тем же правилам: startup
не требует сохранения глобального покрытия.

## Выполнение детекторов и реакции

Применяемые политики могут ссылаться на один детектор. Исполняемая система
запускает такой детектор один раз для данного фрагмента, а затем передаёт
нормализованный результат всем потребителям среди политик. Срок каждой
политики ограничивает ожидание её набора детекторов. Итоговое объяснение и
порядок результатов детерминированы.

Полный доменный контракт допускает:

- `detected`: `ALLOW`, `BLOCK`, а для `ALLOW` также `MASK`;
- `clean`: `ALLOW` или `BLOCK`, преобразования запрещены;
- `error`: `ALLOW` или `BLOCK`, преобразования запрещены.

Aggregation создаёт canonical instructions только из selected MASK findings.
ALLOW findings не расширяют MASK unions. Existing ordering и overlap/adjacency
union rules общие для request/response; detector не запускается повторно.

REQUEST оценивает все fragments: technical failure/deadline даёт safe `503`
с `Retry-After: 1`, затем policy BLOCK или structural MASK даёт `403`, иначе
применяются free-text patches или original replay. Clean ALLOW не отменяет
решений других fragments. Known non-text gaps сохраняются без изменения.

Structural names, JSON Schema keys/constraints, arguments/custom input,
grammar и location не переписываются; selected MASK finding блокирует весь
request. Free-text content/descriptions/titles/examples/filename/reasoning/
prediction допускают MASK. Полная classification и raw rewrite contract описаны
в [REQUEST enforcement](../spec/requirements/request-enforcement.md#field-classification),
а recognized protocol surface - в
[Chat Completions reference](openai-chat-completions.md).

REQUEST marker не длиннее decoded UTF-8 span: full marker сохраняется, иначе
слово внутри brackets сокращается справа; budgets 1/2 дают `*`/`**`.
Например, `1.1.1.1` становится `[IP_MA]`. Canonical finding metadata и полные
RESPONSE markers не изменяются.

Прежние shadow snapshots с REQUEST `error=ALLOW` несовместимы: обновите их
на BLOCK явно. Автоматического fallback нет. Response timeout/error сохраняет
fail-closed `503` независимо от configured error reaction.

## Проверка и безопасные ошибки

Запуск отклоняется как минимум в следующих случаях:

- файл политик отсутствует или недоступен для чтения;
- поля HOCON неизвестны, отсутствуют или имеют неверный тип;
- идентификатор политики пуст или повторяется, версия пуста;
- список идентификаторов детекторов пуст, содержит повторы или неизвестное
  значение;
- условие сопоставления, субъект, срок или реакция недопустимы;
- переопределение ссылается на себя, неизвестно, повторяется или образует цикл;
- REQUEST clean отличается от ALLOW или error отличается от BLOCK;
- clean/error содержит transformations, в том числе у disabled policies.

При этом сервер приложения не начинает принимать запросы. В `stderr` не
выводятся тело политики, исходные значения, учётные данные и путь к файлу; код
завершения процесса равен `2`.

## Что пока отсутствует

- горячая перезагрузка политик и плоскость управления;
- измерения арендатора, приложения, агента, инструмента и ресурса;
- настраиваемые реакции fail-open, fail-closed и передача оператору сверх
  текущего executable REQUEST/RESPONSE contract;
- среда проверки политик, пользовательский интерфейс и воспроизведение
  исторических трасс;
- внешний реестр детекторов и подключаемых модулей.

Статус целевых требований приведён в
[карте покрытия требований](requirements-coverage.md).
