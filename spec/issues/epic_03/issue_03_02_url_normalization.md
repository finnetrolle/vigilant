# VIG-03-02: Нормализация policy URL

**Статус:** Draft  
**Epic:** [EPIC-03](../../epics/epic_03_policy_context_extraction.md)  
**Ветка:** URL normalization  
**Зависит от:** [VIG-03-01](issue_03_01_context_contract.md)  
**Оценка после уточнения:** 2-3 инженерных дня

## Результат

Pure deterministic component преобразует destination URI в единственный
нормативный policy match key, согласованный с EPIC-04.

## Требует решения VIG-03-01

- Какие URI components входят в key.
- Case rules отдельно для scheme, host и path.
- Default ports, percent encoding, dot segments и trailing slash.
- Поведение для malformed или unsupported URI.

## Тестовый seam

Table-driven pure unit tests без Armeria и сетевых вызовов.

## Критерии готовности после уточнения

- [ ] Эквивалентные разрешённые URI дают один key.
- [ ] Различия, значимые для policy matching, не схлопываются.
- [ ] Query, credentials и secrets не попадают в context или errors, если они
  исключены принятым контрактом.
- [ ] Unsupported inputs дают typed safe error либо явно определённый result.
- [ ] Locale пользователя не влияет на normalization.
- [ ] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [ ] Focused tests и `./gradlew test` проходят.

## Не входит

Glob/regex matching, redirects, DNS resolution и policy selection.
