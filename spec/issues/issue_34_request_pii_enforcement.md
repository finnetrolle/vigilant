# VIG-34: Request-side PII enforcement

- **ID:** `VIG-34`
- **Тип:** Issue
- **Статус:** Draft
- **Приоритет:** High
- **Зависит от:** [VIG-20-03](epic_20/issue_20_03_reusable_text_masker.md), [VIG-29](issue_29_openai_error_contract.md), [VIG-32-01](epic_32/issue_32_01_stdout_request_audit_migration.md)
- **Блокирует:** нет
- **Оценка:** не оценено

## Цель

Добавить обязательное request-side enforcement для поддержанного OpenAI Chat
Completions path: применить `ALLOW`, `MASK` или `BLOCK` после complete request
inspection и до upstream handoff.

Issue остаётся Draft до отдельного диалога с владельцем продукта, который
зафиксирует requirements, границы и implementation plan.

## Известный контекст

- Current runtime выполняет request-side PII inspection в shadow mode и
  replay-ит original request upstream byte-for-byte.
- Existing policy engine, match dimensions, overrides и detector registry
  сохраняются как future-capable foundation. Эта issue интегрируется с ними и
  не создаёт задачу на их упрощение.
- `ALLOW` должен сохранить exact original replay.
- `MASK` должен изменить только выбранные model-visible text spans, сохранить
  valid JSON, unknown fields и все невыбранные bytes/values согласно
  согласованному protocol rewrite contract.
- `BLOCK` и technical failure используют VIG-29 safe OpenAI-compatible errors
  и не начинают upstream handoff.
- Dependency VIG-29 завершена: закрытая production matrix уже предоставляет
  request `BLOCK` и request inspection failure без optional details. Эта issue
  владеет только выбором этих outcomes из будущей reaction/lifecycle logic.
- Реально начатый request analysis публикует stdout pair по contract
  VIG-32-01.

## Открытые решения

- Как существующие policy reactions агрегируются в final `ALLOW`, `MASK` или
  `BLOCK`, включая precedence и simultaneous policies.
- Какие current policy states разрешены при startup и требуется ли отдельная
  coverage validation для enforcement mode.
- Как protocol layer представляет rewrite locations без потери unknown fields
  и без повторного detector execution.
- Какие exact bytes допускается изменить вокруг masked JSON string values:
  escaping, Unicode representation и serialization formatting.
- Как обрабатываются несколько fragments, overlapping findings и mixed policy
  reactions в одном request.
- Точный cancellation, timeout, capacity и audit lifecycle относительно
  upstream ownership handoff.
- Нужна ли migration/compatibility стадия от shadow-only deployment.

## Требования

- `MVP-01`: request inspection до upstream forwarding.
- `MVP-03`: `ALLOW`, `MASK`, `BLOCK` reaction semantics.
- `PROXY-02`: exact-span transformation без изменения остальных данных.
- `PROXY-03`: stable safe error contract.
- `MVP-06`, `OBS-01`, `OBS-02`: safe audit, metrics и tracing evidence.

## Не входит

- Удаление или упрощение current policy engine, URL/model/subject matching,
  overrides, detector registry или future policy capabilities.
- Response source, response parser, SSE и response enforcement.
- Новый detector, protocol кроме Chat Completions, policy hot reload или
  control plane.
- Audit persistence, WAL, custom logging queue или external Collector.

## Критерий готовности задачи

Issue становится `Ready for implementation`, когда через отдельный
requirements dialogue согласованы reaction aggregation, rewrite contract,
startup compatibility, lifecycle matrix, audit/metrics evidence и один
real-Armeria E2E seam.

## Ambiguity Report

```text
Goals:        0.10  request enforcement outcome known
Acceptance:   0.70  exact rewrite and lifecycle matrices unresolved
Boundaries:   0.15  response and policy redesign excluded
Alternatives: 0.55  migration and serialization choices open
Assumptions:  0.45  current policy result can drive canonical masking
Aggregate:    0.39  Draft: product dialogue required.
```
