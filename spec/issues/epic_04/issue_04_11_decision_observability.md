# VIG-04-11: Deterministic decision и safe observability

**Статус:** Done  
**Epic:** [EPIC-04](../../epics/epic_04_policy_engine.md)  
**Ветка:** Decision > explanation and safe logs  
**Зависит от:** [VIG-04-04](issue_04_04_snapshot_provider.md), [VIG-04-08](issue_04_08_deadlines_cancellation.md), [VIG-04-09](issue_04_09_fail_fast.md), [VIG-04-10](issue_04_10_reaction_aggregation.md)  
**Оценка:** 4-5 инженерных дня

## Результат

`PolicyEngine.evaluate` возвращает полный детерминированный `PolicyDecision`,
объясняющий matched/overridden/applied policies, нормализованные detector и
policy results и reactions. При fail-fast `BLOCK` explanation сохраняет только
policy results, фактически сформировавшие `BLOCK`, и соответствующие detector
results: отменённые и завершённые только с `ALLOW` outcomes не должны делать
serialized decision зависимым от completion timing. Deadline и detector errors
логируются безопасно и ровно с требуемой cardinality.

## Наблюдаемое поведение

- Все массивы decision стабильно сортируются по policy/detector ID.
- Provider order и detector completion order не меняют serialized decision.
- Fail-fast `BLOCK` содержит нормализованное explanation только из policy
  results с применённой `BLOCK` reaction и соответствующих detector results.
- Один detector failure логируется один раз на фактический запуск с sorted
  affected policies.
- Каждый policy deadline логируется один раз с sorted unfinished detectors.
- Payload, matched text, credentials и identity headers отсутствуют в logs.

## Тестовый seam

Orchestration tests с fake provider/detectors и captured JSONL stdout. Sentinel
values проверяют отсутствие чувствительных данных.

## Критерии готовности

- [x] Decision содержит все explanation fields и duration.
- [x] No-match evaluation возвращает ALLOW без detector results.
- [x] Error/deadline events имеют обязательные structured fields EPIC-04.
- [x] Один actual detector invocation создаёт не более одного error event.
- [x] Перестановка policies и completion order даёт эквивалентный decision.
- [x] Production engine не интегрирован в HTTP proxy path.
- [x] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [x] Все focused tests и `./gradlew build` проходят.

## Не входит

HTTP enforcement, audit storage, metrics/traces и конкретный PII adapter.
