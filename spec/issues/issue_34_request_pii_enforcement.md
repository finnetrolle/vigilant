# VIG-34: Request-side PII enforcement

- **ID:** `VIG-34`
- **Тип:** Issue
- **Статус:** Done
- **Приоритет:** High
- **Зависит от:** [VIG-06-02](epic_06/issue_06_02_chat_completions_request_parser.md), [VIG-08-02](epic_08/issue_08_02_bounded_request_source.md), [VIG-20-03](epic_20/issue_20_03_reusable_text_masker.md), [VIG-29](issue_29_openai_error_contract.md), [VIG-32-01](epic_32/issue_32_01_stdout_request_audit_migration.md)
- **Блокирует:** нет
- **Оценка:** 4-5 инженерных дней; confidence Medium. Один вертикальный результат и основной real-Armeria HTTP seam; существующие selection, detector, masker и transport переиспользуются.

## Цель

Добавить request-side enforcement явно настроенных применимых политик для
поддержанного OpenAI Chat Completions path: применить `ALLOW`, `MASK` или
`BLOCK` после complete request inspection и до upstream handoff. При отсутствии
применимых политик передавать исходный request без запуска PII detector.

Продуктовые решения согласованы с владельцем продукта. Этот документ задаёт request enforcement contract.
Реализация завершена. Полные field/reaction/replay/privacy matrices, installed
process и actual OCI cases, work-item validator и полный build прошли.
Точные команды, RED/GREEN и closure ledger сохранены в
[implementation evidence](../../docs/request-enforcement-evidence.md).

## Принятые решения

### Применение PII-политик выбирает системный администратор

- Единственный источник включения PII inspection - явно настроенные policies
  в immutable startup snapshot из файла политик. Скрытая global/default policy
  и обязательный запуск `fast-pii` отсутствуют.
- `fast-pii` запускается только тогда, когда хотя бы одна enabled policy после
  existing context matching и simultaneous overrides осталась в applied set
  соответствующей фазы и ссылается на `fast-pii`.
- Если во всём файле нет включённой применимой PII policy, `fast-pii` не
  запускается ни для request, ни для response. Наличие только response policy
  не запускает request inspection; response selection сохраняет свой текущий
  независимый контракт.
- Startup больше не требует global REQUEST coverage или сохранения global
  coverage policy после overrides. Existing validation структуры policies,
  detector references, matching и override graph сохраняется. Новые detector
  types, пустой `detectors` внутри отдельной policy и изменение selectors не
  добавляются.
- Явный пустой список `policies = []` является валидным выбором администратора.
  Обязательность самого файла и safe startup errors для отсутствующего,
  нечитаемого или невалидного файла сохраняются.
- Без applied request policy source передаётся upstream byte-for-byte, без
  detector invocation и без REQUEST analysis audit pair. Identity, protocol
  validation, bounded source admission и response context handoff сохраняются;
  отсутствие PII policy не отключает эти проверки.
- Shadow-era требование global coverage из VIG-12 заменяется этой issue при
  реализации VIG-34. Прошлый `Done` status и историческое evidence VIG-12 не
  переписываются; current runtime docs обновляются после изменения runtime.

### Evidence для выбора политик

Конечная обязательная matrix:

| Case | Конфигурация / контекст | Ожидаемый запуск `fast-pii` |
| --- | --- | --- |
| `EMPTY_SNAPSHOT` | `policies = []` | Нет в обеих фазах |
| `DISABLED_ONLY` | Только disabled PII policies | Нет в обеих фазах |
| `RESPONSE_ONLY` | Совпавшая enabled RESPONSE PII policy | Нет в REQUEST; есть в RESPONSE |
| `REQUEST_ONLY` | Совпавшая enabled REQUEST PII policy | Есть в REQUEST; нет в RESPONSE |
| `URL_MISS` | Единственная REQUEST policy не совпала по URL | Нет в REQUEST |
| `MODEL_MISS` | Единственная REQUEST policy не совпала по model | Нет в REQUEST |
| `USER_MISS` | Единственная REQUEST policy не совпала по USER | Нет в REQUEST |
| `GROUP_MISS` | Единственная REQUEST policy не совпала по GROUP | Нет в REQUEST |
| `SELECTED_PII` | Enabled REQUEST PII policy совпала | Есть в REQUEST |
| `OVERRIDDEN_GLOBAL` | Совпавшая scoped policy переопределяет global PII policy | Startup разрешён; один запуск на fragment для оставшейся policy |

- **Stimulus:** для каждой строки startup с указанным snapshot и реальный
  authenticated `POST /v1/chat/completions` с одним непустым text fragment;
  upstream возвращает valid JSON response с одним непустым text fragment.
  В этой matrix `detected`/`clean` - `ALLOW`, REQUEST `error` - `BLOCK` по
  принятому startup contract. Проверки завершаются успешно, чтобы изолировать
  selection.
- **Public seam:** `loadPolicySnapshot` в `PolicyConfigurationLoadingTest` для
  startup validation; основной HTTP seam в `RequestInspectionE2eTest` через
  real Armeria gateway/upstream и существующий `GatewayTestFixture`. Для
  response-only и empty cases используется тот же production response path.
- **Observable result:** успешный startup, exact upstream request bytes;
  invocation count detector для каждой фазы согласно matrix; REQUEST audit
  pair есть только при реально начатом request analysis. Для строк с REQUEST
  invocation count `0` pair отсутствует после terminal workflow completion.
- **Independent oracle:** ожидаемые bytes задаются исходным fixture, вызовы
  считает test-owned detector на existing execution boundary, а ожидаемые
  counts задаются matrix и не вычисляются через production selector. Positive
  control `SELECTED_PII` доказывает, что counter подключён к работающему path.
  Для `OVERRIDDEN_GLOBAL` applied policy references проверяются против явно
  заданного ожидаемого ID. Audit capture синхронизируется с terminal workflow
  publication через bounded observation, без sleeps.

### `MASK` в структурных полях означает whole-request `BLOCK`

- Если canonical masking instructions от applied `MASK` policy затрагивают
  любой structural fragment из конечной таблицы ниже, при отсутствии
  technical failure весь request получает `BLOCK`. Technical failure имеет
  приоритет `503` согласно aggregation contract. Никакая часть исходного или
  частично переписанного request не передаётся upstream.
- Правило применяется по назначению поля, даже когда конкретное
  переименование не создало бы collision. Gateway не ремонтирует ссылки,
  не переименовывает связанные поля и не доказывает безопасность изменения.
- Finding только от `ALLOW` policy не активирует это правило. `BLOCK` policy
  по-прежнему блокирует request целиком. Structural `MASK` вместе с обычным
  text `MASK` или `ALLOW` в другом fragment также даёт whole-request `BLOCK`.
- HTTP result - existing VIG-29 request `403`, без `Retry-After`, с exact body
  `{"error":{"message":"Request blocked: PII detected.","type":"policy_violation","code":"policy_blocked"}}`.
  Новая причина, optional details или раскрытие имени/ключа не добавляются.
  Терминальный audit отражает фактически выбранный `BLOCK`.
- Обычный text fragment остаётся кандидатом для exact-span masking.
  Структурное назначение определяет protocol parser по recognized field,
  а не detector по содержимому строки. Только `FragmentSemanticKind.LABEL`
  недостаточно: этот kind также используется для filename.
- Modern/deprecated function arguments и custom tool input проверяются как
  полная декодированная строка согласно текущему parser contract. При selected
  `MASK` finding в ней всегда применяется `BLOCK`, включая plain text и
  корректный JSON, который технически можно было бы переписать. Вложенный JSON,
  grammar или произвольный язык не парсятся и не переписываются ради masking.
  Это правило не зависит от того, попала ли находка в ключ, string value,
  number или другой участок строки. Если instructions отсутствуют или находка
  относится только к `ALLOW` policy, original arguments/input сохраняются.

Полный набор structural cases на текущей parser surface:

| Cases | Источник структурного fragment |
| --- | --- |
| `FUNCTION_DEFINITION_NAME`, `CUSTOM_DEFINITION_NAME`, `LEGACY_FUNCTION_DEFINITION_NAME` | `tools[*].function.name`, `tools[*].custom.name`, `functions[*].name` |
| `FUNCTION_CALL_NAME`, `CUSTOM_CALL_NAME`, `LEGACY_FUNCTION_CALL_NAME` | `messages[*].tool_calls[*].function.name`, `messages[*].tool_calls[*].custom.name`, `messages[*].function_call.name` |
| `FUNCTION_CHOICE_NAME`, `CUSTOM_CHOICE_NAME`, `LEGACY_FUNCTION_CHOICE_NAME` | `tool_choice.function.name`, `tool_choice.custom.name`, root `function_call.name` |
| `ALLOWED_FUNCTION_NAME`, `ALLOWED_CUSTOM_NAME` | `allowed_tools.tools[*].function.name`, `allowed_tools.tools[*].custom.name` |
| `PROPERTIES_KEY`, `PATTERN_PROPERTIES_KEY`, `DEPENDENT_SCHEMAS_KEY` | Model-visible member names в JSON Schema containers `properties`, `patternProperties`, `dependentSchemas` |
| `FUNCTION_ARGUMENTS`, `LEGACY_FUNCTION_ARGUMENTS`, `CUSTOM_TOOL_INPUT` | `messages[*].tool_calls[*].function.arguments`, `messages[*].function_call.arguments`, `messages[*].tool_calls[*].custom.input` |
| `MESSAGE_NAME` | `messages[*].name` для каждой supported role: developer, system, user, assistant, tool, function |
| `RESPONSE_SCHEMA_NAME` | `response_format.json_schema.name` |
| `SCHEMA_ENUM`, `SCHEMA_CONST`, `SCHEMA_DEFAULT`, `SCHEMA_PATTERN` | Строковые `enum[*]`, `const`, `default`, `pattern` в recognized JSON Schema nodes |
| `CUSTOM_GRAMMAR_LARK`, `CUSTOM_GRAMMAR_REGEX` | `tools[*].custom.format.grammar.definition` при `syntax=lark` и `syntax=regex` |
| `LOCATION_COUNTRY`, `LOCATION_REGION`, `LOCATION_CITY`, `LOCATION_TIMEZONE` | `web_search_options.user_location.approximate.country`, `.region`, `.city`, `.timezone` |

Schema key и schema value cases обязательны в каждом из трёх supported roots:
`tools[*].function.parameters`, `functions[*].parameters`,
`response_format.json_schema.schema`. Отдельные cases проверяют вложенный
recognized schema node и ключ с JSON Pointer escaping (`~`, `/`), чтобы
классификация не зависела от textual prefix/suffix locator. Числовые, boolean,
null, object и array values не становятся новыми text fragments: сохраняется
current schema walker vocabulary. Служебные keywords, identifiers и остальные
поля, которые current parser не отдаёт detector, не включаются в inspection
ради этой classification.

### Свободный текст допускает exact-span `MASK`

Только следующие recognized text values могут переписываться по выбранным
instructions. Наличие JSON, кода, регулярного выражения или другого формального
текста внутри такого value не меняет его класс: gateway сохраняет outer JSON
и нетронутые bytes, но не проверяет и не ремонтирует язык внутри free-text value.

| Cases | Источник свободного текста |
| --- | --- |
| `MESSAGE_SCALAR_TEXT`, `MESSAGE_PART_TEXT` | `messages[*].content` как string и `messages[*].content[*].text`; отдельно для developer, system, user, assistant, tool, function |
| `REFUSAL_TEXT` | Assistant `messages[*].content[*].refusal` |
| `FUNCTION_DESCRIPTION`, `CUSTOM_DESCRIPTION`, `LEGACY_FUNCTION_DESCRIPTION` | `tools[*].function.description`, `tools[*].custom.description`, `functions[*].description` |
| `SCHEMA_TITLE`, `SCHEMA_DESCRIPTION`, `SCHEMA_EXAMPLE` | Строковые `title`, `description`, `examples[*]` в recognized nodes всех трёх schema roots |
| `FILE_NAME` | User `messages[*].content[*].file.filename`; source `file_data` или `file_id` остаётся inspection gap |
| `REASONING_TEXT`, `REASONING_SUMMARY` | Assistant `messages[*].reasoning.text`, `.summary` |
| `PREDICTION_SCALAR_TEXT`, `PREDICTION_PART_TEXT` | `prediction.content` как string и `prediction.content[*].text` |

Protocol-owned classification зависит от точного места в разобранной
поддержанной структуре. `LABEL` включает и structural names, и free-text
filename; `SCHEMA_TEXT` включает и constraints, и free-text annotations.
Нельзя выводить класс только из semantic kind или искать имена keywords в
произвольном JSON Pointer. Например, property с именем `description` остаётся
structural key, а её дочернее поле `description` является free text.

Изображения, audio bytes, file data/id, opaque audio reference и encrypted
reasoning сохраняют текущий inspection-gap contract без masking. Unknown
не-content fields сохраняются, current malformed/ambiguous/unsupported
content failures не превращаются в passthrough.

### Evidence для structural `MASK`

- **Stimulus:** каждый именованный structural case выше с одной находкой
  applied `MASK` policy в этом поле. Дополнительные cases: structural `MASK`
  плюс text `MASK`; тот же structural finding под `ALLOW`; text `MASK` при
  structural field без находок; две находки в разных schema keys, которые
  при masking дали бы одинаковое имя.
- Для каждого из `FUNCTION_ARGUMENTS`, `LEGACY_FUNCTION_ARGUMENTS`,
  `CUSTOM_TOOL_INPUT` обязательны значения: plain text с PII, valid nested
  JSON с PII в string value, valid nested JSON с PII в numeric value,
  valid nested JSON с PII в key, malformed nested JSON с PII. Outer request
  JSON во всех cases валиден. Все пять форм под selected `MASK` дают `403`;
  их копии под `ALLOW` сохраняют original request bytes. Отдельные negative
  controls для трёх полей: пустая строка и непустой текст без находок под
  `MASK` не вызывают structural `BLOCK`.
- **Public seam:** real authenticated HTTP в `RequestInspectionE2eTest`
  через `GatewayTestFixture`; parser classification проверяется через public
  typed parse result, если он расширяется metadata для enforcement.
- **Observable result:** structural `MASK` даёт exact `403` выше, ноль
  upstream requests и terminal audit reaction `BLOCK`. `ALLOW` передаёт
  original bytes; обычный text `MASK` изменяет только выбранный текст.
- **Independent oracle:** fixture явно задаёт field path, finding и outcome;
  ожидаемый список structural fields берётся из таблицы, не из production
  classifier. Реальный upstream считает полученные requests; HTTP body
  сравнивается с literal VIG-29 fixture. Audit и отсутствие handoff проверяются
  после наблюдаемого terminal workflow state с bounded synchronization.

### Evidence для полного field classification

- **Stimulus:** каждый named case обеих field tables с selected `MASK`
  finding в указанном fragment. Для role-specific rows используются все
  перечисленные roles, для schema rows - каждый из трёх roots. Отдельный
  parameterized parser contract проверяет сохранение classification в
  каждом current walker container: `properties`, `patternProperties`,
  `dependentSchemas`, `$defs`, `definitions`, `items`, `contains`,
  `additionalProperties`, `not`, `if`, `then`, `else`, `propertyNames`,
  `prefixItems`, `allOf`, `anyOf`, `oneOf`, schema-valued legacy `dependencies`.
  В каждом container сравниваются structural `const` и free-text `description`.
- Дополнительные contrasts: schema key `description` против annotation
  `description`; schema key `examples` против string `examples[*]`; filename
  против message name; free-text schema title против response schema name.
  Для каждого structural case есть контроль с тем же finding под `ALLOW` и
  с отсутствием findings под `MASK`.
- **Public seam:** `ChatCompletionsRequestParser.parse` для container matrix
  и `RequestInspectionE2eTest` с real Armeria для всех named field cases.
- **Observable result:** structural `MASK` возвращает exact VIG-29 `403`
  без upstream handoff; free-text `MASK` передаёт valid outer JSON с marker
  только вместо выбранного span. Контроли передают original bytes. Audit
  reaction соответствует фактическому `BLOCK`, `MASK` или `ALLOW`.
- **Independent oracle:** тестовые inputs и expected bytes/results заданы
  вручную; schema paths не генерируются из production classification table.
  Отдельный JSON reader подтверждает valid outer JSON, полный byte comparison
  подтверждает сохранение unknown fields, whitespace, ordering и нетронутого
  escaping. Detector invocation counter доказывает отсутствие повторной
  проверки ради classification/rewrite.

Матрицы этого раздела являются обязательным evidence field classification;
reaction, rewrite и lifecycle evidence задаются ниже и дополняют их.

## Известный контекст

- Исторический baseline до VIG-34 выполнял request-side PII inspection в shadow
  mode и replay-ил original request upstream byte-for-byte.
- Existing policy engine, match dimensions, overrides и detector registry
  сохраняются как future-capable foundation. Эта issue интегрируется с ними и
  не создаёт задачу на их упрощение.
- `ALLOW` должен сохранить exact original replay.
- `MASK` должен изменить только выбранные model-visible text spans, сохранить
  valid JSON, unknown fields и все невыбранные bytes/values согласно
  согласованному protocol rewrite contract.
- `BLOCK` и technical failure используют VIG-29 safe OpenAI-compatible errors
  и не начинают upstream handoff, если outcome установлен до передачи.
- Dependency VIG-29 завершена: закрытая production matrix уже предоставляет
  request `BLOCK` и request inspection failure без optional details. Эта issue
  владеет выбором этих outcomes из request reaction/lifecycle logic.
- Реально начатый request analysis публикует stdout pair по contract
  VIG-32-01.

## Принятое решение: `clean` в REQUEST

### Контракт

- В каждой REQUEST policy `reactions.clean` остаётся обязательным и допускает
  только `disposition = "ALLOW"`, `transformations = []`.
- Правило проверяется при startup для enabled и disabled REQUEST policies,
  включая политики, которые в конкретном контексте будут overridden.
  `enabled`, context match и overrides влияют на применение валидных policies,
  но не разрешают невалидную конфигурацию.
- `clean = BLOCK`, любые clean transformations, отсутствие `clean` и неверные
  типы завершают startup с existing exit code `2` до приёма трафика. Ошибка
  безопасно указывает поле/причину без вывода полного файла. Конфигурация не
  исправляется автоматически, policy не игнорируется и режим не переключается.
- `clean` относится к успешно завершённой проверке конкретного fragment по
  конкретной applied policy. Это не эквивалент отсутствию policies, detector
  failure/timeout или inspection gap и не утверждает отсутствие PII во всём
  запросе либо в непроверяемом содержимом.
- `clean = ALLOW` означает отсутствие blocking/masking contribution от этой
  policy для данного fragment. Она не отменяет `BLOCK` или selected `MASK`
  другого fragment/policy. Complete request решение и upstream handoff остаются
  после required inspection и aggregation, без ранней отправки чистого fragment.
- `PolicyReactions`, domain finalization/aggregation и текущий RESPONSE
  contract не сужаются. Ограничение живёт в startup validation исполняемого
  REQUEST path. Technical failure не переводится в `clean`; его принятый
  контракт описан ниже и использует VIG-29 safe `503`.

### Причина и отклонённая альтернатива

Request PII increment блокирует по обнаруженному PII, в том числе
когда выбранный `MASK` нельзя применять к structural field. `clean = BLOCK`
задаёт другой сценарий: отклонять запрос именно за отсутствие PII. Такого
продуктового требования пока нет; existing VIG-29 `Request blocked: PII
detected.` также не соответствует чистому результату.

Поддержка `clean = BLOCK` с общей либо отдельной правдивой причиной policy
rejection технически возможна; сам status `403` этому не препятствует. Эта
альтернатива отклонена для VIG-34: request increment не вводит сценарий
блокировки за отсутствие PII и не расширяет VIG-29 ради него.

### Обязательное evidence

- **Stimulus:** startup cases `CLEAN_ALLOW`, `CLEAN_BLOCK`,
  `CLEAN_ALLOW_MASK`, `CLEAN_BLOCK_MASK`, `CLEAN_MISSING`, `CLEAN_WRONG_TYPE`;
  каждый валидный по остальным полям snapshot проверяется с enabled и
  disabled REQUEST policy. Отдельно - невалидная clean reaction у policy,
  на которую ссылается valid overriding policy.
- **Public seam:** `loadPolicySnapshot` для полной config matrix и existing
  packaged process fixture для valid startup и safe exit `2` на `CLEAN_BLOCK`;
  `RequestInspectionE2eTest` для runtime consequences.
- **Observable result:** только `CLEAN_ALLOW` разрешает startup. Runtime cases:
  clean-only request передаётся exact; clean fragment вместе с detected text
  `MASK` даёт masked request; clean вместе с detected `BLOCK` или structural
  `MASK` даёт `403` без upstream handoff. Detector failure/timeout не даёт
  clean success; no-policy case не запускает detector.
- **Independent oracle:** expected startup outcomes заданы case table,
  процесс наблюдается через exit code/readiness, HTTP - через реальные
  gateway/upstream; expected bytes и error body задаются literal fixtures.
  Detector counters и controlled outcomes разделяют Clean, Error и отсутствие
  invocation, а не выводят их из пустого списка findings.

## Принятое решение: `error` в REQUEST

### Контракт и миграция

- В каждой REQUEST policy `reactions.error` обязательна и допускает только
  `disposition = "BLOCK"`, `transformations = []`. Это означает запрет
  upstream forwarding при техническом отказе включённой проверки, а не
  утверждение о найденном PII.
- Правило проверяется при startup для enabled, disabled и потенциально
  overridden REQUEST policies. `error = ALLOW`, transformations,
  отсутствующая реакция и неверные типы дают safe startup failure с exit
  code `2` до приёма трафика. Silent correction, игнорирование policy и
  fallback на пропуск непроверенного запроса запрещены.
- Detector failure или policy/detector timeout до upstream handoff возвращает
  VIG-29 `503` с `Retry-After: 1` и exact body
  `{"error":{"message":"Request inspection unavailable.","type":"server_error","code":"request_inspection_unavailable"}}`.
  Это technical outcome, а не PII-related `403`. Upstream не получает ни
  запроса, ни части body. Ошибка не раскрывает payload, identity, configured
  policy values или внутреннюю причину; safe audit использует ERROR outcome.
- Отсутствие applied PII policy сохраняет принятый no-detector path. Включение
  проверки остаётся выбором администратора; технический отказ уже включённой
  проверки не разрешает её обойти.
- Domain `PolicyReactions`, coordinator и aggregator сохраняют поддержку
  legal error reactions. Новое ограничение находится в REQUEST startup
  validation; VIG-34 не меняет текущие RESPONSE error configuration semantics.
- При переходе на реализацию VIG-34 существующие REQUEST shadow policies с
  `error = ALLOW` нужно явно обновить до `error = BLOCK`. Старый файл
  отклоняется, а не автоматически мигрирует. `detected = ALLOW` остаётся
  допустимым выбором администратора: он допускает найденный PII, но не
  техническую невозможность выполнить настроенную проверку.
- В implementation change обновляются `politics.conf.example`, canonical
  test policy fixtures, packaged process/OCI/performance launch consumers и
  owning configuration/deployment docs. Sample и runtime меняются вместе:
  REQUEST `error = BLOCK` обязателен для нового executable contract.

### Обязательное evidence

- **Stimulus:** config cases `ERROR_BLOCK`, `ERROR_ALLOW`, `ERROR_ALLOW_MASK`,
  `ERROR_BLOCK_MASK`, `ERROR_MISSING`, `ERROR_WRONG_TYPE` с enabled и disabled
  REQUEST policy; отдельно - невалидная reaction у overridden policy.
  Runtime cases: typed detector error, detector exception, policy deadline
  с незавершённым detector. Каждый runtime case повторяется с detected
  reaction `ALLOW`, `MASK`, `BLOCK`, при этом actual detector outcome в
  данном case - технический отказ, а не detection.
- **Public seam:** `loadPolicySnapshot` / `PolicyConfigurationLoadingTest`
  для config matrix, real HTTP в `RequestInspectionE2eTest` для runtime,
  existing packaged process fixture для startup migration.
- **Observable result:** только `ERROR_BLOCK` разрешает startup в error
  matrix; старый all-ALLOW shadow snapshot завершает процесс с exit `2`,
  snapshot с исправленной error reaction достигает readiness. Runtime
  technical cases дают exact `503`/body/`Retry-After` и ноль upstream requests;
  реально начатый analysis завершает safe ERROR audit.
- **Independent oracle:** configured snapshots и expected outcomes заданы
  fixtures. Test-owned detector выдаёт отдельные controlled failure outcomes;
  deadline case удерживает незавершённый detector bounded handshake, без
  sleeps. Реальный upstream считает обращения; HTTP сравнивается с literal
  VIG-29 contract, lifecycle/audit наблюдаются после terminal publication.

## Принятое решение: сокращение request masking marker

- Запрос не должен отклоняться только потому, что выбранное слово-маркер
  длиннее заменяемого PII и увеличивает размер body. Пользователь отверг
  предложение возвращать `503` из-за такого увеличения и выбрал сокращение
  самого masking marker.
- Сокращается только replacement text. Исходный PII span заменяется целиком;
  его prefix/suffix не сохраняются ради ограничения длины. Неизменённый PII
  или частично замаскированное значение не являются fallback.
- Полный marker и FindingType остаются canonical policy metadata. Сокращённое
  request representation не должно терять тип в safe aggregate audit или
  менять selection, finding spans, overlap resolution и structural `BLOCK`.
- Бюджет замены `N = endUtf8 - startUtf8` измеряется в байтах UTF-8 исходного
  декодированного PII span после canonical overlap/adjacency merging. Это
  не количество Kotlin `Char`, Unicode code points или raw JSON bytes.
- Если полный canonical ASCII marker занимает не больше `N` bytes, он
  используется без изменения. Иначе при `N >= 3` сохраняются `[` и `]`, а
  внутреннее слово обрезается справа до первых `N - 2` ASCII characters.
  При `N = 1` используется `*`, при `N = 2` - `**`. Нулевой или невалидный
  span остаётся ошибкой instructions, а не разрешением удалить содержимое.
- Примеры: span `1.1.1.1` (7 UTF-8 bytes) и marker `[IP_MASKED]` дают
  `[IP_MA]`; `a@b.co` (6 bytes) и `[EMAIL_MASKED]` дают `[EMAI]`.
  Алгоритм не зависит от доступной global quota, общего размера запроса или
  того, были ли исходные символы записаны directly либо через JSON escapes.
- Сначала проверяются полные canonical instructions, после чего request
  rendering получает допустимую укороченную форму. Сейчас `TextMasker`
  принимает только полный bracketed marker с suffix `_MASKED`; нельзя
  ослабить эту validation до произвольных строк ради сокращения. Общую
  validation следует переиспользовать без повторного detector execution.
- ASCII replacement не требует JSON escaping и занимает не больше decoded
  UTF-8 span, который в свою очередь занимает не больше его raw JSON range.
  Поэтому каждый raw patch не расширяет source, а полный masked request
  не превышает исходное число bytes. Дополнительный size rejection из-за
  masking marker не нужен.
- Текущая RESPONSE representation и её validation не меняются. Shared
  transport-neutral logic переиспользуется там, где семантика идентична;
  request rendering не должно незаметно сокращать response markers.
- Existing admission/quota failures и genuine technical errors сохраняются.
  Отвергнут именно отказ из-за роста маркера; это не разрешает неограниченные
  payload copies или отключение bounded request-source contract. Ownership
  и представление masked replay определены ниже.

### Отклонённая альтернатива

Строить увеличенное masked body, сравнивать его с per-request limit и
отказывать с `503`, если увеличение вызвано длиной canonical marker. Такой
подход отклоняет исходно допустимый запрос из-за внутреннего представления
маскировки и заменяется сокращением marker.

### Обязательное evidence

- **Stimulus:** для каждого canonical marker текущего набора
  `[EMAIL_MASKED]`, `[CARD_MASKED]`, `[PHONE_MASKED]`, `[IP_MASKED]`,
  `[IBAN_MASKED]`, `[INN_MASKED]`, `[SNILS_MASKED]`, `[PASSPORT_MASKED]`,
  `[OMS_MASKED]`, `[PII_MASKED]` проверяются бюджеты `1`, `2`, `3`, `L-1`,
  `L`, `L+1`, где `L` - ASCII byte length полного marker. Отдельно:
  multibyte UTF-8, surrogate pair, escaped JSON representation одного и того
  же decoded span, adjacent/overlapping unions с одинаковыми и разными types.
- **Public seam:** transport-neutral request marker rendering/validation
  contract для полной finite matrix и `RequestInspectionE2eTest` для exact
  upstream bytes. Это тест rendering уже готовых legal instructions;
  произвольный малый span не выдаётся за доказательство нового detector type.
- **Observable result:** literal expected replacement для каждого budget,
  полная замена PII span, сохранение всего остального source и
  `maskedBodyBytes <= originalBodyBytes`. Полные type counts в audit не
  заменяются сокращённым text marker. Request ровно на configured ingress
  limit с коротким PII успешно проходит MASK при доступной исходной quota.
- **Independent oracle:** expected marker strings и raw JSON задаются
  вручную, без вызова production formatter для расчёта ожиданий. Upstream
  сохраняет фактически полученные bytes. Case с `\\u0031.1.1.1` должен дать
  тот же `[IP_MA]`, что direct `1.1.1.1`, хотя полный marker мог бы поместиться
  в escaped raw range. Отдельные regressions сохраняют exact RESPONSE
  full-marker output и rejection невалидных canonical instructions.

## Exact source rewrite и bounded replay

- `ALLOW` и no-policy path воспроизводят exact original request. `MASK`
  заменяет только raw source ranges выбранных free-text PII spans на
  согласованное request representation marker.
- Все остальные bytes сохраняются: whitespace, порядок полей, unknown
  metadata, number formatting, untouched escapes и Unicode spelling, включая
  prefix/suffix того же JSON string value. Полное body и изменённый string
  value не пересериализуются. Весь JSON structurally разбирается один раз;
  detector execution не повторяется ради patching.
- Request parser владеет field classification, fragment ordinal/locator и
  raw string source locations. Schema keys являются structural fragments и
  не получают permissive rewrite locations как обычные string values.
  Идентичная семантика raw JSON coordinate validation должна переиспользовать
  canonical helpers существующего ordinary JSON response rewriter; response
  behavior и его memory policy не копируются в request path.
- Перед upstream handoff проверяются все выбранные locators, UTF-8 boundaries,
  source ranges, canonical instruction ordering и вычисленная exact output
  length. Невалидный план даёт safe technical `503` до upstream, без частично
  переписанного или исходного unmasked fallback.
- `Content-Length` соответствует вычисленному размеру exact patched replay;
  исходный body-dependent digest удаляется по header contract ниже.
- Реализация сохраняет один immutable bounded source и
  полностью проверенный компактный patch plan; replay выдаёт bytes с
  backpressure после принятия решения. Source quota остаётся во владении
  terminal replay до завершения или отмены, а не освобождается после чтения
  последнего input segment при ещё не выданном output. Вторая полная копия
  original/rewritten body и повторная admission из-за неё не требуются.

### Ответственность и public source seam

- `ChatCompletionsRequestParser.parse` добавляет immutable metadata для
  каждого fragment: ordinal, точный recognized field class и, для free-text
  string values, raw token location. Token locations собираются в том же
  structural parse pass; existing schema walker, source order, limits и
  typed parse failures сохраняются. Metadata не содержит rebuilt body.
- Protocol-owned request rewrite planner получает original source view,
  normalized metadata и canonical per-fragment instructions. На existing
  blocking-safe executor он строит полностью проверенный immutable raw patch
  plan с exact output length. Sequential decoding выбранных raw literals
  для проверки source coordinates разрешено; второй structural JSON parse,
  повторный detector execution и `readAllBytes` всей request source запрещены.
  Common JSON scalar/escape rules из `JsonResponseRewriter.kt` следует
  переиспользовать или извлечь в общий helper со streaming input; response
  rewriter не переводится на иной алгоритм и сохраняет output.
- Source package расширяет public `BoundedRequestSourceOwner` операцией
  подготовки patched replay: вход - упорядоченные непересекающиеся raw byte
  replacements, выход - typed ready replay с известной длиной либо safe
  failure. Имена новых declarations выбирает implementer; обязательная
  семантика этого seam задана здесь. Source не знает OpenAI, PII, policy или
  audit. План привязан к владельцу, на котором подготовлен; перенос между
  owners, повторный handoff и второй subscriber запрещены.
- Подготовка проверяет source COMPLETE, отсутствие active view/replay lease,
  ranges, отсутствие перекрытий, non-expansion каждого raw patch и output
  length до первого upstream действия. Ошибка подготовки отображается
  workflow в `503`, не в current generic `400 invalid_request_source`.
- Exact и patched replay используют одного owner и один terminal cleanup
  mechanism. Patched replay не оборачивает завершающийся exact publisher
  так, чтобы тот мог стереть bytes до выдачи оставшегося patched output.
  Quota считает исходный retained payload до terminal output; второй owner,
  полный rewritten buffer, новая очередь, executor или replay capacity
  admission не создаются.
- Patch plan хранит только ranges и короткие immutable replacement values;
  одинаковые marker representations переиспользуются. Число non-overlapping
  patches ограничено исходным byte length, новые неограниченные metadata
  коллекции и payload copies не создаются. Output выдаётся по demand с
  bounded scratch buffer не больше существующего storage segment size.
- Borrowed source buffers живут до возврата соответствующего `onNext`;
  `RequestBodyFlowAdapters` копирует их в независимо принадлежащий Armeria
  `HttpData`, как в current exact replay. Очистка owner не повреждает уже
  скопированные transport bytes. Terminal cleanup наступает после последнего
  output `onNext`, даже если последний input segment прочитан раньше.
  Конкурентный owner close запрашивает остановку, но не стирает buffer и не
  освобождает reservation, пока callback им пользуется. Cleanup откладывается
  до возврата callback; event loop не блокируется ожиданием этого возврата.
- `ReplayReadyRequest` владеет prepared source/plan до единственного
  `transferTo`; после успешного handoff replay владеет cleanup. Неуспешный
  синхронный callback, close до передачи или проигрыш cancellation/shutdown
  освобождает ready resource без upstream повторной попытки. Existing
  `InspectionCancellation` и response-analysis shutdown admission сохраняются.

### Request headers

- Для `ALLOW` сохраняется existing request header behavior. Для `MASK`
  `Content-Length` задаётся по validated output length, даже если inbound
  length отсутствовал или body был chunked.
- При `MASK` удалить `Content-MD5`, `Digest`, `Content-Digest` и `Repr-Digest`,
  поскольку проверка целостности должна соответствовать изменённому body.
  Последние два поля определены в [RFC 9530](https://www.rfc-editor.org/rfc/rfc9530.html#section-2)
  для content/representation integrity; удаление при mutation является
  выбранным contract gateway. Не пересчитывать digest и не создавать signing
  subsystem. `Want-Content-Digest`/`Want-Repr-Digest` выражают preferences
  получателя и сохраняются по existing end-to-end header rules.
- Existing `BypassProxyService` остаётся owner scheme/authority/path rewrite,
  fixed hop-by-hop filtering и полей из `Connection`. Accepted Authorization
  сохраняется; session/tracing headers проходят existing canonical rewrite.
  Прочие end-to-end headers сохраняются. Request trailers и compressed
  request support не расширяются в этой issue.
- Evidence: real upstream получает expected headers для inbound fixed length
  и chunked bodies, с каждым из четырёх digest fields отдельно и всеми вместе.
  Для `ALLOW`
  digest values сохраняются; для `MASK` отсутствуют. Mixed-case, repeated
  `Connection` fields и nominated headers проверяются existing canonical
  request transport tests, дополненными реальным MASK request.

## Принятое решение: aggregation и приоритет outcomes

- Формат policy reactions не меняется: `Disposition` содержит только
  `ALLOW|BLOCK`. Для REQUEST detected допустимы ровно existing combinations
  `ALLOW + []`, `ALLOW + [MASK]`, `BLOCK + []`; итоговый `MASK` является
  derived action, а не новым disposition или строковым значением config.
  `BLOCK + [MASK]`, `REMOVE`, неизвестные values и неверные field types
  отвергаются existing strict validation. Clean/error restrictions заданы выше.
- Применяется existing immutable snapshot, context matching и simultaneous
  overrides. Detector вызывается один раз на fragment для всех applied
  policies, которые на него ссылаются; windowing внутри adapter сохраняется.
  При отсутствии applied policies detector не запускается.
- Независимые fragments проверяются в canonical source ordinal order.
  Policy `BLOCK` или structural `MASK` в раннем fragment не завершает
  request workflow досрочно: оставшиеся fragments должны дать свои outcomes,
  иначе можно скрыть technical failure, который имеет более высокий приоритет.
  Existing domain fail-fast внутри одной fragment evaluation не меняется.
  Typed detector/policy errors сохраняются в decision; unexpected fatal
  orchestration failure или cancellation прерывает дальнейшую работу безопасно.
- Итог для живого request до handoff: (1) любой реально полученный detector
  error, deadline либо невозможность закончить workflow - `503`; (2) иначе
  любой applied `BLOCK` или selected structural `MASK` - `403`; (3) иначе
  хотя бы одна free-text masking instruction - `MASK`; (4) иначе `ALLOW`.
  Rewrite plan строится только для пункта (3); не нужно запускать ненужный
  rewrite после уже установленного policy `BLOCK` ради поиска его ошибок.
- Overlapping и adjacent spans одного fragment объединяются existing
  canonical `ReactionPlan` rule: одинаковый marker сохраняется, разные
  markers дают `[PII_MASKED]`. Только после объединения применяется shortening
  по длине union. Spans разных fragments никогда не объединяются. `ALLOW`
  findings не создают masking instructions и не расширяют MASK unions.
- Known inspection gaps сохраняются. При applied policies и пустом списке
  fragments используется current empty-payload evaluation для policy/error
  lifecycle; synthetic empty payload не считается реально inspected fragment.
  Gap-only result без ошибок - `INSPECTION_GAP`/`ALLOW`, ordinary empty text
  без gaps - `CLEAN`/`ALLOW`. Без applied policies audit pair отсутствует.

### Evidence aggregation и gap cases

| Cases | Ожидаемый итог |
| --- | --- |
| `ALL_CLEAN`, `DETECTED_ALLOW_ONLY`, `NO_APPLIED_POLICY` | Original replay |
| `TEXT_MASK_ONLY`, `TEXT_MASK_PLUS_CLEAN`, `TEXT_MASK_PLUS_ALLOW` | Exact patched replay |
| `DETECTED_BLOCK`, `BLOCK_PLUS_TEXT_MASK`, `STRUCTURAL_MASK_PLUS_TEXT_MASK` | `403`, ноль upstream requests |
| `ERROR_PLUS_ALLOW`, `ERROR_PLUS_TEXT_MASK`, `ERROR_PLUS_BLOCK`, `ERROR_PLUS_STRUCTURAL_MASK` | `503`, ноль upstream requests |

- **Stimulus:** каждую двухкомпонентную строку выполнить с обоими порядками
  fragments и обоими порядками policies в snapshot. Для четырёх ERROR cases
  отдельно typed detector error и policy deadline. Для чисто policy mixes
  дополнительно один общий fragment с несколькими applied policies, чтобы
  count доказывал один detector invocation на fragment, а не на policy.
- Overlap cases: disjoint, duplicate, nested, partially overlapping, adjacent;
  для последних четырёх - equal markers и mixed markers, оба порядка input
  instructions. Same offsets в двух разных fragments остаются независимыми.
  Public pure plan/formatter tests задают legal synthetic instructions и не
  выдают их за новые production PII types.
- Gap matrix использует каждый current kind: IMAGE, AUDIO, FILE,
  OPAQUE_AUDIO_REFERENCE, OPAQUE_REASONING. Для каждого: gap-only,
  gap-plus-clean, gap-plus-text-MASK, gap-plus-structural-MASK,
  gap-plus-BLOCK, gap-plus-error. Gap bytes/values не переписываются;
  BLOCK/error запрещают передачу всего request. Pure empty-text case
  проверяется отдельно от gap-only и no-policy.
- **Public seam:** `RequestInspectionE2eTest`, с pure canonical-plan tests
  только для искусственных overlap inputs.
- **Observable result:** exact HTTP outcome, upstream bytes или отсутствие
  handoff, один aggregate audit, фактические detector invocation counts.
- **Independent oracle:** literal expected bytes/status/body и вручную
  заданные counts; controlled detector results для errors/overlaps,
  обязательные positive cases с настоящим `fast-pii` для email/IP masking
  и PII BLOCK. Expected unions не рассчитываются production normalizer.

## Lifecycle и причинное evidence

Один source owner удерживает payload от ingest до terminal replay либо
отказа. Analysis lifecycle начинается только перед первым detector invocation
и заканчивается до разрешённого handoff. Публикация terminal audit не означает
подтверждение доставки bytes upstream. Cancellation после этой публикации
не создаёт вторую analysis completion; transport отражает свой outcome сам.

| Case | Stimulus / наблюдаемая граница | Обязательный результат |
| --- | --- | --- |
| `BEFORE_ANALYSIS_CANCEL` | Cancel до первого detector invocation | Detector не стартует, audit pair отсутствует, upstream не вызван, source освобождён |
| `DURING_ANALYSIS_CANCEL` | Detector entered, затем cancel | Работа отменена; одна terminal ERROR attempt, нет handoff, source освобождён |
| `PREPARE_FAILURE` | Planner получает invalid location/span/marker/owner binding | `503` до upstream; одна ERROR attempt, source освобождён |
| `READY_CANCEL` | Valid exact или patched replay готов; cancel выигрывает handoff claim | Ни одного upstream вызова; ready source закрыт; повторной completion нет |
| `READY_CLOSE` | Close готового replay до transfer | Нет callback/subscription, owner закрыт; повторный close идемпотентен |
| `HANDOFF_THROW` | Forward callback синхронно бросает exception | Ready resource освобождён, retry отсутствует; exception отображается existing transport boundary |
| `REPLAY_SUCCESS` | Subscriber принимает весь actual ALLOW/MASK output | Exact expected bytes; quota освобождается после последнего output callback |
| `REPLAY_CANCEL` | Cancel после первого output chunk | Последующая выдача прекращается, source/plan освобождены |
| `REPLAY_SUBSCRIBER_FAILURE` | Subscriber бросает на actual output | Один terminal failure, cleanup без повторной передачи |
| `OWNER_CLOSE_DURING_REPLAY` | Caller close при активном replay | Replay завершается безопасно, cleanup однократен, дальнейшие bytes не выдаются |
| `LAST_INPUT_PENDING_OUTPUT` | Последний input прочитан, но output удержан demand | Source quota остаётся занята до выдачи или отмены оставшегося output |
| `PEER_CLOSE` | Real upstream закрывает соединение во время ALLOW/MASK upload | Existing safe transport failure, replay отменён, quota восстановлена; upstream мог получить уже отправленный prefix |
| `SHUTDOWN_BEFORE_HANDOFF` | Existing shutdown admission закрывается перед claim | Новый upstream не стартует, pending request завершается cancellation по existing lifecycle, source освобождён |
| `SHUTDOWN_ACTIVE_REPLAY` | Process/server shutdown при held upstream upload | Readiness закрыта; existing drain затем cancel/cleanup, процесс завершается в existing shutdown deadline |
| `DOUBLE_TRANSFER`, `DOUBLE_SUBSCRIBE`, `VIEW_REPLAY_CONFLICT` | Повторный/конкурентный illegal access | Второй доступ отвергнут; не меняет успешного владельца и не освобождает его ресурс раньше времени |

- **Public seams:** real HTTP через `GatewayTestFixture` для gateway paths;
  public source/quota API в `BoundedRequestSourceTest` и
  `ReplayReadyRequestTest` для demand, ownership и illegal interleavings;
  `GatewayProcessFixture` для process shutdown. Табличные ALLOW/MASK cases
  должны реально использовать original replay и patched replay с изменёнными
  bytes, а не передавать только название реакции одному generic close path.
- **Independent oracle:** upstream-owned counters и captured bytes,
  source state плюс `activeOwners`, `retainedBytes`, `retainedSegments`,
  process exit/readiness и test-owned callbacks/barriers. Стартовое значение
  counters записывается, после terminal cleanup ожидается возврат к нему.
  Последовательность фиксируется handshake на нужном state после publication;
  callback entry или client response не заменяет completion barrier.
- Race cases выполняются с обоими явно удержанными порядками contenders:
  cancel до публикации handle и после неё; cancel до handoff claim и после;
  close до последнего output callback и после. Нет sleeps, wall-clock races,
  повторного использования released ephemeral port или нового process launcher.
- Source demand matrix для patched replay: no demand, `request(1)`, bounded
  batch и unbounded demand, invalid `request(0/-1)`. Patch находится целиком
  внутри segment, начинается/заканчивается на boundary, пересекает две и три
  segments; соседние patches и final suffix после последней замены. Tiny
  input chunks не увеличивают configured storage-segment count. Expected
  output берётся из literal source/patch fixtures, не из production rewriter.
- Capacity regressions: original body на exact ingress limit с expanding
  full marker успешно проходит shortening; overflow ingress на один byte
  сохраняет `413`; исчерпание original byte/owner quota сохраняет `503`.
  Пока один patched replay удержан demand, конкурентный request наблюдает
  занятые original reservations; после terminal cleanup admission успешна.
- После начала upstream exchange невозможно обещать отсутствие уже выданных
  bytes. Transport cancellation/failure обрывает передачу по существующему
  контракту без unmasked replay/retry. Гарантия нулевого upstream handoff
  относится к решениям inspection/rewrite, принятым до передачи.

## Audit, metrics и tracing

- Сохраняется exact safe schema VIG-32-01. Successful REQUEST completion
  получает фактическую `reaction=ALLOW|MASK|BLOCK`; `ERROR` содержит
  `error.code` и не содержит `reaction`. Новые audit fields, queues,
  persistence, delivery acknowledgement, retry и drop metrics не вводятся.
- Outcome precedence: ERROR, затем DETECTED при findings, затем
  INSPECTION_GAP, затем CLEAN. Полные type/evidence counts берутся из
  canonical detector outcomes один раз на fragment, без умножения на число
  policies и без вычисления из shortened marker. Policy references и counts
  используют existing deterministic ordering helpers.
- Если typed decisions получены, ERROR сохраняет их safe aggregate; при
  прерванной evaluation учитываются только завершённые evaluations, а не
  будущие fragments. Known parser coverage сохраняется и не заменяется
  `UNINSPECTABLE` только из-за cancellation. Synthetic empty evaluation
  оставляет `fragments.inspected=0`.
- Absence matrix: unsupported descriptor, malformed/ambiguous/unresolved
  request, failed identity/context/source, no applied policy, cancellation
  до analysis - нет REQUEST pair. После реально начатого analysis каждый
  terminal path делает одну completion attempt, даже если logger бросает.
- In-process causal logging fixture удерживает detector и затем handoff:
  started attempt предшествует invocation, completed attempt следует final
  decision/validated replay preparation и предшествует handoff. Slow/full/
  throwing sink не меняет HTTP, readiness или handoff. Process JSONL
  проверяется после доставки через existing bounded stdout observation;
  production не ждёт этой доставки.
- Metrics используют существующие instruments: requests/responses,
  status class, active requests и existing transport counters/durations.
  BLOCK даёт `4xx`, technical refusal `5xx`, оба без upstream duration или
  CLIENT span. Normal ALLOW/MASK с test upstream `200` дают `2xx` и один
  existing upstream CLIENT span. Active requests возвращается к baseline
  после terminal callback, включая cancellation; новых policy labels нет.
- REQUEST inspection span сохраняет existing SERVER parent и завершение
  на terminal workflow. RESPONSE inspection продолжает existing lineage и
  request-derived context. Policy BLOCK не записывается как сырое exception;
  technical inspection failure отмечает ERROR без payload disclosure.
- **Evidence:** operator-visible JSONL из `PiiShadowProxyProcessTest` для
  CLEAN/ALLOW, DETECTED/ALLOW, DETECTED/MASK (full и shortened),
  DETECTED/BLOCK (policy и structural), INSPECTION_GAP/ALLOW, ERROR/503,
  no-policy absence и startup migration. Reuse existing 1ns policy deadline
  fixture для packaged ERROR, без production test switches. In-process
  telemetry exporter/capture проверяет metrics и span lineage после
  owning completion publication. Expected fields/counts задаются literals.
- Privacy sentinel matrix проверяет оба audit events и client error bodies: body/PII
  value/span, path/query, headers, Bearer, user/groups, session, raw inbound
  propagation и exception text отсутствуют. Policy/detector references остаются
  safe stdout metadata, не client details.
- Captured telemetry сохраняет existing OBS-01/OBS-02 tracing contract:
  effective opaque session, standard trace/span/parent identifiers и path без
  query разрешены. Existing ordinary application-log MDC может содержать
  принятые valid inbound traceparent/tracestate; это отдельные raw propagation
  fields, а не новые audit fields или metric labels. В audit и client error bodies
  эти raw strings запрещены. Во всех telemetry records запрещены body/PII
  values/spans, identity/user/groups, credentials, query values и raw exception
  disclosure. Tests отдельно доказывают отсутствие forbidden sentinels и
  сохранение разрешённых correlation fields. Это согласованное уточнение
  сохраняет current tracing, не вводит его redesign.

## План реализации и проверки

Основной public seam - authenticated Chat Completions HTTP через реальные
Armeria gateway/upstream в `RequestInspectionE2eTest`. Public parser/source
и pure marker tests дополняют его там, где нужны precise coordinates,
invalid instructions или управляемая demand; они не заменяют HTTP evidence.

| Компонент / файлы | Изменение |
| --- | --- |
| `policy/config/PolicyConfiguration.kt`, `PolicyConfigurationLoadingTest` | Снять global coverage/shadow-only ограничения, разрешить detected ALLOW/MASK/BLOCK, применить REQUEST clean/error contract |
| `protocol/openai/ChatCompletionsRequestParser.kt` и request result models | Immutable field classification и raw token metadata без новых schema branches |
| `policy/masking/TextMasker.kt`, `policy/domain/ReactionModels.kt`, новый request formatter | Переиспользовать canonical validation/union; request-only shortening без изменения response marker semantics |
| `protocol/openai`, новый request rewrite planner | Validated raw patch plan; reuse/extraction existing JSON coordinate rules без full request byte copy |
| `source/BoundedRequestSource.kt`, `RequestSourceModels.kt`, `BoundedRequestSourceTest` | Owner-bound patched replay, demand, buffer lifetime, exact quota cleanup |
| `gateway/proxy/ShadowInspectionWorkflow.kt`, `ReplayReadyRequest.kt` | Final priority, typed original/masked/reject outcomes, once-only handoff ownership |
| `PiiShadowProxyService.kt`, `RequestBodyFlowAdapters.kt`, existing request header helpers | Correct masked headers и HTTP outcome mapping через VIG-29; existing transport/context/identity сохраняются |
| `ShadowAuditLogger.kt`, workflow/process/telemetry tests | Actual reaction, safe completed-result aggregate, causal publication/absence/privacy evidence |
| `politics.conf.example`, `TestPolicyConfiguration.kt`, `GatewayProcessFixture`, process/OCI/performance consumers | Явно мигрировать REQUEST error reaction; никаких дополнительных mandatory settings |

Paths в таблице относятся к существующим packages под
`src/main/kotlin/io/vigilant` и соответствующим `src/test/kotlin/io/vigilant`;
новые файлы названы как новые, а не как уже существующие. Массовое переименование
`Shadow*` не требуется: KDoc и user-facing docs должны описывать конечное
поведение, без отдельной rename/refactor кампании.

TDD выполняется вертикальными slices по CLAUDE.md: startup contract, policy
BLOCK/technical priority, первый free-text MASK, затем остальные named
classification/shortening/replay/lifecycle cases. Каждый новый observable
behavior сначала получает focused failing test, затем минимальную реализацию;
не писать всю test matrix до первого implementation slice. Current public
seams выше являются согласованной границей для этой работы.

Обязательные команды implementation evidence запускаются последовательно:

```bash
# Focused suites выбираются по текущему TDD slice; process lane отдельно.
./gradlew test -x processTest --tests 'io.vigilant.gateway.proxy.RequestInspectionE2eTest'
./gradlew processTest --tests 'io.vigilant.gateway.PiiShadowProxyProcessTest' --tests 'io.vigilant.gateway.MainTest'
./gradlew installDist
./scripts/oci-smoke-test
./gradlew validateWorkItems
./gradlew build
```

OCI smoke должен реально проверить configured request ALLOW, shortened MASK,
policy BLOCK и structural MASK/BLOCK через mounted policy files и реальный
upstream, плюс old-config startup rejection. Existing fixture/launcher и
versioned image используются повторно; не добавлять альтернативный launcher.
Процесс и OCI не заменяют друг друга. Performance configs мигрируют, но новый
RPS/latency/SLO claim, benchmark suite и обязательный full performance run
в VIG-34 не вводятся.

В implementation change синхронно обновляются README, CLAUDE.md, current
`docs/policies.md`, `docs/openai-chat-completions.md`, `docs/runtime-contract.md`,
`docs/observability.md`, configuration/deployment/coverage docs, owning UML
request sequence/component diagrams и active roadmap frontier. Historical
VIG-12, VIG-32-01 и response leaves сохраняют Done/evidence; добавляются
точные successor pointers, а не переписывается прошлая реализация. Общая
историческая очистка принадлежит [EPIC-36](../epics/epic_36_current_requirements_and_work_item_lifecycle.md). Ready spec не описывается как уже
доступный runtime до выполнения acceptance evidence.

## Открытые решения

Нет блокирующих продуктовых или архитектурных решений. Имена новых internal
declarations и layout небольших helpers выбираются в рамках указанных
responsibility boundaries и canonical-reuse rules.

## Требования

- `MVP-01`: request inspection до upstream forwarding.
- `MVP-03`: `ALLOW`, `MASK`, `BLOCK` reaction semantics.
- `MVP-04`: явно настроенные policies и отсутствие PII inspection без applied
  policy; current расширенный matching contract сохраняется.
- `PROXY-02`: exact-span transformation без изменения остальных данных.
- `PROXY-03`: stable safe error contract.
- `MVP-06`, `OBS-01`, `OBS-02`: safe audit, metrics и tracing evidence.

## Не входит

- Удаление или упрощение current policy engine, URL/model/subject matching,
  overrides, detector registry или future policy capabilities.
- Изменение behavior response source/parser/SSE/enforcement. Минимальное
  извлечение общих JSON/marker helpers допустимо при сохранении response
  contracts и их regression evidence.
- Новый detector, protocol кроме Chat Completions, policy hot reload или
  control plane.
- Audit persistence, WAL, custom logging queue или external Collector.
- Новый performance/SLO claim, общий policy schema redesign, request trailer
  forwarding, compressed request support, HTTP message signing и bulk rename.

## Критерии выполнения

Checklist закрывается только по завершённому implementation evidence.
Каждый использует stimulus, public seam, observable result и independent
oracle соответствующего раздела; named cases и заданные там permutations
обязательны, representative happy path не заменяет finite matrix.

- [x] Selection/startup matrix: нет скрытого PII запуска; empty/disabled/
  unmatched policies разрешены; current matching и overrides сохранены.
- [x] REQUEST reactions: detected ALLOW/MASK/BLOCK, clean ALLOW, error BLOCK;
  invalid startup states и явная migration проверены config и process tests.
- [x] Все structural/free-text field cases и schema-container contrasts
  проходят parser/HTTP evidence без расширения protocol vocabulary.
- [x] Aggregation/priority/overlap/gap matrices подтверждены; technical failure
  превосходит PII BLOCK независимо от порядка fragments/policies.
- [x] Shortening matrix подтверждает exact expected representations,
  non-expansion, immutable canonical metadata и прежний response output.
- [x] Exact raw JSON patches сохраняют untouched bytes и headers по contract;
  invalid plan даёт `503` до upstream, detector/structural parse не повторяются.
- [x] Public patched source replay проходит demand/segment/ownership/race
  matrix; original quota остаётся занятой до terminal output и возвращается
  к baseline на каждом предусмотренном terminal path.
- [x] Real gateway cancellation, peer close и packaged shutdown evidence
  выполнено для actual ALLOW и actual MASK; handoff/replay не повторяются.
- [x] JSONL schema, actual reaction/counts, absence/privacy/logging-failure
  matrices и metrics/tracing evidence проходят через указанные seams.
- [x] Installed process и OCI cases выполнены указанными commands; все
  canonical startup consumers используют совместимые REQUEST policies.
- [x] KDoc/Javadoc обновлены в тех же TDD slices, current docs/UML и work-item
  graph согласованы; required RED/GREEN evidence сохранено,
  `./gradlew validateWorkItems` и финальный `./gradlew build` проходят.

## Ambiguity Report

```text
Goals:        0.00  explicit admin-selected request enforcement
Acceptance:   0.10  finite cases and public evidence contracts specified
Boundaries:   0.05  response behavior and policy redesign excluded
Alternatives: 0.00  product choices and rejected alternatives resolved
Assumptions:  0.10  existing seams extended within explicit ownership contract
Aggregate:    0.05  Implementation completed; required runtime evidence and build passed.
```
