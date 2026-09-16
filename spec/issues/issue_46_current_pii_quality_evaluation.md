# VIG-46: Оценить текущее качество детекции PII

- **ID:** `VIG-46`
- **Тип:** Issue
- **Статус:** Draft
- **Приоритет:** Medium
- **Зависит от:** нет
- **Выполненные предпосылки:** [Fast PII quality contract](../requirements/fast-pii.md#quality), [canonical и RedMadRobot reports](../../docs/development.md#pii-quality), [HiveTrace benchmark](../../docs/development.md#hivetrace-pii-benchmark), [AdvPIIBench](../../docs/development.md#advpiibench-adversarial-benchmark)
- **Блокирует:** нет
- **Связанные требования:** `MVP-15`, `MVP-19`
- **Оценка:** 1-2 инженерных дня после подключения корпусов
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
| HiveTrace | Entity и domain splits отдельно; domain breakdown по owning контракту | Per-type exact/relaxed метрики и false-positive rate на clean subset |
| AdvPIIBench | Baseline, каждая attack family, combined stages; negative/hard_negative отдельно | Exact/relaxed результаты по типам, recall по атакам и изменение относительно сопоставимого baseline; false-positive rate на обоих negative subsets |

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

## Открытые решения перед Ready for implementation

- Команды и владельцы manifests/views/negative subsets/caveats закреплены выше.
  Перед Ready подтвердить единый воспроизводимый snapshot всех четырёх корпусов
  и применимость опубликованного reporting contract к общему замеру.
- Убедиться, что все обязательные показатели доступны через owning runners;
  недостающий отчётный показатель проработать в соответствующей benchmark
  issue до её закрытия, без расширения runtime scope.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   текущая реализация и наблюдаемый baseline определены
  Acceptance:   0.25  команды обоих corpora закреплены; общая readiness замера ещё не проверена
  Boundaries:   0.0   оценка без tuning, runtime integration и performance claims
  Alternatives: 0.0  используются существующие runners и раздельные отчёты
  Assumptions:  0.25  общий snapshot и пригодность всех отчётных seams нужно подтвердить
  Aggregate:    0.10
```

Статус `Draft` сохраняется до отдельной readiness-проверки общего замера;
низкий aggregate не заменяет исполнимые команды и полную матрицу наблюдений.
