# VIG-20-04: Retained in-memory response contract

**Статус:** Ready for implementation
**Epic:** [EPIC-20](../../epics/epic_20_response_spooling_secure_spill.md)
**Ветка:** Response memory > terminology and deployment boundary
**Зависит от:** нет
**Блокирует:** [VIG-20-01](issue_20_01_bounded_memory_response_source.md)
**Оценка:** 1 инженерный день; confidence High

## Цель

Устранить противоречие между термином `bounded response source` и принятым MVP
контрактом, в котором response source не имеет application-level size limit,
shared quota или capacity admission.

Во всех активных нормативных документах компонент называется
`retained in-memory response source`. Application отвечает за освобождение
owned buffers/references на каждом terminal path, а deployment отвечает за JVM
heap sizing и runtime OOM policy.

## Принятые решения

- Эта issue изменяет только normative terminology и contract wording.
- Application-level response byte limit, shared response quota, spill-to-disk
  и response capacity rejection не добавляются.
- Слово `bounded` сохраняется только для действительно ограниченных ресурсов,
  например request source, CPU executor и queues.
- `retained` означает, что полный response удерживается до terminal protocol
  state и final policy decision, после чего owned references освобождаются.
- Отсутствие application-level quota не объявляется memory safety guarantee.
  JVM heap sizing и OOM policy являются явной deployment responsibility.

## Требования

- `MVP-01`: response удерживается до применения policy.
- `CONC-01`: response quota намеренно отсутствует, cleanup обязателен.
- `PROXY-01`: client disclosure начинается только после complete analysis.

## Не входит

- Production code, class rename, response source implementation или tests.
- Добавление hard limit, quota, admission, spill, storage или нового config.
- Изменение request source EPIC-08.

## Критерии готовности

- [ ] `MVP_FUNCTIONS.md` и `MVP_NON_FUNCTIONAL_REQUIREMENTS.md` используют
  `retained in-memory response source` и не называют его bounded.
- [ ] EPIC-20 и VIG-20-01 одинаково фиксируют отсутствие application-level
  response limit/quota и deployment-owned heap/OOM boundary.
- [ ] `docs/requirements-coverage.md` не обещает bounded response memory при
  отсутствии application-level bound.
- [ ] Поиск по активным MVP, epic, issue и coverage documents не находит
  противоречивого `bounded response` claim.
- [ ] `./gradlew validateWorkItems` и `git diff --check` проходят.

## Ambiguity Report

```text
Goals:        0.0   terminology outcome exact
Acceptance:   0.0   affected normative surfaces enumerated
Boundaries:   0.0   documentation-only contract change
Alternatives: 0.0   retained source selected over a new hard limit
Assumptions:  0.10  deployment owns heap sizing and OOM behavior
Aggregate:    0.02  Ready for implementation.
```
