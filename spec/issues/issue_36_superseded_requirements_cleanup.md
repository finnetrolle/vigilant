# VIG-36: Очистка superseded требований и архитектурных документов

- **ID:** `VIG-36`
- **Тип:** Issue
- **Статус:** Draft
- **Приоритет:** High
- **Зависит от:** [VIG-20-04](epic_20/issue_20_04_retained_memory_response_contract.md), [VIG-32-02](epic_32/issue_32_02_durable_audit_removal.md), [VIG-35](issue_35_production_identity_mode.md)
- **Блокирует:** нет
- **Оценка:** не оценено

## Цель

Очистить активную normative и runtime documentation от требований завершённых
milestones, которые были заменены текущим MVP, сохранив исторические work item
файлы и проверяемую историю решений.

Issue имеет высокий приоритет, потому что текущие MVP specs, completed epics,
roadmap и runtime docs одновременно описывают разные audit, identity, policy и
response contracts.

## Известный scope инвентаризации

- `spec/ROADMAP.md` и ссылки на достигнутый request-only shadow milestone.
- Completed EPIC-21/22 и durable audit contract, заменяемый VIG-32-02.
- `spec/MINIMUM_AUDIT_TRAIL_CONTRACT.md`.
- `docs/architecture.md`, `docs/runtime-contract.md`,
  `docs/configuration.md`, `docs/deployment.md`, `docs/observability.md` и
  связанные UML sources.
- `docs/requirements-coverage.md` как активная карта current MVP.
- Identity documentation после решения VIG-35.
- Response terminology после VIG-20-04.

## Правило сохранения истории

- Completed epic/issue files не удаляются и не переписываются так, будто их
  исходный milestone никогда не существовал.
- Исторический документ помечается как superseded и ссылается на current
  normative owner.
- Устаревший текст удаляется из активных runtime и deployment документов.
- Registry statuses завершённых work items не меняются без отдельного
  доказательства нарушения completion contract.

## Открытые решения

- Какие документы остаются историческими snapshots, а какие должны описывать
  только current runtime.
- Нужен ли отдельный `spec/history/` index или достаточно superseded banners и
  обратных ссылок.
- Какие durable audit qualification reports остаются release evidence прежнего
  milestone и как исключить их из current MVP claims.
- Как синхронизировать CLAUDE.md после фактического удаления durable runtime,
  не описав будущую реализацию как уже работающую.

## Требования

- Все `MVP-*`, `PERF-*`, `CONC-*`, `PROXY-*` и `OBS-*` requirements должны
  иметь один current normative owner и непротиворечивый coverage status.
- `STAGE-*` и `OUT-*` остаются явно отделены от current MVP и не описываются
  как реализованное runtime behavior.
- Эта documentation issue не меняет фактический implementation status ни
  одного требования без соответствующего dynamic evidence.

## Не входит

- Production code, build task removal или runtime configuration changes.
- Удаление исторических epic/issue/evidence файлов.
- Пересмотр current policy capabilities.
- Реализация VIG-20-04, VIG-32-02 или identity decision VIG-35.

## Критерий готовности задачи

Issue становится `Ready for implementation`, когда завершена таблица
`document -> current owner -> keep/update/supersede`, выполнены зависимости и
для каждого изменяемого UML source назван owning document.

## Предварительные критерии выполнения

- [ ] Каждый активный документ описывает current runtime и явно отличает его от
  approved future MVP work.
- [ ] Superseded completed requirements сохраняют историю и ссылаются на новый
  normative owner.
- [ ] Durable audit, request-only shadow, response memory и identity claims не
  противоречат MVP specs и `docs/requirements-coverage.md`.
- [ ] Все relative links разрешаются, epic/issue statuses остаются согласованы,
  `./gradlew validateWorkItems` и `git diff --check` проходят.

## Ambiguity Report

```text
Goals:        0.10  consistency outcome known
Acceptance:   0.35  inventory table not yet built
Boundaries:   0.10  historical files are explicitly preserved
Alternatives: 0.40  archival presentation remains open
Assumptions:  0.30  dependency decisions may alter active documentation
Aggregate:    0.25  Draft: inventory and dependency outcomes required.
```
