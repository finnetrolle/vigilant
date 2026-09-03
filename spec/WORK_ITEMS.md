# Vigilant: epics и issues

Этот каталог хранит продуктовые требования и исполняемые work items проекта.
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
| [VIG-01A: Benchmark логирования](issues/issue_01A_benchmark.md) | `Done` | завершена | 0 дней осталось |
| [EPIC-02: Быстрый PII-detector](epics/epic_02_fast_pii_detector.md) | `Done` | 16/16 | 0 дней осталось |
| [EPIC-03: Policy context extraction](epics/epic_03_policy_context_extraction.md) | `Done` | 7/7 | 0 дней осталось |
| [EPIC-04: Policy engine](epics/epic_04_policy_engine.md) | `Done` | 11/11 | 0 дней осталось |
| [EPIC-05: v0 hardening](epics/epic_05_v0_hardening.md) | `Done` | 9/9 | 0 дней осталось |
| [EPIC-06: Разбор LLM-сообщений и извлечение payload](epics/epic_06_llm_message_parsing.md) | `In progress` | 3/3 | Published Chat Completions scope завершён; OpenAI Responses не оценён |
| [EPIC-07: Windowed payload processing](epics/epic_07_windowed_payload_processing.md) | `Done` | 2/2 | 0 дней осталось |
| [EPIC-08: Bounded in-memory request source and replay](epics/epic_08_message_spooling_replay.md) | `Done` | 2/2 | 0 дней осталось |
| [EPIC-09: Закрытие архитектурных рисков v0](epics/epic_09_v0_architecture_closure.md) | `Done` | 9/9 | 0 дней осталось |
| [EPIC-10: Повышение качества детерминированного PII-распознавания](epics/epic_10_pii_detection_quality.md) | `Done` | 8/8 | 0 дней осталось |
| [EPIC-20: Atomic in-memory response analysis](epics/epic_20_atomic_in_memory_response_analysis.md) | `In progress` | 1/5 | 13-19 дней; VIG-20-01 и VIG-20-03 готовы независимо |
| [EPIC-21: Post-milestone architecture closure](epics/epic_21_post_milestone_architecture_closure.md) | `Done` | 5/5 | 0 дней осталось |
| [EPIC-22: Durable minimum audit trail](epics/epic_22_durable_minimum_audit_trail.md) | `Done` | 5/5 | 0 дней осталось |
| [VIG-11: Fast PII policy adapter](issues/issue_11_fast_pii_policy_adapter.md) | `Done` | завершена | 0 дней осталось |
| [VIG-12: Global shadow coverage validation](issues/issue_12_global_shadow_coverage_validation.md) | `Done` | завершена | 0 дней осталось |
| [VIG-13: PII shadow request tracer bullet](issues/issue_13_pii_shadow_request_tracer.md) | `Done` | завершена | 0 дней осталось |
| [VIG-14: Strict protocol and inspection-gap outcomes](issues/issue_14_strict_protocol_gap_outcomes.md) | `Done` | завершена | 0 дней осталось |
| [VIG-15: Capacity and cancellation outcomes](issues/issue_15_capacity_cancellation_outcomes.md) | `Done` | завершена | 0 дней осталось |
| [VIG-16: Packaged shadow proxy evidence](issues/issue_16_packaged_shadow_proxy_evidence.md) | `Done` | завершена | 0 дней осталось |
| [VIG-17: Сквозной tracing context и OTLP JSON через stdout](issues/issue_17_request_tracing_stdout_otlp.md) | `Done` | завершена | 0 дней осталось |
| [VIG-18: Inspection load baseline and production report](issues/issue_18_inspection_load_report.md) | `Done` | завершена | 0 дней осталось |
| [VIG-19: Типизированный workflow request-side shadow inspection](issues/issue_19_typed_shadow_inspection_workflow.md) | `Done` | завершена | 0 дней осталось |
| [VIG-26: Универсальное ядро windowed inspection](issues/issue_26_generic_windowing_core.md) | `Done` | завершена | 0 дней осталось |
| [VIG-27: Dummy Bearer identity extractor](issues/issue_27_dummy_identity_extractor.md) | `Done` | завершена | 0 дней осталось |
| [VIG-28: Offline trusted JWT Bearer identity extractor](issues/issue_28_keycloak_jwt_identity_extractor.md) | `Done` | завершена | 0 дней осталось |
| [VIG-29: OpenAI-compatible error contract for enforcement](issues/issue_29_openai_error_contract.md) | `Done` | завершена | 0 дней осталось |
| [VIG-30: External Bearer identity extractor](issues/issue_30_external_identity_extractor.md) | `Draft` | внешний identity contract не определён | не оценено |
| [VIG-31: Cache external identity lookup](issues/issue_31_identity_lookup_cache.md) | `Draft` | cache semantics не определены | не оценено |
| [EPIC-32: Best-effort stdout audit migration](epics/epic_32_best_effort_stdout_audit.md) | `Done` | 2/2 | 0 дней осталось |
| [VIG-33: Availability SLO and operational evidence](issues/issue_33_availability_slo_and_operations.md) | `Draft` | production SLO не определён | не оценено |
| [VIG-34: Request-side PII enforcement](issues/issue_34_request_pii_enforcement.md) | `Draft` | reaction, rewrite и lifecycle contract требуют диалога | не оценено |
| [VIG-35: Выбор production identity mode](issues/issue_35_production_identity_mode.md) | `Draft` | external-only или dual production mode не выбран | не оценено |
| [VIG-36: Очистка superseded требований и архитектурных документов](issues/issue_36_superseded_requirements_cleanup.md) | `Draft` | требуется inventory и завершение contract dependencies | не оценено |

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

### Phase 0: подготовить удаление durable audit

- [x] Декомпозировать [EPIC-32](epics/epic_32_best_effort_stdout_audit.md) на два
  independently reviewable work items размером 1-5 дней:
  - [VIG-32-01](issues/epic_32/issue_32_01_stdout_request_audit_migration.md)
    публикует stdout request audit через один operator-visible logging seam;
  - [VIG-32-02](issues/epic_32/issue_32_02_durable_audit_removal.md) удаляет
    WAL/recovery/handoff и всех runtime, packaging и documentation consumers
    старого audit path.
- [x] Зафиксировать hard dependency: durable-audit removal начинается только
  после готовой stdout migration.
- [x] Перенести все критерии VIG-32 в новые leaves без потери требований,
  добавить estimate/confidence и обновить зависимости VIG-20-02/VIG-34.

### Phase 1: удалить ненужный durable audit

- [x] Реализовать и закрыть
  [VIG-32-01](issues/epic_32/issue_32_01_stdout_request_audit_migration.md).
- [x] Реализовать и закрыть
  [VIG-32-02](issues/epic_32/issue_32_02_durable_audit_removal.md): WAL, segments, recovery,
  Collector handoff, reservation/acknowledgement, audit-driven
  admission/readiness, audit configuration, Docker volume, durability Gradle
  tasks/tests и current runtime docs.
- [x] Подтвердить, что logging failure больше не влияет на HTTP outcome,
  readiness или upstream handoff.

### Phase 2: закрыть готовый standalone frontier

- [x] Реализовать [VIG-29](issues/issue_29_openai_error_contract.md): exact
  OpenAI-compatible status/body matrix, `Retry-After` и отсутствие upstream или
  payload disclosure.

### Phase 3: завершить декомпозицию EPIC-20

- [x] Назначить EPIC-06 единственным owner response JSON/SSE parser contracts.
- [x] Опубликовать combined
  [VIG-06-03](issues/epic_06/issue_06_03_chat_completions_response_parser.md)
  для ordinary JSON, SSE framing и standalone `data: [DONE]` parsing.
- [x] Опубликовать отдельный bounded
  [VIG-20-05](issues/epic_20/issue_20_05_sse_response_enforcement.md) для SSE
  inspection/enforcement.
- [x] Добавить estimates, confidence, hard dependencies, non-goals и один
  основной public test seam каждому leaf.
- [x] Перевести EPIC-20 в `Ready for implementation` только после полного
  дерева обязательных leaves.

### Phase 4: закрыть готовые leaves EPIC-20

- [x] Реализовать
  [VIG-20-04](issues/epic_20/issue_20_04_retained_memory_response_contract.md)
  и синхронизировать retained-memory terminology.
- [ ] После VIG-20-04 реализовать
  [VIG-20-01](issues/epic_20/issue_20_01_retained_memory_response_source.md).
- [ ] Независимо реализовать
  [VIG-20-03](issues/epic_20/issue_20_03_reusable_text_masker.md).

### Phase 5: уточнить и реализовать Draft capabilities

- [ ] Принять human-owned решение
  [VIG-35](issues/issue_35_production_identity_mode.md): external-only или
  external plus offline JWT.
- [ ] После VIG-35 уточнить и реализовать
  [VIG-30](issues/issue_30_external_identity_extractor.md), затем
  [VIG-31](issues/issue_31_identity_lookup_cache.md).
- [ ] Провести отдельный requirements dialogue по
  [VIG-34](issues/issue_34_request_pii_enforcement.md), перевести issue в Ready
  и только затем реализовать request `ALLOW`/`MASK`/`BLOCK`.
- [ ] Закрыть открытые решения
  [VIG-20-02](issues/epic_20/issue_20_02_response_inspection_enforcement.md),
  затем реализовать non-stream response enforcement и опубликованные SSE
  leaves.

### Phase 6: финальная документация и operations

- [ ] После VIG-20-04, VIG-32-02 и VIG-35 уточнить и выполнить
  [VIG-36](issues/issue_36_superseded_requirements_cleanup.md), сохранив
  исторические work items и evidence.
- [ ] После стабилизации identity, enforcement и observability уточнить
  [VIG-33](issues/issue_33_availability_slo_and_operations.md).

Текущий следующий шаг: реализовать VIG-20-01; VIG-20-03 доступна независимо.
VIG-29 завершён: exact error matrix реализована без подключения enforcement.

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
