# VIG-04-07: Deduplicated parallel detector execution

**Статус:** Ready for implementation  
**Epic:** [EPIC-04](../../epics/epic_04_policy_engine.md)  
**Ветка:** Detector execution > deduplicated parallel execution  
**Зависит от:** [VIG-04-05](issue_04_05_matching_overrides.md), [VIG-04-06](issue_04_06_detector_executor.md)  
**Блокирует:** [VIG-04-08](issue_04_08_deadlines_cancellation.md)  
**Оценка:** 4-5 инженерных дней

## Результат

Engine строит execution plan для applied policies, запускает разные detector
IDs параллельно и выполняет один detector ID ровно один раз для одного payload,
даже если его используют несколько policies.

## Наблюдаемое поведение

- Policies получают ссылки на общий normalized detector result.
- Разные detectors фактически стартуют параллельно.
- Completion order не меняет decision inputs или сортировку.
- Evaluation cancellation передаётся всем активным executions.
- No matching policy не создаёт tasks.

## Тестовый seam

Controllable fake detectors с latches/barriers доказывают concurrency и точное
число invocations без sleep-based assertions.

## Критерии готовности

- [ ] Deduplication key равен stable detector ID для одного payload/evaluation.
- [ ] Один ID запускается один раз для нескольких consumers.
- [ ] Независимые IDs достигают barrier до освобождения любого из них.
- [ ] Results сортируются по detector ID.
- [ ] Structured cancellation не оставляет orphan tasks.
- [ ] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [ ] Focused tests и `./gradlew build` проходят.

## Не входит

Policy deadlines, fail-fast, detector dependencies и cascade execution.
