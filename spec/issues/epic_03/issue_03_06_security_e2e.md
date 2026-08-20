# VIG-03-06: E2E security и upstream identity stripping

**Статус:** Draft  
**Epic:** [EPIC-03](../../epics/epic_03_policy_context_extraction.md)  
**Ветка:** Lifecycle > end-to-end security behavior  
**Зависит от:** [VIG-03-03](issue_03_03_identity_extraction.md), [VIG-03-05](issue_03_05_response_handoff.md)  
**Оценка после уточнения:** 3-4 инженерных дня

## Результат

E2E tests через реальные Armeria server и upstream подтверждают, что context
строится для request/response phases, а Vigilant-only identity headers и
credentials не передаются upstream и не раскрываются в логах или errors.

## Критерии готовности после уточнения

- [ ] Проверены все принятые identity sources и precedence.
- [ ] Служебные identity headers удаляются только по конфигурации.
- [ ] End-to-end headers и raw body, не относящиеся к identity, сохраняются.
- [ ] Basic password, Authorization и sentinel values отсутствуют upstream,
  stdout и stderr согласно принятому контракту.
- [ ] Request и streaming/non-streaming response получают один context.
- [ ] Existing proxy transparency, backpressure и cancellation tests проходят.
- [ ] `./gradlew build` проходит.

## Не входит

Policy engine invocation, masking/block response и external authentication.
