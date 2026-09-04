# VIG-04-10: Reaction и transformation span aggregation

**Статус:** Done
**Epic:** [EPIC-04](../../epics/epic_04_policy_engine.md)  
**Ветка:** Decision > reaction aggregation  
**Зависит от:** [VIG-04-01](issue_04_01_domain_contracts.md), [VIG-04-05](issue_04_05_matching_overrides.md), [VIG-04-06](issue_04_06_detector_executor.md)  
**Блокирует:** [VIG-04-11](issue_04_11_decision_observability.md)  
**Оценка:** 3-5 инженерных дня

> Current final-plan contract is superseded by VIG-20-03: `ReactionPlan` now
> exposes typed `MaskingInstruction`, and startup rejects `REMOVE`. This issue
> remains historical evidence for the original aggregation seam.

## Результат

Pure aggregation component формирует `ReactionPlan`: BLOCK precedence,
deduplicated MASK/REMOVE operations и deterministic merge пересекающихся или
соприкасающихся UTF-8 spans.

## Наблюдаемое поведение

- При любом BLOCK transformations итогового plan пусты.
- Без BLOCK transformations всех applied policies объединяются.
- `REMOVE` сильнее `MASK` для одного finding и объединённого диапазона.
- Duplicate operations удаляются.
- Overlapping и adjacent spans объединяются детерминированно.
- Исходный payload не изменяется.

## Тестовый seam

Table-driven pure tests на ASCII и mixed-Unicode byte spans.

## Критерии готовности

- [x] Все reaction aggregation cases EPIC-04 покрыты.
- [x] Merge не создаёт boundaries внутри UTF-8 code point.
- [x] Input order не влияет на plan.
- [x] Empty findings/transformations обрабатываются явно.
- [x] Output collections immutable и стабильно отсортированы.
- [x] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [x] Focused tests и `./gradlew build` проходят.

## Не входит

Фактическое mask/remove исходного текста и HTTP response generation.
