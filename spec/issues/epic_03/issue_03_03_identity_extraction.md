# VIG-03-03: Настраиваемое identity extraction

**Статус:** Done
**Epic:** [EPIC-03](../../epics/epic_03_policy_context_extraction.md)  
**Ветка:** Identity > configurable extraction  
**Зависит от:** [VIG-03-01](issue_03_01_context_contract.md)  
**Блокирует:** [VIG-03-06](issue_03_06_security_e2e.md)  
**Оценка:** 3-5 инженерных дней

## Результат

Config-driven extractor получает только разрешённые identity sources,
возвращает normalized user/groups и отмечает служебные headers для удаления
перед upstream forwarding.

## Нормативный contract

- Config выбирает ровно один mode: `ANONYMOUS`, `TRUSTED_HEADERS` или `BASIC`.
  Смешивание sources запрещено strict startup validation, поэтому precedence
  между header и Basic отсутствует.
- `TRUSTED_HEADERS` задаёт optional user header, optional groups header и
  непустой список trusted CIDR. Identity принимается только по immediate peer
  socket address; `Forwarded`/`X-Forwarded-For` не расширяют boundary.
- User header имеет ровно одно value. Groups принимают repeated/combined
  values как comma-separated ASCII tokens с optional OWS, максимум 128 groups.
  Token grammar: `[A-Za-z0-9][A-Za-z0-9._:@/\-]{0,127}`. Values приводятся к
  Locale.ROOT lowercase; duplicate groups дедуплицируются.
- `BASIC` strict-decodes Base64 bytes, разделяет по первому `:` и использует
  только ASCII username по той же token grammar. Password bytes не
  декодируются в String, не сохраняются и не попадают в errors/logs.
- Отсутствие configured identity value даёт anonymous identity. Duplicate,
  malformed или contradictory values дают typed safe failure.
- Supplied configured identity header от untrusted peer даёт
  `UNTRUSTED_IDENTITY` и request не forwarding-ится. Vigilant-only identity
  headers всегда входят в strip set; `Authorization` входит в него только
  когда был consumed в `BASIC` mode.

## Нормативные ограничения

- Header names не зашиты в production code.
- Basic password и raw Authorization value не сохраняются.
- Ошибки и логи не содержат credentials или исходные identity headers.
- External directory lookup отсутствует.

## Критерии приёмки

- [x] Strict config validation отклоняет unknown/contradictory settings.
- [x] Все поддерживаемые sources дают одинаковый normalized contract.
- [x] Duplicate/missing/malformed identity имеет явную семантику.
- [x] Extractor возвращает точный набор headers для upstream stripping.
- [x] Security tests используют unique sentinels для всех credential values.
- [x] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [x] Focused tests и `./gradlew test` проходят.

## Не входит

Authentication, authorization и проверка пользователя во внешнем каталоге.
