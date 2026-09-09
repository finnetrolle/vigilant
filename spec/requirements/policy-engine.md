# Policy engine

Нормативный owner startup policy schema, выбора политик, выполнения detectors
и transport-neutral aggregation для [MVP-03 и MVP-04](../MVP_FUNCTIONS.md#mvp-03-реакции-policy)
и [PERF-03](../MVP_NON_FUNCTIONAL_REQUIREMENTS.md#perf-03-policy-deadline).
Формирование `PolicyContext` принадлежит
[identity/context](identity-and-context.md#assembly-и-handoff), protocol fields
принадлежат [Chat Completions](chat-completions-protocol.md), а применение
REQUEST результата принадлежит [request enforcement](request-enforcement.md).
Runtime composition и operator examples описаны в
[policies guide](../../docs/policies.md).

## Domain contracts

Engine принимает immutable `PolicyContext` и один decoded text payload. Context
содержит `url`, `model`, `phase`, optional `user` и ordered unique `groups`.
Engine не разбирает HTTP, protocol body или events, не получает raw Bearer и не
изменяет payload.

Policy, detector и decision contracts используют явные типы и immutable
collections. Отсутствие, `null` и empty collection не кодируют business status.
Stable identifiers policy, version, detector, finding type и subject непусты.

Один finding содержит exclusive UTF-8 span `[startUtf8, endUtf8)`, type и safe
metadata. Matched text в finding не хранится. Span обязан удовлетворять
`0 <= startUtf8 < endUtf8 <= payloadUtf8Size`, а обе границы совпадают с
границами UTF-8 code points.

## Startup snapshot and schema

Policy file обязателен. `VIGILANT_POLITICS_CONFIG` выбирает explicit path,
иначе используется `./politics.conf`. File читается и валидируется один раз до
старта server. Missing, unreadable или invalid file завершает процесс с safe
stderr и exit code `2`. Hot reload, hidden default policy и provider-side
filtering отсутствуют. Явный `policies = []` допустим.

Strict parser запрещает unknown fields на любом уровне, HOCON `include` и
подстановку внешних system properties. Field order значения не имеет. Error
называет поле и безопасную причину, но не выводит весь config, source value,
credential или payload.

Каждая policy имеет следующую полную schema:

| Field | Contract |
|---|---|
| `id` | Обязательный непустой unique ID внутри snapshot |
| `version` | Обязательная непустая version; одновременно одна version каждого ID |
| `enabled` | Обязательный boolean; disabled policy остаётся в snapshot, но не участвует в selection |
| `match.url` | Exact case-insensitive value или полное `*` |
| `match.model` | Exact case-insensitive value или полное `*` |
| `match.phase` | Ровно `REQUEST` или `RESPONSE`; wildcard запрещён |
| `match.subject.type` | `USER`, `GROUP` или `*` |
| `match.subject.id` | Exact case-insensitive value или допустимый полный `*` |
| `detectors` | Непустой список unique известных detector IDs |
| `deadline` | Positive duration на весь detector set policy; default `50ms` |
| `reactions.detected` | Обязательные disposition и transformations |
| `reactions.clean` | Обязательные disposition и пустые transformations |
| `reactions.error` | Обязательные disposition и пустые transformations |
| `overrides` | Список unique policy IDs, не version IDs |

Validated snapshot и все nested collections immutable. Startup file provider
возвращает один и тот же complete snapshot без I/O, parsing, matching или hot
reload. Provider contract остаётся suspend source полного списка policies, а
каждый `evaluate` использует полученный snapshot до своего terminal outcome.

## Validation

Startup отклоняет snapshot при любом из следующих состояний:

- обязательное поле отсутствует, имеет неверный тип или неизвестное sibling
  field присутствует;
- `id` или `version` пусты, policy ID повторяется;
- detector ID неизвестен или повторяется внутри policy, либо `detectors` пуст;
- `overrides` содержит unknown ID, self-reference, duplicate или cycle;
- URL, model или subject содержат partial wildcard, glob или regex;
- phase отличается от `REQUEST`/`RESPONSE` либо содержит wildcard;
- subject type отличается от `USER`/`GROUP`/`*`, subject ID отсутствует или
  `type=*` соединён с exact ID;
- deadline неположительный;
- reaction state отсутствует, disposition отличается от `ALLOW`/`BLOCK`,
  transformation отличается от `MASK`, либо `BLOCK`, `clean` или `error`
  содержит transformations.

Validation order детерминирован: один и тот же snapshot даёт одну и ту же
первую safe ошибку. Проверка охватывает enabled и disabled policies до
selection. Исполняемый REQUEST path дополнительно требует
[clean ALLOW и error BLOCK](request-enforcement.md#request-reactions-and-priority)
даже у disabled или overridden policies. Это не сужает transport-neutral
domain model и RESPONSE-specific contract.

`REMOVE`, `REQUIRE_APPROVAL`, `ESCALATE`, retry, reask, regenerate,
`STOP_AGENT`, shadow reaction и detector parameters не входят в текущую schema.

## Selection and overrides

Selection выполняется для каждого evaluation в следующем порядке:

1. получить complete immutable snapshot;
2. исключить disabled policies;
3. сопоставить URL, model, phase и subject;
4. одновременно применить overrides;
5. отсортировать matched, overridden и applied sets по policy ID.

Сравнения locale-independent и case-insensitive. Wildcard распознаётся только
как целое значение `*`. URL normalization, model extraction и identity
normalization выполняются до engine.

Subject semantics:

- `USER` с exact ID требует равенства `PolicyContext.user`;
- `GROUP` с exact ID совпадает, если ID присутствует в `groups`;
- `USER/*` требует определённого user;
- `GROUP/*` требует хотя бы одну group;
- `*/*` совпадает с любым context, включая отсутствие identity.

User policy не отменяет group policy неявно. Overrides действуют только от
matched enabled policies и разрешаются одновременно:

```text
matched = all matched enabled policies
overriddenIds = union(matched.overrides)
applied = matched - overriddenIds
```

Для `A overrides B`, `B overrides C`, когда совпали A/B/C, overridden set
содержит B и C, а applied set содержит A. Disabled или unmatched source
override не действует. Provider order не меняет результат.

Обязательные selection states: explicit empty snapshot, disabled-only,
unmatched URL/model/USER/GROUP, selected policy, response-only/request-only
phase и matched override. Empty applied set возвращает ALLOW без detector task.

## Detector contract

Detector получает только один payload и возвращает ровно один explicit result:

- `CLEAN` не содержит findings или error;
- `DETECTED` содержит минимум один valid finding и не содержит error;
- `ERROR` содержит stable error code и safe message, но не findings;
- mixed detected/error state запрещён.

Unknown detector ID, invalid status/content/span или unexpected exception
нормализуются в stable ERROR, включая отдельный
`INVALID_DETECTOR_RESULT`. Cancellation остаётся cancellation и не
маскируется под detector error. Raw exception, payload, findings и credentials
не записываются в safe result или log.

## Execution and deadlines

Для одного payload одинаковый detector ID запускается ровно один раз для всех
applied policies. Разные detector IDs стартуют параллельно. Completion order
не меняет decision, а no-match не создаёт task.

Deadline каждой policy начинается после snapshot/selection при запуске её
detector set. Policies с разными deadlines независимо ожидают общий execution.
Timeout короткой policy создаёт только для неё
`ERROR/POLICY_DEADLINE_EXCEEDED`; shared detector продолжает работу, пока он
нужен более длинной policy. Unfinished execution отменяется после ухода
последнего consumer. External cancellation отменяет все ожидания и executions.
Partial detector results не становятся successful clean result.

Для одной policy `detected` применяется к каждому DETECTED, `error` к каждому
ERROR, включая deadline, а `clean` только когда все detectors вернули CLEAN.
Если detectors одной policy одновременно дали DETECTED и ERROR, применяются
обе reactions. Отдельный inferred policy status не создаётся.

## Reaction and decision

Transport-neutral plan имеет disposition `ALLOW|BLOCK` и ordered immutable
`MaskingInstruction` values. MASK допустим только для detected reaction с
ALLOW; engine создаёт instructions из уже полученных findings и не изменяет
payload.

Aggregation выполняет следующие правила:

- любой applied BLOCK сильнее ALLOW и MASK; executable transformations в
  BLOCK plan отсутствуют;
- без BLOCK instructions всех selected MASK policies объединяются;
- ALLOW findings не создают и не расширяют mask ranges;
- duplicates удаляются;
- overlapping и adjacent UTF-8 spans объединяются;
- одинаковый marker union сохраняет marker, conflict использует
  `[PII_MASKED]`;
- input/config/completion order не меняет plan.

Внутри одного payload фактически сформированный BLOCK завершает evaluation
досрочно; ALLOW не завершает его до outcomes остальных policies. Executions
без consumers отменяются. Нормализованный BLOCK decision сохраняет только
policy results с реально применённой BLOCK reaction и соответствующие
detector results, чтобы timing уже завершившихся ALLOW outcomes не менял
serialized result.

`PolicyDecision` содержит reaction plan, sorted matched/overridden/applied
policy references, policy results, detector results и duration. При ALLOW он
содержит все завершённые results. Все collections immutable и стабильно
сортируются по policy/detector ID.

Detector error наблюдается не более одного раза на actual invocation с sorted
affected policies. Policy deadline наблюдается один раз с policy ID/version,
deadline и sorted unfinished detectors. Точные stdout fields и privacy
принадлежат
[observability contract](observability.md#operational-application-events);
они не влияют на decision.

## Boundaries

Policy engine не владеет HTTP parsing/error bodies, request/response source,
identity extraction, protocol locators, actual source mutation, audit storage,
provider timeout, DB/control plane, hot reload, policy priority, partial
wildcards, detector dependency graph или retry.

## Conformance

Pure parser/validator/selector/executor/engine tests проверяют все validation,
matching, override, shared-deadline, cancellation, fail-fast, ordering и
aggregation cases через immutable public contracts и controlled detectors.
REQUEST integration и exact wire outcomes проверяются отдельно по
[request enforcement contract](request-enforcement.md#conformance).
[Coverage](../../docs/requirements-coverage.md#policy-request-source-and-request-enforcement)
отделяет target от существующих source/test observations и новых dynamic runs.
