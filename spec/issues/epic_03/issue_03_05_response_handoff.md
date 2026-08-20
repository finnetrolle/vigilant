# VIG-03-05: Перенос PolicyContext в response phase

**Статус:** Draft  
**Epic:** [EPIC-03](../../epics/epic_03_policy_context_extraction.md)  
**Ветка:** Lifecycle > request-to-response handoff  
**Зависит от:** [VIG-03-01](issue_03_01_context_contract.md), [VIG-03-02](issue_03_02_url_normalization.md), [VIG-03-03](issue_03_03_identity_extraction.md), [VIG-03-04](issue_03_04_model_extraction.md)  
**Блокирует:** [VIG-03-06](issue_03_06_security_e2e.md)  
**Оценка после уточнения:** 3-5 инженерных дней

## Результат

Один immutable request context безопасно переиспользуется для соответствующей
`RESPONSE` phase без повторного извлечения URL, model или identity из response
body и без нарушения streaming/cancellation semantics.

## Требует решения VIG-03-01

- Где хранится context в lifetime Armeria request.
- Как context доступен response adapter-у.
- Cleanup при completion, error, timeout и client cancellation.

## Критерии готовности после уточнения

- [ ] Response context отличается только `phase=RESPONSE`.
- [ ] URL, model, user и groups точно совпадают с request phase.
- [ ] Concurrent requests не смешивают contexts.
- [ ] Streaming TTFB не ждёт полного upstream response.
- [ ] Cancellation/error освобождают request-scoped references.
- [ ] Context и credentials не попадают в логи.
- [ ] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [ ] Focused tests и `./gradlew test` проходят.

## Не входит

Policy selection, response body inspection и detector execution.
