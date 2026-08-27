# VIG-15: Capacity and cancellation outcomes

- **ID:** `VIG-15`
- **Тип:** Issue
- **Статус:** Done
- **Приоритет:** High
- **Зависит от:** [VIG-13](issue_13_pii_shadow_request_tracer.md)
- **Блокирует:** [VIG-16](issue_16_packaged_shadow_proxy_evidence.md)
- **Оценка:** 2-4 инженерных дня
- **Уверенность:** Medium

## Результат

Configurable request-source limits, client cancellation и policy deadlines
дают bounded stable outcomes без partial upstream disclosure и без retained
owner, bytes, segments или detector tasks.

## Public seam

Real Armeria E2E seam с small configured limits, controlled request publisher,
slow detector/deadline scenario and observable process-owned quota lifecycle.

## Критерии готовности

- [x] Known и streamed per-request overflow дают `413 request_too_large`.
- [x] Owner/global byte exhaustion дают `503 inspection_capacity_exhausted`.
- [x] Client cancellation прекращает ingest/inspection/replay и освобождает source.
- [x] Deadline в shadow mode forwarding-ит request и создаёт safe ERROR observations.
- [x] Replay/upstream failure и shutdown освобождают source и owned executor resources.
- [x] Ingest/replay demand остаётся bounded, unbounded queues отсутствуют.
- [x] Focused E2E tests и `./gradlew build` проходят.

## Не входит

Disk spill, autoscaling, enforcement и response spool.
