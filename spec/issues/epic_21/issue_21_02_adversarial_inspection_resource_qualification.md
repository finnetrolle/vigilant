# VIG-21-02: Qualification граничных request inspection shapes

**Статус:** Ready for implementation
**Epic:** [EPIC-21](../../epics/epic_21_post_milestone_architecture_closure.md)
**Ветка:** Inspection resource evidence > max-shape packaged-process qualification
**Зависит от:** нет
**Блокирует:** [VIG-21-05](issue_21_05_roadmap_frontier_reconciliation.md)
**Оценка:** 3-5 инженерных дней
**Уверенность:** Medium

## Результат

Воспроизводимая test-only qualification измеряет heap/RSS, latency, HTTP,
audit, replay и cleanup на граничных request shapes, которые runtime реально
принимает. Report отделяет подтверждённые bounds от observed deviations и не
распространяет один `64 KiB` profile на всю supported surface.

## Требования

`CONC-01`, `CONC-03`, roadmap safety gates `no OOM`, `no unbounded memory`,
`no truncation`, deterministic outcome; finding AR-11.

## Критерии готовности

- [ ] Packaged `MainKt` и real upstream запускаются в отдельных processes с
  fixed heap, direct-memory limit, source limits и recorded environment.
- [ ] Matrix включает exact default per-request boundary `8 MiB`: один большой
  text fragment, largest accepted normalized-fragment count перед parser
  rejection и gap-dense supported content.
- [ ] Concurrent case удерживает несколько accepted requests в пределах
  default `64 MiB` raw-source quota и отдельно проверяет expected stable
  capacity rejection за границей.
- [ ] Для каждого accepted case проверены exact HTTP outcome, byte-identical
  replay, один expected safe audit event и отсутствие silent truncation.
- [ ] После success, rejection, client cancellation и process shutdown source
  owners, retained bytes, executor tasks и memory trend возвращаются к
  опубликованному bounded baseline.
- [ ] Multi-fragment case публикует total inspection duration и явно показывает
  multiplier последовательной per-fragment policy evaluation; новый latency
  threshold не придумывается внутри test.
- [ ] Report отдельно показывает raw source bytes и peak heap/RSS, потому что
  Jackson tree, decoded strings, gaps, detector preflight arrays и windows не
  входят в `RequestSourceQuota.retainedBytes`.
- [ ] Synthetic fixtures не содержат production payload или raw PII и не
  записывают body/locators в report или logs.
- [ ] Production code не меняется. Если qualification выявляет defect, он
  получает отдельную RED-first TDD issue с exact failing case.
- [ ] Focused contract tests, qualification command и `./gradlew build`
  проходят; versioned report сохраняется в `docs/`.

## Test/demo seam

Packaged `MainKt`, real Armeria upstream, deterministic synthetic request
generator, bounded OS/JVM memory sampler и safe audit reader. Cases запускаются
последовательно либо в явно описанной concurrent phase.

## Не входит

Изменение source defaults, parser semantics, fragment concatenation, policy
deadline semantics, production optimization без failing gate, response/SSE
storage и disk spill.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.05  supported max-shape envelope измеряется
  Acceptance:   0.15  shape, process и terminal matrices заданы
  Boundaries:   0.05  test-only until a defect is proven
  Alternatives: 0.15  fixture implementation остаётся локальным выбором
  Assumptions:  0.25  measured hardware envelope не является universal promise
  Aggregate:    0.13  below threshold (0.3 issue)
```
