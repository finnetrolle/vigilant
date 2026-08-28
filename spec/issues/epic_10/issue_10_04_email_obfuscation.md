# VIG-10-04: Whitespace-обфускация email

**Статус:** Done
**Epic:** [EPIC-10](../../epics/epic_10_pii_detection_quality.md)
**Ветка:** Obfuscation tolerance > email whitespace
**Зависит от:** [VIG-10-01](issue_10_01_quality_diagnostics.md)
**Блокирует:** [VIG-10-08](issue_10_08_quality_qualification.md)
**Связанные требования:** `MVP-15`, `MVP-19`
**Оценка:** 3-4 инженерных дня
**Уверенность:** Medium

## Результат

`PiiDetector.detect` распознаёт email, намеренно разделённый ограниченными
ASCII-пробелами вокруг `@` или domain dots. Finding сохраняет непрерывный span
исходного payload, а нормализованный local/domain проходят те же strict
dot-atom и IDN/DNS проверки, что canonical email.

## Нормативная surface

- Между local part и `@`, после `@`, а также с обеих сторон domain dot
  допускается от одного до трёх `U+0020` только когда candidate содержит хотя
  бы один такой obfuscation gap.
- Пробелы внутри local-part atom, внутри DNS label, в начале/конце candidate и
  вокруг других local-part symbols не допускаются.
- После удаления только разрешённых gaps local part, IDN conversion, DNS
  labels и normalized length обязаны удовлетворять EMAIL_ADDRESS contract.
- Finding span включает внутренние пробелы, но не соседний prose whitespace или
  punctuation.
- Finding получает `FORMAT_ONLY`; `confidence` остаётся `null`.

## Критерии готовности

- [x] RED test через `PiiDetector.detect` фиксирует хотя бы по одному gap возле
      `@` и domain dot с точными UTF-8 offsets.
- [x] Compact canonical email behavior и metadata остаются неизменными.
- [x] Допустимые combinations 1..3 spaces распознаются после strict local
      normalization; четыре и более spaces отклоняются.
- [x] Hard negatives покрывают prose `word @ word`, arithmetic-like text,
      spaced punctuation, invalid local dots, single-label domain и Unicode
      local part.
- [x] Scanner не поглощает соседние words и продолжает поиск после invalid
      obfuscated candidate.
- [x] Tuning/evaluation diagnostics показывают рост EMAIL recall, а exact
      precision полного source-aligned report не падает ниже `0.75`.
- [x] Алгоритм bounded/linear, поддерживает cancellation и не сохраняет
      normalized email вне локальной validation.
- [x] `recognizerVersion`, KDoc и canonical corpora обновлены.
- [x] Focused tests и `./gradlew build` проходят.

## Test/demo seam

Публичный `PiiDetector.detect`, canonical positive/hard-negative corpora и
EMAIL section frozen RedMadRobot report.

## Не входит

Quoted local parts, comments, domain literals, Unicode local part, tabs,
zero-width characters, arbitrary whitespace collapse или global payload
normalization.
