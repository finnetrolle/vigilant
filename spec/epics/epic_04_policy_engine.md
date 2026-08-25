# Epic 04: Выбор политик и оркестрация детекторов

**ID:** `EPIC-04`  
**Тип:** Epic  
**Статус:** In progress  
**Приоритет:** High  
**Суммарная оценка:** 36-51 инженерных дней  
**Связанные требования:** `MVP-01`, `MVP-02`, `MVP-13`, `MVP-14`, `PERF-03`, `PERF-04`, `CONC-04`

## Карта декомпозиции

```text
EPIC-04 Policy engine
├── Domain contracts
├── Policy source
│   ├── strict parser
│   ├── semantic validator
│   └── immutable startup snapshot
├── Selection
│   └── matching and overrides
├── Detector execution
│   ├── result validation and error mapping
│   ├── deduplicated parallel execution
│   ├── per-policy deadlines and shared cancellation
│   └── fail-fast finalization
└── Decision
    ├── reaction and transformation aggregation
    └── deterministic explanation and safe logs
```

## Дочерние issues

- [x] [VIG-04-01: Domain contracts](../issues/epic_04/issue_04_01_domain_contracts.md) - `Done`
- [x] [VIG-04-02: Strict HOCON parser](../issues/epic_04/issue_04_02_config_parser.md) - `Done`
- [x] [VIG-04-03: Semantic policy validation](../issues/epic_04/issue_04_03_policy_validation.md) - `Done`
- [x] [VIG-04-04: Immutable provider snapshot и startup](../issues/epic_04/issue_04_04_snapshot_provider.md) - `Done`
- [x] [VIG-04-05: Matching и overrides](../issues/epic_04/issue_04_05_matching_overrides.md) - `Done`
- [x] [VIG-04-06: Detector executor](../issues/epic_04/issue_04_06_detector_executor.md) - `Done`
- [x] [VIG-04-07: Deduplicated parallel execution](../issues/epic_04/issue_04_07_parallel_execution.md) - `Done`
- [x] [VIG-04-08: Policy deadlines и shared cancellation](../issues/epic_04/issue_04_08_deadlines_cancellation.md) - `Done`
- [ ] [VIG-04-09: Fail-fast BLOCK](../issues/epic_04/issue_04_09_fail_fast.md) - `Ready for implementation`
- [ ] [VIG-04-10: Reaction и span aggregation](../issues/epic_04/issue_04_10_reaction_aggregation.md) - `Ready for implementation`
- [ ] [VIG-04-11: Deterministic decision и safe logs](../issues/epic_04/issue_04_11_decision_observability.md) - `Ready for implementation`

## Принятое решение

Policy engine получает готовый нормализованный контекст и один текстовый payload, выбирает все применимые политики, разрешает явные `overrides`, дедуплицирует detectors, запускает независимые detectors параллельно и возвращает итоговый `ReactionPlan`.

Policy engine не разбирает HTTP-запросы и ответы. [EPIC-03](epic_03_policy_context_extraction.md) собирает готовый `PolicyContext`, а [EPIC-06](epic_06_llm_message_parsing.md) извлекает из protocol body и events schema-derived attributes и payload fragments.

Detector получает только payload. Detector не знает URL, модель, фазу, пользователя или группы, не выбирает политику и не применяет reaction.

Первая версия поддерживает:

- точное case-insensitive сопоставление и полный wildcard `*`;
- фазы `REQUEST` и `RESPONSE`;
- subject типа `USER`, `GROUP` или `*`;
- явные `overrides` между политиками;
- параллельный запуск независимых detectors;
- общий deadline набора detectors для каждой policy;
- dispositions `ALLOW` и `BLOCK`;
- transformations `MASK` и `REMOVE`;
- файловый `DummyPolicyProvider` для `politics.conf`.

## Цель

Реализовать изолированный механизм:

```text
PolicyContext + payload
        |
        v
PolicyProvider.getPolicies()
        |
        v
enabled -> match -> overrides
        |
        v
deduplicate detectors
        |
        v
parallel execution with policy deadlines
        |
        v
aggregate DetectionResults
        |
        v
PolicyDecision with ReactionPlan
```

После реализации один и тот же engine должен работать с текущим файловым provider и будущим provider из отдельной БД. Решения о фильтрации и применении политик не должны находиться в provider.

## Границы ответственности

### `PolicyConfigParser`

- читает HOCON из `politics.conf`;
- запрещает неизвестные поля;
- создаёт policy objects;
- не хранит политики и не выполняет matching.

### `PolicyValidator`

- выполняет семантическую валидацию полного списка политик;
- проверяет ссылки на detectors и другие policies;
- не читает файл и не выполняет policies.

### `PolicyProvider`

Контракт provider допускает будущий источник из БД:

```kotlin
suspend fun getPolicies(): List<Policy>
```

Provider возвращает все имеющиеся policies и не фильтрует их по контексту.

Каждый вызов `PolicyEngine.evaluate` получает у provider новый immutable snapshot и использует его до завершения evaluation. При startup `PolicyConfigParser` и `PolicyValidator` создают validated snapshot, который передаётся в `DummyPolicyProvider`. Сам `DummyPolicyProvider` не читает и не парсит файл, а возвращает один и тот же snapshot. Hot reload в первой версии отсутствует.

### `PolicyEngine`

- исключает disabled policies;
- выполняет matching;
- применяет `overrides`;
- строит план запуска detectors;
- дедуплицирует одинаковые вызовы;
- управляет policy deadlines;
- агрегирует результаты и reactions;
- не читает конфигурационный файл и не изменяет payload.

### `DetectorExecutor`

- находит detector по стабильному ID;
- запускает detector с payload;
- поддерживает cancellation;
- проверяет инварианты `DetectionResult`;
- преобразует неожиданный exception detector в явный `DetectionResult.ERROR`;
- не выполняет matching и не применяет reactions.

### Detector

- получает только один текстовый payload;
- возвращает явный `DetectionResult`;
- не получает `PolicyContext`;
- не применяет `ALLOW`, `BLOCK`, `MASK` или `REMOVE`.

## Контекст применения

Policy engine принимает готовый контекст:

```text
PolicyContext
  url
  model
  phase
  user
  groups
```

Один вызов `evaluate` обрабатывает ровно один строковый payload. Если HTTP-запрос или ответ содержит несколько проверяемых частей, внешний adapter вызывает engine отдельно для каждого payload.

Формирование `PolicyContext`, получение identity и выделение payload не входят в эту issue.

## Модель политики

Каждая policy содержит:

```text
Policy
  id
  version
  enabled
  match
    url
    model
    phase
    subject
      type
      id
  detectors[]
  deadline
  reactions
    detected
    clean
    error
  overrides[]
```

### Идентификация

- `id` обязателен и уникален внутри snapshot.
- `version` обязательна.
- Одновременно допускается только одна версия каждого `id`.
- `overrides` ссылается на `id`, а не на конкретную версию.
- Порядок policies в provider не влияет на результат.

### Enabled

Поле `enabled` обязательно. Policy с `enabled=false` остаётся в snapshot provider, но не участвует в matching и её `overrides` не применяются.

Shadow mode не входит в первую версию.

### Match

Пример точного subject:

```hocon
subject {
  type = "USER"
  id = "user-123"
}
```

Пример группового subject:

```hocon
subject {
  type = "GROUP"
  id = "developers"
}
```

Пример policy для любого subject:

```hocon
subject {
  type = "*"
  id = "*"
}
```

Правила сопоставления:

- `url` — точное case-insensitive совпадение либо полное значение `*`;
- `model` — точное case-insensitive совпадение либо полное значение `*`;
- `phase` — обязательное значение `REQUEST` или `RESPONSE`, wildcard запрещён;
- `subject.type=USER` — `subject.id` сравнивается с `PolicyContext.user`;
- `subject.type=GROUP` — policy совпадает, если `subject.id` присутствует в `PolicyContext.groups`;
- `subject.type=USER` и `subject.id=*` — policy совпадает с любым определённым user;
- `subject.type=GROUP` и `subject.id=*` — policy совпадает с контекстом, содержащим хотя бы одну group;
- `subject.type=*` и `subject.id=*` — policy совпадает с любым контекстом, включая контекст без identity;
- комбинация `subject.type=*` с точным `subject.id` запрещена;
- все строковые сравнения locale-independent и case-insensitive;
- wildcard распознаётся только как целое значение `*`;
- `qwen-*`, `https://*.example.com/*` и другие glob/regex/partial patterns запрещены.

URL canonicalization, identity normalization и правила отсутствующих значений определяются задачей извлечения контекста. Engine не выполняет дополнительную canonicalization.

## Формат `politics.conf`

Путь задаётся переменной `VIGILANT_POLITICS_CONFIG`. Если переменная отсутствует, используется `./politics.conf`.

Файл обязателен. Отсутствующий или невалидный файл завершает запуск приложения с сообщением в `stderr` и кодом `2`, как другие ошибки startup-конфигурации. Пустой набор policies разрешается только явной валидной конфигурацией:

```hocon
policies = []
```

Пример:

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
      detected {
        disposition = "ALLOW"
        transformations = ["MASK"]
      }
      clean {
        disposition = "ALLOW"
        transformations = []
      }
      error {
        disposition = "BLOCK"
        transformations = []
      }
    }

    overrides = []
  }
]
```

`deadline` действует на весь набор detectors одной policy. Значение по умолчанию при отсутствии поля — `50ms`.

## Валидация конфигурации

Приложение не запускается, если нарушено хотя бы одно правило:

- отсутствует обязательное поле;
- присутствует неизвестное поле;
- duplicate policy ID;
- неизвестный detector ID;
- неизвестный policy ID в `overrides`;
- policy переопределяет сама себя;
- override graph содержит цикл;
- `detectors` пуст;
- detector ID повторяется внутри одной policy;
- `url`, `model` или subject содержат partial wildcard/glob/regex;
- `phase` отличается от `REQUEST`/`RESPONSE` или содержит wildcard;
- subject type отличается от `USER`, `GROUP` или `*`;
- subject ID отсутствует;
- `subject.type=*` используется с точным subject ID;
- reaction state отсутствует;
- disposition отличается от `ALLOW`/`BLOCK`;
- transformation отличается от `MASK`/`REMOVE`;
- непустые transformations заданы для `BLOCK`, `clean` или `error`;
- deadline неположительный.

Ошибки должны указывать policy ID, поле и понятную причину без вывода всего файла или чувствительных значений.

## Выбор политик

Для каждого evaluation:

1. Получить immutable snapshot через `PolicyProvider.getPolicies()`.
2. Исключить policies с `enabled=false`.
3. Выбрать все policies, чьи `url`, `model`, `phase` и `subject` совпадают с `PolicyContext`.
4. Применить `overrides`.
5. Собрать detectors оставшихся policies.

Если ни одна policy не совпала, engine не запускает detectors и возвращает `ALLOW` с пустыми списками matched/applied policies.

Политика конкретного пользователя не переопределяет групповую автоматически. По умолчанию применяются обе. Ослабление групповой policy требует явной ссылки через `overrides`.

## Семантика `overrides`

Overrides применяются только от enabled policies, прошедших matching.

Алгоритм не зависит от порядка элементов:

```text
matched = все совпавшие enabled policies
overriddenIds = union(matched.overrides)
applied = matched - overriddenIds
```

Все overrides применяются одновременно.

Пример:

```text
A overrides B
B overrides C
A, B, C matched

overridden = B, C
applied = A
```

Override из unmatched или disabled policy не действует.

## Контракт detector

Detector вызывается с единственным аргументом — payload.

`DetectionResult` всегда содержит явный status:

```text
DetectionResult.Clean
  status = CLEAN

DetectionResult.Detected
  status = DETECTED
  findings[]

DetectionResult.Error
  status = ERROR
  error.code
  error.message
```

Policy engine переключается только по `status`. Он не выводит status из пустоты списка, nullable-полей или exception.

Инварианты:

- `CLEAN` не содержит findings или error;
- `DETECTED` содержит минимум один finding;
- `ERROR` содержит стабильные `error.code` и безопасный `error.message`;
- смешанного `DETECTED + ERROR` нет;
- штатные ошибки detector возвращаются как `DetectionResult.ERROR`;
- неожиданный exception перехватывается `DetectorExecutor` и преобразуется в `ERROR` с отдельным стабильным code;
- retry и поле `retryable` отсутствуют в первой версии.

Минимальный общий finding:

```text
Finding
  startUtf8
  endUtf8
  type
```

Требования к span:

- `startUtf8` включительно, `endUtf8` исключительно;
- offsets указывают на исходный payload;
- `0 <= startUtf8 < endUtf8 <= payloadUtf8Size`;
- границы совпадают с границами UTF-8 code points;
- нарушение, относящееся ко всему payload, использует span всего payload;
- найденный текст не копируется в finding.

Detector-specific metadata может присутствовать в конкретном типе finding, но engine его не интерпретирует.

Неконсистентный result преобразуется `DetectorExecutor` в:

```text
status = ERROR
error.code = INVALID_DETECTOR_RESULT
error.message = <safe stable message>
```

## Планирование и выполнение detectors

- Policies ссылаются на detectors по стабильным строковым IDs.
- Параметры detector на уровне policy в первой версии отсутствуют.
- Одинаковый detector ID для одного payload запускается один раз, даже если его используют несколько applied policies.
- Независимые detectors запускаются параллельно.
- Зависимости и последовательные detector pipelines не поддерживаются.
- Порядок завершения detectors не влияет на итог.
- Cancellation evaluation передаётся всем активным detector executions.

### Разные policy deadlines

Deadline начинается после получения snapshot и matching, в момент запуска detectors policy. Получение policies и построение execution plan в deadline не входят. Отдельный timeout provider не вводится.

Если несколько policies используют один detector, detector execution остаётся общей, но каждая policy ожидает её до собственного deadline.

Пример:

```text
Policy A deadline = 20ms
Policy B deadline = 100ms
Обе используют detector X

20ms: A получает ERROR/DEADLINE_EXCEEDED
X продолжает работу для B
100ms: B получает result либо ERROR/DEADLINE_EXCEEDED
```

Общий detector execution отменяется, когда ожидающих policies больше нет.

### Fail-fast

`BLOCK` сильнее любого разрешающего или преобразующего плана. Как только хотя бы одна applied policy даёт `BLOCK`, итоговое решение фиксируется как `BLOCK`, а detector executions, больше не нужные другим потребителям, отменяются.

`ALLOW` не завершает evaluation досрочно: другой detector или policy ещё может вернуть `BLOCK`.

## Применение reactions внутри policy

Каждая policy обязана явно задать reaction для трёх состояний:

- `detected` применяется для каждого `DETECTED`;
- `error` применяется для каждого `ERROR`, включая policy deadline;
- `clean` применяется только если все detectors policy вернули `CLEAN` без ошибок и deadline.

Если один detector вернул `DETECTED`, а другой `ERROR`, применяются обе соответствующие reactions. Более строгий итог выбирается при общей агрегации.

Policy-level status поверх detector statuses не создаётся. `PolicyResult` хранит исходные detector results и фактически применённые reactions.

## Reaction plan

Reaction не является одним плоским enum:

```text
ReactionPlan
  disposition = ALLOW | BLOCK
  transformations[] = MASK | REMOVE
```

Правила:

- disposition обязателен;
- transformations разрешены только для `detected` с disposition `ALLOW`;
- `BLOCK` с transformations невалиден;
- `clean` и `error` не могут содержать transformations;
- engine возвращает plan, но не изменяет payload;
- безопасный HTTP-ответ при `BLOCK` не входит в эту issue;
- `REQUIRE_APPROVAL`, `ESCALATE`, `REASK`, `REGENERATE` и `STOP_AGENT` не поддерживаются первой версией;
- shadow mode не моделируется как reaction.

### Агрегация нескольких policies

Если хотя бы одна applied policy возвращает `BLOCK`, итоговый disposition равен `BLOCK`. Исполняемые transformations при этом отсутствуют, но исходные policy results сохраняются для объяснения решения.

Если `BLOCK` отсутствует, итоговый disposition равен `ALLOW`, а transformations всех applied policies объединяются.

Для одного finding `REMOVE` сильнее `MASK`. Duplicate transformations удаляются.

Пересекающиеся или соприкасающиеся UTF-8 spans объединяются. Если объединённый диапазон содержит хотя бы один `REMOVE`, итоговая операция для диапазона — `REMOVE`; иначе — `MASK`.

## Результат engine

`PolicyEngine.evaluate` возвращает один `PolicyDecision`:

```text
PolicyDecision
  reactionPlan
  matchedPolicies[]
  overriddenPolicies[]
  appliedPolicies[]
  policyResults[]
  detectorResults[]
  duration
```

`PolicyResult` содержит:

```text
PolicyResult
  policyId
  policyVersion
  detectorResults[]
  appliedReactions[]
  deadlineExceeded
```

Engine не создаёт единый status policy, потому что detectors одной policy могут одновременно вернуть `DETECTED` и `ERROR`.

Result должен объяснять:

- какие policies совпали;
- какие были отменены через overrides;
- какие реально применились;
- какие detectors были запущены;
- какой result вернул каждый detector;
- какие reactions сформировали итоговый plan.

Списки в result сортируются по policy ID и detector ID. Порядок конфигурации и параллельного завершения не меняет сериализованное решение.

## Логирование ошибок

Payload, найденный текст, credentials и identity headers не попадают в логи.

### Policy deadline

Каждое превышение deadline policy создаёт понятное структурированное событие уровня `ERROR`:

```text
event.name=policy.deadline_exceeded
error.code=POLICY_DEADLINE_EXCEEDED
policy.id=<id>
policy.version=<version>
deadline_ms=<value>
unfinished_detectors=<sorted ids>
message="Policy evaluation deadline exceeded"
```

### Detector error

Один фактический запуск detector создаёт не более одного события уровня `ERROR`, даже если его result используют несколько policies:

```text
event.name=detector.failed
error.code=<DetectionResult.error.code>
error.message=<safe message>
detector.id=<id>
affected_policies=<sorted ids>
message="Detector returned an error"
```

Raw exception, stack trace внешнего plugin worker, payload и findings не записываются. Для неожиданной внутренней ошибки допустим локальный stack trace после гарантированного удаления чувствительных данных из exception message.

## Не входит в задачу

- Парсинг HTTP-запроса или ответа.
- Извлечение URL, модели, пользователя, групп или payload.
- Конфигурация identity headers и Basic Authentication.
- Изменение request/response payload по spans.
- Формирование безопасного HTTP-ответа при `BLOCK`.
- Интеграция engine в текущий streaming proxy path.
- Hot reload `politics.conf`.
- Хранение policies в БД.
- Timeout будущего DB provider.
- Provider-side filtering или query optimization.
- Partial wildcards, glob, regex и policy DSL.
- Policy priority и неявное правило «user policy сильнее group policy».
- Shadow mode.
- `REQUIRE_APPROVAL`, `ESCALATE`, retry, reask и regenerate.
- Detector dependencies и каскады.
- Конфигурация detector parameters внутри policy.
- Реализация конкретного detector или plugin transport.
- Надёжное хранение audit log.

## Почему выбран этот вариант

| Вариант | Решение | Причина |
|---|---|---|
| Все matching policies + явные overrides | Выбран | Нет скрытого ослабления групповой защиты пользовательской policy |
| User policy автоматически сильнее group policy | Отклонён | Специфичность subject не должна неявно отключать security baseline |
| Provider возвращает уже отфильтрованный набор | Отклонён | Смешивает источник данных и семантику policy engine |
| Независимые detectors параллельно | Выбран | Соответствует `PERF-03` и снижает latency |
| Последовательный запуск по порядку файла | Отклонён | Порядок конфигурации не является зависимостью между checks |
| Явный `DetectionResult.status` | Выбран | Engine не выводит business-result из nullable fields, пустого списка или exception |
| Плоский reaction enum | Отклонён | `MASK`/`REMOVE` являются transformations и могут сочетаться с `ALLOW` |
| Полный wildcard `*` | Выбран | Покрывает общие политики без сложности glob/regex |
| Partial wildcard/glob/regex | Отложен | Нет подтверждённого сценария, но потребуются escaping и отдельная семантика URL matching |
| Parser внутри provider | Отклонён | Нарушает single responsibility и усложняет будущую замену источника на БД |

## План реализации по компонентам

1. Определить immutable domain contracts: `Policy`, `PolicyContext`, `DetectionResult`, `Finding`, `ReactionPlan`, `PolicyDecision`.
2. Реализовать strict `PolicyConfigParser` для HOCON.
3. Реализовать `PolicyValidator` и startup validation.
4. Реализовать `DummyPolicyProvider` со snapshot из `politics.conf`.
5. Реализовать registry и `DetectorExecutor`.
6. Реализовать matching и одновременное разрешение overrides.
7. Реализовать дедупликацию, parallel execution, per-policy deadlines и cancellation.
8. Реализовать reaction aggregation и нормализацию transformation spans.
9. Реализовать структурированные ошибки без чувствительных данных.
10. Добавить unit и orchestration tests.

Интеграция с HTTP gateway выполняется отдельной issue после определения extraction/enforcement contracts.

## Обязательные тесты

### Config parsing и validation

- Загружается валидный `politics.conf`.
- Используется `VIGILANT_POLITICS_CONFIG`, иначе `./politics.conf`.
- Отсутствующий файл завершает startup ошибкой.
- `policies=[]` успешно загружается.
- Неизвестное поле отклоняется.
- Проверены все validation rules из соответствующего раздела.
- Error содержит policy ID и поле, но не печатает весь config.

### Matching

- Exact matching работает case-insensitive для URL, model и subject.
- `url=*` и `model=*` совпадают с любым значением.
- Полный wildcard subject совпадает с контекстом без identity.
- USER policy совпадает только с соответствующим user.
- GROUP policy совпадает, если группа входит в context groups.
- Пользователь одновременно получает собственную и групповые matching policies.
- Phase `REQUEST` не совпадает с `RESPONSE`.
- Disabled policy не применяется.
- Отсутствие matching policies возвращает `ALLOW` без запуска detectors.

### Overrides

- User policy без `overrides` не отменяет group policy.
- Override применяется только от matching enabled policy.
- Все overrides применяются одновременно.
- Chain `A overrides B`, `B overrides C` исключает B и C, если все три matched.
- Порядок policies не меняет applied set.

### Detector execution

- Detector получает только payload.
- Один detector ID запускается один раз для нескольких policies.
- Разные detector IDs стартуют параллельно.
- Порядок завершения не меняет decision.
- Неожиданный exception превращается в явный `ERROR`.
- Неконсистентный result превращается в `INVALID_DETECTOR_RESULT`.
- Cancellation evaluation отменяет ненужные executions.

### Deadlines

- Deadline применяется ко всему detector set policy.
- Default deadline равен `50ms`.
- Policies с разными deadlines независимо ожидают общий detector execution.
- Timeout одной policy не отменяет detector, ещё нужный другой policy.
- Detector отменяется после исчезновения последнего ожидающего consumer.
- Deadline создаёт явный `PolicyResult.deadlineExceeded` и `reaction.error`.
- Deadline логируется один раз на policy с обязательными полями.

### Reactions

- `CLEAN`, `DETECTED` и `ERROR` выбирают явно настроенные reactions.
- Одновременные `DETECTED` и `ERROR` одной policy применяют обе reactions.
- Любой `BLOCK` побеждает `ALLOW` и прекращает evaluation досрочно.
- Без `BLOCK` transformations всех policies объединяются.
- `REMOVE` побеждает `MASK` для одного finding.
- Duplicate и overlapping spans нормализуются детерминированно.
- Engine не изменяет исходный payload.

### Result и logs

- Decision содержит matched, overridden и applied policies с versions.
- Decision содержит detector results и применённые reactions.
- Массивы result имеют стабильный порядок.
- Один detector error логируется один раз независимо от числа consumers.
- Логи не содержат payload, matched text, credentials или identity headers.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ clear
  Acceptance:   0.0   ✓ clear
  Boundaries:   0.0   ✓ clear
  Alternatives: 0.0   ✓ clear
  Assumptions:  0.25  ✓ extraction contract isolated in issue 03
  ──────────────────────────────
  Aggregate:    0.05  ✓ below threshold (0.2 spec)

Push lightly on: URL and identity extraction semantics in issue 03 before HTTP integration.
```
