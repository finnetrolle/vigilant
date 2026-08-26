# VIG-09-08: Полный graceful shutdown lifecycle

**Статус:** Done
**Epic:** [EPIC-09](../../epics/epic_09_v0_architecture_closure.md)
**Ветка:** Operations > bounded active-request shutdown and resource lifecycle
**Зависит от:** нет
**Блокирует:** нет
**Оценка:** 3-5 инженерных дней
**Уверенность:** Medium

## Результат

Production-process E2E tests и явное ownership wiring доказывают полный
shutdown lifecycle: readiness становится `503`, новый traffic прекращается,
активный request получает bounded drain, зависший exchange закрывается по force
timeout, а dedicated upstream client и OTel providers закрываются после server
в безопасном порядке.

## Требования

Раздел «Поставка»: graceful shutdown с прекращением новых requests и bounded
wait активных requests; finding AR-08.

## Критерии готовности

- [x] SIGTERM во время активного конечного streaming exchange сначала делает
  `/readyz` недоступным и позволяет exchange завершиться без обрыва в пределах
  quiet/force bounds.
- [x] После начала shutdown новый обычный proxy request не принимается как
  normal traffic согласно принятой Armeria lifecycle semantics.
- [x] Никогда не завершающийся exchange принудительно закрывается, а process
  выходит не позже configured force timeout плюс малый test tolerance.
- [x] Созданный приложением dedicated `ClientFactory` имеет явного owner и
  закрывается после server drain; library defaults не заменяют lifecycle
  contract приложения.
- [x] Traces и metrics, завершившиеся до закрытия providers, flush-ятся в
  test OTLP collector; shutdown ordering не теряет completion callbacks.
- [x] Tests используют production child process и выводят последний observed
  lifecycle state при deadline failure.
- [x] Для добавленных/изменённых declarations написан KDoc, focused tests и
  `./gradlew build` проходят.

## Test/demo seam

Packaged `MainKt` child process, real slow/stuck upstream, streaming client и
local OTLP HTTP collector. Если force timeout делает regular build слишком
долгим, test seam должен позволять уменьшить durations без изменения
production defaults.

## Не входит

Kubernetes manifests, upstream health in readiness, rolling deployment policy,
retry/replay активного request после force close.

## Рекомендуемое решение внутри issue

Metro graph должен владеть closeable upstream client resource отдельно от
`WebClient`; shutdown order: mark not ready, drain/stop server, close upstream
client factory, затем flush/close tracing and metrics providers.
