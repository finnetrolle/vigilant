# VIG-03-05: Перенос PolicyContext в response phase

**Статус:** Done
**Epic:** [EPIC-03](../../epics/epic_03_policy_context_extraction.md)  
**Ветка:** Lifecycle > request-to-response handoff  
**Зависит от:** [VIG-03-01](issue_03_01_context_contract.md), [VIG-03-02](issue_03_02_url_normalization.md), [VIG-03-03](issue_03_03_identity_extraction.md), [VIG-03-04](issue_03_04_model_extraction.md)  
**Блокирует:** [VIG-03-06](issue_03_06_security_e2e.md)  
**Оценка:** 3-5 инженерных дней

## Результат

Один immutable request context безопасно переиспользуется для соответствующей
`RESPONSE` phase без повторного извлечения URL, model, identity или других
attributes из response body и без нарушения streaming/cancellation semantics.

## Нормативный handoff

- Один immutable request snapshot сохраняется typed attribute в соответствующем
  Armeria `ServiceRequestContext` до первого upstream call.
- Response phase читает тот же snapshot и создаёт новый `PolicyContext`, меняя
  только `phase=RESPONSE`; thread-local, global map и response-body parsing
  запрещены.
- Attribute не переносится между requests. Completion, upstream/client error,
  timeout и cancellation завершают request scope; callback не удерживает
  context после lifecycle.

## Тестовый seam

Один основной seam - public request-to-response handoff operation над реальным
Armeria `ServiceRequestContext`. Focused tests создают request snapshot через
public assembly contract, сохраняют его handoff operation и получают response
`PolicyContext` через тот же API; private attribute key и lifecycle callbacks не
проверяются напрямую. Existing proxy tests через реальные Armeria server и
upstream являются только regression gate для streaming TTFB и cancellation и
не образуют второй feature seam.

## Критерии приёмки

- [x] Response context отличается только `phase=RESPONSE`.
- [x] URL, model, user и groups точно совпадают с request phase.
- [x] Reported model из upstream response не переопределяет request model.
- [x] Concurrent requests не смешивают contexts.
- [x] Completion, cancellation и error освобождают request-scoped references.
- [x] Context и credentials не попадают в логи.
- [x] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [x] Focused handoff tests и existing real-server regression tests для
  streaming TTFB/cancellation проходят вместе с `./gradlew test`.

## Не входит

Policy selection, response body inspection и detector execution.
