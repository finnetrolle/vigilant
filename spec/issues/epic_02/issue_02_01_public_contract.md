# VIG-02-01: Public API и invariants PII-detector

**Статус:** Done  
**Epic:** [EPIC-02](../../epics/epic_02_fast_pii_detector.md)  
**Ветка:** Public contract  
**Зависит от:** нет  
**Блокирует:** [VIG-02-02](issue_02_02_payload_preflight.md), [VIG-02-03](issue_02_03_recognizer_pipeline.md)  
**Оценка:** 2-3 инженерных дня

## Результат

В package `io.vigilant.detectors.pii` существует transport-neutral публичный
контракт: `PiiDetector`, `PiiType`, immutable `ALL_PII_TYPES`, `PiiFinding`,
`EvidenceStrength`, `PiiDetectionException` и `PiiDetectionError`.

## Границы

- Реализовать модели и их context-free constructor invariants из разделов
  «Публичный API» и «Контракт результата» epic.
- Не создавать `FastPiiDetector`, recognizer-ы, DI registration или HTTP
  integration.
- Production-код использует только Kotlin/JDK и не зависит от logging.

## Тестовый seam

Focused unit tests публичных типов из Kotlin и Java caller perspective.

## Критерии готовности

- [x] Все публичные модели и enum соответствуют нормативному контракту epic.
- [x] Context-free invalid finding state отклоняется без PII в exception message.
- [x] `ALL_PII_TYPES` immutable; API требует immutable result collections,
  реализация требования проверяется вместе с `FastPiiDetector` в VIG-02-03.
- [x] API не содержит Armeria, coroutine, DI, policy или transport types.
- [x] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [x] Focused tests и `./gradlew test` проходят.

## Не входит

Сканирование payload, UTF-8 offsets, recognizer orchestration и benchmark.
