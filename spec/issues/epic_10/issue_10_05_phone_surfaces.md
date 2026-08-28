# VIG-10-05: Расширенные формы российских телефонов

**Статус:** Done
**Epic:** [EPIC-10](../../epics/epic_10_pii_detection_quality.md)
**Ветка:** Obfuscation tolerance > Russian phone surfaces
**Зависит от:** [VIG-10-01](issue_10_01_quality_diagnostics.md)
**Блокирует:** [VIG-10-08](issue_10_08_quality_qualification.md)
**Связанные требования:** `MVP-15`, `MVP-19`
**Оценка:** 3-4 инженерных дня
**Уверенность:** Medium

## Результат

`PiiDetector.detect` распознаёт распространённые российские phone surfaces с
Unicode-разделителями. Десятизначная national form и форма с префиксом `7`
принимаются только рядом с сильным phone context, чтобы прирост recall не
превращал обычные длинные числа в телефоны.

## Нормативная surface

- Existing `+7` и `8` forms дополнительно принимают одиночные `U+00A0`,
  `U+2009`, `U+2010`, `U+2011` и `U+2013` в позициях, где V1 допускает
  `U+0020` или `U+002D`.
- Normalized `+7`/`8` candidate по-прежнему содержит ровно 11 digits и
  получает `FORMAT_ONLY`.
- Ровно 10 national digits либо 11 digits с первым `7` принимаются только при
  whole-word context `телефон`, `тел`, `мобильный`, `моб`, `phone` или
  `contact` не далее 32 Unicode code points с любой стороны.
- Contextual national forms получают `CONTEXTUAL`; context не входит в span.
- Balanced area-code parentheses, digit boundaries, repeated separator reject
  и extension boundary остаются обязательными.

## Критерии готовности

- [x] RED tests через `PiiDetector.detect` покрывают Unicode separator и обе
      contextual national forms с точными byte offsets.
- [x] Standalone 10-digit и `7`-prefixed numbers без phone context остаются
      hard negatives.
- [x] Partial keywords, context за пределом окна, timestamps, order IDs,
      versions и длинные соседние digit runs не создают findings.
- [x] Context matching является whole-word и locale-stable для русского и
      ASCII vocabulary.
- [x] Extension не входит в finding, но candidate перед поддерживаемым
      extension delimiter обнаруживается.
- [x] Existing `+7`/`8` findings сохраняют прежние offsets/evidence; новые
      separators включаются в исходный span.
- [x] Tuning/evaluation diagnostics показывают рост PHONE recall при exact
      precision не ниже `0.90` для PHONE_NUMBER.
- [x] Scanner остаётся bounded/linear и вызывает cancellation checkpoints.
- [x] `recognizerVersion`, KDoc и canonical corpora обновлены.
- [x] Focused tests и `./gradlew build` проходят.

## Test/demo seam

Публичный `PiiDetector.detect`, PHONE positive/hard-negative corpora и frozen
RedMadRobot PHONE metrics.

## Не входит

Международные номера других стран, operator lookup, extension validation,
short service numbers, arbitrary digit normalization или телефонный ML model.
