# VIG-11: Fast PII policy adapter

- **ID:** `VIG-11`
- **Тип:** Issue
- **Статус:** Done
- **Приоритет:** High
- **Зависит от:** [EPIC-02](../epics/epic_02_fast_pii_detector.md), [EPIC-04](../epics/epic_04_policy_engine.md), [VIG-07-02](epic_07/issue_07_02_windowed_fast_pii_execution.md)
- **Блокирует:** [VIG-13](issue_13_pii_shadow_request_tracer.md)
- **Оценка:** 1-2 инженерных дня
- **Уверенность:** High

## Результат

Built-in `FastPiiDetector` доступен registry policy engine под стабильным ID
`fast-pii`. Adapter выполняет полный windowed scan одного logical fragment и
lossless переносит type, exact UTF-8 span, confidence, evidence strength,
recognizer ID и recognizer version в `policy.domain.DetectionResult`.

## Public seam

Focused tests вызывают production adapter только через
`policy.domain.Detector.detect`. Tests используют реальный `FastPiiDetector` и
bounded executor, не мокают recognizers, window provider или policy classes.

## Критерии готовности

- [x] No-finding payload возвращает `CLEAN`, findings возвращают `DETECTED`.
- [x] Все PII metadata и deterministic order сохраняются без matched text.
- [x] Fragment больше detector limit проходит через versioned window capability.
- [x] Typed detector/window failures дают stable safe `ERROR`, cancellation
  остаётся cancellation.
- [x] CPU work выполняется только на переданном bounded executor.
- [x] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [x] Focused tests и `./gradlew build` проходят.

## Не входит

HTTP routing, parsing, policy selection, audit logging, source spooling и
application lifecycle.
