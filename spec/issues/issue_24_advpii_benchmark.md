# VIG-24: AdvPIIBench adversarial benchmark

- **ID:** `VIG-24`
- **Тип:** Issue
- **Статус:** Ready for implementation
- **Приоритет:** Medium
- **Зависит от:** нет
- **Выполненные предпосылки:** [Fast PII quality contract](../requirements/fast-pii.md#quality), [RedMadRobot benchmark](../../docs/development.md#external-pii-benchmark)
- **Блокирует:** [VIG-46](issue_46_current_pii_quality_evaluation.md)
- **Связанные требования:** `MVP-15`, `MVP-19`
- **Оценка:** 2-3 инженерных дня
- **Уверенность:** Medium
- **Архитектурный риск:** Low - изолированный benchmark и тестовый инструментарий без изменения runtime, публичного detector API или deployment responsibility.

## Context sources

- `spec/requirements/fast-pii.md#api`
- `spec/requirements/fast-pii.md#taxonomy`
- `spec/requirements/fast-pii.md#quality`
- `spec/requirements/fast-pii.md#lifecycle-и-privacy`
- `docs/development.md#external-pii-benchmark`
- `spec/issues/issue_46_current_pii_quality_evaluation.md#протокол-оценки`

## Контекст

Текущие canonical и RedMadRobot corpora проверяют обычные и часть
obfuscated surfaces, но не дают отдельного систематического отчёта
по inference-time attacks и PII-shaped hard negatives.

Выбран [AdvPIIBench](https://huggingface.co/datasets/roei-ar/AdvPIIBench),
опубликованный под CC BY 4.0. Он даёт character-accurate spans для
synthetic email, phone, payment card и IBAN, а также attack-family labels,
clean examples и hard negatives.

## Результат

Явно запускаемый Gradle benchmark воспроизводимо публикует
деградацию recall по каждой PII-level attack family, baseline и combined
stages, а также false-positive rate на clean и hard-negative subsets. Dataset
остаётся held-out non-gating evidence и не используется для tuning production
rules.

## Согласованный scope

- Dataset закрепляется по [manifest корпуса](#manifest-корпуса).
- Preparation task проверяет download и offline input до parsing; dataset
  не добавляется в git и production runtime classpath. Неуспешная загрузка
  или проверка не оставляет partial file, принимаемый следующим запуском.
- Новый adapter читает опубликованный Parquet через JVM reader с зависимостью
  только в тестовом инструментарии. Schema, categories, attack labels,
  уникальность `uid`, границы spans и соответствие исходному тексту проверяются
  до scoring. Ошибки дают безопасный код без исходных значений и parser payload.
- Adapter оценивает только `email`, `phone_number`,
  `credit_card_number` и `iban`; `ssn` не входит в текущую
  таксономию Vigilant и учитывается только в coverage. Mapping:
  `email -> EMAIL_ADDRESS`, `phone_number -> PHONE_NUMBER`,
  `credit_card_number -> PAYMENT_CARD`, `iban -> IBAN`.
  SSN-only positive rows не включаются в negative subsets.
- Input text читается byte-for-byte. NFC, NFD, NFKC, NFKD, trimming и
  удаление invisible characters запрещены, потому что изменяют
  attack surface или source offsets. Python Unicode code-point offsets
  преобразуются в UTF-8 offsets исходного `llm_input`; исходный срез должен
  точно совпадать с `value_fuzzy or value`. Одинаковый текст с разными `uid`
  сохраняется: это отдельные attack configurations, а не повод для deduplication.
- Runner вызывает публичный `PiiDetector.detect(..., stopOnFirst=false)`
  с явным набором четырёх mapped types. Gold не зависит от predictions.
- Report публикует baseline recall, exact/overlap recall по каждой
  PII-level attack family и combined configuration. Baseline сопоставляется
  по тем же исходным `input_id` и scored types; изменение recall публикуется
  в процентных пунктах относительно этого baseline. PII-only и combined
  результаты разделены; пересекающиеся context labels не складываются
  как независимые subsets.
- Matching переиспользует общий `PiiQualityScorer` и
  [quality contract](../requirements/fast-pii.md#quality): одинаковый type,
  exact boundaries либо непустое overlap, one-to-one maximum-cardinality
  pairing внутри записи. Посторонний span не даёт TP.
- Для потребителя [VIG-46](issue_46_current_pii_quality_evaluation.md#протокол-оценки)
  отчёт также содержит source-aligned TP/FP/FN, gold/prediction counts и
  exact/overlap precision, recall, F1 по типам и micro aggregate для
  публикуемых subsets. Пустой denominator обозначается явно.
- False-positive rate для `negative` и `hard_negative` считается отдельно:
  число документов хотя бы с одним finding / число документов subset,
  с явным enabled-types набором. Это отдельная метрика от entity FP.
  Дополнительный document-level recall для positive rows не вводится.
- Reports явно учитывают dataset caveat для `pi_few_shot_safe` и не
  принимают unrelated predicted span за успешное обнаружение attacked value.
  Source-aligned counts сохраняются; precision сопровождается оговоркой
  о неразмеченных вспомогательных примерах и ограничении интерпретации FP.
- Privacy floor для детальных attack/type groups: минимум `5` разных
  исходных `input_id` в группе. Повторные attack variants одного prompt
  не увеличивают support. При меньшем support группа помечается suppressed,
  без публикации её counts и производных метрик; общая coverage сохраняется.
- Reports, logs и diagnostics не содержат raw prompts, PII values,
  `value_fuzzy`, tokens, candidates или reversible fingerprints.
- Обычные `build` и `test` не требуют сети и не запускают
  external benchmark.

## Manifest корпуса

- **Revision:** `02741d9f99a91b8fdcf48f4316a2c73be7a7449a`.
- **Файл:** `data/train-00000-of-00001.parquet`.
- **Download URL:** <https://huggingface.co/datasets/roei-ar/AdvPIIBench/resolve/02741d9f99a91b8fdcf48f4316a2c73be7a7449a/data/train-00000-of-00001.parquet>.
- **Размер:** `4 258 476` bytes.
- **SHA-256:** `e97f6a32132e7fa058919798c030fca54aaad3318c47954d281717a435bfeb69`.
- **License declaration:** CC BY 4.0.
- **Attribution:** Roei Arpaly and Yoni Birman, Reichman University,
  *AdvPIIBench: The Adversarial Collapse of PII Detection in LLM Interactions*,
  2026, Hugging Face.
- **Источники:** [dataset card закреплённой версии](https://huggingface.co/datasets/roei-ar/AdvPIIBench/blob/02741d9f99a91b8fdcf48f4316a2c73be7a7449a/README.md)
  и [upstream stats.json](https://huggingface.co/datasets/roei-ar/AdvPIIBench/blob/02741d9f99a91b8fdcf48f4316a2c73be7a7449a/stats.json).

Размер и SHA-256 независимо вычислены по скачанному Parquet при планировании.
Counts ниже взяты из опубликованной статистики/карточки этого pin;
их проверка новым adapter остаётся частью реализации, а не уже пройденным тестом.

| Coverage | Expected count |
|---|---:|
| Все записи | 104 728 |
| `positive` | 80 936 |
| `negative` | 22 560 |
| `hard_negative` | 1 232 |
| Все gold spans | 114 101 |
| `credit_card_number` spans | 25 460 |
| `phone_number` spans | 25 460 |
| `iban` spans | 24 656 |
| `email` spans | 24 120 |
| `ssn` spans, только coverage | 14 405 |
| Mapped gold spans четырёх типов | 99 696 |
| Positive baseline rows | 1 208 |
| Positive PII-only rows | 7 248 |
| Positive combined rows | 72 480 |

Каждая PII-level family `homoglyph`, `chunking`, `emojify`, `char_to_word`,
`invisible_chars`, `separators` встречается в `13 288` rows с учётом
PII-only и combined stages. Combined stage содержит `10` фиксированных
context configurations по `7 248` rows; counts отдельных labels пересекаются:

| Context label | Expected rows |
|---|---:|
| `supportive_context` | 72 480 |
| `affix_redacted` | 7 248 |
| `affix_ignore_pii` | 14 496 |
| `affix_category_prime` | 14 496 |
| `pi_ceo_instruct` | 7 248 |
| `pi_few_shot_safe` | 14 496 |
| `pi_hypothetical` | 7 248 |
| `pi_educational_framing` | 7 248 |
| `pi_category_prime` | 14 496 |

## Критерии готовности

- [ ] Закреплены upstream revision, URL, CC BY 4.0 attribution, exact size,
      SHA-256 и expected category/type/attack counts в metadata resource;
      preparation проверяет bytes, adapter подтверждает counts manifest.
- [ ] Download и offline input проходят одинаковую integrity validation
      до parsing; неверные size/hash и прерванная загрузка безопасно
      отклоняются без принимаемого partial artifact.
- [ ] Focused adapter tests покрывают schema validation, type mapping,
      Unicode code-point to UTF-8 offset conversion, source-slice identity,
      отклонение duplicate `uid`, сохранение одинакового текста с разными `uid`
      и safe failures. SSN-only positives не становятся negatives.
- [ ] Focused scorer tests доказывают exact/overlap matching, per-attack
      aggregation, сравнение с baseline тех же `input_id`, отдельные
      document-level false-positive rates и `pi_few_shot_safe` caveat:
      unrelated finding не повышает recall атакованного gold.
- [ ] Явная benchmark task создаёт воспроизводимые JSON и Markdown
      reports в `build/reports/pii/advpii/` с provenance, coverage,
      baseline/attack metrics, TP/FP/FN, gold/prediction counts и P/R/F1,
      необходимыми VIG-46; download/offline runs дают одинаковые отчёты
      на одной версии detector/evaluator.
- [ ] Report tests проверяют privacy floor на `4` и `5` разных `input_id`,
      повторные variants одного prompt не обходят порог. JSON, Markdown,
      logs и ошибки не раскрывают исходные значения или обратимые fingerprints.
- [ ] Reports не смешиваются с canonical, RedMadRobot и HiveTrace
      evidence, не объявляются release gate и не раскрывают PII values.
- [ ] Методология, команды, offline input, mapping, caveats и privacy floor
      опубликованы в development docs, PII guide и requirements coverage.
      Обычные build/test не запускают benchmark и не загружают корпус.
- [ ] Focused tests, `./gradlew build` и `./gradlew validateWorkItems` проходят.

## Test/demo seam

Основной observable seam - явная Gradle task для AdvPIIBench с
pinned download и offline input, которая пишет отдельные JSON и Markdown
artifacts в `build/reports/pii/advpii/`.

## Согласованный план реализации

План и решения согласованы оператором 2026-09-15 ответом
«все устраивает, фиксируем». Агент-исполнитель должен руководствоваться
этим планом и согласованным scope. Существенное отступление требует обсуждения
с оператором; молчаливая замена подхода не разрешена. Конкретные имена новых
классов, tasks, offline property и выбор версии JVM Parquet reader остаются
деталями реализации при сохранении согласованных границ.

**Цель:** отдельное held-out evidence о деградации текущего detector,
воспроизводимое по закреплённому Parquet и пригодное для VIG-46.

**Текущий код:**
[RedMadRobot preparation](../../src/test/kotlin/io/vigilant/detectors/pii/benchmark/redmadrobot/RedMadRobotCorpusPreparationMain.kt)
и [Gradle wiring](../../build.gradle.kts) уже показывают явную загрузку/offline
import с integrity check и публикацией полного файла.
[PiiQualityScorer](../../src/test/kotlin/io/vigilant/detectors/pii/quality/PiiQualityScorer.kt)
выполняет deterministic maximum-cardinality exact/overlap matching и уже
используется RedMadRobot. Новый benchmark переиспользует этот scorer;
RedMadRobot-specific CSV/BIO adapter и tuning split ему не требуются.

1. **Корпус и preparation.** Добавить новые metadata и preparation в
   `src/test/resources/io/vigilant/detectors/pii/benchmark/advpii/` и
   `src/test/kotlin/io/vigilant/detectors/pii/benchmark/advpii/`.
   Закрепить manifest, реализовать verified download/offline import и добавить
   явные tasks в `build.gradle.kts`, сохранив изоляцию от обычного build/test.
2. **Adapter.** Подключить JVM Parquet reader только к тестовому инструментарию.
   Читать и валидировать исходную schema, перевести offsets без изменения
   текста, сохранить row identity и четыре mapped types по согласованному scope.
   До scoring подтвердить coverage manifest и сопоставимость baseline по `input_id`.
3. **Runner и scoring.** Через публичный detector получить полный результат
   для четырёх типов и передать spans общему scorer. Добавить агрегирование
   по baseline, PII-only families и combined configurations, сравнение с
   сопоставимым baseline и отдельный negative/hard-negative FPR.
   Это сохраняет единый matching contract без копирования его алгоритма.
4. **Reports и документация.** Из одной модели безопасных агрегатов писать
   воспроизводимые JSON/Markdown в `build/reports/pii/advpii/`, применяя
   согласованные caveat и privacy floor. Сохранить counts и метрики для VIG-46.
   В `docs/development.md` описать воспроизведение и методологию, в
   `docs/pii-detection.md` и `docs/requirements-coverage.md` отразить отдельное
   non-gating evidence и ограничения, без обещаний production quality.

Порядок зависимостей: verified bytes -> validated source spans/coverage ->
detector и matching -> aggregate reports. Focused tests добавляются вместе
с соответствующими частями по project testing mode.

**Ожидаемые проверки, ещё не выполнены:** focused adapter/scorer/report и
preparation tests с независимыми synthetic input/output examples; реальные
download/offline benchmark runs с одинаковыми artifacts; проверка изоляции
обычных build/test и отсутствия payload в output; затем `./gradlew build`,
`./gradlew validateWorkItems`. Точные команды новых tasks и focused tests
исполнитель закрепляет в development docs по фактическим именам реализации.

## Не входит

Изменение production recognizer behavior, добавление `SSN` в таксономию
Vigilant, автоматическая Unicode normalization, обучение модели, tuning
по benchmark, runtime integration, enforcement, automatic download из обычного
build, release threshold и юридическая оценка лицензии.
Дополнительный document-level recall positive rows и tuning/evaluation split
внутри AdvPIIBench не вводятся: весь корпус остаётся held-out.

## Решения и статус планирования

Revision, integrity metadata и expected counts закреплены в manifest.
Дополнительный positive document-level recall исключён; privacy floor
согласован как `5` разных исходных `input_id` на детальную attack/type group.
Открытых архитектурных решений по согласованному плану нет. Полная проверка
корпуса новым adapter и перечисленные implementation checks ещё предстоят.
Readiness проверена по [risk-based readiness](../WORK_ITEMS.md#risk-based-readiness):
архитектурный риск Low, scope и критерии согласованы, manifest закреплён,
hard dependencies и открытые архитектурные решения отсутствуют. По поручению
оператора 2026-09-15 задача переведена в `Ready for implementation`.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ adversarial degradation report определён
  Acceptance:   0.0   ✓ pin, counts, metrics и проверяемые критерии закреплены
  Boundaries:   0.0   ✓ held-out benchmark изолирован от production behavior
  Alternatives: 0.0   ✓ отдельный Parquet adapter и общий span scorer согласованы
  Assumptions:  0.0   ✓ positive document recall исключён; privacy floor = 5 input_id
  --------------------------------------------------------------
  Aggregate:    0.0   ✓ план согласован; implementation checks ещё не выполнены
```
