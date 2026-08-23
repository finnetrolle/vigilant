# VIG-02-03: Последовательный recognizer pipeline

**Статус:** Done  
**Epic:** [EPIC-02](../../epics/epic_02_fast_pii_detector.md)  
**Ветка:** Public contract  
**Зависит от:** [VIG-02-01](issue_02_01_public_contract.md), [VIG-02-02](issue_02_02_payload_preflight.md)  
**Блокирует:** все recognizer issues EPIC-02  
**Оценка:** 3-4 инженерных дня

## Результат

`FastPiiDetector` и internal `PiiRecognizer` обеспечивают канонический
последовательный порядок, `enabledTypes`, stop-on-first, immutable results и
cooperative cancellation. Pipeline тестируется fake recognizer-ами до
появления конкретных правил.

## Наблюдаемое поведение

- Пустой `enabledTypes` немедленно возвращает empty result без preflight.
- Непустой набор всегда выполняет полный preflight до recognizer-ов.
- Отключённые recognizer-ы не запускаются и не меняют порядок остальных.
- `stopOnFirst=true` прекращает pipeline после первого валидного finding по
  каноническому порядку типов.
- Interrupt проверяется при входе, между recognizer-ами и validations;
  выбрасывается `CancellationException` без очистки flag.
- Один instance безопасен для concurrent calls и не хранит request state.

## Тестовый seam

Focused orchestration tests с deterministic fake recognizer-ами.

## Критерии готовности

- [x] Реализованы ordering, filtering, early exit и empty-set semantics.
- [x] Cancellation и concurrent calls проверены воспроизводимыми тестами.
- [x] Package не создаёт threads, executor или coroutine scope.
- [x] Pipeline не зависит от gateway, DI или logging.
- [x] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [x] Focused tests и `./gradlew test` проходят.

## Не входит

Конкретные PII formats, cross-type deduplication и JMH.
