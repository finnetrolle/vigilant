# VIG-02-10: Recognizer `RU_SNILS`

**Статус:** Done  
**Epic:** [EPIC-02](../../epics/epic_02_fast_pii_detector.md)  
**Ветка:** Recognizers > RU_SNILS  
**Зависит от:** [VIG-02-03](issue_02_03_recognizer_pipeline.md)  
**Оценка:** 1-2 инженерных дня

## Результат

Fast detector распознаёт compact и две зафиксированные formatted forms СНИЛС,
проверяет порог и checksum, возвращая metadata `fast.ru_snils`, `1.0.0`,
`VALIDATED`.

## Критерии готовности

- [x] Поддержаны только forms и ASCII separators из epic.
- [x] Первые девять digits должны быть больше `001001998`.
- [x] Checksum `S mod 101` и special case `100 -> 00` реализованы точно.
- [x] Property tests проверяют valid generation и checksum mutations.
- [x] Digit boundaries, offsets и invalid candidate continuation покрыты.
- [x] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [x] Focused tests и `./gradlew test` проходят.

## Не входит

Внешняя проверка выдачи СНИЛС и альтернативные Unicode separators.
