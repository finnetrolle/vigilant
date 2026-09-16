# VIG-46: Оценить текущее качество детекции PII

- **ID:** `VIG-46`
- **Тип:** Issue
- **Статус:** Ready for implementation
- **Приоритет:** Medium
- **Зависит от:** нет
- **Выполненные предпосылки:** [Fast PII quality contract](../requirements/fast-pii.md#quality), [canonical и RedMadRobot reports](../../docs/development.md#pii-quality), [HiveTrace benchmark](../../docs/development.md#hivetrace-pii-benchmark), [AdvPIIBench](../../docs/development.md#advpiibench-adversarial-benchmark)
- **Блокирует:** нет
- **Связанные требования:** `MVP-15`, `MVP-19`
- **Оценка:** 1-2 инженерных дня
- **Уверенность:** Medium
- **Архитектурный риск:** Low - запуск существующих benchmark-команд и анализ их отчётов без изменения runtime, публичного API или инфраструктуры.

## Контекст

Существующие canonical fixtures проверяют соблюдение заданных форматов, а
RedMadRobot даёт внешнюю оценку на одном русском корпусе. Для оценки текущей
реализации нужны также независимые русские примеры HiveTrace и adversarial
сценарии AdvPIIBench. [HiveTrace runner](../../docs/development.md#hivetrace-pii-benchmark)
и [AdvPIIBench runner](../../docs/development.md#advpiibench-adversarial-benchmark)
уже доступны.

Задача фиксирует качество одной версии `FastPiiDetector`, включая слабые
результаты. Старые отчёты служат историческим контекстом и не заменяют новый
прогон. Оценивается распознавание текста через `PiiDetector.detect`, а не
полнота извлечения полей HTTP-протокола или применение policy reactions.

## Результат

Воспроизводимый baseline текущего детектора: исходные безопасные JSON/Markdown
отчёты каждого корпуса и сводный документ
`docs/pii-quality-evaluation.md` с датой, версией реализации, метриками,
ограничениями интерпретации и приоритетами последующих улучшений.

Оценка отвечает на три вопроса: сколько размеченных сущностей обнаруживается,
какая доля срабатываний верна и какие форматы или атаки дают больше всего
пропусков и ложных срабатываний. Низкие значения являются результатом замера,
а не основанием менять правила, разметку или знаменатели в этой задаче.

## Context sources

- `spec/requirements/fast-pii.md#api`
- `spec/requirements/fast-pii.md#taxonomy`
- `spec/requirements/fast-pii.md#quality`
- `spec/requirements/fast-pii.md#lifecycle-и-privacy`
- `docs/development.md#pii-quality`
- `docs/development.md#устойчивый-запуск-проверок`
- `docs/pii-detection.md#ограничения`

## Протокол оценки

1. Перед прогоном зафиксировать Git revision, dirty state и digest изменений
   detector/evaluator, если они есть, JDK, ОС, дату, команды и enabled types.
   Все корпуса прогнать на одной версии detector и evaluator. Изменение этих
   inputs во время замера делает затронутые результаты несопоставимыми и
   требует нового прогона.
2. Использовать полный поддерживаемый контрактом каждого benchmark scored
   subset закреплённой версии данных. Manifest содержит upstream revision,
   SHA-256, размер, license declaration/attribution, версию adapter/mapping,
   matching rules, partition/view и количество принятых/отклонённых записей.
   Не выбирать удобную выборку и не подбирать правила по evaluation split.
3. Вызывать публичный `PiiDetector.detect(..., stopOnFirst=false)` с явным
   набором типов, соответствующим mapping корпуса. Сохранить исходный текст,
   Unicode и UTF-8 offsets. Gold annotations независимы от predictions.
4. Для каждого корпуса, partition/view и поддерживаемого типа публиковать
   `TP`, `FP`, `FN`, gold/prediction counts, exact и relaxed precision,
   recall, F1. Matching соответствует [quality contract](../requirements/fast-pii.md#quality).
   Это относится к scored subsets; canonical positive/hard-negative gates
   представлены exact-contract/rejection результатом и counts, mixed отдельно.
   Suppressed groups AdvPIIBench сохраняют suppression без восстановления counts.
   Непокрытые корпусом типы обозначать `not covered`; отсутствие denominator
   у метрики пояснять явно, не интерпретировать как подтверждение качества.
5. Не объединять разные корпуса в единственный headline F1. Для каждого
   корпуса отдельно сохранять micro aggregate и per-type результаты.
   Source-aligned разметка остаётся основным наблюдением; дополнительные
   согласованные views публикуются рядом с версиями и counts adjustments.
   Checksum-invalid значения не удалять из исходного gold ради метрики.
6. На чистых и hard-negative subsets, где они определены benchmark-контрактом,
   публиковать число записей со срабатыванием, общее число записей и их
   отношение. Это document-level false-positive rate, отдельный от entity FP.
   Примеры с неподдерживаемой PII не считать чистыми без явной семантики
   owning benchmark. Enabled types указывать рядом с показателем.
7. Сохранить исходные artifacts и manifest под
   `build/reports/pii/evaluation/<run-id>/`. Сводный документ содержит
   команды воспроизведения, ссылки на artifacts, полные агрегированные
   таблицы и ограничения. Raw dataset, payload, отдельные matched values
   и обратимые previews в документ и Git не попадают.

## Обязательная матрица наблюдений

| Корпус | Обязательные представления | Наблюдение |
|---|---|---|
| Canonical | Positive и hard-negative для каждого из 9 типов; mixed отдельно | Exact contract/rejection результаты и counts; synthetic gate не выдаётся за внешнее качество |
| RedMadRobot | Source-aligned, product-aligned, nested IP reference; full/tuning/evaluation отдельно | Per-type и aggregate exact/relaxed counts и метрики; сохранены исходный denominator и counts adjustments |
| HiveTrace | Source-aligned и product-aligned; entity, domain, каждый из 9 domain codes и Full отдельно | Per-type exact/relaxed метрики, adjustment counts и false-positive rate на clean subset |
| AdvPIIBench | Baseline, PII-only и combined; все 6 families в обоих attack stages, все 10 combined context configurations и их пересечения с families; negative/hard_negative отдельно | Exact/relaxed результаты по типам, recall по атакам и изменение относительно сопоставимого baseline; false-positive rate на обоих negative subsets; suppression по owning контракту |

Для AdvPIIBench detection постороннего span, в том числе внутри
`pi_few_shot_safe`, не засчитывается как обнаружение атакованного gold value.
Неаннотированные вспомогательные spans и редкие buckets интерпретируются
строго по [AdvPIIBench contract](../requirements/fast-pii.md#advpiibench),
с указанием его caveats и privacy floor.

## Критерии готовности

- [ ] Доступные HiveTrace и AdvPIIBench воспроизведены; их pinned inputs, mapping, splits, команды и
      ограничения опубликованы у постоянных owners и использованы в прогоне.
- [ ] Все четыре строки матрицы имеют свежие artifacts на одной зафиксированной
      реализации. Manifest позволяет проверить input identity и coverage;
      отсутствующий или неуспешный прогон обозначен как неполный и не закрывает issue.
- [ ] Сводные таблицы сверены с JSON каждого runner: counts совпадают,
      P/R/F1 воспроизводятся из TP/FP/FN, доли ложных срабатываний имеют
      явные числитель и знаменатель. Несовместимые views не объединены.
- [ ] Отдельно разобраны taxonomy mismatch, checksum/context ограничения,
      границы spans, Unicode/обфускация и gaps разметки. Недоказанные причины
      помечены гипотезами; выводы опираются на безопасные aggregate diagnostics.
- [ ] Составлен приоритизированный список следующих улучшений с затронутыми
      типами, корпусами и наблюдаемыми ошибками. Он не включает настройку
      production rules или изменение gold в рамках этого замера.
- [ ] `docs/pii-quality-evaluation.md` содержит итог и воспроизведение;
      `docs/pii-detection.md` ссылается на новый baseline. Отчёт не утверждает
      полную готовность gateway, выполнение JMH qualification или качество
      на реальном production traffic.
- [ ] Проверены отсутствие raw PII в публикуемых artifacts, стабильность
      detector inputs за время прогона, ссылки документации и каталог задач.

## Проверки и основной seam

Основной seam - реальные benchmark-команды и их JSON/Markdown output,
сверенный с закреплёнными annotations и independently defined matching.
Сводка не вычисляет gold из результатов detector.

Уже доступные команды: `./gradlew piiQualityReport` и
`./gradlew redMadRobotPiiBenchmark`; offline input для RedMadRobot задаётся
`-PredMadRobotPiiDataset=/absolute/path/to/test.csv`.
AdvPIIBench: `./gradlew advPiiBenchmark`; offline:
`./gradlew --offline advPiiBenchmark -PadvPiiCorpusDirectory=/absolute/path/to/advpii`.
Каталог содержит `train-00000-of-00001.parquet`; pinned manifest, source-aligned
baseline/PII-only/combined views, negative/hard_negative FPR, few-shot caveat
и privacy floor 5 distinct input IDs принадлежат
[методике](../../docs/development.md#advpiibench-adversarial-benchmark).
HiveTrace: `./gradlew hiveTracePiiBenchmark`;
offline: `./gradlew --offline hiveTracePiiBenchmark -PhiveTracePiiCorpusDirectory=/absolute/path/to/hivetrace`.
Запуски последовательны и используют существующий `scripts/check-run`.
Финальные documentation checks: `./gradlew validateWorkItems` и
`git diff --check`. Изменение evaluator при обнаружении дефекта требует
отдельного исправления с focused tests и повторного сопоставимого замера.

## Не входит

Подключение новых корпусов или написание их adapters, изменение recognizers,
новые PII types, исправление benchmark annotations, обучение/сравнение ML-моделей,
tuning, сбор production payload, HTTP/protocol/enforcement E2E, latency/JMH
qualification, новый release threshold или автоматизация регулярных прогонов.

## Подтверждённая готовность к исполнению

Readiness проверена 2026-09-16 на revision
`499c6d1a34e5af80be1014f3858ce4cc789e52df`: прочитаны owning contracts,
Gradle tasks, entry points и report writers, проверена структура существующих
JSON artifacts. Это проверка доступности измерения; свежие результаты всех
четырёх прогонов и итоговый baseline остаются результатом выполнения issue.

### Воспроизводимые inputs

- Canonical: version `pii-corpus-v1`, 18 positive/hard-negative TSV и `mixed.tsv`
  из [repository fixtures](../../src/test/resources/io/vigilant/detectors/pii/quality/canonical/).
  В manifest замера записать Git revision и SHA-256 каждого fixture.
- RedMadRobot: revision `f77ea831274daf980cc45c61a93c226be9d978d6`,
  `test.csv`; [pinned metadata](../../src/test/resources/io/vigilant/detectors/pii/benchmark/redmadrobot/metadata.properties).
- HiveTrace: revision `cd6a18ace16daf23e79247ccf1e2b245d4054654`,
  entity и domain Parquet; [pinned metadata](../../src/test/resources/io/vigilant/detectors/pii/benchmark/hivetrace/metadata.properties).
- AdvPIIBench: revision `02741d9f99a91b8fdcf48f4316a2c73be7a7449a`,
  `train-00000-of-00001.parquet`; [pinned metadata](../../src/test/resources/io/vigilant/detectors/pii/benchmark/advpii/metadata.properties).

При readiness все четыре внешних файла доступны в локальных build-кешах;
их размеры и SHA-256 совпали с metadata. При исполнении runners повторяют
integrity/coverage checks. Все четыре Gradle tasks используют текущие main/test
outputs одного checkout. Перед первым прогоном зафиксировать новый общий
snapshot detector/evaluator, fixtures, metadata, build configuration и JDK/OS;
передавать один `--snapshot` в последовательные `scripts/check-run` запуски.
Для каждого запуска объявить inputs и соответствующий report directory как
artifact, проверить terminal exit 0 и `applicability: current`. JSON/Markdown
скопировать без изменения в отдельные corpus-подкаталоги
`build/reports/pii/evaluation/<run-id>/`, сохранить их digests и run IDs в
общем manifest. Readiness revision и прежние artifacts не подменяют этот замер.

### Источники обязательных показателей

| Корпус | JSON под `build/reports/pii/` | Поля для сводки |
|---|---|---|
| Canonical | `canonical/pii-quality-report.json` | `corpus.perType` и успешный exit подтверждают positive/rejection gates; `metrics.aggregate/perType` относятся только к mixed |
| RedMadRobot | `redmadrobot/redmadrobot-pii-benchmark.json` | `sourceAligned`, `productAligned`, `nestedIpAligned`: `metrics` и `partitions.full/tuning/evaluation`; `coverage`, `productAligned.adjustments`, `nestedIpAligned.reference` |
| HiveTrace | `hivetrace/hivetrace-pii-benchmark.json` | `partitions`: `coverage`, `sourceAligned`, `productAligned`, `adjustmentCounts`, `cleanFpr`; общие `enabledTypes` и `notCovered` |
| AdvPIIBench | `advpii/advpii-benchmark.json` | `coverage`, `subsets` с `micro/perType`, `sourceAligned`, `comparableBaseline`, `recallDeltaPercentagePoints`; `documentFalsePositiveRates`, `privacyFloor`, `enabledTypes`, `notCovered` |

Сводка использует существующие reports без нового evaluator или adapters.
Если отдельные gold/prediction counts отсутствуют, они однозначно вычисляются
как `TP + FN` и `TP + FP`. Поле `overlap` AdvPIIBench соответствует relaxed
matching; переименование допустимо только в сводке, исходные JSON неизменны.
Для каждой метрики проверять её denominator: precision `TP + FP`, recall
`TP + FN`, F1 `2*TP + FP + FN`. При нуле показывать `N/A` с пояснением, даже
если исходный runner сериализует `0.0`; исходное значение сохраняется в artifact.
Suppressed groups не расшифровывать, пересекающиеся rollups не суммировать.
Document FPR брать только из опубликованных negative/clean subsets.

Обязательный анализ причин ограничен имеющимися безопасными aggregate
diagnostics и опубликованными ограничениями. Отсутствующий breakdown
обозначается как ограничение доказательств; неподтверждённая причина остаётся
гипотезой. Добавление диагностики или исправление evaluator требует отдельной
задачи и не является скрытым условием этого замера.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   текущая реализация и наблюдаемый baseline определены
  Acceptance:   0.0   матрица сопоставлена с полями reports, gates и privacy suppression
  Boundaries:   0.0   оценка без tuning, runtime integration и performance claims
  Alternatives: 0.0  используются существующие runners и раздельные отчёты
  Assumptions:  0.0   pinned inputs проверены; общий snapshot и повторные проверки заданы
  Aggregate:    0.0
```

Открытых решений перед исполнением нет. Статус `Ready for implementation`
подтверждает определённость замера; acceptance criteria закрываются только
после свежих прогонов, сверки сводки и проверки публикуемых artifacts.
