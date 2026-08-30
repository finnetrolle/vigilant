# VIG-21-03: Детерминированный upstream-error evidence

**Статус:** Ready for implementation
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

- [ ] Existing intermittent case воспроизводится bounded stress run или
  root-cause evidence объясняет, почему старый fixture допускал `RST_STREAM`.
- [ ] Test наблюдает client response и matching log event одного exchange;
  `runCatching` не превращает transport exception в допустимый success path.
- [ ] Dead upstream seam не освобождает OS-ephemeral port перед connect и не
  может быть занят параллельным Armeria `http(0)` fixture.
- [ ] Synchronization ждёт exact response/log observations с bounded deadline и
  печатает last observed state при failure.
- [ ] Body, query token и Authorization sentinels отсутствуют во всём captured
  event, exception и client error surface.
- [ ] Focused class и повторный full-suite sample проходят без intermittent
  outcome; число повторов и environment опубликованы.
- [ ] Production code не меняется, пока deterministic test не докажет runtime
  defect. Такой defect получает отдельную RED-first issue.
- [ ] Open papercut закрывается только ссылкой на root cause и verification
  evidence, а не по одному green run.

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
