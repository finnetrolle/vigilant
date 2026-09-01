# VIG-32: Best-effort asynchronous audit writer

- **ID:** `VIG-32`
- **Тип:** Issue
- **Статус:** Draft
- **Приоритет:** High
- **Зависит от:** нет
- **Блокирует:** нет
- **Оценка:** не оценено

## Цель

Пересмотреть audit path как asynchronous best-effort writer в файл, который не
влияет на detection, policy decision или traffic forwarding.

## Известный контекст

- Audit record содержит policy IDs, outcome, safe aggregate findings и tracing
  context: session, trace ID, span ID, parent span ID.
- Raw body, PII values/spans, Bearer token, user ID и groups запрещены в audit,
  logs и metrics.
- При заполнении bounded queue удаляются oldest records, сохраняются latest
  records, а technical log выдаёт rate-limited alert о потерях.
- Failure writer или audit file не блокирует traffic.

## Открытые решения

- Exact audit file schema, rotation, retention и recovery behavior.
- Queue size, writer batching и flush semantics.
- Alert content, rate limit и metrics для drops/write failures.
- Migration от текущего durable admission/WAL contract.

## Не входит

- Policy decisions, response enforcement, external Collector и обязательная
  durability before forwarding.

## Критерий готовности задачи

Issue становится `Ready for implementation`, когда file/queue lifecycle и
overload matrix гарантируют отсутствие backpressure на traffic path.

## Ambiguity Report

```text
Goals: 0.25; Acceptance: 0.75; Boundaries: 0.0; Alternatives: 0.5;
Assumptions: 0.5; Aggregate: 0.40. Draft: writer contract intentionally deferred.
```
