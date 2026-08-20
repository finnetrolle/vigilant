# VIG-02-01: Public API и invariants PII-detector

**Статус:** Ready for implementation  
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

- Реализовать модели и их constructor invariants из разделов «Публичный API»
  и «Контракт результата» epic.
- Не создавать `FastPiiDetector`, recognizer-ы, DI registration или HTTP
  integration.
- Production-код использует только Kotlin/JDK и не зависит от logging.

## Тестовый seam

Focused unit tests публичных типов из Kotlin и Java caller perspective.

## Критерии готовности

- [ ] Все публичные модели и enum соответствуют нормативному контракту epic.
- [ ] Invalid finding state отклоняется без PII в exception message.
- [ ] `ALL_PII_TYPES` и возвращаемые коллекции нельзя изменить caller-у.
- [ ] API не содержит Armeria, coroutine, DI, policy или transport types.
- [ ] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [ ] Focused tests и `./gradlew test` проходят.

## Не входит

Сканирование payload, UTF-8 offsets, recognizer orchestration и benchmark.
