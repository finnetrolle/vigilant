# VIG-02-08: Recognizer `IBAN`

**Статус:** Ready for implementation  
**Epic:** [EPIC-02](../../epics/epic_02_fast_pii_detector.md)  
**Ветка:** Recognizers > IBAN  
**Зависит от:** [VIG-02-03](issue_02_03_recognizer_pipeline.md)  
**Оценка:** 3-4 инженерных дня

## Результат

Fast detector распознаёт compact и canonical grouped IBAN, валидирует country
length из pinned SWIFT registry release 102 и streaming mod-97, возвращая
metadata `fast.iban`, `1.0.0+iban-registry.102`, `VALIDATED`.

## Критерии готовности

- [ ] Добавлен version-controlled country-length resource с provenance header.
- [ ] Resource загружается детерминированно и валидируется тестом.
- [ ] Поддержаны только compact и canonical four-character groups epic.
- [ ] Country code, total length, boundaries и mod-97 обязательны.
- [ ] Mod-97 не создаёт большой integer.
- [ ] Property tests используют фиксированный seed и checksum mutations.
- [ ] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [ ] Focused tests и `./gradlew test` проходят.

## Не входит

Обновление registry во время runtime и банковский account lookup.
