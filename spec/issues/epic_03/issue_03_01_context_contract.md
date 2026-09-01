# VIG-03-01: PolicyContext contract и trust boundary

**Статус:** Done  
**Epic:** [EPIC-03](../../epics/epic_03_policy_context_extraction.md)  
**Ветка:** Context contract and trust boundary  
**Зависит от:** нет  
**Блокирует:** все остальные issues EPIC-03  
**Оценка:** 2-3 инженерных дня

## Результат

Нормативный `PolicyContext` и trust boundary определены настолько точно, что
URL, identity, приём protocol-derived attributes, context assembly и response
handoff можно реализовать независимо без разных трактовок. Принятые решения
записываются обратно в EPIC-03, после чего сам epic и зависимые issues проходят
повторный ambiguity gate.

## Закрытые решения

1. URL match key является canonical effective upstream URL: lowercase scheme
   и IDNA ASCII host, default port удалён, non-default port сохранён, effective
   path нормализует dot segments и percent encoding. Query, fragment и
   user-info не входят; malformed input даёт typed `INVALID_POLICY_URL`.
2. EPIC-06 передаёт versioned immutable
   `NormalizedProtocolAttributes(model)`. Произвольной map нет; protocol
   family/operation/transport и fragments не входят в `PolicyContext`.
3. `model` обязателен и непуст после successful parse. Missing/invalid model
   даёт typed assembly failure. Generic `NormalizedIdentity` сохраняет явную
   форму `user=null`, immutable empty `groups` для extractors, которым она
   нормативно разрешена; policy subject `ANY` совпадает с ней.
4. Source-specific extraction не входит в assembly contract. После VIG-27
   current runtime принимает configured normalized identity только через
   temporary Dummy Bearer boundary.
5. Raw authentication values не входят в `PolicyContext`. Accepted end-to-end
   Authorization остаётся transport-owned и передаётся upstream unchanged.
6. Existing immutable `PolicyContext(url, model, phase, user, groups)` является
   engine contract. Request context создаётся один раз, сохраняется typed
   attribute в Armeria `ServiceRequestContext`, response context меняет только
   phase. Thread-local и повторный parse запрещены; request completion,
   cancellation и error завершают lifetime ссылки.

## Рекомендованный baseline

- Не принимать в EPIC-03 raw body, protocol events или protocol-specific
  document model. EPIC-06 передаёт только normalized attributes.
- Исключить query, fragment и user-info из policy URL key.
- Представлять отсутствующие optional values явно, а groups как immutable set.
- Доверять identity headers только от явно настроенного trusted ingress;
  иначе удалять supplied values или отклонять запрос согласно принятой модели.
- Не сохранять password/token/raw credential в `PolicyContext`.

## Критерии готовности

- [x] Все шесть решений имеют один выбранный вариант и rationale.
- [x] EPIC-03 не содержит конфликтующих open decisions.
- [x] Public contract согласован с matching semantics EPIC-04.
- [x] Граница с EPIC-06 не содержит повторного protocol parsing и согласована
  с его normalized attributes contract.
- [x] Обновлён Ambiguity Report EPIC-03 с aggregate не выше `0.2`.
- [x] Зависимые issues переведены в `Ready for implementation` только после
  обновления их acceptance criteria.

## Не входит

Production implementation extractors, protocol parsing, policy matching и
detector execution.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ exact context result fixed
  Acceptance:   0.05  ✓ normalization and identity outcomes testable
  Boundaries:   0.05  ✓ protocol and transport ownership explicit
  Alternatives: 0.10  ✓ identity modes and trust boundary selected
  Assumptions:  0.15  ✓ request-scoped Armeria handoff is established seam
  Aggregate:    0.07  ✓ below threshold (0.2 spec issue)
```
