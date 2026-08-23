# VIG-02-05: Recognizer `PHONE_NUMBER`

**Статус:** Done  
**Epic:** [EPIC-02](../../epics/epic_02_fast_pii_detector.md)  
**Ветка:** Recognizers > PHONE_NUMBER  
**Зависит от:** [VIG-02-03](issue_02_03_recognizer_pipeline.md)  
**Оценка:** 2-3 инженерных дня

## Результат

Fast detector распознаёт российские номера с `+7` или `8` и только
разрешёнными epic separators, возвращая metadata `fast.phone_number.ru`,
`1.0.0`, `FORMAT_ONLY`.

## Критерии готовности

- [x] После локальной нормализации остаётся ровно 11 ASCII digits.
- [x] Поддержана одна корректная пара скобок вокруг трёх цифр area code.
- [x] Повторные, крайние и неподдерживаемые separators отклонены.
- [x] Extensions не входят в finding и не валидируются.
- [x] Candidate boundaries и продолжение после invalid candidate проверены.
- [x] Есть positive, hard-negative, Unicode offset и adversarial tests.
- [x] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [x] Focused tests и `./gradlew test` проходят.

## Не входит

Operator/area-code registry, международные номера и `libphonenumber`.
