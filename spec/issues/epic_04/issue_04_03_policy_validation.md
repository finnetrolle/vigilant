# VIG-04-03: Semantic validation policy snapshot

**Статус:** Done

**Epic:** [EPIC-04](../../epics/epic_04_policy_engine.md)  
**Ветка:** Policy source > semantic validator  
**Зависит от:** [VIG-04-01](issue_04_01_domain_contracts.md), [VIG-04-02](issue_04_02_config_parser.md)  
**Блокирует:** [VIG-04-04](issue_04_04_snapshot_provider.md)  
**Оценка:** 3-5 инженерных дней

## Результат

`PolicyValidator` проверяет полный parsed snapshot, detector registry metadata,
override graph и все semantic rules раздела «Валидация конфигурации» EPIC-04.

## Тестовый seam

Table-driven pure unit tests: один invalid rule на case и несколько valid
boundary snapshots.

## Критерии готовности

- [x] Покрыты все validation bullets EPIC-04, включая cycles и partial wildcard.
- [x] Duplicate policy/detector IDs и unknown references отклоняются.
- [x] Reaction/disposition/transformation combinations валидируются полностью.
- [x] Validation order детерминирован; один snapshot даёт стабильную ошибку.
- [x] Error указывает policy ID и field, но не печатает полный config.
- [x] Validator не читает файл и не выполняет matching.
- [x] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [x] Focused validation tests и `./gradlew build` проходят.

## Не входит

Runtime detector lookup, provider storage и hot reload.
