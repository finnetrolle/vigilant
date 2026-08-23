# VIG-02-12: Recognizer `RU_OMS`

**Статус:** Done  
**Epic:** [EPIC-02](../../epics/epic_02_fast_pii_detector.md)  
**Ветка:** Recognizers > RU_OMS  
**Зависит от:** [VIG-02-03](issue_02_03_recognizer_pipeline.md)  
**Оценка:** 1-2 инженерных дня

## Результат

Fast detector распознаёт compact и four-by-four spaced номер ОМС, проверяет
зафиксированный Mod10 и возвращает metadata `fast.ru_oms`, `1.0.0`,
`VALIDATED`.

## Критерии готовности

- [x] Поддержаны только compact и группы по четыре с одиночным `U+0020`.
- [x] Hyphens и Unicode spaces отклоняются.
- [x] Mod10 соответствует нормативной формуле epic.
- [x] Property tests проверяют valid generation и checksum mutations.
- [x] Digit boundaries, offsets и invalid candidate continuation покрыты.
- [x] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [x] Focused tests и `./gradlew test` проходят.

## Не входит

Проверка полиса во внешнем реестре и распознавание старых форм полиса.
