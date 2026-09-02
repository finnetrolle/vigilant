# VIG-35: Выбор production identity mode

- **ID:** `VIG-35`
- **Тип:** Issue
- **Статус:** Draft
- **Приоритет:** High
- **Зависит от:** нет
- **Блокирует:** [VIG-30](issue_30_external_identity_extractor.md)
- **Оценка:** не оценено

## Цель

Выбрать и зафиксировать production identity strategy до реализации External
Bearer Identity Extractor: заменить ли завершённый offline JWT extractor или
поддерживать оба production mode.

Это human-owned decision issue. Она не изменяет production code и не закрывает
VIG-28 задним числом.

## Варианты решения

### External-only production

- Production использует External Identity Extractor из VIG-30 и bounded cache
  из VIG-31.
- Dummy остаётся только development/test mode.
- Offline JWT implementation и configuration удаляются отдельным migration
  change после готовности VIG-30/VIG-31.
- Это минимальный runtime/config/security surface для текущего MVP.

### External plus offline JWT

- Оба extractor являются поддержанными production modes.
- Требуются явные mode selection, configuration matrix, security ownership,
  deployment documentation и E2E evidence для каждого режима.
- VIG-30 и VIG-31 не заменяют VIG-28, а добавляют альтернативный path.

## Решение должен принять

Владелец продукта после сравнения deployment scenarios, trust boundaries,
операционной стоимости и требований обратной совместимости.

## Влияние решения

- Scope и non-goals VIG-30 External Identity Extractor.
- Scope VIG-31 cache и перечень modes, использующих cache.
- Статус offline JWT в runtime configuration и deployment docs.
- Необходимость отдельного removal/migration issue после VIG-30/VIG-31.
- Test matrix и production startup validation.

## Требования

- `MVP-05`: production External Identity Extractor и cache.
- `CONC-03`: blocking external lookup не выполняется на event loop.
- `OBS-01`, `OBS-02`: safe identity metrics/tracing без token или identity
  disclosure.

## Не входит

- Реализация external provider protocol или cache.
- Изменение policy matching и group semantics.
- Немедленное удаление offline JWT, Dummy extractor или их tests.
- Выбор конкретного external identity provider внутри этой issue.

## Критерий готовности задачи

- [ ] Выбран `external-only` либо `external plus offline JWT` production mode.
- [ ] Зафиксированы migration и backward-compatibility expectations.
- [ ] Названы поддерживаемые development/test modes.
- [ ] Обновлены VIG-30, VIG-31 и обязательный follow-up на сохранение либо
  удаление offline JWT path.
- [ ] Решение отражено в MVP, runtime configuration и coverage documentation.

После принятия и публикации решения issue может быть переведена напрямую в
`Done`, поскольку production implementation принадлежит зависимым work items.

## Ambiguity Report

```text
Goals:        0.0   decision outcome identified
Acceptance:   0.10  decision record and affected items explicit
Boundaries:   0.0   no production implementation
Alternatives: 0.75  production mode not yet selected
Assumptions:  0.45  backward compatibility requirements unknown
Aggregate:    0.26  Draft: product owner decision required.
```
