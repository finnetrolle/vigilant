# VIG-10-07: Contextual fallback для ОМС

**Статус:** Done
**Epic:** [EPIC-10](../../epics/epic_10_pii_detection_quality.md)
**Ветка:** Contextual recovery > OMS
**Зависит от:** [VIG-10-01](issue_10_01_quality_diagnostics.md)
**Блокирует:** [VIG-10-08](issue_10_08_quality_qualification.md)
**Связанные требования:** `MVP-15`, `MVP-19`
**Оценка:** 2-3 инженерных дня
**Уверенность:** Medium

## Результат

`PiiDetector.detect` сохраняет strict Mod10 path для ОМС и распознаёт
structurally plausible 16-digit policy number с ошибочной checksum только под
сильным ограниченным контекстом ОМС.

## Нормативная surface

- Checksum-valid candidate дополнительно принимает четыре группы по четыре
  digits с одиночным `U+002D`, `U+2010`, `U+2011`, `U+00A0` или `U+2009`,
  когда один separator используется последовательно между всеми groups.
- Checksum-valid candidate получает `VALIDATED` независимо от context.
- Checksum-invalid 16-digit candidate принимается только при whole-word `омс`
  либо последовательности whole words `полис` и `обязательного медицинского
  страхования` в окне 48 Unicode code points.
- Contextual candidate не может состоять из одной повторяющейся цифры и обязан
  иметь exact digit boundaries.
- Context не входит в finding span; `confidence` остаётся `null`.

## Критерии готовности

- [x] RED tests через `PiiDetector.detect` покрывают alternate valid separator
      и invalid-checksum contextual candidate.
- [x] Existing compact/grouped Mod10-valid cases сохраняют `VALIDATED`, exact
      offsets и recognizer ID.
- [x] Validated path имеет приоритет и не создаёт duplicate contextual finding
      на том же span.
- [x] Standalone invalid-checksum number, repeated digits, mixed separators,
      partial keyword и distant context остаются hard negatives.
- [x] Context vocabulary сопоставляется whole-word, locale-stable и без
      включения context в span.
- [x] Tuning/evaluation diagnostics разделяют validated/contextual contribution;
      aggregate exact precision не падает ниже epic floor.
- [x] Scanner bounded/linear, cancellation и no-retained-payload invariants
      сохраняются.
- [x] `recognizerVersion`, KDoc и canonical corpora обновлены.
- [x] Focused tests и `./gradlew build` проходят.

## Test/demo seam

Публичный `PiiDetector.detect`, OMS positive/hard-negative corpora и frozen
RedMadRobot per-evidence diagnostics.

## Не входит

Удалённая проверка полиса, legacy non-16-digit policies, arbitrary separator
collapse, standalone checksum-invalid values или изменение Mod10 algorithm.
