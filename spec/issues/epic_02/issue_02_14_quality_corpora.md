# VIG-02-14: Versioned quality corpora и report

**Статус:** Ready for implementation  
**Epic:** [EPIC-02](../../epics/epic_02_fast_pii_detector.md)  
**Ветка:** Evidence > quality corpora  
**Зависит от:** [VIG-02-13](issue_02_13_cross_recognizer_semantics.md)  
**Блокирует:** [VIG-02-15](issue_02_15_jmh_baseline.md)  
**Оценка:** 4-5 инженерных дней

## Результат

Репозиторий содержит version-controlled synthetic corpora и воспроизводимый
quality report, подтверждающий точный контракт всех девяти recognizer-ов.

## Scope

- Positive и hard-negative TSV corpus для каждого типа.
- Не менее 100 positive и 100 hard negatives на тип.
- Header `# pii-corpus-v1`, Base64 payload и canonical expected findings.
- Отдельный realistic mixed-text corpus.
- Exact и relaxed precision, recall и F1 с deterministic matching.

## Критерии готовности

- [ ] Positive corpus проходит со 100% exact type/span match.
- [ ] Hard-negative corpus проходит со 100% rejection.
- [ ] Все fixtures синтетические и не содержат production PII.
- [ ] Parser fixtures отклоняет invalid format понятной безопасной ошибкой.
- [ ] Quality report публикует per-type и aggregate metrics.
- [ ] Test failures идентифицируют `caseId`, но не печатают raw payload.
- [ ] Focused corpus tests и `./gradlew test` проходят.

## Не входит

Production telemetry, сбор пользовательских payload и числовой realistic-corpus
release threshold.
