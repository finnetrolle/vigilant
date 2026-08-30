# Epic 21: Post-milestone architecture closure

**ID:** `EPIC-21`
**Тип:** Epic
**Статус:** In progress
**Приоритет:** High
**Суммарная оценка:** 2-4 инженерных дня осталось; 7-11 завершены
**Связанные требования:** `MVP-18`, `MVP-19`, `CONC-01`, `CONC-03`, `PROXY-01`, production milestone safety gates

## Контекст

Источник epic - [архитектурное ревью от 2026-08-29](../../docs/architecture-review-2026-08-29.md).
Ревью подтвердило готовность основного request-side shadow path, но обнаружило
один незакрытый MVP contract, одну слишком широкую resource claim, два
недетерминированных verification seams и устаревший roadmap frontier.

Epic не переоткрывает завершённые implementation epics и не добавляет
response inspection, enforcement, новые protocols или production fixes без
RED evidence.

## Целевой результат

Audit и resource boundaries имеют честный нормативный contract и
воспроизводимое evidence, известные test races получают deterministic
observation seams, а roadmap точно отделяет достигнутый 64 KiB shadow baseline
от ещё незакрытых production и MVP capabilities.

## Карта декомпозиции

```text
EPIC-21 post-milestone architecture closure
├── Audit governance
│   └── minimum mandatory audit-trail contract
├── Inspection resource evidence
│   └── max-shape packaged-process qualification
├── Verification determinism
│   ├── stable upstream-error observation
│   └── streaming-before-final-chunk observation barrier
└── Documentation consistency
    └── roadmap and frontier reconciliation
```

## Дочерние issues

- [x] [VIG-21-01: Контракт минимального обязательного audit trail](../issues/epic_21/issue_21_01_minimum_audit_trail_contract.md) - `Done`
- [x] [VIG-21-02: Qualification граничных request inspection shapes](../issues/epic_21/issue_21_02_adversarial_inspection_resource_qualification.md) - `Done`
- [x] [VIG-21-03: Детерминированный upstream-error evidence](../issues/epic_21/issue_21_03_upstream_error_test_determinism.md) - `Done`
- [ ] [VIG-21-04: Детерминированный streaming evidence](../issues/epic_21/issue_21_04_streaming_evidence_determinism.md) - `Ready for implementation`
- [ ] [VIG-21-05: Синхронизация roadmap и repository frontier](../issues/epic_21/issue_21_05_roadmap_frontier_reconciliation.md) - `Ready for implementation`

VIG-21-01 выбрал application-owned WAL contract и опубликовал отдельный
production [EPIC-22](epic_22_durable_minimum_audit_trail.md), не расширяя scope
EPIC-21. VIG-21-02 опубликовал max-shape resource qualification без изменения
production code. VIG-21-03 заменил upstream-error race на causal test-only
evidence без изменения production code. VIG-21-04 остаётся следующей
независимой verification ветвью.
VIG-21-05 зависит от опубликованных результатов VIG-21-01..04, потому что
документация не должна заранее объявлять audit или resource closure.

## Требования

- Нормативный [audit contract](../MINIMUM_AUDIT_TRAIL_CONTRACT.md)
  различает safe event contents, application boundary acceptance, durable
  retention и external delivery.
- Contract сохраняет OUT-06: собственное observability storage или SIEM не
  добавляется как скрытый scope.
- Packaged-process qualification покрывает max per-request bytes,
  fragment-dense, gap-dense и concurrent accepted requests, а не только один
  `64 KiB` fragment.
- Test-only issues не меняют production behavior. Подтверждённый runtime defect
  получает отдельную RED-first implementation issue.
- Streaming evidence использует causal synchronization, а не cross-thread
  timestamps или широкие sleep assertions.
- Roadmap claims содержат точный profile, date и remaining frontier.

## Не входит

- Выбор audit durability mechanism внутри этого parent epic без VIG-21-01.
- Реализация audit storage/delivery до отдельной готовой implementation issue.
- Изменение parser field map, fragment independence или policy semantics.
- Response/SSE inspection, disk spill и EPIC-20 decisions.
- `BLOCK`, `MASK`, `REMOVE`, новые PII types или новые OpenAI APIs.
- Изменение source limits или load profile только ради искусственного PASS.

## Критерии готовности

- VIG-21-01..05 имеют status `Done`, а checklist и `WORK_ITEMS.md` обновлены в
  тех же change sets.
- Audit contract публикует independently grabbable implementation leaves либо
  явно фиксирует human-owned blocker без выдуманного default.
- Max-shape qualification имеет воспроизводимую команду, fixed environment,
  exact HTTP/audit/resource outcomes и не использует production payload.
- Оба known test races либо воспроизводятся и исправляются, либо закрываются
  опубликованным bounded evidence без молчаливого удаления assertions.
- Roadmap больше не называет завершённую VIG-01A текущим frontier и не расширяет
  claims за пределы фактического evidence.
- `./gradlew build` и `./gradlew validateWorkItems` проходят.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.05  review findings и observable closure заданы
  Acceptance:   0.15  каждый leaf имеет public evidence seam
  Boundaries:   0.05  production fixes и future protocol scope исключены
  Alternatives: 0.30  audit mechanism выбирается отдельным contract leaf
  Assumptions:  0.20  test-only qualification может открыть новый defect
  Aggregate:    0.15  below threshold (0.2 spec)
```
