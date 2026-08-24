# VIG-10-01: Safe quality diagnostics и frozen evaluation split

**Статус:** Ready for implementation
**Epic:** [EPIC-10](../../epics/epic_10_pii_detection_quality.md)
**Ветка:** Evidence > safe diagnostics and frozen split
**Зависит от:** нет
**Блокирует:** [VIG-10-03](issue_10_03_product_aligned_report.md), [VIG-10-04](issue_10_04_email_obfuscation.md), [VIG-10-05](issue_10_05_phone_surfaces.md), [VIG-10-06](issue_10_06_snils_contextual.md), [VIG-10-07](issue_10_07_oms_contextual.md)
**Связанные требования:** `MVP-15`, `MVP-19`
**Оценка:** 3-4 инженерных дня
**Уверенность:** High

## Результат

RedMadRobot benchmark дополнительно публикует безопасную, воспроизводимую
диагностику unmatched gold/predicted spans и frozen tuning/evaluation
partitions. Команда показывает, какой класс несовпадения изменился, не раскрывая
исходный payload и не превращая внешний test set в canonical contract.

## Scope

- Stable case ID разделяется SHA-256 от versioned salt, dataset revision и
  case ID. Функция, salt, граница partition и точные counts фиксируются в
  metadata и тестах.
- Existing full source-aligned exact/relaxed report и matching semantics не
  изменяются.
- Для full, tuning и evaluation partitions публикуются одинаковые coverage и
  per-type exact/relaxed metrics.
- Safe aggregate mismatch buckets включают как минимум `NO_OVERLAPPING_FINDING`,
  `SPAN_MISMATCH`, `TYPE_MISMATCH` и `EXTRA_PREDICTION`.
- Дополнительные необратимые aggregate surface attributes могут включать digit
  count, ASCII/non-ASCII separator class, checksum-valid count и наличие
  обязательного discriminator. Они не публикуются на уровне case ID и не
  содержат literals, hashes исходных значений или редкие комбинации с count
  меньше согласованного privacy floor.
- Rejected alignment cases продолжают учитываться отдельно и не попадают ни в
  одну scored partition.

## Критерии готовности

- [ ] Existing baseline full metrics и coverage counts остаются byte-for-byte
      эквивалентны прежнему source-aligned report по числовым значениям.
- [ ] Frozen split воспроизводим на pinned dataset, не зависит от порядка
      filesystem, locale, timezone или JVM random seed.
- [ ] Tuning и evaluation coverage в сумме точно равны full processed/scored
      coverage без пересечения case IDs.
- [ ] Exact/relaxed one-to-one matching независимо проверен synthetic examples
      для каждого safe mismatch bucket.
- [ ] Privacy floor агрегатов проверяется тестами на редких категориях.
- [ ] JSON и Markdown reports не содержат raw payload, tokens, candidates,
      matched values, reversible previews или value hashes.
- [ ] `./gradlew redMadRobotPiiBenchmark`, focused tests и `./gradlew build`
      проходят.

## Test/demo seam

Generated JSON/Markdown artifacts `redMadRobotPiiBenchmark` и focused tests
benchmark scorer/report writer. Изменений production detector в issue нет.

## Не входит

Изменение recognizer behavior, product-aligned denominator, новый внешний
dataset, публикация per-case false negatives или автоматический release gate.
