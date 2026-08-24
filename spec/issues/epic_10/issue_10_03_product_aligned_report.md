# VIG-10-03: Product-aligned external quality report

**Статус:** Ready for implementation
**Epic:** [EPIC-10](../../epics/epic_10_pii_detection_quality.md)
**Ветка:** Evidence > product-aligned semantic view
**Зависит от:** [VIG-10-01](issue_10_01_quality_diagnostics.md)
**Блокирует:** [VIG-10-08](issue_10_08_quality_qualification.md)
**Связанные требования:** `MVP-15`, `MVP-19`
**Оценка:** 3-4 инженерных дня
**Уверенность:** Medium

## Результат

External quality report рядом с неизменёнными source-aligned metrics публикует
отдельный product-aligned view. Он явно показывает, какая часть разницы вызвана
таксономией или entity boundaries, а какая остаётся дефектом recall/precision
текущего detector.

## Нормативные adjustments

- Upstream `INN` с ровно 10 ASCII digits классифицируется как
  `LEGAL_ENTITY_INN_TAXONOMY_MISMATCH` и не преобразуется в `RU_INN` в
  product-aligned view. Source-aligned mapping и denominator не меняются.
- Upstream `INN` с 12 ASCII digits продолжает оцениваться как `RU_INN` даже
  при invalid checksum; validation failure не является taxonomy mismatch.
- Две соседние upstream `PASSPORT` entities могут объединяться в одну expected
  entity только когда одна содержит ровно 4 digits, следующая ровно 6 digits,
  обе находятся в одном record, не overlap и gap между ними содержит только
  bounded whitespace/punctuation либо слова `серия`/`номер` в установленном
  порядке. Полный объединённый source span и adjustment count публикуются без
  исходных значений.
- Checksum-invalid card, SNILS или OMS не удаляются из product-aligned
  denominator на основании текущих recognizer rules.
- Все adjustments версионируются, перечисляются в Markdown provenance и имеют
  точные aggregate counts.

## Критерии готовности

- [ ] Source-aligned JSON/Markdown metrics и matching rules остаются доступны
      без изменения исходной семантики.
- [ ] Product-aligned section имеет отдельные aggregate/per-type metrics и не
      смешивается с headline source-aligned result.
- [ ] Synthetic tests покрывают legal-entity INN mismatch, physical-person INN,
      mergeable/non-mergeable passport pairs и ambiguous gaps.
- [ ] Passport adjustment выполняет deterministic one-to-one grouping и не
      объединяет три entities, reversed pair или entities через произвольный
      текст.
- [ ] Report публикует counts каждого adjustment до scoring, чтобы изменение
      denominator не могло остаться незаметным.
- [ ] Product-aligned failures и reports не содержат raw spans, text, tokens,
      digit values или reversible fingerprints.
- [ ] `./gradlew redMadRobotPiiBenchmark`, focused tests и `./gradlew build`
      проходят.

## Test/demo seam

JSON/Markdown artifacts `redMadRobotPiiBenchmark` с независимыми
`sourceAligned` и `productAligned` sections на synthetic adapter fixtures и
pinned external corpus.

## Не входит

Добавление нового production `PiiType`, изменение `FastPiiDetector`, удаление
noisy checksum labels, переименование исходной RedMadRobot taxonomy или
leaderboard comparison.
