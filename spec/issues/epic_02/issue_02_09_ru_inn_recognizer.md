# VIG-02-09: Recognizer `RU_INN`

**Статус:** Done  
**Epic:** [EPIC-02](../../epics/epic_02_fast_pii_detector.md)  
**Ветка:** Recognizers > RU_INN  
**Зависит от:** [VIG-02-03](issue_02_03_recognizer_pipeline.md)  
**Оценка:** 1-2 инженерных дня

## Результат

Fast detector распознаёт только 12-значный ИНН физического лица с двумя
контрольными цифрами и metadata `fast.ru_inn`, `1.0.0`, `VALIDATED`.

## Критерии готовности

- [x] Реализованы обе checksum formula из epic.
- [x] 10-значный ИНН юридического лица не является finding.
- [x] Внутренние separators и соседние ASCII digits отклоняются.
- [x] Property tests проверяют valid generation и checksum mutations.
- [x] Проверены payload boundaries и продолжение после invalid candidate.
- [x] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [x] Focused tests и `./gradlew test` проходят.

## Не входит

Проверка существования ИНН во внешних системах.
