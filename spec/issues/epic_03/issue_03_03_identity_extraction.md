# VIG-03-03: Настраиваемое identity extraction

**Статус:** Draft  
**Epic:** [EPIC-03](../../epics/epic_03_policy_context_extraction.md)  
**Ветка:** Identity > configurable extraction  
**Зависит от:** [VIG-03-01](issue_03_01_context_contract.md)  
**Блокирует:** [VIG-03-06](issue_03_06_security_e2e.md)  
**Оценка после уточнения:** 3-5 инженерных дней

## Результат

Config-driven extractor получает только разрешённые identity sources,
возвращает normalized user/groups и отмечает служебные headers для удаления
перед upstream forwarding.

## Требует решения VIG-03-01

- Config schema и validation.
- Header, Basic Authentication или оба источника.
- Precedence при нескольких источниках.
- Формат groups и escaping.
- Trusted ingress behavior при подмене headers клиентом.

## Нормативные ограничения

- Header names не зашиты в production code.
- Basic password и raw Authorization value не сохраняются.
- Ошибки и логи не содержат credentials или исходные identity headers.
- External directory lookup отсутствует.

## Критерии готовности после уточнения

- [ ] Strict config validation отклоняет unknown/contradictory settings.
- [ ] Все поддерживаемые sources дают одинаковый normalized contract.
- [ ] Duplicate/missing/malformed identity имеет явную семантику.
- [ ] Extractor возвращает точный набор headers для upstream stripping.
- [ ] Security tests используют unique sentinels для всех credential values.
- [ ] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [ ] Focused tests и `./gradlew test` проходят.

## Не входит

Authentication, authorization и проверка пользователя во внешнем каталоге.
