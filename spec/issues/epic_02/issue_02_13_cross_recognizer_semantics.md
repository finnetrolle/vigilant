# VIG-02-13: Cross-recognizer semantics

**Статус:** Ready for implementation  
**Epic:** [EPIC-02](../../epics/epic_02_fast_pii_detector.md)  
**Ветка:** Evidence > cross-recognizer semantics  
**Зависит от:** [VIG-02-04](issue_02_04_email_recognizer.md), [VIG-02-05](issue_02_05_phone_recognizer.md), [VIG-02-06](issue_02_06_payment_card_recognizer.md), [VIG-02-07](issue_02_07_ip_address_recognizer.md), [VIG-02-08](issue_02_08_iban_recognizer.md), [VIG-02-09](issue_02_09_ru_inn_recognizer.md), [VIG-02-10](issue_02_10_ru_snils_recognizer.md), [VIG-02-11](issue_02_11_ru_passport_recognizer.md), [VIG-02-12](issue_02_12_ru_oms_recognizer.md)  
**Блокирует:** [VIG-02-14](issue_02_14_quality_corpora.md), [VIG-02-15](issue_02_15_jmh_baseline.md)  
**Оценка:** 3-4 инженерных дня

## Результат

Полная реализация `FastPiiDetector` детерминированно объединяет результаты
всех реальных recognizer-ов, сохраняет разрешённые пересечения и выполняет
точную deduplication согласно epic.

## Наблюдаемое поведение

- Порядок: canonical recognizer order, затем `startUtf8`, `endUtf8`,
  `recognizerId`.
- Пересечения разных типов сохраняются.
- Удаляется только точный duplicate одного типа, span и recognizer ID.
- Первый full result совпадает с `stopOnFirst=true` при тех же enabled types.
- Результат стабилен при повторных и concurrent calls.

## Критерии готовности

- [ ] Все общие regression cases из epic реализованы.
- [ ] Проверены overlap, duplicates, ordering и filtered type sets.
- [ ] Проверены immutable return list и отсутствие retained request state.
- [ ] Cancellation во время candidate scan не возвращает partial results.
- [ ] Adversarial no-match inputs завершаются без catastrophic backtracking.
- [ ] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [ ] Focused tests и `./gradlew test` проходят.

## Не входит

Новые типы PII, разрешение неоднозначности типа и benchmark threshold.
