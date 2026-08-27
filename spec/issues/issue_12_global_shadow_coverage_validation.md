# VIG-12: Global shadow coverage validation

- **ID:** `VIG-12`
- **Тип:** Issue
- **Статус:** Done
- **Приоритет:** High
- **Зависит от:** [EPIC-04](../epics/epic_04_policy_engine.md)
- **Блокирует:** [VIG-13](issue_13_pii_shadow_request_tracer.md)
- **Оценка:** 2-3 инженерных дня
- **Уверенность:** High

## Результат

Startup принимает policy snapshot только когда он содержит effective enabled
global `REQUEST` coverage policy с `url=*`, `model=*`, `subject=ANY` и detector
`fast-pii`. Все reactions первого increment имеют `ALLOW` без transformations.

## Public seam

Configuration tests вызывают `loadPolicySnapshot` с real temporary HOCON file.
Process tests вызывают packaged `MainKt` и наблюдают exit code `2` и safe stderr.

## Критерии готовности

- [x] Валидная global shadow policy создаёт immutable startup snapshot.
- [x] Missing, disabled, overridden или non-global coverage отклоняется.
- [x] Любая non-ALLOW reaction или transformation отклоняется до server start.
- [x] Error сообщает только stable field/reason без policy source или secret.
- [x] Example и test policy configuration содержат valid coverage.
- [x] Focused config/process tests и `./gradlew build` проходят.

## Не входит

Hidden default policy, hot reload, enforcement reactions и detector execution.
