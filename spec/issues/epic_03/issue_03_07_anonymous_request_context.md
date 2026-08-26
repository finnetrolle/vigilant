# VIG-03-07: Anonymous request PolicyContext

**Статус:** Ready for implementation  
**Epic:** [EPIC-03](../../epics/epic_03_policy_context_extraction.md)  
**Ветка:** Context assembly > anonymous request  
**Зависит от:** [VIG-03-01](issue_03_01_context_contract.md), [VIG-03-02](issue_03_02_url_normalization.md), [VIG-06-02](../epic_06/issue_06_02_chat_completions_request_parser.md)  
**Блокирует:** PII shadow request tracer bullet  
**Оценка:** 2-3 инженерных дня  
**Уверенность:** Medium

## Результат

Pure assembler получает normalized effective URL и successful Chat
Completions protocol attributes и возвращает immutable request
`PolicyContext` с `phase=REQUEST`, `user=null` и empty groups. Такой context
применяет global subject `ANY` без identity extraction, raw body или transport
types.

## Public seam

Table-driven pure unit tests вызывают public assembler с typed normalized URL
и `NormalizedProtocolAttributes`. Consumer example использует production
`PolicySelector`, чтобы показать match global `ANY` policy и отсутствие match
для USER/GROUP policy, без Armeria и mocks собственных production classes.

## Критерии приёмки

- [ ] Exact normalized URL и request model переносятся в `PolicyContext` без
  повторной canonicalization или protocol parsing.
- [ ] Result всегда имеет `phase=REQUEST`, `user=null` и immutable empty
  `groups`.
- [ ] Global enabled REQUEST policy с `url=*`, `model=*`, `subject=ANY`
  совпадает с anonymous context; USER/GROUP policies не совпадают.
- [ ] Missing, blank или contradictory normalized input даёт typed safe
  assembly failure без raw exception и без partial context.
- [ ] Public API не принимает HTTP headers, raw body, JSON tree, fragments,
  provenance, credentials или provider-specific document type.
- [ ] Locale и mutable input collection не меняют structural result.
- [ ] Для добавленных и изменённых Kotlin declarations написан KDoc.
- [ ] Focused tests и `./gradlew build` проходят.

## Не входит

URL normalization implementation, protocol parsing, identity extraction,
request-to-response handoff, policy execution, detector execution и HTTP
integration.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ one anonymous request context
  Acceptance:   0.10  ✓ exact fields and selector behavior fixed
  Boundaries:   0.05  ✓ pure normalized-input seam
  Alternatives: 0.05  ✓ separate leaf selected over identity shortcut
  Assumptions:  0.15  ✓ upstream normalized types arrive from dependencies
  Aggregate:    0.07  ✓ below threshold (0.3 issue)
```
