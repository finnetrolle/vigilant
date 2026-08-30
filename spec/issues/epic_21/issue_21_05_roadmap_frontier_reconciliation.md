# VIG-21-05: Синхронизация roadmap и repository frontier

**Статус:** Ready for implementation
**Epic:** [EPIC-21](../../epics/epic_21_post_milestone_architecture_closure.md)
**Ветка:** Documentation consistency > roadmap and frontier reconciliation
**Зависит от:** [VIG-21-01](issue_21_01_minimum_audit_trail_contract.md), [VIG-21-02](issue_21_02_adversarial_inspection_resource_qualification.md), [VIG-21-03](issue_21_03_upstream_error_test_determinism.md), [VIG-21-04](issue_21_04_streaming_evidence_determinism.md)
**Блокирует:** завершение EPIC-21
**Оценка:** 1-2 инженерных дня
**Уверенность:** High

## Результат

Roadmap, current runtime docs и work-item registry используют одну фактическую
frontier: завершённые items не названы текущей работой, historical load profile
не расширен до неподтверждённых shapes, а implemented, excluded и future
capabilities разделены явно.

## Требования

Finding AR-14 и результаты VIG-21-01..04.

## Критерии готовности

- [ ] `ROADMAP.md` больше не называет Done item VIG-01A текущим repository
  frontier.
- [ ] VIG-18 claims явно ограничены `64 KiB`, single-fragment, measured
  hardware/heap и date либо расширены только фактическим VIG-21-02 evidence.
- [ ] Current runtime identity modes отделены от исторического scope первого
  production milestone; implemented feature не перечисляется как current
  exclusion без temporal qualifier.
- [ ] Safe aggregate event и guaranteed minimal audit trail описаны раздельно
  согласно VIG-21-01.
- [ ] Open test gaps отражены фактическими statuses; исторические reports и
  закрытые issue files не переписываются как будто новые evidence существовали
  раньше.
- [ ] EPIC-20 остаётся единственным owner response/SSE spooling and secure spill
  decisions; EPIC-21 не создаёт дублирующий future scope.
- [ ] `WORK_ITEMS.md`, EPIC-21 checklist и dependent links согласованы одним
  change set.
- [ ] `./gradlew validateWorkItems` и `./gradlew build` проходят.

## Test/demo seam

Project work-item validator плюс deterministic text assertions на current
frontier claims, если существующий validator их не покрывает.

## Не входит

Изменение production behavior, переписывание исторических measurement values,
закрытие EPIC-20 decisions и создание новых product capabilities.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   one current frontier across documents
  Acceptance:   0.10  stale claims named explicitly
  Boundaries:   0.0   documentation/status only
  Alternatives: 0.10  wording follows completed evidence
  Assumptions:  0.10  predecessor results are source of truth
  Aggregate:    0.06  below threshold (0.3 issue)
```
