# VIG-03-03: Настраиваемое identity extraction

**Статус:** Done
**Epic:** [EPIC-03](../../epics/epic_03_policy_context_extraction.md)  
**Ветка:** Identity > configurable extraction  
**Зависит от:** [VIG-03-01](issue_03_01_context_contract.md)  
**Блокирует:** [VIG-03-06](issue_03_06_security_e2e.md)  
**Оценка:** 3-5 инженерных дней

## Результат

Эта historical implementation была полностью заменена VIG-27. Current runtime
имеет отдельный development/test-only Dummy Bearer extractor, сохраняет общий
`NormalizedIdentity` contract и передаёт accepted Authorization upstream
unchanged. Детальные active acceptance rules принадлежат VIG-27.

## Current disposition

- Legacy source modes, configuration, extractor code and header-strip behavior
  удалены VIG-27 без compatibility aliases.
- Generic normalized identity, policy matching и request-to-response handoff
  остаются active contracts.
- Real Bearer authentication принадлежит последующим implementation issues.

## Нормативные ограничения

- Raw Bearer token не сохраняется.
- Ошибки и логи не содержат credentials или исходные Authorization values.
- External directory lookup отсутствует.

## Критерии приёмки

- [x] Strict config validation отклоняет unknown/contradictory settings.
- [x] Current Dummy source даёт generic normalized contract.
- [x] Duplicate/missing/malformed identity имеет явную семантику.
- [x] Accepted Authorization сохраняется для upstream forwarding.
- [x] Security tests используют unique sentinels для всех credential values.
- [x] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [x] Focused tests и `./gradlew test` проходят.

## Не входит

Authentication, authorization и проверка пользователя во внешнем каталоге.
