# VIG-04-05: Policy matching и simultaneous overrides

**Статус:** Ready for implementation  
**Epic:** [EPIC-04](../../epics/epic_04_policy_engine.md)  
**Ветка:** Selection > matching and overrides  
**Зависит от:** [VIG-04-01](issue_04_01_domain_contracts.md)  
**Блокирует:** [VIG-04-07](issue_04_07_parallel_execution.md)  
**Оценка:** 3-4 инженерных дня

## Результат

Pure selection component получает immutable snapshot и `PolicyContext`, затем
возвращает sorted matched, overridden и applied sets согласно exact wildcard,
subject и simultaneous override semantics EPIC-04.

## Наблюдаемое поведение

- Disabled policies исключаются до matching.
- URL/model/subject exact matching locale-independent и case-insensitive.
- Wildcard действует только как полное `*`.
- USER/GROUP/global subject semantics соответствуют epic.
- Overrides действуют только от matched enabled policies и применяются
  одновременно, независимо от provider order.

## Критерии готовности

- [ ] Все matching и override cases EPIC-04 покрыты table-driven tests.
- [ ] User policy без explicit override не отменяет group policy.
- [ ] Chain overrides дают нормативный applied set.
- [ ] No match возвращает empty selection без detector execution.
- [ ] Output имеет стабильную сортировку по policy ID.
- [ ] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [ ] Focused tests и `./gradlew build` проходят.

## Не входит

URL normalization, provider I/O, detector planning и policy priority.
