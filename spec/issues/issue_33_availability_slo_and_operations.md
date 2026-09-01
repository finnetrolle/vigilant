# VIG-33: Availability SLO and operational evidence

- **ID:** `VIG-33`
- **Тип:** Issue
- **Статус:** Draft
- **Приоритет:** Medium
- **Зависит от:** нет
- **Блокирует:** нет
- **Оценка:** не оценено

## Цель

После появления production telemetry определить availability SLO и operational
evidence для горизонтально масштабируемого deployment Vigilant.

## Известный контекст

- MVP работает в нескольких stateless replicas за load balancer.
- Каждая replica владеет local identity cache, spool и audit file.
- Graceful shutdown: readiness становится `503`, новые requests не принимаются,
  активные операции drain-ятся до deadline, затем отменяются.
- Численный availability target пока намеренно не выбран.

## Открытые решения

- Availability target и окно измерения.
- Какие failures входят в SLI: Vigilant, identity provider, upstream LLM,
  load balancer или инфраструктура.
- Alerting, rollout/recovery evidence и production telemetry period.

## Не входит

- Реализация autoscaling, shared runtime state, external audit delivery или
  change functional enforcement semantics.

## Критерий готовности задачи

Issue становится `Ready for implementation`, когда SLI/SLO, ownership внешних
dependencies и required operational evidence утверждены.

## Ambiguity Report

```text
Goals: 0.25; Acceptance: 0.75; Boundaries: 0.0; Alternatives: 0.5;
Assumptions: 0.75; Aggregate: 0.45. Draft: production evidence not yet available.
```
