# VIG-04-09: Fail-fast finalization при `BLOCK`

**Статус:** Done
**Epic:** [EPIC-04](../../epics/epic_04_policy_engine.md)  
**Ветка:** Detector execution > fail-fast finalization  
**Зависит от:** [VIG-04-08](issue_04_08_deadlines_cancellation.md)  
**Блокирует:** [VIG-04-11](issue_04_11_decision_observability.md)  
**Оценка:** 3-4 инженерных дня

## Результат

Как только фактически применённая reaction любой policy фиксирует `BLOCK`,
итоговый disposition становится `BLOCK`, а executions без оставшихся
потребителей отменяются. `ALLOW` не завершает evaluation досрочно.

## Наблюдаемое поведение

- Reaction выбирается явно для `CLEAN`, каждого `DETECTED` и каждого `ERROR`.
- Одновременные `DETECTED` и `ERROR` одной policy применяют обе reactions.
- Любой `BLOCK` сильнее `ALLOW` и transformations.
- Detector, ещё нужный для объяснения уже применённой policy либо другого
  consumer, не отменяется преждевременно.

## Тестовый seam

Controllable detector completion order и cancellation probes.

## Критерии готовности

- [x] BLOCK first/middle/last даёт один детерминированный итог.
- [x] ALLOW не завершает evaluation до результатов остальных policies.
- [x] Ненужные executions получают cancellation после BLOCK.
- [x] Decision при BLOCK не содержит исполняемых transformations.
- [x] Policy results сохраняют результаты, уже сформировавшие BLOCK.
- [x] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [x] Focused tests и `./gradlew build` проходят.

## Не входит

HTTP block response, approval/escalate/retry и payload modification.
