# VIG-04-01: Immutable domain contracts policy engine

**Статус:** Done  
**Epic:** [EPIC-04](../../epics/epic_04_policy_engine.md)  
**Ветка:** Domain contracts  
**Зависит от:** нет  
**Блокирует:** все остальные issues EPIC-04  
**Оценка:** 3-4 инженерных дня

## Результат

Transport-neutral domain package содержит immutable `PolicyContext`, `Policy`,
match/reaction models, `Detector`, `DetectionResult`, `Finding`,
`ReactionPlan`, `PolicyResult` и `PolicyDecision` с явными invariants.

## Границы

- Модели отражают нормативные контракты EPIC-04 без HTTP, HOCON, Armeria или
  provider details.
- Engine-owned `PolicyContext` является входным контрактом; EPIC-03 позднее
  реализует его producer.
- Status не выводится из null/empty values.
- Finding не хранит matched text.

## Тестовый seam

Focused constructor/invariant tests из Kotlin и Java caller perspective.

## Критерии готовности

- [x] Все перечисленные domain contracts имеют immutable collections.
- [x] Invalid mixed statuses, spans, confidence и reaction combinations
  отклоняются safe exception без payload.
- [x] `CLEAN`, `DETECTED`, `ERROR` являются явными mutually exclusive states.
- [x] Result models поддерживают deterministic sorting без transport types.
- [x] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [x] Focused tests и `./gradlew build` проходят.

## Не входит

Parsing, matching, detector execution и logging.
