# VIG-02-14: Versioned quality corpora и report

**Статус:** Ready for implementation  
**Epic:** [EPIC-02](../../epics/epic_02_fast_pii_detector.md)  
**Ветка:** Evidence > quality corpora  
**Зависит от:** [VIG-02-13](issue_02_13_cross_recognizer_semantics.md)  
**Блокирует:** [VIG-02-15](issue_02_15_jmh_baseline.md)  
**Оценка:** 5-7 инженерных дней

## Результат

Репозиторий содержит version-controlled synthetic corpora и воспроизводимый
quality report, подтверждающий точный контракт всех девяти recognizer-ов. В
дополнение к каноническим fixtures отдельный non-gating report показывает
поведение detector на закреплённой версии русского
[RedMadRobot PII benchmark](https://huggingface.co/datasets/redmadrobot-rnd/pii_benchmark).

## Scope

- Positive и hard-negative TSV corpus для каждого типа.
- Не менее 100 positive и 100 hard negatives на тип.
- Header `# pii-corpus-v1`, Base64 payload и canonical expected findings.
- Отдельный realistic mixed-text corpus.
- Exact и relaxed precision, recall и F1 с deterministic matching.
- Внешний RedMadRobot benchmark использует только `test.csv` из immutable
  revision `f77ea831274daf980cc45c61a93c226be9d978d6`, заявленную upstream
  лицензию MIT и SHA-256
  `43f9ac83fb08b88c45f4937de9d6c0ae87fecbc4011606a6933f4d2bad7f29ce`.
- `prepareRedMadRobotPiiCorpus` явно скачивает закреплённый файл в `build/`,
  проверяет размер `3 225 069` bytes и SHA-256 до parsing. Для offline-запуска
  тот же task принимает локальный файл через Gradle property
  `redMadRobotPiiDataset` и выполняет над ним те же проверки.
- `redMadRobotPiiBenchmark` зависит от preparation task, преобразует upstream
  token-level BIO annotations в UTF-8 byte spans исходного `text` и создаёт
  отдельные JSON и Markdown reports в `build/reports/pii/redmadrobot/`.
- Поддерживается явный mapping только для пересекающихся типов:
  `EMAIL -> EMAIL_ADDRESS`, `PHONE -> PHONE_NUMBER`,
  `CREDIT_CARD -> PAYMENT_CARD`, `IP_ADDRESS -> IP_ADDRESS`,
  `INN -> RU_INN`, `SNILS -> RU_SNILS`, `PASSPORT -> RU_PASSPORT` и
  `OMS -> RU_OMS`. Остальные upstream labels являются out of scope; IBAN этим
  dataset не покрывается.
- Adapter выравнивает tokens с исходным `text` слева направо без Unicode
  normalization или восстановления текста из token list. Неоднозначное или
  невозможное выравнивание отклоняет case безопасной ошибкой с `caseId`, но без
  raw text, token или candidate.
- RedMadRobot labels оцениваются как опубликованные upstream, включая noisy и
  typo-containing identifiers. Эти source-aligned metrics публикуются отдельно,
  не смешиваются с canonical corpus metrics, не задают release threshold и не
  изменяют checksum, context или boundary contract recognizer-ов.
- Dataset card, pinned revision, SHA-256, license declaration, attribution,
  mapping и число обработанных/отклонённых cases включаются в report.

## Критерии готовности

- [ ] Positive corpus проходит со 100% exact type/span match.
- [ ] Hard-negative corpus проходит со 100% rejection.
- [ ] Все version-controlled fixtures синтетические и не содержат production
      PII; RedMadRobot `test.csv` не добавлен в git и существует только как
      явно подготовленный внешний benchmark input под `build/`.
- [ ] Parser fixtures отклоняет invalid format понятной безопасной ошибкой.
- [ ] Quality report публикует per-type и aggregate metrics.
- [ ] Test failures идентифицируют `caseId`, но не печатают raw payload.
- [ ] Focused adapter tests проверяют label mapping, BIO span conversion,
      supplementary code points, adjacent punctuation, repeated token text и
      безопасный отказ при ambiguous alignment.
- [ ] Preparation task отклоняет неверные revision bytes, размер или SHA-256 до
      parsing и не оставляет частичный файл как валидный corpus.
- [ ] `./gradlew redMadRobotPiiBenchmark` воспроизводимо создаёт отдельные
      per-type и aggregate exact/relaxed metrics с provenance metadata.
- [ ] `./gradlew build` и `./gradlew test` не требуют сети, не скачивают внешний
      dataset и не запускают RedMadRobot benchmark.
- [ ] Focused corpus tests и `./gradlew test` проходят.

## Не входит

Production telemetry, сбор пользовательских payload и числовой realistic-corpus
release threshold. Также не входят vendoring RedMadRobot data, обучение ML-модели,
слияние external и canonical metrics и изменение recognizer contract ради
совпадения с noisy upstream labels.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ clear
  Acceptance:   0.0   ✓ clear
  Boundaries:   0.0   ✓ clear
  Alternatives: 0.25  ✓ raw vendoring and metric merging rejected
  Assumptions:  0.25  ✓ upstream license declaration pinned in provenance
  ──────────────────────────────
  Aggregate:    0.10  ✓ below threshold (0.3 ticket)

Push lightly on: no unresolved implementation decisions.
```
