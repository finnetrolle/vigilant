# VIG-02-11: Recognizer `RU_PASSPORT`

**Статус:** Ready for implementation  
**Epic:** [EPIC-02](../../epics/epic_02_fast_pii_detector.md)  
**Ветка:** Recognizers > RU_PASSPORT  
**Зависит от:** [VIG-02-03](issue_02_03_recognizer_pipeline.md)  
**Оценка:** 2-3 инженерных дня

## Результат

Fast detector распознаёт четыре зафиксированные формы серии и номера паспорта
только при обязательном русском контексте, возвращая metadata
`fast.ru_passport`, `1.0.0`, `CONTEXTUAL`.

## Критерии готовности

- [ ] Поддержаны ровно четыре формы из epic.
- [ ] Окно ограничено 64 Unicode code points с каждой стороны.
- [ ] Контекст принимает prefix `паспорт` либо отдельные слова `серия` и
  `номер` одновременно.
- [ ] Одного слова `серия` или `номер` недостаточно.
- [ ] Контекст не входит в finding span.
- [ ] Проверены границы payload, supplementary code points и false positives.
- [ ] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [ ] Focused tests и `./gradlew test` проходят.

## Не входит

Region/year plausibility, OCR variants и проверка документа во внешней системе.
