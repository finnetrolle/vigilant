# VIG-04-06: Detector executor и result validation

**Статус:** Done  
**Epic:** [EPIC-04](../../epics/epic_04_policy_engine.md)  
**Ветка:** Detector execution > result validation  
**Зависит от:** [VIG-04-01](issue_04_01_domain_contracts.md)  
**Блокирует:** [VIG-04-07](issue_04_07_parallel_execution.md)  
**Оценка:** 3-5 инженерных дня

## Результат

`DetectorExecutor` разрешает stable detector ID через immutable registry,
запускает detector только с payload, проверяет result invariants и возвращает
stable safe `ERROR` для invalid result или unexpected exception.

## Наблюдаемое поведение

- Unknown ID не маскируется и имеет stable error semantics.
- Invalid span/status превращается в `INVALID_DETECTOR_RESULT`.
- Unexpected exception превращается в отдельный stable error code.
- Cancellation не превращается в detector error.
- Raw exception message, payload и findings не логируются.

## Критерии готовности

- [x] Detector получает только payload, без `PolicyContext`.
- [x] Все `DetectionResult` invariants проверены через fake detectors.
- [x] UTF-8 span boundaries валидируются относительно исходного payload.
- [x] Один фактический invocation возвращает один normalized result.
- [x] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [x] Focused tests и `./gradlew build` проходят.

## Не входит

Конкретный fast-PII adapter, parallel scheduling, deadlines и retries.
