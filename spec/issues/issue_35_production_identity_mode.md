# VIG-35: Выбор production identity mode

- **ID:** `VIG-35`
- **Тип:** Issue
- **Статус:** Done
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

## Принятое решение

Vigilant сохраняет три identity mode:

- внешний identity lookup из VIG-30 с bounded cache из VIG-31;
- завершённый offline JWT extractor из VIG-28;
- завершённый Dummy extractor из VIG-27.

Все три mode реализуют общий `BearerIdentityExtractor` contract. Startup
configuration выбирает ровно одну реализацию для полного lifecycle процесса.
Автоматический fallback, одновременная композиция нескольких extractor и
переключение mode без restart не поддерживаются.

Общий extraction contract становится асинхронным и cancellation-aware.
`DUMMY` и JWT завершают его локальным результатом без network I/O; внешний
extractor завершает тот же contract результатом lookup. Client cancellation,
identity timeout и application shutdown обязаны прекращать незавершённый
external lookup и публиковать не более одного terminal result. VIG-30
реализовал exact `CompletableFuture` contract и async Armeria integration.

Startup selector сохраняет один обязательный key `identity-mode` с exact
значениями `DUMMY`, `JWT` и `EXTERNAL`; environment override использует
`VIGILANT_IDENTITY_MODE` с теми же значениями. Default, aliases и fallback
отсутствуют. Конфигурация выбранного mode обязательна, а settings любого
невыбранного identity mode дают startup error с exit code `2`.

External Identity Extractor добавляется как третий path и не заменяет offline
JWT. Удаление JWT implementation, configuration или tests не требуется.
`DUMMY` остаётся разрешён только в `development` и `test`: он не проверяет
credential и поэтому запрещён при `environment=production`. `JWT` и внешний
identity mode разрешены во всех трёх environments, чтобы production paths
можно было запускать в development и проверять в test.

## Рассмотренная альтернатива

`external-only` production отклонён. Владельцу продукта нужны deployments как
с внешним identity service, так и с полностью локальной offline JWT validation;
`DUMMY` также сохраняется отдельной реализацией общего контракта.

## Влияние решения

- Scope и non-goals VIG-30 External Identity Extractor.
- Scope VIG-31 cache и перечень modes, использующих cache.
- Offline JWT остаётся поддерживаемым mode в runtime configuration и
  deployment docs без removal/migration issue.
- Startup validation обязана принимать настройки только выбранного mode и
  отклонять смешанную configuration. Production startup принимает только JWT
  или внешний mode; `DUMMY` fail-fast отклоняется до запуска server.
- Test matrix обязана независимо покрывать выбор и поведение всех трёх
  реализаций общего interface.
- VIG-30 обязан обновить общий interface и gateway orchestration так, чтобы
  external network wait не блокировал Armeria event loop, а cancellation и
  timeout были наблюдаемыми частями lifecycle contract.

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

- [x] Выбран `external plus offline JWT`; `DUMMY` сохранён отдельным mode.
- [x] Offline JWT сохраняется без removal migration; External добавляется как
  новый явно выбираемый mode.
- [x] `development`/`test` поддерживают `DUMMY`, JWT и внешний mode;
  `production` поддерживает только JWT и внешний mode.
- [x] Для всех трёх реализаций выбран единый async и cancellation-aware
  `BearerIdentityExtractor` contract.
- [x] VIG-30 и VIG-31 обновлены; offline JWT сохраняется, поэтому removal
  follow-up не создаётся.
- [x] Решение отражено в MVP, runtime configuration и coverage documentation.

После принятия и публикации решения issue может быть переведена напрямую в
`Done`, поскольку production implementation принадлежит зависимым work items.

## Ambiguity Report

```text
Goals:        0.0   three-mode outcome explicit
Acceptance:   0.0   decision publication matrix complete
Boundaries:   0.0   no production implementation
Alternatives: 0.0   external-only rejected explicitly
Assumptions:  0.05  VIG-30 still owns its provider protocol and JVM async type
Aggregate:    0.01  Done: production identity mode decision published.
```
