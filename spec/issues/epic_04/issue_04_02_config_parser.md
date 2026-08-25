# VIG-04-02: Strict HOCON parser для `politics.conf`

**Статус:** Done

**Epic:** [EPIC-04](../../epics/epic_04_policy_engine.md)  
**Ветка:** Policy source > strict parser  
**Зависит от:** [VIG-04-01](issue_04_01_domain_contracts.md)  
**Блокирует:** [VIG-04-03](issue_04_03_policy_validation.md), [VIG-04-04](issue_04_04_snapshot_provider.md)  
**Оценка:** 2-4 инженерных дня

## Результат

`PolicyConfigParser` читает HOCON contract EPIC-04, запрещает неизвестные поля
и создаёт parsed policy objects без semantic validation, storage или matching.

## Наблюдаемое поведение

- Поддерживается полный пример `politics.conf` из epic.
- `deadline` по умолчанию равен `50ms`.
- `policies=[]` парсится успешно.
- Unknown fields, malformed duration и отсутствующие syntactic fields дают
  safe error с полем, но без dump всего файла.

## Критерии готовности

- [x] Parser имеет одну ответственность и не читает environment variables.
- [x] Все nested unknown fields отклоняются.
- [x] Parsed result не зависит от порядка HOCON fields.
- [x] Errors не содержат config body или потенциальные secret values.
- [x] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [x] Focused parser tests и `./gradlew build` проходят.

## Не входит

Cross-policy validation, file discovery и application startup.
