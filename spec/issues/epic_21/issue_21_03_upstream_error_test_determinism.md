# VIG-21-03: Детерминированный upstream-error evidence

**Статус:** Done
**Epic:** [EPIC-21](../../epics/epic_21_post_milestone_architecture_closure.md)
**Ветка:** Verification determinism > stable upstream-error observation
**Зависит от:** нет
**Блокирует:** [VIG-21-05](issue_21_05_roadmap_frontier_reconciliation.md)
**Оценка:** 2-3 инженерных дня
**Уверенность:** Medium

## Результат

Focused и full-suite tests детерминированно доказывают, что один upstream
connection failure даёт client `502 upstream_unavailable` и один safe WARN
event без HTTP/2 `RST_STREAM`, swallowed client exception или shared-fixture
cross-talk.

## Требования

`PROXY-03`, roadmap build gate, finding AR-12, open papercut
`pc_cc7188284a8f`.

## Критерии готовности

- [x] Existing intermittent case воспроизводится bounded stress run или
  root-cause evidence объясняет, почему старый fixture допускал `RST_STREAM`.
- [x] Test наблюдает client response и matching log event одного exchange;
  `runCatching` не превращает transport exception в допустимый success path.
- [x] Dead upstream seam не освобождает OS-ephemeral port перед connect и не
  может быть занят параллельным Armeria `http(0)` fixture.
- [x] Synchronization ждёт exact response/log observations с bounded deadline и
  печатает last observed state при failure.
- [x] Body, query token и Authorization sentinels отсутствуют во всём captured
  event, exception и client error surface.
- [x] Focused class и повторный full-suite sample проходят без intermittent
  outcome; число повторов и environment опубликованы.
- [x] Production code не меняется, пока deterministic test не докажет runtime
  defect. Такой defect получает отдельную RED-first issue.
- [x] Open papercut закрывается только ссылкой на root cause и verification
  evidence, а не по одному green run.

## Completion evidence

История показала два дефекта старого evidence seam. До `81fe836` helper
освобождал `ServerSocket(0)` из OS ephemeral range до connect, поэтому
параллельный Armeria `http(0)` мог занять endpoint и вернуть HTTP/2
`RST_STREAM`. Позднее logging case обернул downstream exchange в `runCatching`
и ждал любой event класса, поэтому client exception и WARN другого exchange не
опровергали тест.

`DisconnectingTestUpstream` теперь удерживает bound loopback socket, принимает
реальную connection и немедленно reset-ит её до HTTP response. Upstream и
downstream clients используют scenario-owned `ClientFactory`; test ждёт в
пределах 5 секунд одновременно accepted connection, terminal response и ровно
один WARN для exact safe path. Любой client exception сканируется на sentinels
и затем обязательно проваливает test. Captured Logback event сканируется по
message, arguments, key-values, MDC, markers и recursive throwable surface;
client status, headers и body проверяются вместе с ним.

30 августа 2026 года exact focused method прошёл `20/20` последовательных
`--rerun-tasks` запусков, а полный `./gradlew test --rerun-tasks -q` прошёл
`3/3` последовательных samples. После test-only refactor exact method и
`MalformedUpstreamResponseTest` повторно прошли вместе, `./gradlew detekt
--rerun-tasks --no-daemon` завершился GREEN. Environment: macOS `26.3.1`
`arm64`, Apple M3 Max, `36 GiB` RAM, Gradle `9.7.1`, launcher JDK `25.0.2`,
project JVM toolchain `25`. Production code не изменён, отдельная runtime defect
issue не потребовалась. Финальные `./gradlew validateWorkItems --rerun-tasks
--no-daemon` и `./gradlew build --rerun-tasks --no-daemon` прошли; forced build
выполнил все 16 tasks и завершился за `5m 53s`.

Post-issue verification remediation получила поведенческий RED на no-op
`closeAllResources`, затем GREEN на сохранение первого cleanup failure,
suppressed later failures и обязательный вызов всех cleanup actions. После
перевода teardown и обоих raw upstream fixtures на общие lifecycle helpers
`TestResourceLifecycleTest`, `BypassProxyServiceTest`,
`MalformedUpstreamResponseTest` и `DynamicResponseConnectionHeadersTest`
совместно прошли forced focused run.

## Test/demo seam

Real Armeria client and gateway, deterministically unavailable local endpoint,
request-scoped captured Logback event и bounded observation helper.

## Не входит

Retry, circuit breaker, upstream failover, изменение stable error schema,
mid-response failures и production logging channel redesign.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   one failure, one HTTP outcome, one safe event
  Acceptance:   0.15  stress and causal observations are explicit
  Boundaries:   0.05  test-only until runtime defect exists
  Alternatives: 0.15  endpoint reservation seam is local
  Assumptions:  0.25  intermittent failure may be fixture-only
  Aggregate:    0.12  below threshold (0.3 issue)
```
