# VIG-02-02: Payload preflight и UTF-8 offsets

**Статус:** Ready for implementation  
**Epic:** [EPIC-02](../../epics/epic_02_fast_pii_detector.md)  
**Ветка:** Public contract  
**Зависит от:** [VIG-02-01](issue_02_01_public_contract.md)  
**Блокирует:** [VIG-02-03](issue_02_03_recognizer_pipeline.md)  
**Оценка:** 2-3 инженерных дня

## Результат

Внутренний preflight одним линейным проходом валидирует UTF-16 payload,
вычисляет точный UTF-8 byte size и предоставляет преобразование исходных
character boundaries в UTF-8 byte offsets без полной `ByteArray`-копии.

## Наблюдаемое поведение

- Пустой payload валиден.
- Ровно `1 MiB` UTF-8 валиден, первый байт сверх лимита даёт
  `PAYLOAD_TOO_LARGE`.
- Unpaired surrogate даёт `INVALID_UNICODE` с приоритетом над размером.
- Ошибки не содержат payload или preview.
- ASCII, кириллица, emoji и supplementary code points дают точные offsets.

## Тестовый seam

Pure unit tests preflight и offset conversion. Integration с pipeline входит в
[VIG-02-03](issue_02_03_recognizer_pipeline.md).

## Критерии готовности

- [ ] Все правила разделов «Размер payload» и «Ошибки входа» epic покрыты.
- [ ] Нет полной UTF-8 копии payload во время preflight.
- [ ] Проверены invalid surrogate и граничные размеры.
- [ ] Проверены byte offsets на mixed Unicode.
- [ ] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [ ] Focused tests и `./gradlew test` проходят.

## Не входит

Пустой `enabledTypes`, stop-on-first и запуск recognizer-ов.
