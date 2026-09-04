# VIG-32-01: Stdout request audit migration

**Статус:** Done
**Epic:** [EPIC-32](../../epics/epic_32_best_effort_stdout_audit.md)
**Ветка:** Migrate request audit contract > operator-visible stdout pair
**Зависит от:** нет
**Блокирует:** [VIG-32-02](issue_32_02_durable_audit_removal.md), [VIG-20-02](../epic_20/issue_20_02_response_inspection_enforcement.md), [VIG-34](../issue_34_request_pii_enforcement.md)
**Оценка:** 3-5 инженерных дней
**Уверенность:** Medium

## Результат

Один supported Chat Completions request, для которого после parse, context и
policy selection действительно начинается detector execution, публикует в
existing JSONL stdout ровно одну safe пару `policy.analysis_started` и
`policy.analysis_completed`. Публикация best-effort не ожидает delivery и не
требует durable acknowledgement перед исходным HTTP outcome или upstream
handoff.

Этот tracer bullet мигрирует request audit contract первым. Durable store,
его outer admission/readiness integration и packaging ещё могут существовать
до VIG-32-02, но request analysis больше не создаёт и не ждёт durable record.

## Public seam и TDD slices

Основной seam - installed distribution, запущенный против real Armeria
upstream, и его operator-visible JSONL stdout. Первый RED case фиксирует
`analysis_started` до причинно удерживаемого detector execution и
`analysis_completed` после terminal decision, но до разрешённого transport
handoff. Последующие vertical slices добавляют outcome/privacy matrix и
non-blocking failure behavior по одному observable case.

Внутренние test appenders или detector barriers допустимы только как
детерминированные fixtures этого seam. Они не заменяют process stdout evidence,
не используют sleeps/timestamps и имеют bounded waits с last observed state.

## Stdout schema contract

Оба event используют existing structured JSONL encoding и содержат:

- `event.name`, `protocol=openai.chat_completions`, `phase=REQUEST`;
- `trace.id`, server-generated `span.id` и `parent.span.id` одного inspection
  span. `trace.id` либо генерируется server-side, либо продолжает валидный W3C
  parent по existing tracing contract; session ID, raw inbound
  `traceparent`/`tracestate` и другие raw user-controlled correlation values
  отсутствуют;
- canonical deterministic policy references в формате `id@version` и detector
  references `detector.id`/`detector.version`.

`policy.analysis_started` публикуется ровно один раз после successful complete
source parse, identity/context assembly и policy selection, непосредственно
перед первым detector execution. Пустой selected policy set, unsupported или
malformed request, failed identity/context/source и cancellation до detector
execution не создают started/completed pair.

`policy.analysis_completed` публикуется ровно один раз после всех реально
запущенных fragment evaluations и содержит:

- `outcome`: `CLEAN`, `DETECTED`, `INSPECTION_GAP` или `ERROR`;
- фактически applied canonical policy references;
- `reaction=ALLOW` для текущего successful shadow contract; для `ERROR` поле
  `reaction` отсутствует и присутствует stable `error.code`;
- aggregate `coverage`, inspected-fragment count, total findings, counts by PII
  type и evidence strength, а также non-negative `analysis.duration_ms`.

Event никогда не содержит payload, content preview, PII value/span, request
path/query, headers, credentials, identity, user ID, groups, session ID, raw
exception, generated event ID или reversible payload-derived value. Policy и
detector references остаются stdout-only и не попадают в client errors.

## Требования

`MVP-06`, `OBS-01`, `OBS-02`; current request shadow-inspection and tracing
contract; [EPIC-32](../../epics/epic_32_best_effort_stdout_audit.md).

## Критерии готовности

- [x] Causal real-Armeria test доказывает exact ordering: started видим после
  selection и до первого detector execution; completed видим после final
  outcome и до transport release. Один request создаёт не более одной пары,
  независимо от количества fragments и policies.
- [x] Table-driven stdout assertions покрывают `CLEAN`, `DETECTED`,
  `INSPECTION_GAP` и detector/policy `ERROR`, exact required fields, canonical
  ordering policy references, aggregate counts/duration и отсутствие
  `reaction` для `ERROR`.
- [x] Tests покрывают отсутствие пары для unsupported/malformed request,
  identity/context/source failure, empty selection и cancellation до analysis,
  а также terminal completion при cancellation после реально начатого analysis.
- [x] Request workflow не резервирует audit record, не submit-ит его и не ждёт
  write/force/durable future. Upstream и исходные stable request errors больше
  не зависят от durable acknowledgement.
- [x] Existing Logback `AsyncAppender` с `neverBlock=true` остаётся единственной
  queue. Slow, full или throwing logging sink не меняет status/body, не
  задерживает upstream handoff и не создаёт audit queue/worker/config/metric/
  callback/drop alert.
- [x] Privacy tests используют уникальные sentinels для body, PII values/spans,
  path/query, headers, credentials, identity, user/groups, session и inbound
  propagation, затем доказывают их отсутствие в обоих stdout events и client
  errors.
- [x] На момент закрытия VIG-32-01 `MVP-06`, `OBS-01`, `OBS-02` и
  observability/coverage docs описывали реализованную REQUEST pair, сохраняя
  RESPONSE pair за owning leaves EPIC-20; ordinary pair позднее закрыта
  VIG-20-02.
- [x] Все добавленные и изменённые Kotlin declarations, lifecycle helpers и
  test methods имеют актуальный KDoc. Зафиксированы expected RED, focused GREEN,
  affected request/process suite, `./gradlew validateWorkItems` и
  `./gradlew build`.

## Evidence

- RED: causal real-Armeria test до production change не увидел
  `policy.analysis_started` перед held detector execution; focused command
  завершился `BUILD FAILED` за 12s. Migration RED также показал,
  что durable capacity всё ещё менял request outcome.
- GREEN: causal ordering, exact outcome/schema, absence/cancellation,
  slow/full/throwing logger, privacy и installed-process stdout slices прошли
  focused tests. Дополнительные RED cases обнаружили два early-rejection
  inspection-span leak; оба focused GREEN прошли после one-shot span cleanup.
- Affected suites: complete `PiiShadowProxyServiceTest` прошёл за 2m24s,
  complete `PiiShadowProxyProcessTest` прошёл за 22s, а
  `ShadowInspectionWorkflowTest` вместе с `PolicyEngineTest` прошли за 2s.
- Repository gates: `./gradlew validateWorkItems` прошёл с
  `Work-item graph is valid`; `./gradlew build` прошёл с `BUILD SUCCESSFUL`.
- Verification fix pass: packaged-process table подтвердил exact JSONL schema
  для `CLEAN`, `DETECTED`, `INSPECTION_GAP` и deterministic policy `ERROR`, а
  packaged privacy case покрыл полный sentinel matrix и client error.
  Correlation fields и MDC теперь используют одно canonical tracing rule;
  complete affected request/process/workflow suites прошли за 2m49s.

## Не входит

- Удаление audit store, WAL, Collector handoff, audit configuration,
  admission/readiness integration, packaging и qualification. Это VIG-32-02.
- RESPONSE audit pair, response source/parser/SSE или enforcement reactions.
- Новый logger backend, direct exporter, custom queue, batching, retries,
  durability, drop metric/alert или audit delivery configuration.
- Изменение policy selection, detector behavior, request protocol/error
  semantics или tracing generation.

## Ambiguity Report

```text
Goals:        0.0   operator-visible REQUEST pair определена
Acceptance:   0.10  outcome, absence и privacy matrices explicit
Boundaries:   0.05  removal и response ownership отделены
Alternatives: 0.05  existing JSONL/AsyncAppender seam обязателен
Assumptions:  0.15  current policy seam потребует bounded migration refactor
Aggregate:    0.07  Ready for implementation.
```
