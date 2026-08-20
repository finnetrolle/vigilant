# VIG-03-01: PolicyContext contract и trust boundary

**Статус:** Draft  
**Epic:** [EPIC-03](../../epics/epic_03_policy_context_extraction.md)  
**Ветка:** Context contract and trust boundary  
**Зависит от:** нет  
**Блокирует:** все остальные issues EPIC-03  
**Оценка после уточнения:** 2-3 инженерных дня

## Результат

Нормативный `PolicyContext` и trust boundary определены настолько точно, что
URL, identity, model и response handoff можно реализовать независимо без
разных трактовок. Принятые решения записываются обратно в EPIC-03, после чего
сам epic и зависимые issues проходят повторный ambiguity gate.

## Решения, которые нужно закрыть

1. URL match key и правила normalization.
2. Поддерживаемые в первой версии OpenAI-compatible operations.
3. Представление отсутствующих `model`, `user` и `groups`.
4. Разрешённые identity sources, их precedence и формат groups.
5. Кто имеет право устанавливать identity headers и где проходит trusted
   proxy boundary.
6. Точный immutable Kotlin contract и способ request-to-response handoff.

## Рекомендованный baseline

- Ограничить V1 OpenAI-compatible поверхностью, реально нужной ближайшему
  HTTP integration slice, а не обещать все OpenAI/Anthropic schemas.
- Исключить query, fragment и user-info из policy URL key.
- Представлять отсутствующие optional values явно, а groups как immutable set.
- Доверять identity headers только от явно настроенного trusted ingress;
  иначе удалять supplied values или отклонять запрос согласно принятой модели.
- Не сохранять password/token/raw credential в `PolicyContext`.

## Критерии готовности draft

- [ ] Все шесть решений имеют один выбранный вариант и rationale.
- [ ] EPIC-03 не содержит конфликтующих open decisions.
- [ ] Public contract согласован с matching semantics EPIC-04.
- [ ] Protocol boundary следует lossless/strict compatibility principle.
- [ ] Обновлён Ambiguity Report EPIC-03 с aggregate не выше `0.2`.
- [ ] Зависимые issues переведены в `Ready for implementation` только после
  обновления их acceptance criteria.

## Не входит

Production implementation extractors, policy matching и detector execution.
