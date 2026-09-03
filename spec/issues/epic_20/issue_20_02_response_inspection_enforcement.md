# VIG-20-02: Non-stream response inspection and enforcement

**Статус:** Draft
**Epic:** [EPIC-20](../../epics/epic_20_response_spooling_secure_spill.md)
**Ветка:** Response enforcement > non-stream Chat Completions
**Зависит от:** [VIG-06-03](../epic_06/issue_06_03_chat_completions_response_parser.md), [VIG-20-01](issue_20_01_bounded_memory_response_source.md), [VIG-20-03](issue_20_03_reusable_text_masker.md), [VIG-29](../issue_29_openai_error_contract.md), [VIG-32-01](../epic_32/issue_32_01_stdout_request_audit_migration.md)
**Блокирует:** нет
**Оценка:** 3-5 инженерных дней; confidence Medium

## Цель

После полного получения ordinary non-stream Chat Completions response извлечь
inspectable text, выбрать response policies из request identity/context,
выполнить detectors и применить `ALLOW`, `MASK` или `BLOCK` до первого client
byte.

Ровно одна stdout audit pair из VIG-32-01 отражает реально начатый и завершённый
response analysis.

Прежний scope VIG-20-02, включавший одновременно non-stream JSON, SSE framing,
cross-chunk masking, audit и полный lifecycle, сохранён на нормативном уровне
[EPIC-20](../../epics/epic_20_response_spooling_secure_spill.md). SSE framing
завершён в VIG-06-03; SSE enforcement будет опубликован отдельным bounded leaf.

## Принятые решения

- Response enforcement является обязательной частью MVP, а не shadow-only
  observability.
- Response JSON/SSE parsing использует единственный public contract VIG-06-03;
  EPIC-20 не создаёт competing parser API.
- Каждый string `choices[].message.content` является независимым inspection
  fragment. Findings не пересекают границы choices.
- `ALLOW` раскрывает exact original response byte-for-byte.
- `MASK` изменяет только exact selected PII spans в затронутых choices,
  сохраняет valid OpenAI JSON, unknown fields и остальную structure,
  пересчитывает `Content-Length` и удаляет upstream `Transfer-Encoding` плюс
  другие hop-by-hop headers по proxy rules.
- Любой applicable `BLOCK` блокирует весь response. Клиент получает VIG-29
  safe `403 policy_blocked` и не получает upstream status, headers или body.
- Все upstream Chat Completions responses, включая `4xx` и `5xx`, проходят
  inspection до client disclosure. Upstream error status не является policy
  bypass.
- Recognized `null`, image/audio/file/tool-call и другие recognized non-text
  shapes дают `INSPECTION_GAP` и `ALLOW` без изменения response.
- Missing `choices`, invalid content type, malformed или ambiguous
  content-bearing shape дают VIG-29 safe `502 invalid_upstream_response`.
- Detector/policy timeout или failure дают VIG-29 safe `503` с
  `Retry-After: 1` и `response_inspection_unavailable` без partial disclosure.
- Dependency VIG-29 завершена: закрытая production matrix уже предоставляет
  response `BLOCK`, response inspection failure и invalid upstream outcomes без
  optional details. Эта issue владеет только выбором этих outcomes после
  будущего response analysis.
- `analysis_started` публикуется после parse, context assembly и policy
  selection непосредственно перед detector execution. `analysis_completed`
  публикуется после final outcome и содержит только safe aggregate fields.
- Существующий расширенный policy engine сохраняется. Эта issue не удаляет и
  не упрощает URL/model/subject matching, overrides или detector registry.

## Открытые решения

- Как существующие `PolicyReactions` и `ReactionPlan` предоставляют final
  `ALLOW`/`MASK`/`BLOCK` плюс canonical masking instructions без detector rerun.
- Какая existing gateway boundary принимает retained source ownership и
  передаёт exact replay либо transformed response клиенту.

## Требования

- `MVP-01`, `MVP-02`, `MVP-03`, `MVP-04`: response inspection, fast-pii,
  reactions и response policy selection.
- `PERF-03`, `CONC-01`, `CONC-03`, `CONC-04`: deadline, retained memory,
  execution isolation, cancellation и shutdown.
- `PROXY-01`, `PROXY-02`, `PROXY-03`: hold-before-release, exact masking и safe
  errors.
- `MVP-06`, `OBS-01`, `OBS-02`: safe stdout audit pair, metrics и tracing.

## Не входит

- SSE framing, `data: [DONE]` и text assembly по `choice.index` завершены в
  VIG-06-03; cross-chunk findings и SSE response rewrite принадлежат будущему
  enforcement leaf EPIC-20.
- Response source ownership и memory lifecycle: VIG-20-01.
- Audit persistence, custom queue, WAL, file handoff или Collector.
- Упрощение существующей policy model, новые detector types, policy hot reload,
  protocol кроме Chat Completions, retries или response regeneration.

## Условия перехода в Ready

- [x] EPIC-06 назначен owner единственного public response parser seam VIG-06-03.
- [ ] Зафиксировано преобразование existing policy result в final reaction и
  canonical masking instructions без изменения policy scope.
- [ ] Назван real-Armeria E2E seam для удержания response до decision и exact
  client disclosure.
- [ ] Все acceptance cases ниже сопоставлены конкретному test evidence.

## Предварительные критерии выполнения

- [ ] Deterministic real-Armeria E2E покрывает `ALLOW` byte-for-byte replay,
  `MASK` с exact replacement и header rewrite, а также `BLOCK` без одного
  upstream byte клиенту.
- [ ] Tests покрывают independent choices, upstream `4xx`/`5xx`, recognized
  non-text `INSPECTION_GAP`, malformed/ambiguous response, detector/policy
  timeout/failure, cancellation и shutdown cleanup.
- [ ] Response публикует ровно одну safe VIG-32-01 stdout pair после actual parse
  и policy selection. Payload, spans, credentials и identity не логируются.
- [ ] Новые и изменённые Kotlin declarations, test methods и lifecycle helpers
  имеют KDoc; focused tests, `validateWorkItems` и `./gradlew build` проходят.

## Ambiguity Report

```text
Goals:        0.0   non-stream enforcement outcome fixed
Acceptance:   0.20  response matrix retained from the former broad issue
Boundaries:   0.15  VIG-06-03 parser ownership fixed; integration seam open
Alternatives: 0.20  integration seam with current policy needs confirmation
Assumptions:  0.20  source handoff seam will reuse gateway ownership model
Aggregate:    0.15  Draft: resolve policy-result and integration seams.
```
