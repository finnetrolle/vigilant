# EPIC-36: Действующие требования и жизненный цикл work items

**ID:** `EPIC-36`
**Тип:** Epic
**Статус:** Ready for implementation
**Приоритет:** High
**Оценка:** 12-21 инженерный день суммарно; confidence Medium
**Связанные требования:** `MVP-01..07`, `PERF-01..03`, `CONC-01..04`, `PROXY-01..03`, `OBS-01..02`, `ST1-01..23`, `OUT-01..13`

## Результат

В рабочей ветке остаются согласованные действующие требования, документация
фактического runtime и применимое evidence. Requirements доступны без открытия
завершённых задач. Epics и issues описывают только незавершённую работу.
История прежних contracts, milestones и реализации остаётся в Git.

Это миграция документации и work-item convention. Она не реализует новые
product capabilities и не превращает отсутствие старого документа в
доказательство готовности runtime.

## Принятые решения

- Постоянные требования определяют наблюдаемое поведение, ограничения,
  ошибки и invariants. Существующие MVP/NFR/Stage 1/non-goal документы остаются
  верхним уровнем со stable IDs; подробные contracts живут в
  `spec/requirements/`.
- Один behavioral clause имеет одного normative owner. Остальные документы
  ссылаются на него; runtime/architecture docs объясняют фактическую реализацию.
  Coverage явно показывает target/runtime/evidence gaps.
- Epic временно описывает желаемое изменение, его границы и критерии.
  Issue временно описывает implementation increment и его проверку.
- После выполнения и обязательных checks актуальные требования из epic
  **и из leaf/standalone issue** переносятся к постоянным owners. Implementation
  details переходят в runtime/architecture docs, если нужны для понимания системы.
- Выполненная issue удаляется вместе с registry/checklist entries. Полностью
  завершённый epic после переноса своего контракта также удаляется.
  Минимальные historical records, superseded wrappers и `spec/history/`
  не создаются. Git history не переписывается.
- Hard dependencies остаются только на незавершённую работу. Выполненная
  предпосылка после удаления task становится ссылкой на действующий
  requirement/capability; отсутствие файла никогда не означает, что она готова.
- Правила, заменённые текущим согласованным контрактом, не копируются в
  permanent requirements. Нельзя переносить старую семантику только из-за
  совпадения прежнего и нынешнего requirement ID.
- Product targets не ослабляются под текущий код. Неподтверждённый target
  отражается как gap. Старое измерение не становится новым runtime evidence
  после переименования документа.
- Каждый migration leaf переносит свой contract, обновляет references и
  удаляет свой source batch одним consistency change. Источник с новой
  незавершённой работой не удаляется по старому manifest без повторной сверки.
- Нужные details не сохраняются через полную копию issue в evidence report.
  Временная clause review matrix используется для проверки переноса, затем
  удаляется из working tree.
- Тот же completion protocol применяется к leaves EPIC-36 и самому EPIC-36.
  Они не становятся исключением из правила удаления завершённой работы.

## Постоянные owners

| Destination | Ответственность | Migration owner |
|---|---|---|
| `spec/requirements/README.md` | Индекс contracts и ownership | VIG-36-01, затем каждый domain leaf |
| `spec/requirements/fast-pii.md` | Recognition, findings, ordering, quality | VIG-36-02 |
| `spec/requirements/windowed-inspection.md` | Generic windowing, overlap, offsets, cancellation | VIG-36-02 |
| `spec/requirements/identity-and-context.md` | Bearer modes, cache, context и handoff | VIG-36-03 |
| `spec/requirements/chat-completions-protocol.md` | Descriptor, field maps, JSON/SSE parsing и terminal states | VIG-36-04 |
| `spec/requirements/http-gateway.md` | Exact error matrix, transport, headers, health/shutdown | VIG-36-04: errors; VIG-36-06: остальные clauses |
| `spec/requirements/policy-engine.md` | Startup schema, matching, overrides, execution и aggregation | VIG-36-05 |
| `spec/requirements/request-source.md` | Bounded ingest/read/replay, quota и ownership | VIG-36-05 |
| `spec/requirements/request-enforcement.md` | REQUEST selection/reactions/masking/handoff | VIG-36-05 |
| `spec/requirements/response-enforcement.md` | Retention, JSON/SSE reactions, masking, disclosure | VIG-36-06 |
| `spec/requirements/observability.md` | Logs, audit pairs, metrics/traces, privacy и stdout ownership | VIG-36-07; identity clauses VIG-36-03 |

Shared file не создаёт hard dependency сам по себе: владелец named section
переносит её без перезаписи чужих clauses. До миграции соседней области
используются существующие разрешающиеся ссылки на current overview/runtime
docs; ссылок на ещё не созданные файлы и пустых contract placeholders нет.

Current IDs: ровно `MVP-01..07`, `PERF-01..03`, `CONC-01..04`,
`PROXY-01..03`, `OBS-01..02`, `ST1-01..23`, `OUT-01..13`: 55.
Stage 1 prefix - `ST1`. Task IDs не становятся новыми requirement IDs.
Global SLOs остаются в MVP NFR; commands и test-suite methodology - в
`docs/development.md` и project guide.

## Карта scope

```text
Current requirements и временный backlog
+-- Completion protocol и validation
+-- Поведение продукта
|   +-- PII/windowing
|   +-- identity/context/cache
|   +-- protocol/errors
|   +-- policy/request source/request enforcement
|   +-- response/transport/lifecycle
|   +-- observability
+-- Финальная contract cleanup
    +-- verification methodology
    +-- obsolete reports/navigation
    +-- completeness, references и active frontier
```

## Дочерние issues

- [ ] [VIG-36-01: Закрытие задач с переносом требований](../issues/epic_36/issue_36_01_completion_workflow.md) - `Ready for implementation`
- [ ] [VIG-36-02: Постоянные требования PII и windowing](../issues/epic_36/issue_36_02_pii_windowing_contracts.md) - `Ready for implementation`
- [ ] [VIG-36-03: Постоянный контракт identity и context](../issues/epic_36/issue_36_03_identity_context_contract.md) - `Ready for implementation`
- [ ] [VIG-36-04: Постоянные контракты протокола и HTTP-ошибок](../issues/epic_36/issue_36_04_protocol_error_contracts.md) - `Ready for implementation`
- [ ] [VIG-36-05: Постоянные требования request enforcement](../issues/epic_36/issue_36_05_request_enforcement_contracts.md) - `Ready for implementation`
- [ ] [VIG-36-06: Постоянные требования response и gateway](../issues/epic_36/issue_36_06_response_gateway_contracts.md) - `Ready for implementation`
- [ ] [VIG-36-07: Постоянный контракт observability](../issues/epic_36/issue_36_07_observability_contract.md) - `Ready for implementation`
- [ ] [VIG-36-08: Завершение миграции каталога требований](../issues/epic_36/issue_36_08_current_catalog_closure.md) - `Ready for implementation`

## Delivery graph и estimates

| Issue | Mode | Hard blockers | Оценка | Confidence |
|---|---|---|---|---|
| VIG-36-01 | expand | нет | 1-2 инженерных дня | High |
| VIG-36-02 | migrate | VIG-36-01 | 2-3 инженерных дня | Medium |
| VIG-36-03 | migrate | VIG-36-01 | 1-2 инженерных дня | Medium |
| VIG-36-04 | migrate | VIG-36-01 | 1-2 инженерных дня | Medium |
| VIG-36-05 | migrate | VIG-36-01, VIG-36-04 | 2-4 инженерных дня | Medium |
| VIG-36-06 | migrate | VIG-36-04, VIG-36-05 | 2-3 инженерных дня | Medium |
| VIG-36-07 | migrate | VIG-36-01 | 1-2 инженерных дня | Medium |
| VIG-36-08 | contract | VIG-36-02, VIG-36-03, VIG-36-04, VIG-36-05, VIG-36-06, VIG-36-07 | 2-3 инженерных дня | Medium |

Первый frontier: VIG-36-01. После него независимы VIG-36-02/03/04/07.
VIG-36-05 требует protocol field maps и error matrix VIG-36-04.
VIG-36-06 использует protocol owner VIG-36-04 и policy owner VIG-36-05.
VIG-36-08 закрывает shared consumers только после всех domain migrations.

Мягкий порядок: VIG-36-07 перед остальными domain migrations, чтобы
observability/privacy owner раньше был доступен для cross-links. Это не
формальная блокировка. Отсутствующие после completion dependencies заменяются
ссылками на реализованные prerequisites по VIG-36-01.

## Baseline и coverage миграции

Frozen baseline: Git `ca8ec90`. На публикацию 108 issues имеют `Done`, 13
epics имеют `Done`; это 121 файл и 13 889 строк baseline. Полные path manifests
принадлежат leaves, а не дублируются в epic.

| Leaf | Issues | Epics |
|---|---:|---:|
| VIG-36-02 | 28 | 3 |
| VIG-36-03 | 12 | 1 |
| VIG-36-04 | 5 | 0 |
| VIG-36-05 | 19 | 2 |
| VIG-36-06 | 23 | 3 |
| VIG-36-07 | 10 | 2 |
| VIG-36-08 | 11 | 2 |
| Итого | 108 | 13 |

Каждый baseline source назначен ровно одному removal owner. Другие leaves
могут читать его и переносить собственные sections, но не удаляют чужой source.
EPIC-21 удаляет VIG-36-08 после завершения audit branch VIG-36-07.

## Проверка полноты переноса

Для каждого source batch migration leaf строит временную review matrix
`source section/clause -> disposition -> destination anchor -> evidence`.
Dispositions: current requirement, implementation detail, verification method,
obsolete contract, planning/history. Каждый contract-bearing clause получает
обоснованную disposition, а каждый current clause - одного permanent owner.

Quantified requirement сохраняет каждый именованный case: states, positions,
ordering, content classes, boundary values и terminal outcomes. Несколько
примеров или наличие одного heading не доказывает перенос полной матрицы.

Основной public seam - путь от README/requirements index к нормативному rule,
runtime description и coverage/evidence. Independent oracle - исходный clause
из baseline и согласованный superseding contract, сверенные с source/tests.
Удалённый файл и зелёный link checker сами по себе не доказывают полноту.

## Ограничения и особые случаи

- EPIC-06 не закрывается автоматически. VIG-36-04 переносит Chat Completions
  scope и оставляет только future OpenAI Responses work вне текущего MVP.
  Реализация Responses, Realtime и Batch не входит в epic.
- VIG-33 остаётся `Draft`: VIG-36-08 исправляет context, но не выбирает SLI/SLO.
- Required request targets `16 MiB` text / `20 MiB` raw и runtime default
  `8 MiB` не уравниваются редакторской заменой числа. PERF-01/02 enforcement
  profile не считается подтверждённым прежними bypass/shadow reports.
- Исторические review/durability/load/resource reports удаляются по ownership
  leaves. Их current verification methods, если ещё нужны, переходят в
  development docs без объявления старого результата новым evidence.
- Применимые observations `docs/request-enforcement-evidence.md` сохраняются;
  VIG-36-08 убирает obsolete task/history narrative и обновляет current
  references, не меняя чисел, измерений и наблюдённых исходов.
- Старые слова в production symbols (например, `Shadow`) не являются
  основанием для rename. Комментарий с task ID не является сам по себе
  альтернативной спецификацией.
- `.papercuts.jsonl`, generated reports и CHANGELOG не являются batch
  исторической documentation cleanup; их содержимое не редактируется.

## Не входит

- Изменение production gateway/policy/detector behavior, config/defaults,
  новых HTTP outcomes, detector capabilities или product SLOs.
- Новый runtime evidence, benchmark recalculation, OCI qualification,
  load testing, изменения или удаление runtime/build tasks.
- Архив внутри working tree, переписывание Git history или повторная реализация
  already completed functionality.
- Универсальная documentation platform, новый tracker, plugin/skill migration.
  Разрешены только нужные work-item validation/tests и project guide changes.

## Критерии готовности epic

- [ ] Каждый из 55 current IDs имеет одного top-level owner, связанные
  detailed clauses и честный coverage/evidence status; future и out-of-scope
  требования не описаны как реализованные.
- [ ] Baseline batches leaves покрывают все 121 completed files без пропусков
  и двойного удаления; нужный current contract перенесён и проверен.
- [ ] Completed tasks, obsolete contracts/reviews/reports и historical navigation
  отсутствуют в working tree. EPIC-36/leaves завершаются по тому же protocol.
- [ ] Remaining work items, requirements, runtime docs и UML согласованы,
  не содержат dangling local paths/anchors/dependencies или дублирующих owners.
- [ ] Все leaf evidence contracts выполнены, required focused tooling checks
  и `validateWorkItems` GREEN; ни один coverage claim не повышен без evidence.
- [ ] Production behavior и scope сохранены; source changes ограничены
  согласованными work-item tools/tests и документацией.

## Ambiguity Report

```text
Goals:        0.0   permanent requirements and temporary backlog agreed
Acceptance:   0.25  finite leaf manifests/cases; semantic transfer needs review
Boundaries:   0.0   runtime changes and archival copies excluded
Alternatives: 0.0   lifecycle and owner layout selected by user
Assumptions:  0.25  baseline verified; future drift requires recheck
Aggregate:    0.10  Ready for implementation.
```
