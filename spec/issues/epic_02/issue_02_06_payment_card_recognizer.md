# VIG-02-06: Recognizer `PAYMENT_CARD`

**Статус:** Done  
**Epic:** [EPIC-02](../../epics/epic_02_fast_pii_detector.md)  
**Ветка:** Recognizers > PAYMENT_CARD  
**Зависит от:** [VIG-02-03](issue_02_03_recognizer_pipeline.md)  
**Оценка:** 1-2 инженерных дня

## Результат

Fast detector распознаёт card candidates длиной 13-19 digits с допустимыми
separators и обязательным Luhn, возвращая metadata
`fast.payment_card.luhn`, `1.0.0`, `VALIDATED`.

## Критерии готовности

- [x] Luhn реализован без преобразования всего кандидата в integer.
- [x] Повторяющаяся одна цифра, включая нули, отклоняется.
- [x] Separator и digit-boundary rules соответствуют epic.
- [x] Property tests генерируют валидные формы и checksum mutations с
  фиксированным seed.
- [x] Проверены compact/formatted inputs, offsets и invalid candidates.
- [x] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [x] Focused tests и `./gradlew test` проходят.

## Не входит

Определение платёжной системы, BIN lookup и хранение card value.
