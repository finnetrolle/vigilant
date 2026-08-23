# VIG-02-14: External RedMadRobot PII benchmark

**Статус:** Ready for implementation  
**Epic:** [EPIC-02](../../epics/epic_02_fast_pii_detector.md)  
**Ветка:** Evidence > external realistic benchmark  
**Зависит от:** [VIG-02-13](issue_02_13_cross_recognizer_semantics.md)  
**Блокирует:** Нет  
**Связанные требования:** `MVP-15`, `MVP-19`  
**Оценка:** 2-3 инженерных дня  
**Уверенность оценки:** High

## Результат

Явно запускаемый Gradle benchmark воспроизводимо показывает поведение полного
`FastPiiDetector` на закреплённой версии русского
[RedMadRobot PII benchmark](https://huggingface.co/datasets/redmadrobot-rnd/pii_benchmark).
Отдельные JSON и Markdown reports публикуют source-aligned exact/relaxed
метрики, provenance и полноту обработки dataset, не превращая внешнюю шумную
разметку в контракт recognizer-ов или release gate.

## Scope

- Используется только `test.csv` из immutable revision
  `f77ea831274daf980cc45c61a93c226be9d978d6` по pinned URL
  `https://huggingface.co/datasets/redmadrobot-rnd/pii_benchmark/resolve/f77ea831274daf980cc45c61a93c226be9d978d6/test.csv`.
- Upstream metadata объявляет лицензию MIT. Report фиксирует это как license
  declaration и attribution, не как юридическое заключение Vigilant.
- Ожидаемый размер файла равен `3 225 069` bytes, SHA-256 равен
  `6bf544a380a3ee5bec94b946124bea3afaecce49e734679ad0f0c0e7c12977bb`.
- `prepareRedMadRobotPiiCorpus` явно скачивает закреплённый файл в `build/` и
  проверяет размер и SHA-256 до parsing. Для offline-запуска тот же task
  принимает локальный файл через Gradle property `redMadRobotPiiDataset` и
  выполняет над ним те же проверки.
- Подготовленный `test.csv` не добавляется в git. Неуспешная загрузка или
  проверка целостности не оставляет partial file, который следующий запуск
  может принять за валидный input.
- Adapter ожидает CSV columns `text`, `tokens`, `ner_tags`; `tokens` и
  `ner_tags` содержат JSON arrays одинаковой длины с token-level BIO labels.
- Каждая data record получает стабильный `caseId` вида
  `rmm-test-NNNNNN`, где `NNNNNN` - начинающийся с единицы порядковый номер
  parsed CSV record после header, а не физический номер строки файла.
- Tokens выравниваются с исходным `text` точным регистрозависимым поиском слева
  направо. Case folding, Unicode normalization и восстановление текста из
  token list запрещены. Неоднозначное или невозможное выравнивание отклоняет
  весь case безопасной диагностикой только с `caseId`.
- Зафиксированная revision содержит `2 841` cases и `5 614` BIO entity spans.
  При строгом alignment обрабатываются `2 839` cases; cases
  `rmm-test-000001` и `rmm-test-001629` отклоняются из-за несовпадения регистра
  token и исходного text.
- Поддерживается явный mapping только для пересекающихся типов:
  `EMAIL -> EMAIL_ADDRESS`, `PHONE -> PHONE_NUMBER`,
  `CREDIT_CARD -> PAYMENT_CARD`, `IP_ADDRESS -> IP_ADDRESS`,
  `INN -> RU_INN`, `SNILS -> RU_SNILS`, `PASSPORT -> RU_PASSPORT` и
  `OMS -> RU_OMS`. Остальные upstream labels являются out of scope; IBAN этим
  dataset не покрывается.
- В исходной revision есть `1 902` mapped gold spans. Два отклонённых cases
  содержат по одному `PHONE` span, поэтому scored subset содержит `1 900`
  mapped gold spans. Report публикует total, mapped, scored и rejected counts,
  чтобы исключение cases не улучшало метрики незаметно.
- BIO annotations преобразуются в UTF-8 byte spans исходного `text`. Exact и
  relaxed matching используют семантику раздела `Quality reports` epic,
  включая one-to-one maximum-cardinality matching и детерминированный tie-break.
- RedMadRobot labels оцениваются как опубликованные upstream, включая noisy и
  typo-containing identifiers. Метрики не меняют checksum, context, supported
  forms или boundary contract recognizer-ов.
- `redMadRobotPiiBenchmark` зависит от preparation task и создаёт отдельные
  JSON и Markdown reports в `build/reports/pii/redmadrobot/`.
- Report явно предупреждает, что результаты не сравнимы с headline leaderboard
  dataset card: Vigilant оценивает восемь structured PII types без model
  threshold, тогда как leaderboard использует другой common scope и aggregation.

## Критерии готовности

- [ ] Preparation task принимает pinned download и локальный offline input,
      проверяет точный размер и SHA-256 до parsing.
- [ ] Неверные bytes, schema, JSON, длины arrays или BIO transitions дают
      безопасную ошибку с `caseId`, но без raw text, token, tag или candidate.
- [ ] Focused adapter tests на синтетических fixtures проверяют label mapping,
      BIO span conversion, supplementary code points, adjacent punctuation,
      repeated token text и безопасный отказ при ambiguous alignment.
- [ ] Проверка pinned dataset подтверждает `2 841` total cases, `2 839`
      processed cases, `2` rejected cases, `5 614` total spans, `1 902` mapped
      spans и `1 900` scored mapped spans.
- [ ] `./gradlew redMadRobotPiiBenchmark` воспроизводимо создаёт отдельные
      per-type и aggregate exact/relaxed JSON и Markdown reports.
- [ ] Report содержит dataset URL, revision, size, SHA-256, license declaration,
      attribution, mapping, matching rules и coverage counts.
- [ ] Report не смешивает external и canonical metrics и явно отмечает, что
      external metrics не являются release gate или leaderboard-comparable.
- [ ] Ошибки, test output и reports не содержат raw payload, tokens, candidates
      или matched values.
- [ ] `./gradlew build` и `./gradlew test` не требуют сети, не скачивают внешний
      dataset и не запускают RedMadRobot benchmark.
- [ ] Focused adapter tests и `./gradlew test` проходят.

## Validation seam

Основной observable seam - явный запуск
`./gradlew redMadRobotPiiBenchmark` с pinned download или
`-PredMadRobotPiiDataset=<path>` и проверка JSON/Markdown artifacts под
`build/reports/pii/redmadrobot/`.

## Не входит

Vendoring RedMadRobot data, автоматический сетевой download из `build`/`test`,
обучение ML-модели, изменение recognizer contract ради noisy upstream labels,
release threshold, сравнение с model leaderboard и юридическая оценка лицензии.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ clear
  Acceptance:   0.0   ✓ pinned counts and report artifacts are verifiable
  Boundaries:   0.0   ✓ external metrics are isolated and non-gating
  Alternatives: 0.0   ✓ vendoring, case folding and contract fitting rejected
  Assumptions:  0.25  ✓ upstream MIT declaration is recorded as metadata
  ──────────────────────────────
  Aggregate:    0.05  ✓ below threshold (0.3 ticket)

Push lightly on: legal review only if the dataset later becomes release evidence.
```
