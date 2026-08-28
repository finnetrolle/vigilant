# VIG-10-06: Contextual fallback для СНИЛС

**Статус:** Done
**Epic:** [EPIC-10](../../epics/epic_10_pii_detection_quality.md)
**Ветка:** Contextual recovery > SNILS
**Зависит от:** [VIG-10-01](issue_10_01_quality_diagnostics.md)
**Блокирует:** [VIG-10-08](issue_10_08_quality_qualification.md)
**Связанные требования:** `MVP-15`, `MVP-19`
**Оценка:** 3-4 инженерных дня
**Уверенность:** Medium

## Результат

`PiiDetector.detect` сохраняет строгий checksum-valid SNILS path и
дополнительно обнаруживает structurally plausible SNILS с ошибочной checksum,
когда bounded context однозначно называет СНИЛС. Finding отражает более слабое
основание через `CONTEXTUAL`, не принимая произвольные 11-значные числа.

## Нормативная surface

- Checksum-valid candidate поддерживает V1 forms и дополнительные
  `XXX.XXX.XXX XX`, `XXX XXX XXX XX`, а также эквивалентные single-separator
  forms с `U+00A0`, `U+2009`, `U+2010` или `U+2011`.
- Checksum-valid candidate получает `VALIDATED` независимо от наличия context.
- Checksum-invalid candidate может быть finding только при поддерживаемой
  11-digit surface и whole-word prefix `снилс` в окне не более 32 Unicode code
  points с любой стороны.
- Contextual candidate не может состоять из одной повторяющейся цифры и обязан
  иметь точные digit boundaries.
- Context не входит в finding span; `confidence` остаётся `null`.

## Критерии готовности

- [x] RED tests через `PiiDetector.detect` фиксируют valid alternate separator
      и invalid-checksum candidate под сильным context.
- [x] Existing checksum/threshold rules и `VALIDATED` evidence не меняются для
      V1 forms.
- [x] Один и тот же span не возвращается одновременно как `VALIDATED` и
      `CONTEXTUAL`; validated path имеет приоритет.
- [x] Standalone invalid-checksum 11-digit value, repeated digits, weak/partial
      keyword и context за пределом окна остаются hard negatives.
- [x] Mixed payload с invalid contextual candidate и последующим valid SNILS
      возвращает оба findings в canonical order.
- [x] Exact UTF-8 offsets включают внутренние separators и исключают context.
- [x] Tuning/evaluation diagnostics показывают вклад validated и contextual
      paths отдельно; aggregate exact precision не падает ниже epic floor.
- [x] Scanner bounded/linear, cancellation и no-retained-payload invariants
      сохраняются.
- [x] `recognizerVersion`, KDoc и canonical corpora обновлены.
- [x] Focused tests и `./gradlew build` проходят.

## Test/demo seam

Публичный `PiiDetector.detect`, SNILS positive/hard-negative corpora и frozen
RedMadRobot per-evidence diagnostics.

## Не входит

Удалённая проверка номера, global Unicode normalization, standalone
checksum-invalid values, ML classification или изменение официального
checksum algorithm.
