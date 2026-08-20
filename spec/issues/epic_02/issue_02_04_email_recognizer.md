# VIG-02-04: Recognizer `EMAIL_ADDRESS`

**Статус:** Ready for implementation  
**Epic:** [EPIC-02](../../epics/epic_02_fast_pii_detector.md)  
**Ветка:** Recognizers > EMAIL_ADDRESS  
**Зависит от:** [VIG-02-03](issue_02_03_recognizer_pipeline.md)  
**Оценка:** 2-3 инженерных дня

## Результат

Fast detector распознаёт зафиксированный в epic ASCII dot-atom local-part и
DNS/IDN domain, возвращая исходный UTF-8 span и metadata
`fast.email_address`, `1.0.0`, `FORMAT_ONLY`.

## Критерии готовности

- [ ] Соблюдены length, dot, label, hyphen и candidate-boundary rules epic.
- [ ] Unicode domain проходит `IDN.toASCII` с STD3 rules.
- [ ] Unicode local-part, quoted forms, comments и domain literals отклонены.
- [ ] После invalid candidate поиск продолжается.
- [ ] Есть positive, hard-negative, mixed-Unicode offset и adversarial tests.
- [ ] Pattern bounded и не допускает catastrophic backtracking.
- [ ] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [ ] Focused tests и `./gradlew test` проходят.

## Не входит

SMTP validation, DNS lookup и Unicode local-part.
