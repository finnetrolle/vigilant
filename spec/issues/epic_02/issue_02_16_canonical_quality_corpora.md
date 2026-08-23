# VIG-02-16: Canonical quality corpora и report

**Статус:** Ready for implementation  
**Epic:** [EPIC-02](../../epics/epic_02_fast_pii_detector.md)  
**Ветка:** Evidence > canonical quality gate  
**Зависит от:** [VIG-02-13](issue_02_13_cross_recognizer_semantics.md)  
**Блокирует:** [VIG-02-15](issue_02_15_jmh_baseline.md)  
**Связанные требования:** `MVP-15`, `MVP-19`  
**Оценка:** 4-5 инженерных дней  
**Уверенность оценки:** High

## Результат

Репозиторий содержит version-controlled synthetic corpora и воспроизводимый
quality report, подтверждающий точный контракт всех девяти recognizer-ов через
публичный seam `PiiDetector.detect`. Canonical positive и hard-negative cases
являются release gate, а отдельный synthetic mixed-text report показывает
exact/relaxed качество без числового release threshold.

## Scope

- Positive и hard-negative TSV corpus для каждого из девяти PII types.
- Не менее 100 positive и 100 hard negatives на каждый type; positive cases
  покрывают все заявленные формы, boundaries и UTF-8 offset classes.
- Corpus files хранятся в UTF-8 TSV. Header равен `# pii-corpus-v1`; каждая
  record содержит `caseId`, `enabledTypes` или `*`, Base64 точных UTF-8 bytes
  payload и canonical expected findings по формату epic.
- Все cases запускаются через `PiiDetector.detect(..., stopOnFirst=false)`.
  Stop-on-first equivalence остаётся общей property VIG-02-13 и не дублируется
  отдельным форматом corpus.
- Отдельный version-controlled synthetic mixed-text corpus содержит несколько
  PII types, overlapping candidates, punctuation, кириллицу, emoji и hard
  negatives в одном payload.
- Exact и relaxed span precision, recall и F1 вычисляются по каждому type и в
  целом с one-to-one deterministic matching из epic.
- `piiQualityReport` создаёт JSON и Markdown artifacts под
  `build/reports/pii/canonical/`.
- Test failures и parser diagnostics идентифицируют только `caseId`, category
  и безопасный error code, не печатая raw payload или matched values.
- Изменение corpus или recognizer semantics требует обновить fixtures в том же
  change set и объяснить нормативное изменение в тесте или issue.

## Критерии готовности

- [ ] Для каждого PII type существует не менее 100 positive и 100 hard-negative
      version-controlled synthetic cases.
- [ ] Positive corpus проходит со 100% exact type/span/evidence/metadata match.
- [ ] Hard-negative corpus проходит со 100% rejection.
- [ ] Все fixtures синтетические и не содержат production или RedMadRobot data.
- [ ] Parser отклоняет неверный header, Base64, columns, enum values, offsets,
      ordering и duplicate case IDs понятной безопасной ошибкой.
- [ ] Mixed-text corpus проверяет exact и relaxed one-to-one matching,
      overlapping findings и deterministic aggregate metrics.
- [ ] `./gradlew piiQualityReport` воспроизводимо создаёт per-type и aggregate
      JSON/Markdown reports с corpus version и case counts.
- [ ] Test failures и reports не содержат raw payload, candidates или matched
      PII values.
- [ ] Focused corpus tests и `./gradlew test` проходят.

## Validation seam

Основной observable seam - corpus runner поверх публичного
`PiiDetector.detect` и artifacts команды `./gradlew piiQualityReport` под
`build/reports/pii/canonical/`.

## Не входит

External datasets, production telemetry, сбор пользовательских payload,
генерация fixtures из production logs, числовой realistic-corpus release
threshold и performance benchmarking.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ clear
  Acceptance:   0.0   ✓ exact gate and report artifacts are verifiable
  Boundaries:   0.0   ✓ only synthetic canonical evidence is included
  Alternatives: 0.0   ✓ external benchmark is isolated in VIG-02-14
  Assumptions:  0.0   ✓ public detector seam and corpus format are fixed
  ──────────────────────────────
  Aggregate:    0.00  ✓ below threshold (0.3 ticket)

Push lightly on: no unresolved implementation decisions.
```
