# VIG-23: HiveTrace PII-Bench RU

- **ID:** `VIG-23`
- **Тип:** Issue
- **Статус:** Ready for implementation
- **Приоритет:** Medium
- **Зависит от:** нет
- **Выполненные предпосылки:** [Fast PII quality contract](../requirements/fast-pii.md#quality), [RedMadRobot benchmark](../../docs/development.md#external-pii-benchmark)
- **Блокирует:** [VIG-46](issue_46_current_pii_quality_evaluation.md)
- **Связанные требования:** `MVP-15`, `MVP-19`
- **Оценка:** 2-3 инженерных дня
- **Уверенность:** Medium
- **Архитектурный риск:** Low - изолированное test/benchmark tooling поверх существующего detector API; runtime, deployment и публичные product-контракты не меняются.

## Context sources

- `spec/requirements/fast-pii.md#api`
- `spec/requirements/fast-pii.md#taxonomy`
- `spec/requirements/fast-pii.md#quality`
- `spec/requirements/fast-pii.md#lifecycle-и-privacy`
- `docs/development.md#external-pii-benchmark`
- `docs/agent-workflow.md#behavior-first-development-and-selective-tdd`
- `docs/development.md#устойчивый-запуск-проверок`

## Контекст

Текущий external quality seam измеряет `FastPiiDetector` на
RedMadRobot PII benchmark. Для независимой проверки нужен второй
русскоязычный corpus с реалистичными support и messenger сценариями,
точными character spans и чистыми примерами.

Выбран [HiveTrace PII-Bench RU](https://huggingface.co/datasets/hivetrace/pii-bench),
опубликованный под Apache-2.0. Он содержит пересекающиеся с
Vigilant типы `EMAIL`, `PHONE_NUMBER`, `BANK_CARD_NUMBER`, `INN`,
`SNILS` и `PASSPORT_NUMBER`, а также примеры без PII.

## Результат

Явно запускаемый Gradle benchmark воспроизводимо оценивает
`FastPiiDetector` на закреплённой версии HiveTrace PII-Bench RU. Отдельные
JSON и Markdown reports публикуют coverage, per-type exact/relaxed
metrics и false-positive rate на чистых примерах, не превращая
внешний dataset в canonical contract или release gate.

## Согласованный scope

- Dataset закрепляется immutable revision, exact size и SHA-256.
- Preparation task проверяет download и offline input до parsing; dataset
  не добавляется в git и production runtime classpath.
- Adapter явно сопоставляет только типы, пересекающиеся с текущей
  таксономией Vigilant. Неподдерживаемые и taxonomy-mismatched
  spans отражаются в coverage, а не исчезают из denominator незаметно.
- Source character spans преобразуются в UTF-8 byte offsets без
  case folding, Unicode normalization или изменения input text.
- Report разделяет entity и domain splits, публикует per-type и
  aggregate exact/relaxed metrics, processed/rejected counts и false positives
  на примерах без gold spans.
- Reports, logs и diagnostics не содержат raw text, matched values,
  tokens, candidates или reversible fingerprints.
- Обычные `build` и `test` не требуют сети и не запускают
  external benchmark.

## Закреплённый corpus

Revision: `cd6a18ace16daf23e79247ccf1e2b245d4054654`.
Upstream license declaration: `Apache-2.0`; attribution: `HiveTrace PII-Bench RU`.
Это metadata declaration, не юридическое заключение.

| Split | Pinned URL | Exact bytes | SHA-256 |
|---|---|---:|---|
| domain | [domain-00000-of-00001.parquet](https://huggingface.co/datasets/hivetrace/pii-bench/resolve/cd6a18ace16daf23e79247ccf1e2b245d4054654/data/domain-00000-of-00001.parquet) | 100083 | `7ef3574273c0fe3a987981e38e32cd2764031a82766ca5b235bfccd1f27058eb` |
| entity | [entity-00000-of-00001.parquet](https://huggingface.co/datasets/hivetrace/pii-bench/resolve/cd6a18ace16daf23e79247ccf1e2b245d4054654/data/entity-00000-of-00001.parquet) | 78391 | `6e0c77d566c2f04e7213917b26f1038336e28fc006551fac7a7f44f7627d5f96` |

При исследовании 2026-09-15 оба файла скачаны, размеры и SHA-256 проверены
до чтения Parquet. Следующие counts вычислены по этим bytes независимо от
detector outputs; это corpus qualification, не результат benchmark или tests.

| Split | Total / processed cases | Rejected | Clean cases | Source spans | Mapped/scored source spans | Unsupported spans | Product-aligned spans |
|---|---:|---:|---:|---:|---:|---:|---:|
| entity | 910 | 0 | 0 | 910 | 420 | 490 | 420 |
| domain | 900 | 0 | 378 | 757 | 397 | 360 | 369 |
| Full | 1810 | 0 | 378 | 1667 | 817 | 850 | 789 |

`entity` содержит по 70 записей и spans каждого из 13 исходных типов.
`domain` содержит по 100 записей в `L-CHAT`, `L-DIALOG`, `S-AUTO`, `S-BANK`,
`S-DELIVERY`, `S-HR`, `S-RE`, `S-SUPPORT`, `S-TELECOM`. Внутри splits нет
повторяющихся ID или текстов; одинаковых текстов между splits не обнаружено.

| Source type | Vigilant type | Entity spans | Domain spans |
|---|---|---:|---:|
| EMAIL | EMAIL_ADDRESS | 70 | 103 |
| PHONE_NUMBER | PHONE_NUMBER | 70 | 147 |
| BANK_CARD_NUMBER | PAYMENT_CARD | 70 | 22 |
| INN | RU_INN | 70 | 48 |
| SNILS | RU_SNILS | 70 | 27 |
| PASSPORT_NUMBER | RU_PASSPORT | 70 | 50 |
| NAME | unsupported | 70 | 158 |
| ADDRESS | unsupported | 70 | 106 |
| CVC | unsupported | 70 | 7 |
| KPP | unsupported | 70 | 24 |
| OGRN | unsupported | 70 | 23 |
| OGRNIP | unsupported | 70 | 17 |
| TOKEN | unsupported | 70 | 25 |

## Контракт адаптации и оценки

### Input и offsets

Оба Parquet-файла имеют поля `id: string`, `domain: string`, `text: string`,
`entities: list<struct<end: int64, start: int64, text: string, type: string>>`.
Adapter проверяет схему, обязательные значения, уникальность ID внутри split,
известные labels и `0 <= start < end <= text.codePointCount`.
`entities[].text` должен точно совпадать с исходным диапазоном `[start, end)`.
Source offsets трактуются как Unicode code-point indices и преобразуются
в UTF-8 byte offsets; UTF-16 indices Kotlin не подставляются напрямую.
Input text не нормализуется, не меняет регистр и не реконструируется.
`L-DIALOG` обрабатывается как целая исходная строка `text`, без JSON parsing
вложенного диалога или объединения/извлечения его сообщений.

Malformed schema/нечитаемый Parquet дают safe failure; непригодная разметка
отклоняет целую запись и учитывается в rejected coverage, без частичного scoring.
Для pinned corpus ожидается ноль rejected; расхождение с закреплёнными counts
не позволяет объявить прогон полным. Errors, причины отклонения и parser failures
не выводят raw поля, значения или небезопасные exception causes.

### Mapping и gold views

`FastPiiDetector.detect` вызывается с `stopOnFirst=false` и шестью enabled types
из таблицы mapping. Этот набор явно публикуется рядом с metrics и clean FPR.
`IP_ADDRESS`, `IBAN`, `RU_OMS` обозначаются как `not covered` этим benchmark.
Записи с unsupported entities остаются в processed coverage; они не считаются
чистыми только потому, что их mapped gold пуст.

- **Source-aligned** - основной view: все 817 mapped spans с исходными
  границами. Сохраняются 10- и 12-значные ИНН, checksum-invalid значения и
  passport forms независимо от accepted surface текущего recognizer.
- **Product-aligned v1** - дополнительный view: только adjustment
  `LEGAL_ENTITY_INN_TAXONOMY_MISMATCH` исключает gold `INN` из ровно 10 ASCII
  digits. В pinned corpus это 28 spans из `domain`; 90 двенадцатизначных ИНН
  остаются, даже с неверной checksum. Rule version, provenance и zero/nonzero
  adjustment counts публикуются; predictions не отфильтровываются.
- `PASSPORT_NUMBER` сохраняет опубликованные границы в обоих views.
  Compact forms, отдельный номер и другие размеченные формы не исключаются
  по правилам runtime и не расширяются до другого span. Passport merge из
  RedMadRobot сюда не переносится: это другой annotation contract.

### Splits, metrics и privacy

Scoring использует существующий
[PiiQualityScorer](../../src/test/kotlin/io/vigilant/detectors/pii/quality/PiiQualityScorer.kt)
и [exact/relaxed contract](../requirements/fast-pii.md#quality): one-to-one
maximum-cardinality matching внутри case/type, per-type и micro aggregate
TP/FP/FN и P/R/F1 отдельно для каждого mode и gold view.

Основные результаты публикуются отдельно для `entity`, `domain` и каждого
domain code. `Full` - дополнительный aggregate смеси этих двух splits:
складываются TP/FP/FN, затем вычисляются ratios; F1 разных splits не усредняются.
Report явно показывает состав смеси и не представляет её распределением
production traffic. Splits не превращаются в tuning/evaluation partitions.

Clean subset определяется только исходным `entities.isEmpty()` до mapping и
product adjustments. Document-level FPR = число clean cases хотя бы с одним
finding / число processed clean cases. Публикуются числитель, знаменатель и
ratio: у полного `domain` denominator 378, у каждого домена свой clean count.
Для `entity` denominator 0, ratio `null`/`N/A`, а не свидетельство нулевого FPR.
Entity FP публикуются отдельно от document-level FPR.

Отдельные JSON и Markdown artifacts под `build/reports/pii/hivetrace/` содержат
provenance, coverage, mapping, enabled types, adjustments и metrics из одного
report model. Reports, logs и diagnostics не содержат raw text, отдельные
matched values/spans, tokens, candidates или reversible fingerprints.
Для дополнительных mismatch breakdown действует privacy floor существующей
[external methodology](../../docs/development.md#external-pii-benchmark).
HiveTrace evidence остаётся external/non-gating, отдельно от canonical и
RedMadRobot; numeric release threshold не вводится.

## Критерии готовности

- [ ] Metadata resources содержат закреплённые выше revision, URL, license,
      exact size, SHA-256 и counts обоих файлов; реальный adapter воспроизводит
      coverage, type counts и ноль rejected на этих bytes.
- [ ] Focused synthetic Parquet tests покрывают schema validation, duplicate
      IDs, mapping/unsupported types, Unicode code-point -> UTF-8 offsets,
      invalid bounds/text mismatch, clean examples и safe failures.
- [ ] Оба gold views сохраняют согласованные denominators; только 28
      десятизначных ИНН исключаются в product view, passport spans и
      checksum-invalid значения остаются без изменения, predictions сохранены.
- [ ] Явная `hiveTracePiiBenchmark` создаёт воспроизводимые JSON/Markdown
      reports с provenance, coverage, exact/relaxed per-type и aggregate metrics
      обоих splits, каждого домена и явно обозначенного Full aggregate.
- [ ] Clean FPR имеет явные enabled types, числитель и denominator 378 для
      полного domain; entity получает `null`/`N/A`, unsupported PII не считается clean.
- [ ] Сетевой и offline paths проверяют одинаковые bytes, а partial
      или invalid input ни одного из двух файлов не принимается повторным
      запуском; повторная подготовка перепроверяет кеш до parsing.
- [ ] Reports не смешиваются с canonical и RedMadRobot evidence,
      не объявляются release gate и не раскрывают PII values.
- [ ] Dataset остаётся вне Git, Parquet-reader только в test dependencies;
      обычные `build`/`test` не скачивают corpus и не запускают external benchmark.
- [ ] Development guide описывает HiveTrace, online/offline запуск и методику;
      requirements coverage отражает фактически полученный external seam.
- [ ] Focused tests, `./gradlew build` и `./gradlew validateWorkItems` проходят.

## Test/demo seam

Основной observable seam - новые явные Gradle tasks `prepareHiveTracePiiCorpus`
и `hiveTracePiiBenchmark`, принимающие pinned download или offline-каталог
с обоими исходными Parquet-файлами. JVM-reader работает только в test classpath.
Ни один файл не парсится до проверки его size/SHA-256; проверенные временные
файлы публикуются атомарно, полный benchmark требует оба корректных split files.
Offline property и имена report-файлов определяет исполнитель и документирует
вместе с точными командами; каталог reports закреплён выше.

## Согласованный план реализации

План и решения согласованы оператором 2026-09-15. Агент-исполнитель обязан
руководствоваться этим планом и контрактом выше. Существенное отступление
обсуждается с оператором; выбор JVM Parquet library, внутренних имён классов
и деталей хранения test fixtures остаётся за исполнителем в заданных границах.

Текущий код уже предоставляет общий `PiiQualityScorer` и pattern подготовки
корпуса в
[RedMadRobotCorpusPreparationMain](../../src/test/kotlin/io/vigilant/detectors/pii/benchmark/redmadrobot/RedMadRobotCorpusPreparationMain.kt).
Scorer переиспользуется напрямую, поскольку matching contract одинаков;
CSV/BIO adapter, frozen split и паспортные adjustments RedMadRobot принадлежат
его корпусу и не становятся контрактом HiveTrace.

1. В новых metadata-ресурсах `benchmark/hivetrace` закрепить
   [проверенный corpus](#закреплённый-corpus), чтобы preparation, validation и
   reports имели один источник provenance и expected counts.
2. В [build.gradle.kts](../../build.gradle.kts) добавить две явные tasks,
   test-only Parquet dependency, pinned download/offline import и проверку
   кеша. Использовать существующий подход atomic publication, чтобы сбой
   не оставлял принимаемый partial input. Обычный build остаётся без corpus I/O.
3. В новом пакете `io.vigilant.detectors.pii.benchmark.hivetrace` реализовать
   adapter с [input/offset validation](#input-и-offsets), узким mapping и
   обоими [gold views](#mapping-и-gold-views). Сохранить исходный текст, coverage
   и безопасные причины отклонения; detector outputs не определяют gold.
4. Добавить runner поверх `FastPiiDetector` и общего scorer, затем JSON/Markdown
   writer из одной модели согласно [reporting contract](#splits-metrics-и-privacy).
   Сначала сформировать независимые split/domain counts, затем Full aggregate
   и clean FPR, чтобы исходные denominators оставались проверяемыми.
5. Обновить `docs/development.md` и `docs/requirements-coverage.md` по фактически
   реализованному seam. Выполнять связанные focused tests после каждой
   небольшой части; завершить online/offline воспроизведением и gates ниже.

Порядок: metadata -> preparation -> adapter -> runner/reports. Synthetic
fixtures задают независимые ожидаемые offsets и counts, без внешнего corpus
в canonical fixtures и без вычисления oracle production recognizer-ами.

## Проверки

Следующие проверки относятся к будущей реализации и пока не объявляются
пройденными:

- Synthetic Parquet fixtures: схема, ID, известные/unsupported labels,
  ASCII/кириллица/emoji, code-point boundaries, неверные offsets и несовпадение
  текста, clean/unsupported-only cases, 10/12-digit INN и passport boundaries.
- Независимые литеральные TP/FP/FN для exact/relaxed и split/domain/Full,
  clean FPR с нулевым знаменателем; JSON и Markdown содержат одни результаты
  и не раскрывают synthetic privacy markers или исходные поля.
- Preparation на download/offline путях: size/hash failure, повреждённый кеш,
  прерванный download, сбой одного из двух файлов и повторный запуск.
  Ни partial input, ни stale кеш не проходят до parsing.
- Реальные online/offline benchmark runs на одинаковом detector/evaluator
  воспроизводят закреплённые counts и одинаковые reports; обычные tests
  работают на synthetic fixtures без доступа к corpus network.

Команды выполнять последовательно через
[durable runner](../../docs/development.md#устойчивый-запуск-проверок):

```bash
./gradlew test -x processTest --tests 'io.vigilant.detectors.pii.benchmark.hivetrace.*' --tests 'io.vigilant.detectors.pii.quality.PiiQualityScorerTest'
./gradlew hiveTracePiiBenchmark
./gradlew build
./gradlew validateWorkItems
git diff --check
```

Исполнитель добавляет точную offline-команду после выбора имени property;
если меняется общий preparation helper, focused regression включает его
существующих RedMadRobot consumers.

## Не входит

Изменение production recognizer behavior, добавление имён, адресов,
`CVC`, `KPP`, `OGRN`, `OGRNIP` и `TOKEN` в таксономию Vigilant, обучение
модели, tuning по test split, runtime integration, enforcement, automatic
download из обычного build, release threshold и юридическая оценка
лицензии.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   external report и основной seam определены
  Acceptance:   0.0   revision, integrity metadata, counts и проверки закреплены
  Boundaries:   0.0   benchmark изолирован от production behavior
  Alternatives: 0.0   separate non-gating evidence и shared scorer согласованы
  Assumptions:  0.0   corpus проверен; taxonomy, splits и clean FPR согласованы
  --------------------------------------------------------------
  Aggregate:    0.0
```

Готовность к реализации подтверждена 2026-09-15: corpus квалифицирован,
существенные решения и критерии согласованы, классификация риска `Low`
проверена. Статус изменён по просьбе оператора; реализация и её проверки
ещё предстоят.
