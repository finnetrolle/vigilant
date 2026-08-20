# VIG-04-04: Immutable provider snapshot и startup configuration

**Статус:** Ready for implementation  
**Epic:** [EPIC-04](../../epics/epic_04_policy_engine.md)  
**Ветка:** Policy source > immutable startup snapshot  
**Зависит от:** [VIG-04-02](issue_04_02_config_parser.md), [VIG-04-03](issue_04_03_policy_validation.md)  
**Оценка:** 3-4 инженерных дня

## Результат

Startup загружает обязательный `politics.conf`, валидирует его один раз и
передаёт immutable snapshot в `DummyPolicyProvider`. Каждый `getPolicies()`
возвращает тот же snapshot без повторного I/O или parsing.

## Наблюдаемое поведение

- `VIGILANT_POLITICS_CONFIG` имеет приоритет; default `./politics.conf`.
- Missing/invalid file завершает startup с safe stderr message и exit code `2`.
- Явный `policies=[]` разрешён.
- Provider не фильтрует policies и не выполняет hot reload.

## Критерии готовности

- [ ] File resolution и startup failure покрыты direct configuration tests.
- [ ] Snapshot и вложенные collections immutable.
- [ ] Provider contract остаётся `suspend fun getPolicies(): List<Policy>`.
- [ ] Metro/startup changes минимальны и не затрагивают proxy data path.
- [ ] Existing configuration precedence не ломается.
- [ ] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [ ] Focused tests и `./gradlew build` проходят.

## Не входит

Hot reload, DB provider, provider timeout и runtime filtering.
